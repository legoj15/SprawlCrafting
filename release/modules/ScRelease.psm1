# ScRelease.psm1 — SprawlCrafting release-automation library.
#
# Pure-logic + publishing helpers for cutting a release across GitHub, Modrinth and CurseForge from a
# single input (`version` in the root gradle.properties) plus the per-node Stonecutter configs.
# Mirrors the testing/ harness idiom (PowerShell module + JSON config; tokens via env vars).
#
# Adapted from BuildCraft's BcRelease.psm1, with SprawlCrafting-specific differences:
#   * SprawlCrafting ships TWO loaders per MC version (fabric + neoforge), so every store upload is
#     PER-NODE and tags its own loader — BuildCraft was NeoForge-only with one universal jar per MC.
#   * CurseForge gets the per-MC changelog (general lines + that version's {mc}-tagged lines), while every
#     Modrinth version gets ONE identical unified changelog (tagged lines kept inline as "- (mc) text") —
#     Modrinth's changelog tab dedupes a repeated text, so it must be byte-identical and the featured nodes
#     must upload first. There is no "primary vs backport" framing: every release node is an equal (the
#     modern nodes share one Stonecutter source, auto-discovered from versions/; the legacy 1.12.2 Forge node
#     and the 1.16.5 Forge+Fabric tree are separate builds bundling the com.legoj15:solver library from
#     solver-core/, injected via the `legacyForge` / `legacyTrees` config).
#   * Store dependencies are per NODE (integrations vary by node, not loader): release.json `dependencies`
#     keyed by a short key, each node object carrying the list of keys it advertises (Get-ScNodes derives
#     the modern nodes' keys from which whenProp gradle keys their node pins).
#   * The in-game update manifest is the STANDARD Forge/NeoForge update.json (homepage + per-MC
#     {version:changelog} + promos), rendered as PLAIN text — not BuildCraft's in-game §-formatted file.
#
# Everything here is side-effect-free EXCEPT the functions whose names start with a publishing verb
# (Invoke-*, Update-ScManifest -Write, Reset-*, Step-*). The orchestrator (Invoke-Release.ps1) only
# calls those when run with -Execute.

Set-StrictMode -Version Latest

# ─── Logging ─────────────────────────────────────────────────────────────────
function Write-ScStep  { param([string]$Msg) Write-Host "==> $Msg" -ForegroundColor Cyan }
function Write-ScInfo  { param([string]$Msg) Write-Host "    $Msg" -ForegroundColor Gray }
function Write-ScOk    { param([string]$Msg) Write-Host "    [OK] $Msg" -ForegroundColor Green }
function Write-ScWarn  { param([string]$Msg) Write-Host "    [!] $Msg" -ForegroundColor Yellow }
function Write-ScDry   { param([string]$Msg) Write-Host "    [dry-run] $Msg" -ForegroundColor DarkYellow }

# ─── Small property helpers (StrictMode-safe access to optional JSON fields) ──
# Note: use the PSObject.Properties[$Name] indexer (returns $null when absent) rather than
# `.Properties.Name -contains`, because the latter member-enumerates and THROWS under StrictMode when
# the object has zero properties (e.g. an empty {} from ConvertFrom-Json).
function Test-ScProp { param($Obj, [string]$Name) return ($null -ne $Obj) -and ($null -ne $Obj.PSObject.Properties[$Name]) }
function Get-ScProp  { param($Obj, [string]$Name, $Default = $null) if (Test-ScProp $Obj $Name) { return $Obj.$Name } return $Default }

# ─── Repo / config plumbing ──────────────────────────────────────────────────
function Get-ScRepoRoot {
    # release/modules/ -> release/ -> repo root
    return (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

function Get-ScReleaseConfig {
    param([string]$Path)
    if (-not $Path) { $Path = Join-Path $PSScriptRoot '..\config\release.json' }
    if (-not (Test-Path $Path)) { throw "Release config not found: $Path" }
    return (Get-Content -Raw $Path | ConvertFrom-Json)
}

function Read-ScProperties {
    # Minimal key=value .properties reader (ignores blanks + #comments).
    param([Parameter(Mandatory)][string]$Path)
    $h = @{}
    foreach ($line in (Get-Content -Path $Path)) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        $i = $t.IndexOf('=')
        if ($i -lt 1) { continue }
        $h[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
    }
    return $h
}

function Get-ScModVersion {
    param([string]$RepoRoot)
    if (-not $RepoRoot) { $RepoRoot = Get-ScRepoRoot }
    $p = Read-ScProperties (Join-Path $RepoRoot 'gradle.properties')
    if (-not $p.ContainsKey('version')) { throw "'version' not found in root gradle.properties" }
    return $p['version']
}

# ─── Version grammar ─────────────────────────────────────────────────────────
# Parse 1.0.0[-<suffix><N>] -> rich object. Suffix is one of rc/ar/br/hf (or none).
function Split-ScVersion {
    param([Parameter(Mandatory)][string]$Version)
    if ($Version -notmatch '^(?<base>\d+\.\d+\.\d+)(?:-(?<suffix>[a-z]+)(?<num>\d+))?$') {
        throw "Unrecognised version format: '$Version' (expected e.g. 1.0.0 or 1.0.0-rc4)"
    }
    $base   = $Matches['base']
    $suffix = if ($Matches.ContainsKey('suffix')) { $Matches['suffix'] } else { '' }
    $num    = if ($Matches.ContainsKey('num'))    { [int]$Matches['num'] } else { 0 }
    if ($suffix -and $suffix -notin @('rc','ar','br','hf')) {
        Write-ScWarn "Unknown version suffix '$suffix' — treating as a prerelease. Known: rc, ar, br, hf."
    }
    return [pscustomobject]@{
        Full   = $Version
        Base   = $base
        Suffix = $suffix          # '' for a final release
        Number = $num
        # GitHub: ar/br are pre-releases; rc/hf/none are 'latest'.
        IsGithubPrerelease = ($suffix -in @('ar','br'))
        # A "stable" build for promo purposes is a final release or a hotfix.
        IsStable           = ($suffix -in @('','hf'))
    }
}

# "Release Candidate", "Alpha Release", "Beta Release", "Hotfix", or '' for a final release.
function Get-ScTitlePhrase {
    param([Parameter(Mandatory)]$Split)
    switch ($Split.Suffix) {
        ''   { return '' }
        'rc' { return 'Release Candidate' }
        'ar' { return 'Alpha Release' }
        'br' { return 'Beta Release' }
        'hf' { return 'Hotfix' }
        default { return $Split.Suffix.ToUpper() }
    }
}

# GitHub release title, e.g. "1.0.0 Release Candidate 4" / "1.0.0".
function Get-ScGithubTitle {
    param([Parameter(Mandatory)]$Split)
    $phrase = Get-ScTitlePhrase $Split
    if ($phrase) { return "$($Split.Base) $phrase $($Split.Number)" }
    return $Split.Base
}

# Store (CurseForge/Modrinth) title, e.g. "1.0.0 NeoForge for 26.1.2 Release Candidate 4". The loader
# is in the title because SprawlCrafting uploads one file per (MC, loader) and the store file list
# would otherwise show two identically-named entries for the same MC version.
function Get-ScStoreTitle {
    param([Parameter(Mandatory)]$Split, [Parameter(Mandatory)][string]$McVersion, [Parameter(Mandatory)][string]$LoaderDisplay)
    $phrase = Get-ScTitlePhrase $Split
    $head = "$($Split.Base) $LoaderDisplay for $McVersion"
    if ($phrase) { return "$head $phrase $($Split.Number)" }
    return $head
}

# Cross-surface channel mapping. GitHub latest-vs-prerelease differs from CF/Modrinth on rc!
function Get-ScChannel {
    param([Parameter(Mandatory)]$Split)
    $cf = switch ($Split.Suffix) {
        ''   { 'release' }
        'hf' { 'release' }
        'rc' { 'beta' }
        'br' { 'beta' }
        'ar' { 'alpha' }
        default { 'beta' }
    }
    return [pscustomobject]@{
        GithubPrerelease = $Split.IsGithubPrerelease
        GithubLatest     = (-not $Split.IsGithubPrerelease)
        CurseForge       = $cf       # alpha|beta|release
        Modrinth         = $cf       # same vocabulary (release|beta|alpha)
    }
}

# ─── Stonecutter nodes & jars ────────────────────────────────────────────────
# One entry per versions/<node>/ directory. The node id is "<mc>-<loader>", so the loader is the
# trailing segment; the MC version (the +mc jar tag and the CF/Modrinth game version) is the node's
# minecraft_version. CfLoaderName / MrLoader are the store-specific spellings of the loader.
function Get-ScNodes {
    param([string]$RepoRoot, $Config)
    if (-not $RepoRoot) { $RepoRoot = Get-ScRepoRoot }
    if (-not $Config)   { $Config   = Get-ScReleaseConfig }
    $rootProps = Read-ScProperties (Join-Path $RepoRoot 'gradle.properties')
    # featuredMc names an MC version (not a node): both loaders of that version are featured on Modrinth.
    $featuredMc = Get-ScProp $Config 'featuredMc'
    # Store-dependency catalogue (key -> {type, modrinth, curseforge, whenProp?, loaders?}); modern nodes
    # derive their key list below, the legacy blocks name theirs explicitly.
    $depsCfg = Get-ScProp $Config 'dependencies'
    $nodes = @()
    foreach ($dir in (Get-ChildItem -Directory (Join-Path $RepoRoot 'versions') | Sort-Object Name)) {
        $pp = Join-Path $dir.FullName 'gradle.properties'
        if (-not (Test-Path $pp)) { continue }
        $p = Read-ScProperties $pp
        $loader = $dir.Name.Substring($dir.Name.LastIndexOf('-') + 1)   # 'fabric' | 'neoforge' | 'forge'
        $cfName = switch ($loader) { 'fabric' { 'Fabric' } 'neoforge' { 'NeoForge' } 'forge' { 'Forge' } default { $loader } }
        # Integrations vary per NODE: a modern node advertises exactly the config dependencies whose
        # whenProp gradle key it pins (e.g. no jei_version on the 26.x Forge nodes — no JEI is published
        # for them) AND whose loaders filter (if any) admits its loader (modmenu is Fabric-only). Entries
        # with no whenProp (mixinbooter) are legacy-only and never auto-added here.
        $depKeys = @()
        if ($depsCfg) {
            foreach ($dp in $depsCfg.PSObject.Properties) {
                $when = Get-ScProp $dp.Value 'whenProp'
                if (-not $when) { continue }
                if (-not $p.ContainsKey($when)) { continue }
                $depLoaders = @(Get-ScProp $dp.Value 'loaders' @())
                if ($depLoaders.Count -and $depLoaders -notcontains $loader) { continue }
                $depKeys += $dp.Name
            }
        }
        $nodes += [pscustomobject]@{
            Node          = $dir.Name
            McVersion     = $p['minecraft_version']     # also the jar +mc tag and the CF/MR game version
            Loader        = $loader                     # canonical lower-case loader
            MrLoader      = $loader                     # Modrinth loader name (lower-case)
            CfLoaderName  = $cfName                     # CurseForge loader entry name (capitalised)
            LoaderDisplay = $cfName                     # human-facing loader label (store titles)
            Java          = if ($p.ContainsKey('java_version')) { $p['java_version'] } elseif ($rootProps.ContainsKey('java_version')) { $rootProps['java_version'] } else { $null }
            IsFeatured    = ($p['minecraft_version'] -eq $featuredMc)
            IsLegacy      = $false                      # a standard Stonecutter node (modern jar name + buildAndCollect)
            Dependencies  = @($depKeys)                 # store-dependency keys this node advertises
            ExtraLoaders  = $true                       # eligible for config extraLoaders (e.g. Quilt on fabric)
        }
    }
    # The legacy 1.12.2 Forge build lives OUTSIDE versions/ (its own self-contained Gradle build), so the
    # directory walk above never sees it. Inject it as a synthetic node from the config's `legacyForge`
    # block. Its store identity (Forge loader, Java 8, 1.12.2 game version) and its jar name/location differ
    # from the modern nodes, so it carries IsLegacy + BuildDir/VerifyTasks/BuildTasks/JavaHome that Test-ScJars and the
    # -Build stage special-case. Java is taken from config (NOT inherited) because the forge gradle.properties
    # has no java_version key — without it the CurseForge upload would tag the wrong (or no) Java tier.
    $lf = Get-ScProp $Config 'legacyForge'
    if ($lf -and [bool](Get-ScProp $lf 'enabled' $false)) {
        $lfDir = Get-ScProp $lf 'dir' 'forge-1.12.2'
        if (Test-Path (Join-Path $RepoRoot (Join-Path $lfDir 'gradle.properties'))) {
            $lfMc     = Get-ScProp $lf 'mcVersion' '1.12.2'
            $lfLoader = Get-ScProp $lf 'loader' 'forge'
            $lfCf     = Get-ScProp $lf 'cfLoaderName' 'Forge'
            $nodes += [pscustomobject]@{
                Node          = "$lfMc-$lfLoader"
                McVersion     = $lfMc
                Loader        = $lfLoader
                MrLoader      = $lfLoader               # Modrinth loader id ('forge')
                CfLoaderName  = $lfCf                   # CurseForge loader entry ('Forge')
                LoaderDisplay = $lfCf
                Java          = Get-ScProp $lf 'java' '8'
                IsFeatured    = ($lfMc -eq $featuredMc)
                IsLegacy      = $true
                Dependencies  = @(Get-ScProp $lf 'dependencies' @())   # explicit: the legacy props don't carry the whenProp keys
                ExtraLoaders  = $true
                BuildDir      = $lfDir
                VerifyTasks   = @((Get-ScProp $lf 'buildTask' 'reobfJar'))   # no 'test': the solver suite runs in solver-core (step 0)
                BuildTasks    = @(Get-ScProp $lf 'buildTask' 'reobfJar')
                JavaHome      = Get-ScProp $lf 'javaHome' ''
                JarRel        = 'build/libs/sprawlcrafting-{version}.jar'   # no '+mc<mc>-<loader>' tag on this build
            }
        } else {
            Write-ScWarn "legacyForge.enabled but '$lfDir/gradle.properties' not found — skipping the 1.12.2 node."
        }
    }
    # Further legacy trees (currently legacy-1.16.5/: Forge + Fabric built from one shared source by a
    # separate Gradle build). Each tree is one `legacyTrees` entry in the config and yields one synthetic
    # node PER LOADER, because every store upload is per (MC version, loader). The tree's Gradle tasks are
    # tree-level (VerifyTasks/BuildTasks, run once per tree by the orchestrator); only the jar path and the
    # loader identity differ per node. The jar pattern takes {version}, {mc} and {loader} tokens and
    # defaults to the modern '+mc<mc>-<loader>' name under <loader>/build/libs/.
    foreach ($lt in @(Get-ScProp $Config 'legacyTrees' @())) {
        if (-not [bool](Get-ScProp $lt 'enabled' $true)) { continue }
        $ltDir = Get-ScProp $lt 'dir'
        $ltMc  = Get-ScProp $lt 'mcVersion'
        if (-not $ltDir -or -not $ltMc) { throw "legacyTrees entry needs both 'dir' and 'mcVersion'." }
        if (-not (Test-Path (Join-Path $RepoRoot (Join-Path $ltDir 'gradle.properties')))) {
            Write-ScWarn "legacyTrees entry '$ltDir' enabled but '$ltDir/gradle.properties' not found — skipping the $ltMc nodes."
            continue
        }
        $ltBuild  = @(Get-ScProp $lt 'buildTasks' @('build'))
        $ltVerify = @(Get-ScProp $lt 'verifyTasks' $ltBuild)
        foreach ($ll in @(Get-ScProp $lt 'loaders' @())) {
            $llLoader = Get-ScProp $ll 'loader'
            if (-not $llLoader) { throw "legacyTrees '$ltDir' has a loader entry without a 'loader' name." }
            $llCf = Get-ScProp $ll 'cfLoaderName' ((Get-Culture).TextInfo.ToTitleCase($llLoader))
            $nodeId = "$ltMc-$llLoader"
            if ($nodes | Where-Object { $_.Node -eq $nodeId }) { throw "Duplicate release node '$nodeId' (legacyTrees '$ltDir' collides with an existing node)." }
            $nodes += [pscustomobject]@{
                Node          = $nodeId
                McVersion     = $ltMc
                Loader        = $llLoader
                MrLoader      = $llLoader
                CfLoaderName  = $llCf
                LoaderDisplay = $llCf
                Java          = Get-ScProp $lt 'java' '8'
                IsFeatured    = ($ltMc -eq $featuredMc)
                IsLegacy      = $true
                Dependencies  = @(Get-ScProp $ll 'dependencies' @())   # explicit per-loader list (no whenProp props to derive from)
                # A tree loader may opt out of the config's extra loaders (Quilt never supported 1.16.5).
                ExtraLoaders  = [bool](Get-ScProp $ll 'extraLoaders' $true)
                BuildDir      = $ltDir
                VerifyTasks   = $ltVerify
                BuildTasks    = $ltBuild
                JavaHome      = Get-ScProp $lt 'javaHome' ''
                JarRel        = (Get-ScProp $ll 'jar' '{loader}/build/libs/sprawlcrafting-{version}+mc{mc}-{loader}.jar').Replace('{loader}', $llLoader)
            }
        }
    }
    if ($featuredMc -and -not ($nodes | Where-Object IsFeatured)) {
        Write-ScWarn "featuredMc '$featuredMc' from config matches no shipping node — nothing will be marked featured on Modrinth."
    }
    return $nodes
}

# Resolve -OnlyNodes selectors. Each is either a node name ("26.3-fabric": that node only) or an MC
# version ("26.3": every node on that MC version, so every loader that has one). A selector matching
# nothing throws — a typo in a resumed run must not silently shrink the set of files published.
# (Nodes held back by the ship gate are still matched here; the caller then leaves them out.)
function Select-ScNodes {
    param([Parameter(Mandatory)]$Nodes, [Parameter(Mandatory)][string[]]$Selectors)
    $Nodes = @($Nodes)
    foreach ($sel in $Selectors) {
        if (-not @($Nodes | Where-Object { $_.Node -eq $sel -or $_.McVersion -eq $sel }).Count) {
            throw "-OnlyNodes '$sel' matched none of the configured nodes (node names: $(($Nodes.Node) -join ', '); MC versions: $((@($Nodes.McVersion) | Select-Object -Unique) -join ', '))."
        }
    }
    return @($Nodes | Where-Object { $_.Node -in $Selectors -or $_.McVersion -in $Selectors })
}

# ─── Ship gate ───────────────────────────────────────────────────────────────
# release.json "heldBack" = { "loaders": [...], "nodes": [...], "reason": "..." } names nodes that are
# enumerated, built and verified like every other node (the full set still feeds the test stage, the
# store-config check and the manifest) but are NOT published to GitHub, Modrinth or CurseForge. A run
# lifts the hold with -ShipHeld <loader|node>[,...]; lifting the entry in the config is the permanent
# form. A -ShipHeld value matching no held node throws, like an -OnlyNodes typo. Returns Publish (what
# the store stages may publish), Held (still held), Lifted (held but released for this run) and the
# config's Reason text. SprawlCrafting has no primary node that "always publishes"; the closest
# equivalent — a node of featuredMc — is only WARNED about when held (Modrinth just gets no featured
# version that run) rather than failing the release.
function Resolve-ScHeldNodes {
    param([Parameter(Mandatory)]$Config, [Parameter(Mandatory)]$Nodes, [string[]]$Ship = @())
    $Nodes = @($Nodes)
    $loaders = @(); $names = @(); $reason = ''
    if (Test-ScProp $Config 'heldBack') {
        $hb = $Config.heldBack
        if (Test-ScProp $hb 'loaders') { $loaders = @($hb.loaders | ForEach-Object { ([string]$_).ToLowerInvariant() }) }
        if (Test-ScProp $hb 'nodes')   { $names   = @($hb.nodes   | ForEach-Object { ([string]$_).ToLowerInvariant() }) }
        if (Test-ScProp $hb 'reason')  { $reason  = [string]$hb.reason }
    }
    $held = @($Nodes | Where-Object { $_.Loader -in $loaders -or $_.Node.ToLowerInvariant() -in $names })
    $ship = @($Ship | Where-Object { $_ } | ForEach-Object { $_.Trim().ToLowerInvariant() })
    foreach ($s in $ship) {
        if (-not @($held | Where-Object { $_.Loader -eq $s -or $_.Node.ToLowerInvariant() -eq $s }).Count) {
            $heldNames = if ($held.Count) { ($held.Node) -join ', ' } else { '(none: nothing is held back)' }
            throw "-ShipHeld '$s' matched no held-back node (held: $heldNames)."
        }
    }
    $liftedNames = @($held | Where-Object { $ship -contains $_.Loader -or $ship -contains $_.Node.ToLowerInvariant() } | ForEach-Object Node)
    $lifted = @($held | Where-Object { $_.Node -in $liftedNames })
    $still  = @($held | Where-Object { $_.Node -notin $liftedNames })
    $stillNames = @($still | ForEach-Object Node)
    $featured = @($still | Where-Object IsFeatured)
    if ($featured.Count) { Write-ScWarn "featuredMc node(s) $(($featured.Node) -join ', ') held back — no Modrinth version will be featured this run." }
    return [pscustomobject]@{
        Publish = @($Nodes | Where-Object { $_.Node -notin $stillNames })
        Held    = $still
        Lifted  = $lifted
        Reason  = $reason
    }
}

function Get-ScJarPath {
    # Production jar produced by `:<node>:build` / collected by `buildAndCollect`. Looks in
    # testing/dist/ (where buildAndCollect collects) then build/libs/<version>/ (the mirror). Returns
    # the first that exists, else the expected testing/dist path (for the dry-run / missing report).
    param([Parameter(Mandatory)][string]$RepoRoot, [Parameter(Mandatory)][string]$Version,
          [Parameter(Mandatory)][string]$McVersion, [Parameter(Mandatory)][string]$Loader)
    $name = "sprawlcrafting-$Version+mc$McVersion-$Loader.jar"
    $candidates = @(
        (Join-Path $RepoRoot "testing/dist/$name"),
        (Join-Path $RepoRoot "build/libs/$Version/$name")
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return (Resolve-Path $c).Path } }
    return $candidates[0]
}

function Get-ScLegacyJarPath {
    # The production jar of a legacy node (a self-contained Gradle build outside versions/): <dir>/<JarRel>,
    # with {version} and {mc} substituted. 1.12.2 (RFG: archivesName=mod_id, version=mod_version) is
    # <dir>/build/libs/sprawlcrafting-<ver>.jar with NO '+mc<mc>-<loader>' tag; the 1.16.5 tree names its jars
    # <loader>/build/libs/sprawlcrafting-<ver>+mc1.16.5-<loader>.jar. We name the jar EXACTLY so the sibling
    # '-dev'/'-sources' jars are never picked up. Assumes the legacy build's version is kept in lockstep with
    # the root version (both read it from root gradle.properties; Step-ScVersion bumps that one line).
    param([Parameter(Mandatory)][string]$RepoRoot, [Parameter(Mandatory)][string]$BuildDir, [Parameter(Mandatory)][string]$JarRel,
          [Parameter(Mandatory)][string]$Version, [Parameter(Mandatory)][string]$McVersion)
    $rel  = $JarRel.Replace('{version}', $Version).Replace('{mc}', $McVersion)
    $path = Join-Path $RepoRoot (Join-Path $BuildDir $rel)
    if (Test-Path $path) { return (Resolve-Path $path).Path }
    return $path
}

function Get-ScNodeJarPath {
    # A node's jar path regardless of flavour: the modern Stonecutter jars or a legacy tree's own output.
    param([Parameter(Mandatory)][string]$RepoRoot, [Parameter(Mandatory)][string]$Version, [Parameter(Mandatory)]$Node)
    if (Get-ScProp $Node 'IsLegacy' $false) {
        return (Get-ScLegacyJarPath -RepoRoot $RepoRoot -BuildDir $Node.BuildDir -JarRel $Node.JarRel -Version $Version -McVersion $Node.McVersion)
    }
    return (Get-ScJarPath -RepoRoot $RepoRoot -Version $Version -McVersion $Node.McVersion -Loader $Node.MrLoader)
}

function Test-ScJars {
    # Resolve every PUBLISHING node's jar into a node-name -> path map. Throws when one is missing (a
    # dry run without -AllowMissingJars fails like a real run, so the plan can't quietly assume a jar)
    # unless -AllowMissing downgrades each miss to a warning and maps the path it WOULD have — for
    # previewing a release before the build. Held nodes never come through here (their jars are not
    # required); the orchestrator reports those separately via Get-ScNodeJarPath.
    param([Parameter(Mandatory)][string]$RepoRoot, [Parameter(Mandatory)][string]$Version, [Parameter(Mandatory)]$Nodes,
          [switch]$AllowMissing)
    $map = @{}
    $missing = @()
    foreach ($n in @($Nodes)) {
        $jar = Get-ScNodeJarPath -RepoRoot $RepoRoot -Version $Version -Node $n
        $map[$n.Node] = $jar
        if (-not (Test-Path -LiteralPath $jar)) {
            if ($AllowMissing) { Write-ScWarn "jar not built yet (-AllowMissingJars): $jar" }
            else { $missing += $jar }
        }
    }
    if ($missing.Count) {
        throw "Missing shipping jar(s) for $Version — run ``./gradlew buildAndCollect`` (or pass -Build):`n  " + ($missing -join "`n  ")
    }
    return $map
}

# ─── Changelog variants ──────────────────────────────────────────────────────
# Raw changelog.md body (everything after the '###### Changes since X:' header line), verbatim
# Markdown — used for the GitHub release body and every store upload.
function Get-ScChangelogBody {
    param([string]$RepoRoot)
    if (-not $RepoRoot) { $RepoRoot = Get-ScRepoRoot }
    $path = Join-Path $RepoRoot 'changelog.md'
    if (-not (Test-Path $path)) { throw "changelog.md not found at $path — create it before releasing." }
    $lines = @(Get-Content -Path $path)
    if ($lines.Count -lt 1) { throw 'changelog.md is empty.' }
    # Drop the first line (the "Changes since ..." header) and any leading blank lines.
    $body = @(if ($lines.Count -gt 1) { $lines[1..($lines.Count - 1)] })   # @() — a 1-line body is a scalar (StrictMode .Count throw)
    while ($body.Count -gt 0 -and [string]::IsNullOrWhiteSpace($body[0])) { $body = @($body[1..($body.Count - 1)]) }
    return ($body -join "`n").TrimEnd()
}

# Partition the changelog body into general lines and version-tagged lines, preserving document order.
# A bullet of the form "- {MC.VER} text" is routed only to that MC version's surfaces, with the tag
# stripped so the text reads naturally; every other line is general and goes to every surface. The tag
# is curly-braced and must be a bare dotted version number followed by a space — NOT [MC.VER]: square
# brackets collide with a Markdown link at bullet start ("- [text](url) ..."). Returns Entries
# (ordered; Tag = version string or $null) and Tagged (version -> lines). Tag typos -> Test-ScChangelogTags.
function Split-ScChangelogByVersion {
    param([AllowEmptyString()][string]$Body = '')
    $entries = [System.Collections.Generic.List[object]]::new()
    $tagged  = @{}
    foreach ($raw in ($Body -split "`r?`n")) {
        $line = $raw.TrimEnd()
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line -match '^- \{(\d+(?:\.\d+)+)\} (.*)$') {
            $ver  = $Matches[1]
            $text = "- $($Matches[2].TrimEnd())"
            if (-not $tagged.ContainsKey($ver)) {
                $tagged[$ver] = [System.Collections.Generic.List[string]]::new()
            }
            $tagged[$ver].Add($text)
            $entries.Add([pscustomobject]@{ Tag = $ver; Line = $text })
        } else {
            $entries.Add([pscustomobject]@{ Tag = $null; Line = $line })
        }
    }
    return [pscustomobject]@{ Entries = @($entries); Tagged = $tagged }
}

# Fail loudly when a tag names an MC version nothing serves — a typo'd tag would otherwise silently
# drop the line from every surface except the verbatim GitHub body.
function Test-ScChangelogTags {
    param([Parameter(Mandatory)]$ChangelogSplit, [Parameter(Mandatory)][string[]]$KnownVersions)
    $unknown = @($ChangelogSplit.Tagged.Keys | Where-Object { $_ -notin $KnownVersions } | Sort-Object)
    if ($unknown.Count) {
        throw ("changelog.md tags unknown MC version(s): {$($unknown -join '}, {')} — known: " +
               (($KnownVersions | Sort-Object) -join ', ') + '. Fix the tag or add the version.')
    }
}

# CurseForge changelog for a given MC version: the general (untagged) lines plus that version's own
# {mc}-tagged lines, in document order, tag stripped. Every CurseForge file of an MC version gets this
# (no primary/backport distinction — all versions are first-class). Another version's exclusive {mc}
# line never leaks here. Neutral fallback only if there are no lines at all.
function Get-ScStoreChangelogForMc {
    param([Parameter(Mandatory)]$ChangelogSplit, [Parameter(Mandatory)][string]$McVersion)
    $lines = @($ChangelogSplit.Entries | Where-Object { $null -eq $_.Tag -or $_.Tag -eq $McVersion } | ForEach-Object Line)
    if (-not $lines.Count) { return "Release for Minecraft $McVersion. See the GitHub release page for details." }
    return ($lines -join "`n")
}

# Modrinth changelog, IDENTICAL for every version of one release (every loader, every MC version):
# every line in document order, a {mc}-tag rendered inline as "- (1.12.2) text" instead of routing the
# line away. (CurseForge does NOT use this: its files keep Get-ScStoreChangelogForMc, per MC version.)
# Byte-identity is load-bearing on Modrinth: its changelog tab hides a version's text when an OLDER
# version on the same page carries the exact same string, so one shared text shows once per release —
# which is why the orchestrator uploads the featured nodes FIRST. Split-ScChangelogByVersion yields a
# single .Tag per entry (no multi-version tags), unlike BuildCraft's .Tags array.
function Get-ScUnifiedStoreChangelog {
    param([Parameter(Mandatory)]$ChangelogSplit)
    $lines = @($ChangelogSplit.Entries | ForEach-Object {
        if ($null -ne $_.Tag) { $_.Line -replace '^- ', "- ($($_.Tag)) " } else { $_.Line }
    })
    if (-not $lines.Count) { return 'Compatibility release' }
    return ($lines -join "`n")
}

# ─── Update manifest (update.json) ───────────────────────────────────────────
# Render a Markdown changelog block to PLAIN text for the NeoForge native update notification (and the
# update.json the Fabric/Mod-Menu checker can read). Markdown links [text](url) collapse to their TEXT
# (a bare http(s):// URL written directly stays put). Emphasis + inline `code` markers are removed.
# Bullets keep their '- ' and become \n-separated lines. With -Bold 'section' the emphasis is converted
# to Minecraft legacy section-sign (§) codes instead of stripped, for surfaces that render them.
function ConvertTo-ScPlainChangelog {
    param([Parameter(Mandatory)][string]$Body, [ValidateSet('section','strip')][string]$Bold = 'strip')
    $S = [char]0x00A7
    $out = [System.Collections.Generic.List[string]]::new()
    foreach ($raw in ($Body -split "`r?`n")) {
        $line = $raw.TrimEnd()
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $line = [regex]::Replace($line, '\[([^\]]+)\]\([^)]+\)', '$1')   # [text](url) -> text
        if ($Bold -eq 'section') {
            $line = [regex]::Replace($line, '\*\*(.+?)\*\*', "${S}l`$1${S}r")   # bold
            $line = [regex]::Replace($line, '\*(.+?)\*',     "${S}o`$1${S}r")   # italic (after bold)
        } else {
            $line = [regex]::Replace($line, '\*\*(.+?)\*\*', '$1')
            $line = [regex]::Replace($line, '\*(.+?)\*',     '$1')
        }
        $line = [regex]::Replace($line, '`(.+?)`', '$1')                         # inline code
        $out.Add($line)
    }
    return ($out -join "`n")
}

# The update.json changelog value for one MC version: general lines + that version's {mc}-tagged lines,
# original document order, rendered to plain (or §) text. A block with no applicable lines still needs
# SOME text (the update checker reads the version key) so it falls back to a neutral note.
function Get-ScManifestChangelogForMc {
    param([Parameter(Mandatory)]$ChangelogSplit, [Parameter(Mandatory)][string]$McVersion, [ValidateSet('section','strip')][string]$Bold = 'strip')
    $lines = @($ChangelogSplit.Entries | Where-Object { $null -eq $_.Tag -or $_.Tag -eq $McVersion } | ForEach-Object Line)
    if (-not $lines.Count) { return "Release for Minecraft $McVersion." }
    return ConvertTo-ScPlainChangelog -Body ($lines -join "`n") -Bold $Bold
}

# Escape every non-ASCII char as \uXXXX so the served file is pure ASCII (avoids any UTF-8/BOM
# surprise on a file fetched raw by every client's update check).
function ConvertTo-ScAsciiJson {
    param([Parameter(Mandatory)][string]$Json)
    $sb = [System.Text.StringBuilder]::new()
    foreach ($ch in $Json.ToCharArray()) {
        $code = [int][char]$ch
        if ($code -gt 127) { [void]$sb.Append(('\u{0:x4}' -f $code)) } else { [void]$sb.Append($ch) }
    }
    return $sb.ToString()
}

# Returns the new update.json text (ASCII, validated). Does NOT write unless -Write is given.
# Keyed by MC version (NOT node) — the update checker is loader-agnostic, so both loaders of an MC
# version share one block. NodeMcVersions must be the UNIQUE set of shipping MC versions.
function Update-ScManifest {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$ManifestPath,
        [Parameter(Mandatory)][string]$Version,
        [Parameter(Mandatory)][string]$ChangelogBody,
        [string[]]$NodeMcVersions = @(),
        [string]$IngameBold = 'strip',
        [switch]$PromoteRecommended,
        [switch]$Write
    )
    if (-not (Test-Path $ManifestPath)) { throw "Manifest not found: $ManifestPath" }
    $json  = Get-Content -Raw $ManifestPath | ConvertFrom-Json
    $split = Split-ScVersion $Version
    # Pipe through ForEach-Object (not `.Properties.Name`) so an empty endOfSupportLines {} yields an
    # empty list instead of throwing under StrictMode (member enumeration over a zero-length collection).
    $eol   = @((Get-ScProp $Config 'endOfSupportLines' ([pscustomobject]@{})).PSObject.Properties | ForEach-Object Name)

    # 0. Seed a block + promo pair for any shipping MC version not yet in the manifest, so a newly added
    #    MC line can never silently ship without update coverage. New blocks are inserted before
    #    'promos' to keep the file's homepage -> blocks -> promos shape.
    if (-not ($json.PSObject.Properties.Name -contains 'promos')) { throw "Manifest has no 'promos' object: $ManifestPath" }
    $newBlocks = @($NodeMcVersions | Where-Object { $_ -and -not ($json.PSObject.Properties.Name -contains $_) } | Select-Object -Unique)
    if ($newBlocks.Count) {
        $promos = $json.promos
        $json.PSObject.Properties.Remove('promos')                       # drop, so re-adding keeps it last
        foreach ($mc in $newBlocks) {
            $json | Add-Member -NotePropertyName $mc -NotePropertyValue ([pscustomobject]@{}) -Force
            Write-ScInfo "seeded new manifest block for MC $mc (first release to support it)"
        }
        $json | Add-Member -NotePropertyName 'promos' -NotePropertyValue $promos -Force
    }
    foreach ($mc in @($NodeMcVersions | Select-Object -Unique)) {
        foreach ($kind in @('recommended','latest')) {
            $key = "$mc-$kind"
            if (-not ($json.promos.PSObject.Properties.Name -contains $key)) {
                $json.promos | Add-Member -NotePropertyName $key -NotePropertyValue $Version -Force
                Write-ScInfo "seeded new promo $key -> $Version"
            }
        }
    }

    # 1. Add this version's changelog to every MC block. Version-tagged lines are filtered so each
    #    player sees only general changes plus fixes specific to their MC version.
    $clSplit = Split-ScChangelogByVersion -Body $ChangelogBody
    foreach ($prop in $json.PSObject.Properties) {
        if ($prop.Name -in @('homepage','promos')) { continue }
        if ($prop.Value -is [pscustomobject]) {
            $ingame = Get-ScManifestChangelogForMc -ChangelogSplit $clSplit -McVersion $prop.Name -Bold $IngameBold
            $prop.Value | Add-Member -NotePropertyName $Version -NotePropertyValue $ingame -Force
        }
    }

    # 2. Repoint promos. -latest always advances; -recommended follows the configured policy.
    foreach ($pp in $json.promos.PSObject.Properties) {
        if ($pp.Name -notmatch '^(?<line>.+)-(?<kind>recommended|latest)$') { continue }
        $line = $Matches['line']; $kind = $Matches['kind']
        if ($kind -eq 'latest') {
            $pp.Value = $Version
            continue
        }
        # recommended:
        if ($eol -contains $line) {
            $pp.Value = $Config.endOfSupportLines.$line          # EOL line: pinned, frozen
        } else {
            $curStable = (Split-ScVersion $pp.Value).IsStable
            # Advance recommended if this build is itself stable (final/hotfix), OR if no stable has
            # shipped on this line yet (still in the initial prerelease run-up), OR if this release was
            # explicitly promoted (-PromoteRecommended): a bugfix-heavy prerelease the author wants
            # players pulled onto now, overriding the "don't recommend a prerelease" default.
            if ($split.IsStable -or (-not $curStable) -or $PromoteRecommended) { $pp.Value = $Version }
            # else: a prerelease after a stable already shipped — leave recommended where it is.
        }
    }

    # Post-condition: every shipping MC version must now have a block + both promos.
    foreach ($mc in @($NodeMcVersions | Select-Object -Unique)) {
        if (-not ($json.PSObject.Properties.Name -contains $mc)) { throw "Manifest is missing a block for shipping MC '$mc' after seeding." }
        foreach ($kind in @('recommended','latest')) {
            if (-not ($json.promos.PSObject.Properties.Name -contains "$mc-$kind")) { throw "Manifest is missing promo '$mc-$kind' after seeding." }
        }
    }

    $out = ConvertTo-ScAsciiJson ($json | ConvertTo-Json -Depth 12)
    $null = $out | ConvertFrom-Json    # validate round-trip (throws on malformed)
    if ($Write) {
        Set-Content -Path $ManifestPath -Value $out -Encoding utf8 -NoNewline
        Add-Content -Path $ManifestPath -Value "`n" -Encoding utf8 -NoNewline   # trailing newline
    }
    return $out
}

# ─── GitHub release (via gh) ─────────────────────────────────────────────────
function Test-ScGithubReleaseExists {
    # True when a GitHub release for $Tag already exists. Read-only (gh release view). Returns $null when
    # gh is not installed, so a dry run on a machine without gh still completes; callers decide what that means.
    param([Parameter(Mandatory)]$Config, [Parameter(Mandatory)][string]$Tag)
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { return $null }
    & gh release view $Tag --repo $Config.github.repo *> $null
    return ($LASTEXITCODE -eq 0)
}

function Invoke-ScGithubRelease {
    param(
        [Parameter(Mandatory)]$Config, [Parameter(Mandatory)]$Split, [Parameter(Mandatory)]$Channel,
        [Parameter(Mandatory)][string]$Title, [Parameter(Mandatory)][string]$Body,
        [Parameter(Mandatory)][string[]]$Jars, [Parameter(Mandatory)][string]$Commit,
        [switch]$Execute
    )
    $tag = $Split.Full
    $bodyFile = Join-Path ([System.IO.Path]::GetTempPath()) "sc-release-$tag.md"
    $ghArgs = @('release','create', $tag, '--repo', $Config.github.repo, '--target', $Commit,
                '--title', $Title, '--notes-file', $bodyFile)
    if ($Channel.GithubPrerelease) { $ghArgs += '--prerelease' } else { $ghArgs += '--latest' }
    $ghArgs += $Jars

    Write-ScInfo "tag=$tag  title=`"$Title`"  $(if($Channel.GithubPrerelease){'PRE-RELEASE'}else{'LATEST'})"
    Write-ScInfo "assets: $($Jars.Count) jar(s)"
    if (-not $Execute) {
        Write-ScDry "gh $($ghArgs -join ' ')"
        Write-ScDry "release body = changelog.md (verbatim, $([regex]::Matches($Body,"`n").Count + 1) lines)"
        return
    }
    Set-Content -Path $bodyFile -Value $Body -Encoding utf8
    # Refuse to clobber an existing release/tag silently.
    if (Test-ScGithubReleaseExists -Config $Config -Tag $tag) { throw "GitHub release '$tag' already exists. Delete it or bump the version." }
    & gh @ghArgs
    if ($LASTEXITCODE -ne 0) { throw "gh release create failed (exit $LASTEXITCODE)." }
    Remove-Item $bodyFile -ErrorAction SilentlyContinue
    Write-ScOk "Created GitHub release $tag"
}

# ─── Multipart helper (curl.exe — precise control over part content-types) ───
function Invoke-ScMultipart {
    param([Parameter(Mandatory)][string]$Uri, [string[]]$Headers = @(), [Parameter(Mandatory)][string[]]$Forms)
    $curlArgs = @('-sS','-w','\n%{http_code}','-X','POST', $Uri)
    foreach ($h in $Headers) { $curlArgs += @('-H', $h) }
    foreach ($f in $Forms)   { $curlArgs += @('-F', $f) }
    $resp = & curl.exe @curlArgs 2>&1
    $text = ($resp | Out-String).Trim()
    $parts = $text -split "`n"
    $code = $parts[-1]
    $body = if ($parts.Count -gt 1) { $parts[0..($parts.Count - 2)] -join "`n" } else { '' }
    return [pscustomobject]@{ Code = $code; Body = $body }
}

# ─── Store dependencies & extra loaders ──────────────────────────────────────
# Resolve a node's dependency keys against release.json's top-level "dependencies" catalogue (key ->
# { "type": "required"|"optional", "modrinth": "<projectId>", "curseforge": "<slug>", "whenProp"?,
# "loaders"? }). Returns @{ Key; Type; ModrinthId; CurseForgeSlug } entries, REQUIRED FIRST (both
# stores list required dependencies ahead of optional ones). A key missing from the catalogue throws;
# Test-ScStoreConfig catches that up front, this is the belt to its braces.
function Get-ScStoreDependencyList {
    param([Parameter(Mandatory)]$Config, [string[]]$Keys = @())
    $cfg = Get-ScProp $Config 'dependencies'
    $list = @()
    foreach ($k in @($Keys)) {
        $e = if ($cfg) { Get-ScProp $cfg $k } else { $null }
        if ($null -eq $e) { throw "release.json dependencies has no entry '$k' — a node advertises it but the catalogue lacks it." }
        $list += [pscustomobject]@{
            Key           = $k
            Type          = [string](Get-ScProp $e 'type')
            ModrinthId    = [string](Get-ScProp $e 'modrinth')
            CurseForgeSlug = [string](Get-ScProp $e 'curseforge')
        }
    }
    return @(@($list | Where-Object { $_.Type -eq 'required' }) + @($list | Where-Object { $_.Type -ne 'required' }))
}

# "fabric-api(req), jei(opt), modmenu(opt)" — the preview/log rendering of a dependency list.
function Get-ScDependencyText {
    param([Parameter(Mandatory)]$Deps)
    if (-not @($Deps).Count) { return '(none)' }
    return (@($Deps) | ForEach-Object { "$($_.Key)($(if ($_.Type -eq 'required') { 'req' } else { 'opt' }))" }) -join ', '
}

# Extra loaders a node's file is also tagged with (e.g. Quilt on the fabric files, which run on Quilt
# Loader): config <store>.extraLoaders.<loader> — Modrinth loader ids, or CurseForge loader NAMES. A
# legacyTrees loader entry opts out per node with "extraLoaders": false (Quilt never supported 1.16.5).
# Missing entry = none.
function Get-ScExtraLoaders {
    param([Parameter(Mandatory)]$Config, [Parameter(Mandatory)][ValidateSet('curseforge','modrinth')][string]$Store,
          [Parameter(Mandatory)]$Node)
    if (-not [bool](Get-ScProp $Node 'ExtraLoaders' $true)) { return @() }
    $s = $Config.$Store
    if ((Test-ScProp $s 'extraLoaders') -and (Test-ScProp $s.extraLoaders $Node.Loader)) {
        # ($Node.Loader) in parens: a bare $Node.Loader mid-dot-chain would parse as a property NAME.
        return @($s.extraLoaders.($Node.Loader) | ForEach-Object { [string]$_ })
    }
    return @()
}

# Everything the store legs read from release.json, checked up front (dry run included) so a bad entry
# fails before the slow test suite or any upload, not on the 7th file. Held nodes are checked too:
# lifting the gate later must not be the first time a missing entry shows up.
function Test-ScStoreConfig {
    param([Parameter(Mandatory)]$Config, [Parameter(Mandatory)]$Nodes)
    $cfg = Get-ScProp $Config 'dependencies'
    if ($null -eq $cfg) { throw 'release.json is missing its top-level "dependencies" object — every node store-dep resolves against it.' }
    foreach ($dp in $cfg.PSObject.Properties) {
        foreach ($f in @('type','modrinth','curseforge')) {
            if (-not (Get-ScProp $dp.Value $f)) { throw "release.json dependencies.$($dp.Name) is missing its '$f' field." }
        }
        $t = [string](Get-ScProp $dp.Value 'type')
        if ($t -notin @('required','optional')) {
            throw "release.json dependencies.$($dp.Name).type must be 'required' or 'optional' (got '$t')."
        }
    }
    foreach ($n in @($Nodes)) {
        foreach ($k in @($n.Dependencies)) {
            if ($null -eq (Get-ScProp $cfg $k)) { throw "Node '$($n.Node)' references dependency key '$k' missing from release.json dependencies." }
        }
    }
    # extraLoaders must be arrays of strings (a bare "quilt" where ["quilt"] was meant would upload a
    # loader tag per CHARACTER on Modrinth).
    foreach ($store in @('modrinth','curseforge')) {
        $s = Get-ScProp $Config $store
        $xl = Get-ScProp $s 'extraLoaders'
        if ($null -eq $xl) { continue }
        foreach ($lp in $xl.PSObject.Properties) {
            if ($lp.Value -isnot [System.Array]) {
                throw "release.json ${store}.extraLoaders.$($lp.Name) must be an ARRAY of loader names (e.g. [`"quilt`"] on modrinth, [`"Quilt`"] on curseforge)."
            }
            foreach ($v in @($lp.Value)) {
                if ($v -isnot [string]) { throw "release.json ${store}.extraLoaders.$($lp.Name) has a non-string entry." }
            }
        }
    }
}

# ─── CurseForge ──────────────────────────────────────────────────────────────
# CurseForge models EVERYTHING you can tag a file with — Minecraft versions, the modloader, the Java
# tier, and the Client/Server "environment" indicator — as ordinary entries in ONE flat
# /api/game/versions list, differing only by their gameVersionTypeID. You collect each entry's numeric
# .id and post them together in metadata.gameVersions. Presence of the Client id AND the Server id
# populates the desktop-app environment indicator (and CF requires an environment tag for all MC-mod
# files from 2026-07-15 onward).
#
# This is the PURE resolver: given CF's already-fetched version + version-type lists, it maps the
# desired names to ids. Minecraft version(s) and the loader(s) are matched by name — loader NAMES don't
# collide across CF types, so an extra loader (e.g. Quilt) resolves like the primary one and a missing
# one throws. Environment ("Client"/"Server") and the Java tier are matched WITHIN their version TYPE
# (located by the type's stable slug 'environment*' / 'java*') so a bare name like "Server" can't grab
# an unrelated id. A missing required name throws; a missing Java tier is a recorded warning (CF curates
# the Java list by hand and lags new JDKs). Returns { Ids = int[]; Warnings = string[] }.
function Select-ScCurseForgeVersionIds {
    param(
        [Parameter(Mandatory)]$Versions, [Parameter(Mandatory)]$Types,
        [Parameter(Mandatory)][string]$McVersion, [string[]]$Extra = @(),
        [string[]]$LoaderName = @(), [string[]]$Environments = @(), [string]$JavaName
    )
    $typeIdsForSlug = { param([string]$Pattern) @($Types | Where-Object { $_.slug -like $Pattern } | ForEach-Object id) }
    $envTypeIds  = & $typeIdsForSlug 'environment*'
    $javaTypeIds = & $typeIdsForSlug 'java*'

    $ids      = [System.Collections.Generic.List[int]]::new()
    $warnings = [System.Collections.Generic.List[string]]::new()

    # Minecraft version(s) + loader(s) — required, matched by name. LoaderName carries the node's own
    # loader plus any extra loaders (e.g. 'Fabric','Quilt'); each resolves like the primary loader.
    $loaderNames = @($LoaderName | Where-Object { $_ })
    $required = @($McVersion) + $Extra + $loaderNames
    foreach ($name in ($required | Select-Object -Unique)) {
        $hit = $Versions | Where-Object { $_.name -eq $name } | Select-Object -First 1
        if (-not $hit) {
            if ($loaderNames -contains $name) { throw "CurseForge has no loader named '$name'." }
            throw "CurseForge has no game version named '$name' yet — cannot upload. Wait for CF to add it."
        }
        $ids.Add([int]$hit.id)
    }

    # Environment (Client/Server) — required, scoped to the 'environment' version type.
    foreach ($env in ($Environments | Select-Object -Unique)) {
        $hit = $Versions | Where-Object { $_.gameVersionTypeID -in $envTypeIds -and $_.name -eq $env } | Select-Object -First 1
        if (-not $hit) { throw "CurseForge has no '$env' entry under its 'environment' version type — cannot set the client/server indicator." }
        $ids.Add([int]$hit.id)
    }

    # Java tier (e.g. "Java 25") — optional, scoped to the 'java' version type; warn + skip if absent.
    if ($JavaName) {
        $hit = $Versions | Where-Object { $_.gameVersionTypeID -in $javaTypeIds -and $_.name -eq $JavaName } | Select-Object -First 1
        if ($hit) { $ids.Add([int]$hit.id) }
        else { $warnings.Add("CurseForge has no '$JavaName' tier yet — uploading without a Java tag (add it once CF lists the tier).") }
    }

    return [pscustomobject]@{ Ids = @($ids); Warnings = @($warnings) }
}

# Network wrapper: fetch CF's version + version-type lists, then delegate to the pure resolver above.
# Auth via the X-Api-Token HEADER (never token-in-URL, so it can't leak through a logged request line).
# -LoaderName is the node's CF loader name plus any extra loader names (e.g. 'Fabric','Quilt').
function Resolve-ScCurseForgeVersionIds {
    param([Parameter(Mandatory)]$Config, [Parameter(Mandatory)][string]$Token,
          [Parameter(Mandatory)][string]$McVersion, [string[]]$Extra = @(),
          [Parameter(Mandatory)][string[]]$LoaderName, [string[]]$Environments = @(), [string]$JavaName)
    $headers  = @{ 'X-Api-Token' = $Token }
    $versions = Invoke-RestMethod -Uri 'https://minecraft.curseforge.com/api/game/versions'      -Headers $headers -Method Get
    $types    = Invoke-RestMethod -Uri 'https://minecraft.curseforge.com/api/game/version-types' -Headers $headers -Method Get
    $sel = Select-ScCurseForgeVersionIds -Versions $versions -Types $types -McVersion $McVersion `
              -Extra $Extra -LoaderName $LoaderName -Environments $Environments -JavaName $JavaName
    foreach ($w in $sel.Warnings) { Write-ScWarn $w }
    return $sel.Ids
}

function Invoke-ScCurseForgeUpload {
    param(
        [Parameter(Mandatory)]$Config, [Parameter(Mandatory)][string]$Title,
        [Parameter(Mandatory)][string]$Changelog, [Parameter(Mandatory)][string]$ReleaseType,
        [Parameter(Mandatory)][string]$McVersion, [Parameter(Mandatory)][string]$LoaderName,
        [Parameter(Mandatory)][string]$Jar,
        [string[]]$ExtraGameVersions = @(), [string[]]$Environments = @(), [string]$JavaVersion,
        [string[]]$DependencyKeys = @(), [string[]]$ExtraLoaders = @(), [switch]$Execute
    )
    $metaObj = [ordered]@{
        changelog     = $Changelog
        changelogType = 'markdown'
        displayName   = $Title
        releaseType   = $ReleaseType
    }
    # Relations from the node's dependency keys (config `dependencies`), required first. What each node
    # advertises varies per NODE (e.g. mixinbooter only on 1.12.2-forge, whose client hard-stops without
    # it — see forge-1.12.2 ClientProxy.requireMixinBooter).
    $depList   = @(Get-ScStoreDependencyList -Config $Config -Keys $DependencyKeys)
    $relations = @($depList | ForEach-Object { @{ slug = $_.CurseForgeSlug; type = "$($_.Type)Dependency" } })
    if ($relations.Count) { $metaObj.relations = @{ projects = $relations } }
    # Extra loader names (e.g. Quilt) tag the file like the primary loader does.
    $loaderNames = @(@($LoaderName) + @($ExtraLoaders))
    # The Java toolchain version (e.g. "25") becomes CF's tier NAME "Java 25" — capital J, one space, bare major.
    $javaName = if ($JavaVersion) { "Java $JavaVersion" } else { $null }

    Write-ScInfo "CF [$McVersion/$($loaderNames -join '+')]  `"$Title`"  ($ReleaseType)  <- $(Split-Path $Jar -Leaf)"
    Write-ScInfo "    env=$($Environments -join '+')  java=$(if($javaName){$javaName}else{'(none)'})  deps=$(Get-ScDependencyText $depList)"
    if (-not $Execute) {
        $names = @($McVersion) + $loaderNames + $ExtraGameVersions + $Environments
        if ($javaName) { $names += $javaName }
        $metaObj.gameVersionNames = $names
        Write-ScDry "POST .../projects/$($Config.curseforge.projectId)/upload-file  metadata=$((ConvertTo-Json $metaObj -Depth 6 -Compress))"
        return
    }
    $token = [Environment]::GetEnvironmentVariable($Config.curseforge.tokenEnvVar)
    if ("$($Config.curseforge.projectId)" -like 'REPLACE_ME*') { throw 'CurseForge projectId is not set in release.json.' }
    if (-not $token) { throw "CurseForge token env var '$($Config.curseforge.tokenEnvVar)' is not set." }
    $metaObj.gameVersions = Resolve-ScCurseForgeVersionIds -Config $Config -Token $token -McVersion $McVersion `
        -Extra $ExtraGameVersions -LoaderName $loaderNames -Environments $Environments -JavaName $javaName
    $meta = ConvertTo-Json $metaObj -Depth 6 -Compress
    $r = Invoke-ScMultipart -Uri "https://minecraft.curseforge.com/api/projects/$($Config.curseforge.projectId)/upload-file" `
        -Headers @("X-Api-Token: $token") -Forms @("metadata=$meta", "file=@$Jar;type=application/java-archive")
    if ($r.Code -ne '200') { throw "CurseForge upload failed [$($r.Code)] for $McVersion/${LoaderName}: $($r.Body)" }
    Write-ScOk "CurseForge file id $((ConvertFrom-Json $r.Body).id) ($McVersion/$LoaderName)"
}

# ─── Modrinth ────────────────────────────────────────────────────────────────
function Test-ScModrinthVocab {
    # -SkipHeaderValidation lets us send Modrinth's required descriptive User-Agent (PowerShell
    # otherwise rejects the value's parentheses). The upload path uses curl, which has no such quirk.
    # -Loaders are Modrinth loader ids ('fabric', 'quilt', 'forge', 'neoforge') — EVERY one sent on the
    # upload, extras included, must exist in the tag list.
    param([Parameter(Mandatory)][string]$McVersion, [Parameter(Mandatory)][string[]]$Loaders,
          [string]$UserAgent, [string]$ApiBase = 'https://api.modrinth.com')
    $h = if ($UserAgent) { @{ 'User-Agent' = $UserAgent } } else { @{} }
    $gv = Invoke-RestMethod -Uri "$ApiBase/v2/tag/game_version" -Headers $h -SkipHeaderValidation
    if (-not ($gv.version | Where-Object { $_ -eq $McVersion })) {
        throw "Modrinth has no game_version '$McVersion' yet — cannot upload."
    }
    $ld = Invoke-RestMethod -Uri "$ApiBase/v2/tag/loader" -Headers $h -SkipHeaderValidation
    foreach ($loader in ($Loaders | Select-Object -Unique)) {
        if (-not ($ld.name | Where-Object { $_ -eq $loader })) { throw "Modrinth loader '$loader' not found." }
    }
}

function Invoke-ScModrinthUpload {
    param(
        [Parameter(Mandatory)]$Config, [Parameter(Mandatory)][string]$Title,
        [Parameter(Mandatory)][string]$VersionNumber, [Parameter(Mandatory)][string]$Changelog,
        [Parameter(Mandatory)][string]$VersionType, [Parameter(Mandatory)][string[]]$GameVersions,
        [Parameter(Mandatory)][string]$Loader, [Parameter(Mandatory)][string]$Jar,
        [string[]]$DependencyKeys = @(), [string[]]$ExtraLoaders = @(), [switch]$Featured,
        [string]$ApiBase = 'https://api.modrinth.com', [switch]$Execute
    )
    # Dependencies from the node's dependency keys (config `dependencies`), required first. What a node
    # advertises varies per NODE (e.g. mixinbooter only on 1.12.2-forge; no deps on the 26.x Forge nodes).
    $depList = @(Get-ScStoreDependencyList -Config $Config -Keys $DependencyKeys)
    $deps = @($depList | ForEach-Object {
        @{ project_id = $_.ModrinthId; version_id = $null; file_name = $null; dependency_type = $_.Type }
    })
    $dataObj = [ordered]@{
        name           = $Title
        version_number = $VersionNumber
        changelog      = $Changelog
        dependencies   = $deps
        game_versions  = $GameVersions
        version_type   = $VersionType
        # The node's loader plus its extra loaders (e.g. fabric files also run on quilt).
        loaders        = @(@($Loader) + @($ExtraLoaders))
        featured       = [bool]$Featured
        status         = 'listed'
        project_id     = $Config.modrinth.projectId
        file_parts     = @('file')
        primary_file   = 'file'
    }
    $data = ConvertTo-Json $dataObj -Depth 6 -Compress

    Write-ScInfo "MR [$VersionNumber]  `"$Title`"  ($VersionType)  <- $(Split-Path $Jar -Leaf)"
    Write-ScInfo "    loaders=$($dataObj.loaders -join '+')  deps=$(Get-ScDependencyText $depList)"
    if (-not $Execute) {
        Write-ScDry "POST $ApiBase/v2/version  data=$data"
        return
    }
    $token = [Environment]::GetEnvironmentVariable($Config.modrinth.tokenEnvVar)
    if ("$($Config.modrinth.projectId)" -like 'REPLACE_ME*') { throw 'Modrinth projectId is not set in release.json.' }
    if (-not $token) { throw "Modrinth token env var '$($Config.modrinth.tokenEnvVar)' is not set." }
    Test-ScModrinthVocab -McVersion $GameVersions[0] -Loaders $dataObj.loaders -UserAgent $Config.modrinth.userAgent -ApiBase $ApiBase
    $r = Invoke-ScMultipart -Uri "$ApiBase/v2/version" `
        -Headers @("Authorization: $token", "User-Agent: $($Config.modrinth.userAgent)") `
        -Forms @("data=$data;type=application/json", "file=@$Jar;type=application/java-archive")
    if ($r.Code -notin @('200','201')) { throw "Modrinth upload failed [$($r.Code)] for ${VersionNumber}: $($r.Body)" }
    Write-ScOk "Modrinth version $((ConvertFrom-Json $r.Body).id) ($VersionNumber)"
}

# ─── Post-flight: reset changelog + bump version ─────────────────────────────
function Reset-ScChangelog {
    param([Parameter(Mandatory)][string]$RepoRoot, [Parameter(Mandatory)][string]$ReleasedVersion, [switch]$Execute)
    $path = Join-Path $RepoRoot 'changelog.md'
    $header = "###### Changes since ${ReleasedVersion}:"
    if (-not $Execute) { Write-ScDry "changelog.md -> '$header' (body cleared)"; return }
    Set-Content -Path $path -Value $header -Encoding utf8
    Write-ScOk "Reset changelog.md to '$header'"
}

# Next prerelease version (rc4 -> rc5). Final releases need an explicit -NextVersion (can't guess the
# next cycle). Returns the new version string.
function Step-ScVersion {
    param([Parameter(Mandatory)][string]$RepoRoot, [Parameter(Mandatory)]$Split,
          [string]$NextVersion, [switch]$Execute)
    if (-not $NextVersion) {
        if ($Split.Suffix) { $NextVersion = "$($Split.Base)-$($Split.Suffix)$($Split.Number + 1)" }
        else { Write-ScWarn 'Final release: no -NextVersion given; leaving version unchanged.'; return $Split.Full }
    }
    $path = Join-Path $RepoRoot 'gradle.properties'
    # ONE version source for the whole repo: root gradle.properties `version`. The legacy 1.12.2 Forge build
    # reads this same value (forge-1.12.2/build.gradle's `rootVersion` -> mcmod.info + the generated
    # BuildInfo.VERSION), so bumping this single line covers every jar — modern and legacy alike.
    if (-not $Execute) { Write-ScDry "gradle.properties version $($Split.Full) -> $NextVersion"; return $NextVersion }
    $content = Get-Content -Raw $path
    $new = [regex]::Replace($content, '(?m)^(version=).*$', "`${1}$NextVersion")
    Set-Content -Path $path -Value $new -Encoding utf8 -NoNewline
    Write-ScOk "Bumped version -> $NextVersion"
    return $NextVersion
}

function Invoke-ScGitCommit {
    param([Parameter(Mandatory)][string]$RepoRoot, [Parameter(Mandatory)][string[]]$Paths,
          [Parameter(Mandatory)][string]$Message, [switch]$Execute)
    if (-not $Execute) { Write-ScDry "git commit -m `"$Message`" -- $($Paths -join ' ')"; return }
    Push-Location $RepoRoot
    try {
        # Idempotent: if these paths have no changes (e.g. a resumed run after a mid-release failure
        # already committed them), there's nothing to commit — skip rather than fail.
        if (-not (& git status --porcelain -- @Paths)) {
            Write-ScInfo "nothing to commit for $($Paths -join ', ') — already up to date"
            return
        }
        # Scope the commit to exactly these paths so a release run never sweeps up unrelated changes.
        & git commit -m $Message -- @Paths
        if ($LASTEXITCODE -ne 0) { throw "git commit failed (exit $LASTEXITCODE)." }
        Write-ScOk "Committed: $Message"
    } finally { Pop-Location }
}

# Push HEAD to the remote. REQUIRED before a GitHub release: `gh release create` tags a commit on
# GitHub's servers, so an unpushed commit yields HTTP 422 "target_commitish is invalid". Idempotent.
function Invoke-ScGitPush {
    param([Parameter(Mandatory)][string]$RepoRoot, [string]$Remote = 'origin', [switch]$Execute)
    if (-not $Execute) { Write-ScDry "git push $Remote HEAD (so GitHub has the commit being tagged)"; return }
    Push-Location $RepoRoot
    try {
        & git push $Remote HEAD
        if ($LASTEXITCODE -ne 0) { throw "git push failed (exit $LASTEXITCODE) — a GitHub release can't tag an unpushed commit." }
        Write-ScOk "Pushed HEAD to $Remote"
    } finally { Pop-Location }
}

Export-ModuleMember -Function *-Sc*, ConvertTo-ScPlainChangelog, ConvertTo-ScAsciiJson
