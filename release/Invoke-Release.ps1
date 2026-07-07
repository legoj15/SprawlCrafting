<#
.SYNOPSIS
    Cut a SprawlCrafting release across GitHub, Modrinth and CurseForge from one input (`version`).

.DESCRIPTION
    Derives every title / tag / channel / changelog variant from gradle.properties + the per-node
    Stonecutter configs, then:
      1. rewrites + validates + commits update.json (the in-game update manifest),
      2. creates ONE GitHub release (tag = version) with ALL node jars attached,
      3. uploads one Modrinth version per node (loader + MC version),
      4. uploads one CurseForge file per node (loader + MC version),
      5. resets changelog.md and (for a prerelease) bumps `version` to the next prerelease.

    SprawlCrafting ships SEVEN jars: fabric + neoforge x 1.21.1 / 26.1.2 / 26.2 (the modern Stonecutter
    tree), plus a single Forge jar for the legacy 1.12.2 line (its own self-contained build under
    forge-1.12.2/, injected as a synthetic release node via release.json's `legacyForge` block). Each
    store upload is per-node and tags its own loader. The update manifest is keyed by MC version
    (loader-agnostic), so both loaders of one MC version share a block (1.12.2 has the one Forge jar).

    Changelog lines of the form "- {MC.VER} text" are routed only to that MC version's manifest block
    and store upload (tag stripped); GitHub always gets changelog.md verbatim. A tag naming an unknown
    MC version fails the run up front, dry run included.

    SAFE BY DEFAULT: without -Execute nothing is written, committed, or uploaded — every step prints
    exactly what it WOULD do (a dry run). Read-only API lookups are skipped in dry run too, so no
    tokens are needed to preview.

    STORES OFF BY DEFAULT: curseforge.enabled / modrinth.enabled in release.json are false until you
    create those store projects and set real project IDs. A dry run still PREVIEWS a disabled store
    (labelled), but -Execute skips it. GitHub works today via your existing `gh auth`.

.PARAMETER Execute      Actually perform writes / commits / uploads. Omit for a dry run.
.PARAMETER Only         Restrict to a subset of stages: Manifest, GitHub, Modrinth, CurseForge, PostFlight. Default: all.
.PARAMETER NextVersion  Override the post-flight version bump (required when releasing a final, suffix-less version).
.PARAMETER Build        Build first: `./gradlew buildAndCollect` (six modern jars -> testing/dist/) plus the 1.12.2 Forge build (forge-1.12.2/build/libs/, host JDK 25 via legacyForge.javaHome).
.PARAMETER ModrinthStaging  Target staging-api.modrinth.com for the Modrinth leg (see release/README.md caveat).

.EXAMPLE  ./release/Invoke-Release.ps1                 # full dry run of the current version
.EXAMPLE  ./release/Invoke-Release.ps1 -Execute        # really publish
.EXAMPLE  ./release/Invoke-Release.ps1 -Only GitHub -Execute
.EXAMPLE  ./release/Invoke-Release.ps1 -Build -Execute
#>
#Requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$Execute,
    [ValidateSet('Manifest','GitHub','Modrinth','CurseForge','PostFlight')]
    [string[]]$Only,
    [string]$NextVersion,
    [switch]$Build,
    [switch]$ModrinthStaging
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'modules\ScRelease.psm1') -Force

# Load local (gitignored) secrets if present — sets $env:CURSEFORGE_TOKEN / $env:MODRINTH_TOKEN.
# Skipped silently if absent (e.g. a dry run, or tokens already exported in the environment).
$secretsFile = Join-Path $PSScriptRoot 'config\secrets.ps1'
if (Test-Path $secretsFile) { . $secretsFile }

function Want { param([string]$Stage) return (-not $Only) -or ($Only -contains $Stage) }
# A store is "active" (will really upload) only when enabled in config. In dry run we still preview a
# disabled store so the plan is visible; -Execute skips it.
function Get-StoreEnabled { param($StoreCfg) return [bool](Get-ScProp $StoreCfg 'enabled' $false) }

$root    = Get-ScRepoRoot
$cfg     = Get-ScReleaseConfig
$ver     = Get-ScModVersion -RepoRoot $root
$split   = Split-ScVersion $ver
$chan    = Get-ScChannel $split
$nodes   = @(Get-ScNodes -RepoRoot $root -Config $cfg)
$mcSet   = @($nodes.McVersion | Select-Object -Unique)
$ingameBold = Get-ScProp $cfg 'ingameBold' 'strip'

Write-ScStep "SprawlCrafting release: $ver"
Write-ScInfo "GitHub title : $(Get-ScGithubTitle $split)   [$(if($chan.GithubPrerelease){'pre-release'}else{'latest'})]"
Write-ScInfo "Channels     : Modrinth=$($chan.Modrinth)  CurseForge=$($chan.CurseForge)"
Write-ScInfo "Nodes        : $($nodes.Count) ($(($nodes.Node) -join ', '))"
Write-ScInfo "MC versions  : $($mcSet -join ', ')"
Write-ScInfo "Stores       : Modrinth=$(if(Get-StoreEnabled $cfg.modrinth){'enabled'}else{'DISABLED'})  CurseForge=$(if(Get-StoreEnabled $cfg.curseforge){'enabled'}else{'DISABLED'})"
Write-ScInfo "Mode         : $(if($Execute){'EXECUTE (live)'}else{'DRY RUN (no writes/uploads)'})"
if (-not $Execute) { Write-ScWarn 'Dry run — pass -Execute to publish for real.' }

# ── Optional build ───────────────────────────────────────────────────────────
if ($Build) {
    Write-ScStep 'Build modern node jars (./gradlew buildAndCollect)'
    if (-not $Execute) {
        Write-ScDry "would run: ./gradlew buildAndCollect"
    } else {
        Push-Location $root
        try {
            & (Join-Path $root 'gradlew.bat') buildAndCollect
            if ($LASTEXITCODE -ne 0) { throw "buildAndCollect failed (exit $LASTEXITCODE)." }
        } finally { Pop-Location }
        Write-ScOk 'buildAndCollect succeeded.'
    }

    # The legacy 1.12.2 jar is produced by its OWN self-contained Gradle build (separate wrapper, RFG,
    # host JDK 25) — root buildAndCollect never produces it. Build each legacy node with a second gradlew
    # invocation, switching JAVA_HOME to the configured JDK (the modern build needs JDK 21, the forge build
    # needs JDK 25 — they can't share one host JDK in a single run).
    foreach ($ln in @($nodes | Where-Object { $_.IsLegacy })) {
        $lnDir = Join-Path $root $ln.BuildDir
        Write-ScStep "Build legacy $($ln.McVersion) jar ($($ln.BuildDir)/gradlew $($ln.BuildTask))"
        if (-not $Execute) {
            Write-ScDry "would run: $($ln.BuildDir)/gradlew.bat $($ln.BuildTask)  [JAVA_HOME=$(if($ln.JavaHome){$ln.JavaHome}else{'(current)'})]"
            continue
        }
        if (-not $ln.JavaHome) {
            Write-ScWarn "legacyForge.javaHome not set — building $($ln.McVersion) with the current JAVA_HOME. RFG requires a JDK 25 host; set legacyForge.javaHome in release.json if this fails."
        }
        $savedJavaHome = $env:JAVA_HOME
        Push-Location $lnDir
        try {
            if ($ln.JavaHome) { $env:JAVA_HOME = $ln.JavaHome }
            & (Join-Path $lnDir 'gradlew.bat') $ln.BuildTask
            if ($LASTEXITCODE -ne 0) { throw "legacy $($ln.McVersion) build failed (exit $LASTEXITCODE)." }
        } finally {
            Pop-Location
            $env:JAVA_HOME = $savedJavaHome
        }
        Write-ScOk "Built legacy $($ln.McVersion) jar."
    }
}

# ── Resolve jars + changelog variants ────────────────────────────────────────
$jarInfo = Test-ScJars -RepoRoot $root -Version $ver -Nodes $nodes
$jarMap  = $jarInfo.Map
if ($jarInfo.Missing.Count) {
    if ($Execute) {
        throw ("Missing shipping jar(s) for $ver — run ``./gradlew buildAndCollect`` (or pass -Build):`n  " + ($jarInfo.Missing -join "`n  "))
    }
    Write-ScWarn "Missing $($jarInfo.Missing.Count) jar(s) (dry run continues; -Execute would fail):"
    foreach ($m in $jarInfo.Missing) { Write-ScWarn "  $m" }
}

$body = Get-ScChangelogBody -RepoRoot $root
if ([string]::IsNullOrWhiteSpace($body)) { Write-ScWarn 'changelog.md body is empty — release notes will be blank.' }
$clSplit = Split-ScChangelogByVersion -Body $body

$manifestPath = Join-Path $root 'update.json'
# {mc}-tags must name a real MC version (a manifest block or a shipping node) — a typo would silently
# drop the line everywhere but GitHub. Validated up front so even a dry run catches it.
$manifestBlocks = @()
if (Test-Path $manifestPath) {
    $manifestBlocks = @((Get-Content -Raw $manifestPath | ConvertFrom-Json).PSObject.Properties.Name |
                        Where-Object { $_ -notin @('homepage','promos') })
}
Test-ScChangelogTags -ChangelogSplit $clSplit -KnownVersions (@($mcSet) + $manifestBlocks | Select-Object -Unique)

# ── 1. Manifest (update.json) ────────────────────────────────────────────────
if (Want 'Manifest') {
    Write-ScStep 'Update in-game update manifest (update.json)'
    $null = Update-ScManifest -Config $cfg -ManifestPath $manifestPath -Version $ver -ChangelogBody $body `
                -NodeMcVersions $mcSet -IngameBold $ingameBold -Write:$Execute
    Write-ScOk "Manifest valid for MC version(s): $($mcSet -join ', ')"
    if (-not $Execute) {
        foreach ($mc in ($mcSet | Sort-Object)) {
            $v = Get-ScManifestChangelogForMc -ChangelogSplit $clSplit -McVersion $mc -Bold $ingameBold
            Write-ScDry "block $mc changelog:"
            Write-Host ($v -replace [char]0x00A7, '<§>') -ForegroundColor DarkGray
        }
    }
    Invoke-ScGitCommit -RepoRoot $root -Paths @('update.json') `
        -Message "Announce $ver in update manifest" -Execute:$Execute
}

# The GitHub release must tag the manifest commit, so capture HEAD after it.
$commit = (& git -C $root rev-parse HEAD).Trim()

# ── 2. GitHub release (all jars, one release) ────────────────────────────────
if (Want 'GitHub') {
    Write-ScStep 'Create GitHub release'
    # GitHub tags the commit on its own servers, so HEAD must be on origin first (else gh returns 422).
    Invoke-ScGitPush -RepoRoot $root -Execute:$Execute
    $jars = @($nodes | ForEach-Object { $jarMap[$_.Node] })
    Invoke-ScGithubRelease -Config $cfg -Split $split -Channel $chan `
        -Title (Get-ScGithubTitle $split) -Body $body -Jars $jars -Commit $commit -Execute:$Execute
}

# ── 3. Modrinth (one version per node) ───────────────────────────────────────
if (Want 'Modrinth') {
    Write-ScStep 'Upload to Modrinth'
    $mrEnabled = Get-StoreEnabled $cfg.modrinth
    if ($Execute -and -not $mrEnabled) {
        Write-ScInfo 'Modrinth disabled in release.json (set modrinth.enabled=true + a real projectId to publish) — skipped.'
    } else {
        if (-not $mrEnabled) { Write-ScWarn 'Modrinth is DISABLED — previewing only (would be skipped under -Execute).' }
        $apiBase = if ($ModrinthStaging) { 'https://staging-api.modrinth.com' } else { 'https://api.modrinth.com' }
        foreach ($n in $nodes) {
            $cl  = Get-ScStoreChangelogForMc -ChangelogSplit $clSplit -McVersion $n.McVersion
            $gvs = Get-ScProp $cfg.storeGameVersionsByMc $n.McVersion @($n.McVersion)
            Invoke-ScModrinthUpload -Config $cfg -Title (Get-ScStoreTitle $split $n.McVersion $n.LoaderDisplay) `
                -VersionNumber "$ver+mc$($n.McVersion)-$($n.MrLoader)" -Changelog $cl -VersionType $chan.Modrinth `
                -GameVersions @($gvs) -Loader $n.MrLoader -Jar $jarMap[$n.Node] -IncludeJei -IncludeMixinBooter:$n.IsLegacy -Featured:$n.IsFeatured `
                -ApiBase $apiBase -Execute:($Execute -and $mrEnabled)
        }
    }
}

# ── 4. CurseForge (one file per node) ────────────────────────────────────────
if (Want 'CurseForge') {
    Write-ScStep 'Upload to CurseForge'
    $cfEnabled = Get-StoreEnabled $cfg.curseforge
    if ($Execute -and -not $cfEnabled) {
        Write-ScInfo 'CurseForge disabled in release.json (set curseforge.enabled=true + a real projectId to publish) — skipped.'
    } else {
        if (-not $cfEnabled) { Write-ScWarn 'CurseForge is DISABLED — previewing only (would be skipped under -Execute).' }
        $cfEnvs    = @(Get-ScProp $cfg.curseforge 'environments' @('Client','Server'))
        $cfTagJava = [bool](Get-ScProp $cfg.curseforge 'tagJavaVersion' $true)
        foreach ($n in $nodes) {
            $cl    = Get-ScStoreChangelogForMc -ChangelogSplit $clSplit -McVersion $n.McVersion
            $gvs   = @(Get-ScProp $cfg.storeGameVersionsByMc $n.McVersion @($n.McVersion))
            $extra = @($gvs | Where-Object { $_ -ne $n.McVersion })
            $javaArg = if ($cfTagJava) { $n.Java } else { $null }
            Invoke-ScCurseForgeUpload -Config $cfg -Title (Get-ScStoreTitle $split $n.McVersion $n.LoaderDisplay) `
                -Changelog $cl -ReleaseType $chan.CurseForge -McVersion $n.McVersion -LoaderName $n.CfLoaderName `
                -Jar $jarMap[$n.Node] -ExtraGameVersions $extra -Environments $cfEnvs -JavaVersion $javaArg `
                -IncludeJei -IncludeMixinBooter:$n.IsLegacy -Execute:($Execute -and $cfEnabled)
        }
    }
}

# ── 5. Post-flight: reset changelog + bump version ───────────────────────────
if (Want 'PostFlight') {
    Write-ScStep 'Post-flight: reset changelog + bump version'
    Reset-ScChangelog -RepoRoot $root -ReleasedVersion $ver -Execute:$Execute
    $next = Step-ScVersion -RepoRoot $root -Split $split -NextVersion $NextVersion -Execute:$Execute
    # The version is single-sourced in root gradle.properties (the 1.12.2 build reads it from there), so the
    # bump only ever touches this one file alongside the changelog reset.
    Invoke-ScGitCommit -RepoRoot $root -Paths @('changelog.md','gradle.properties') `
        -Message "Reset changelog and bump $ver -> $next" -Execute:$Execute
    Invoke-ScGitPush -RepoRoot $root -Execute:$Execute
}

Write-ScStep ("Done ({0})." -f $(if ($Execute) { 'published' } else { 'dry run — nothing changed' }))
