<#
.SYNOPSIS
    Cut a SprawlCrafting release across GitHub, Modrinth and CurseForge from one input (`version`).

.DESCRIPTION
    Derives every title / tag / channel / changelog variant from gradle.properties + the per-node
    Stonecutter configs, then:
      0. runs the full pre-release test suite (both JDK trees' unit tests + the boot/connect matrix) —
         any failure aborts BEFORE a single publish action (skippable with -SkipTests),
      1. rewrites + validates + commits update.json (the in-game update manifest),
      2. creates ONE GitHub release (tag = version) with ALL node jars attached,
      3. uploads one Modrinth version per node (loader + MC version),
      4. uploads one CurseForge file per node (loader + MC version),
      5. resets changelog.md and (for a prerelease) bumps `version` to the next prerelease.

    SprawlCrafting ships one jar per (MC version, loader): every versions/<node> of the modern Stonecutter
    tree (auto-discovered: fabric/forge/neoforge across 1.20.1 / 1.21.1 / 26.1.2 / 26.2 / 26.3), a single
    Forge jar for the legacy 1.12.2 line (its own self-contained build under forge-1.12.2/, injected as a
    synthetic release node via release.json's `legacyForge` block), and the Forge + Fabric jars of the legacy
    1.16.5 tree (legacy-1.16.5/, injected via release.json's `legacyTrees` array). Each store upload is
    per-node and tags its own loader. The update manifest is keyed by MC version (loader-agnostic), so all
    loaders of one MC version share a block.

    Changelog lines of the form "- {MC.VER} text" are routed only to that MC version's manifest block
    and CurseForge file (tag stripped). Every Modrinth version instead gets ONE identical changelog —
    tagged lines kept inline as "- (MC.VER) text" — with the featured-MC nodes uploaded FIRST, because
    Modrinth's changelog tab shows a repeated text once, on the oldest version carrying it. GitHub
    always gets changelog.md verbatim. A tag naming an unknown MC version fails the run up front,
    dry run included.

    Nodes named in release.json "heldBack" are enumerated, built and verified but NOT published; the
    dry run lists them and why (-ShipHeld lifts it). -OnlyNodes restricts the GitHub jar list and the
    Modrinth/CurseForge uploads to a subset of nodes.

    SAFE BY DEFAULT: without -Execute nothing is written, committed, or uploaded — every step prints
    exactly what it WOULD do (a dry run). Read-only API lookups are skipped in dry run too, so no
    tokens are needed to preview.

    STORES OFF BY DEFAULT: curseforge.enabled / modrinth.enabled in release.json are false until you
    create those store projects and set real project IDs. A dry run still PREVIEWS a disabled store
    (labelled), but -Execute skips it. GitHub works today via your existing `gh auth`.

.PARAMETER Execute      Actually perform writes / commits / uploads. Omit for a dry run.
.PARAMETER Only         Restrict to a subset of stages: Test, Manifest, GitHub, Modrinth, CurseForge, PostFlight. Default: all.
                        Resuming a partly-published release with -Only <remaining stages> skips Test automatically.
.PARAMETER OnlyNodes    Restrict GitHub-jar / Modrinth / CurseForge publishing to specific nodes. Each value is an MC
                        version (-OnlyNodes 26.3: every node on it, all loaders) or a node name (-OnlyNodes 26.3-fabric:
                        that node only); a value matching nothing fails the run. There's no per-node "already uploaded"
                        tracking, so resuming a stage that partially succeeded (one node's upload failed, the rest went
                        through) would otherwise re-upload every node and leave duplicate store files behind. Changelog-tag
                        validation and the manifest still use the FULL node set, so a scoped run never changes known tags
                        or manifest coverage. NOTE: the GitHub stage would still try to CREATE the release (resumes are
                        normally -Only Modrinth,CurseForge -OnlyNodes ...).
.PARAMETER ShipHeld     Lift the ship gate for this run: loader names (forge) or node names (1.16.2-forge) from release.json
                        "heldBack". Those nodes then publish like any other. A value matching no held node fails the run.
                        Without it every held node is built and verified by the Test stage but left out of GitHub, Modrinth
                        and CurseForge (and out of the jar check).
.PARAMETER RepoRoot     Run against another checkout (a worktree that has nodes this one lacks) instead of the one holding
                        this script. Config and secrets stay this tool's; the version, changelog, manifest, nodes, jars and
                        git operations come from RepoRoot. Mostly for dry runs: with -Execute it publishes FROM that checkout.
.PARAMETER AllowMissingJars
                        Dry run only (refused with -Execute): a node whose jar is not built yet is warned about and previewed
                        with the path it would have, instead of stopping the run. For previewing a plan before the build.
                        Without it a dry run fails on a missing jar, exactly like a real run would.
.PARAMETER NextVersion  Override the post-flight version bump (required when releasing a final, suffix-less version).
.PARAMETER PromoteRecommended
                        Advance each non-EOL line's '-recommended' promo to THIS version even when it's a prerelease
                        (beta/alpha/rc). By default recommended stays on the last stable when shipping a prerelease, so
                        a beta lands as '-latest' only. Set this for a bugfix-heavy beta you want promoted as a full
                        "update available" to everyone. EOL lines stay frozen; the store channels (GitHub pre-release,
                        CurseForge/Modrinth beta) are unaffected — only the in-game recommendation is promoted.
.PARAMETER SkipTests    Bypass the pre-release test suite (stage 0). Publishes UNVERIFIED jars — warns loudly. To run
                        ONLY the suite (verify without publishing anything), use -Only Test instead.
.PARAMETER Build        Build first: `./gradlew buildAndCollect` (every modern jar -> testing/dist/) plus each legacy build: the 1.12.2 Forge build (forge-1.12.2/build/libs/, host JDK 25 via legacyForge.javaHome) and the 1.16.5 tree (legacy-1.16.5/{forge,fabric}/build/libs/, host JDK 21 via the legacyTrees entry's javaHome).
.PARAMETER ModrinthStaging  Target staging-api.modrinth.com for the Modrinth leg (see release/README.md caveat).

.EXAMPLE  ./release/Invoke-Release.ps1                 # full dry run of the current version
.EXAMPLE  ./release/Invoke-Release.ps1 -Execute        # really publish
.EXAMPLE  ./release/Invoke-Release.ps1 -Only GitHub -Execute
.EXAMPLE  ./release/Invoke-Release.ps1 -Build -Execute
.EXAMPLE  ./release/Invoke-Release.ps1 -Only Modrinth,CurseForge -OnlyNodes 26.3,26.2-fabric -Execute   # resume
#>
#Requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$Execute,
    [ValidateSet('Test','Manifest','GitHub','Modrinth','CurseForge','PostFlight')]
    [string[]]$Only,
    [string[]]$OnlyNodes,
    [string[]]$ShipHeld,
    [string]$RepoRoot,
    [switch]$AllowMissingJars,
    [string]$NextVersion,
    [switch]$PromoteRecommended,
    [switch]$Build,
    [switch]$SkipTests,
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
# -RepoRoot: version/changelog/manifest/nodes/jars/git come from that checkout; config + secrets stay
# this tool's (they live next to the script, so a worktree with different nodes still releases as us).
if ($RepoRoot) {
    if (-not (Test-Path -LiteralPath $RepoRoot)) { throw "-RepoRoot '$RepoRoot' not found." }
    $root = (Resolve-Path -LiteralPath $RepoRoot).Path
}
if ($AllowMissingJars -and $Execute) { throw '-AllowMissingJars is for dry runs only: a real publish needs every jar built.' }
$cfg     = Get-ScReleaseConfig
$ver     = Get-ScModVersion -RepoRoot $root
$split   = Split-ScVersion $ver
$chan    = Get-ScChannel $split
# The FULL node set feeds everything repo-wide (tests, build, manifest coverage, changelog-tag
# validation); $nodes below is only the subset the publishing stages walk.
$allNodes = @(Get-ScNodes -RepoRoot $root -Config $cfg)
$mcSet   = @($allNodes.McVersion | Select-Object -Unique)
$ingameBold = Get-ScProp $cfg 'ingameBold' 'strip'
# Ship gate (release.json heldBack): held nodes stay in $allNodes (built + verified by the Test stage,
# covered by the manifest) but are not in $nodes, the set every publishing stage below walks.
# -ShipHeld lifts it for this run.
$gate      = Resolve-ScHeldNodes -Config $cfg -Nodes $allNodes -Ship $ShipHeld
$nodes     = @($gate.Publish)
$heldNodes = @($gate.Held)
if ($OnlyNodes) {
    $selected  = @(Select-ScNodes -Nodes $allNodes -Selectors $OnlyNodes)
    $nodes     = @($selected | Where-Object { $_.Node -in @($gate.Publish | ForEach-Object Node) })
    $heldNodes = @($selected | Where-Object { $_.Node -in @($gate.Held    | ForEach-Object Node) })
    if (-not $nodes.Count) { throw "-OnlyNodes selected only held-back node(s) ($(($heldNodes | ForEach-Object Node) -join ', ')); nothing would be published. Lift the hold with -ShipHeld <loader|node> if shipping them is intended." }
}
if (-not $nodes.Count) { throw "release.json heldBack holds every node; nothing would be published. Lift the hold with -ShipHeld <loader|node>." }
$liftedNodes = @($gate.Lifted | Where-Object { $_.Node -in @($nodes | ForEach-Object Node) })

Write-ScStep "SprawlCrafting release: $ver"
Write-ScInfo "GitHub title : $(Get-ScGithubTitle $split)   [$(if($chan.GithubPrerelease){'pre-release'}else{'latest'})]"
Write-ScInfo "Channels     : Modrinth=$($chan.Modrinth)  CurseForge=$($chan.CurseForge)"
Write-ScInfo "Nodes        : $($nodes.Count) ($(($nodes.Node) -join ', '))"
if ($heldNodes.Count) { Write-ScInfo "Held back ($($heldNodes.Count)) : $(($heldNodes.Node) -join ', ')" }
Write-ScInfo "MC versions  : $($mcSet -join ', ')"
Write-ScInfo "Stores       : Modrinth=$(if(Get-StoreEnabled $cfg.modrinth){'enabled'}else{'DISABLED'})  CurseForge=$(if(Get-StoreEnabled $cfg.curseforge){'enabled'}else{'DISABLED'})"
Write-ScInfo "Mode         : $(if($Execute){'EXECUTE (live)'}else{'DRY RUN (no writes/uploads)'})"
if ($RepoRoot) { Write-ScWarn "-RepoRoot: version, changelog, manifest, nodes, jars and git come from $root." }
if ($OnlyNodes) { Write-ScWarn "Node-restricted resume: only $(($nodes | ForEach-Object Node) -join ', ') publish this run; tag validation and the manifest still use the full node set." }
if ($heldNodes.Count) { Write-ScWarn "Ship gate: $(($heldNodes.Node) -join ', ') built and verified but NOT published.$(if($gate.Reason){" $($gate.Reason)"}else{''}) Lift for a run with -ShipHeld <loader|node>." }
if ($liftedNodes.Count) { Write-ScWarn "Ship gate LIFTED (-ShipHeld) for $(($liftedNodes | ForEach-Object Node) -join ', '): they publish like any other node this run." }
# The manifest is keyed by MC version, so a line whose every node is held still gets announced in-game.
$heldOnlyMc = @($allNodes.McVersion | Select-Object -Unique | Where-Object { $_ -notin @($gate.Publish | ForEach-Object McVersion) })
if ($heldOnlyMc.Count) { Write-ScWarn "MC line(s) $($heldOnlyMc -join ', ') have every node held back, but update.json will still announce $ver for them." }
if (-not $Execute) { Write-ScWarn 'Dry run — pass -Execute to publish for real.' }

# ── Changelog + tag validation (fast, fail-fast BEFORE the slow test suite) ──
# Compute the release-notes body and validate {mc}-tags up front, so a changelog typo aborts in
# seconds rather than after the ~30-min test suite. A tag must name a real MC version (a manifest
# block or a shipping node); an unknown tag would silently drop the line everywhere but the verbatim
# GitHub body.
$body = Get-ScChangelogBody -RepoRoot $root
# An empty changelog (fresh header, no bullets) means there's nothing to announce. Fail FAST for any
# publishing run — before the slow test suite below — rather than wasting it and crashing at the
# manifest step. A Test-only / PostFlight-only run (no publishing stage) is still allowed with just a
# warning, so the suite can be exercised standalone between releases.
$publishing = (Want 'Manifest') -or (Want 'GitHub') -or (Want 'Modrinth') -or (Want 'CurseForge')
if ([string]::IsNullOrWhiteSpace($body)) {
    if ($publishing) { throw 'changelog.md has no content below the header — nothing to release. Write the changelog first (or use -Only Test to just run the suite).' }
    Write-ScWarn 'changelog.md body is empty — no publishing stage selected, continuing (test-only).'
}
$clSplit = Split-ScChangelogByVersion -Body $body
$manifestPath = Join-Path $root 'update.json'
$manifestBlocks = @()
if (Test-Path $manifestPath) {
    $manifestBlocks = @((Get-Content -Raw $manifestPath | ConvertFrom-Json).PSObject.Properties.Name |
                        Where-Object { $_ -notin @('homepage','promos') })
}
Test-ScChangelogTags -ChangelogSplit $clSplit -KnownVersions (@($mcSet) + $manifestBlocks | Select-Object -Unique)

# ── Store config sanity (fail-fast BEFORE the slow test suite) ───────────────
# Every node's dependency keys must resolve against release.json's `dependencies` catalogue, and the
# extraLoaders entries must be string arrays — checked up front (dry run included) so a bad entry
# fails here, not on the 7th upload. Held nodes count too: lifting the gate later must not be the
# first time a missing entry shows up.
if ((Want 'Modrinth') -or (Want 'CurseForge')) {
    Test-ScStoreConfig -Config $cfg -Nodes $allNodes
}

# ── Version not already released (fail-fast BEFORE anything is committed or pushed) ──
# Invoke-ScGithubRelease refuses an existing tag, but it runs AFTER the Manifest stage has committed
# update.json and the GitHub stage has pushed it to main, so a run at an already-shipped version would
# announce that version live in the manifest and then abort. Check up front instead. Only when the GitHub
# stage will run: a resume with -Only Modrinth/CurseForge legitimately runs after the release exists.
if (Want 'GitHub') {
    $relExists = Test-ScGithubReleaseExists -Config $cfg -Tag $split.Full
    if ($relExists) {
        $msg = "GitHub release '$($split.Full)' already exists. Bump version in the root gradle.properties (or resume with -Only <later stages>)."
        if ($Execute) { throw $msg }
        Write-ScWarn "$msg -Execute would stop here."
    } elseif ($null -eq $relExists) {
        if ($Execute) { throw 'gh (GitHub CLI) not found; it is needed to check for and create the GitHub release.' }
        Write-ScWarn 'gh not found; could not check whether this version is already released.'
    }
}

# ── 0. Pre-release verification (gates the whole release) ─────────────────────
# The full suite must pass before ANY publish. The trees run on different JDKs (modern and legacy-1.16.5
# need 21; the legacy 1.12.2 Forge build needs 25 — they can't share one Gradle host), so this stage
# orchestrates all of them:
#   0. solver library (ambient JAVA_HOME = JDK 21): ./gradlew -p solver-core build = the planner's JUnit
#      suite on its Java 8 toolchain. Every shipped jar bundles this library and its tests run ONLY
#      there, so it goes first: a planner regression aborts in seconds, before the slow trees build;
#   1. every legacy tree (own JavaHome from release.json: legacyForge = 1.12.2 on JDK 25, legacyTrees =
#      1.16.5 on JDK 21): its VerifyTasks (the production jars) — built FIRST so the boot matrix's
#      1.12.2-forge leg finds the jar it stages. A tree with several loader nodes (1.16.5) is verified ONCE;
#   2. modern tree (ambient JAVA_HOME = JDK 21): ./gradlew fullTestSuite = solver-core :check again (up to
#      date after step 0) + every node's unit tests (via buildAndCollect) + the production boot/connect
#      matrix (auto-skips if the testing/ harness absent).
# A non-zero exit anywhere aborts before the manifest is even rewritten. -SkipTests bypasses (ships
# UNVERIFIED jars). Resuming with -Only <publish stages> omits Test, so a half-published release is not
# re-gated on the slow suite.
if ((Want 'Test') -and -not $SkipTests) {
    Write-ScStep 'Pre-release verification (full test suite)'
    if (-not $Execute) {
        Write-ScDry 'would run the solver library suite: ./gradlew -p solver-core build (JUnit on a Java 8 toolchain)'
        foreach ($ln in @($allNodes | Where-Object { $_.IsLegacy } | Group-Object BuildDir | ForEach-Object { $_.Group[0] })) {
            Write-ScDry "would verify the legacy $($ln.McVersion) tree: $($ln.BuildDir)/gradlew.bat $($ln.VerifyTasks -join ' ')  [JAVA_HOME=$(if($ln.JavaHome){$ln.JavaHome}else{'(current)'})]"
        }
        Write-ScDry 'would run the modern tree: ./gradlew fullTestSuite (unit tests via buildAndCollect + boot/connect matrix; matrix auto-skips if testing/ absent)'
        Write-ScDry 'a non-zero exit from either would ABORT the release before any publish.'
    } else {
        # 0. Solver library suite FIRST — every shipped jar bundles it, and it is the fastest gate.
        Write-ScStep 'Verify solver library (./gradlew -p solver-core build)'
        Push-Location $root
        try {
            & (Join-Path $root 'gradlew.bat') '-p' 'solver-core' 'build' '--console=plain'
            if ($LASTEXITCODE -ne 0) { throw "solver-core build/tests FAILED (exit $LASTEXITCODE) — release aborted before any publish. Fix the solver, or pass -SkipTests to bypass (ships unverified jars)." }
        } finally { Pop-Location }
        Write-ScOk 'Solver library verified (JUnit suite).'
        # 1. Legacy trees FIRST — the 1.12.2 reobf jar feeds the matrix's 1.12.2-forge leg. One run per tree
        #    (the 1.16.5 tree's forge + fabric nodes share a build), on that tree's configured JDK.
        foreach ($ln in @($allNodes | Where-Object { $_.IsLegacy } | Group-Object BuildDir | ForEach-Object { $_.Group[0] })) {
            $lnDir = Join-Path $root $ln.BuildDir
            Write-ScStep "Verify legacy $($ln.McVersion) ($($ln.BuildDir)/gradlew $($ln.VerifyTasks -join ' '))"
            if (-not $ln.JavaHome) {
                Write-ScWarn "No javaHome configured for legacy $($ln.McVersion) — using the current JAVA_HOME. Set javaHome in its release.json entry if this fails (1.12.2 needs JDK 25, 1.16.5 needs JDK 21)."
            }
            $savedJavaHome = $env:JAVA_HOME
            Push-Location $lnDir
            try {
                if ($ln.JavaHome) { $env:JAVA_HOME = $ln.JavaHome }
                & (Join-Path $lnDir 'gradlew.bat') @($ln.VerifyTasks) '--console=plain'
                if ($LASTEXITCODE -ne 0) { throw "legacy $($ln.McVersion) verification FAILED (exit $LASTEXITCODE) — release aborted before any publish. Fix the tests, or pass -SkipTests to bypass (ships unverified jars)." }
            } finally {
                Pop-Location
                $env:JAVA_HOME = $savedJavaHome
            }
            Write-ScOk "Legacy $($ln.McVersion) verified (production jars built)."
        }
        # 2. Modern tree: per-node unit tests (via buildAndCollect) + the boot/connect matrix.
        Write-ScStep 'Verify modern tree (./gradlew fullTestSuite)'
        Write-ScInfo 'solver-core check + unit tests (via buildAndCollect) + boot/connect matrix. The matrix opens real MC windows (Prism must be CLOSED, Server1 reachable) and is slow (~30 min).'
        Push-Location $root
        try {
            & (Join-Path $root 'gradlew.bat') fullTestSuite '--console=plain'
            if ($LASTEXITCODE -ne 0) { throw "fullTestSuite FAILED (exit $LASTEXITCODE) — release aborted before any publish. Fix the tests, or pass -SkipTests to bypass (ships unverified jars)." }
        } finally { Pop-Location }
        Write-ScOk 'Full test suite passed — RELEASE GATE: PASS.'
    }
} elseif ($SkipTests) {
    Write-ScWarn 'Pre-release verification SKIPPED (-SkipTests) — shipping jars are UNVERIFIED by this run.'
}

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

    # The legacy jars (1.12.2, and the 1.16.5 forge + fabric pair) are produced by their OWN self-contained
    # Gradle builds (separate wrappers) — root buildAndCollect never produces them. Build each legacy TREE
    # with its own gradlew invocation (once per tree, however many loader nodes it feeds), switching
    # JAVA_HOME to the tree's configured JDK (modern + 1.16.5 need JDK 21, the 1.12.2 forge build needs 25 —
    # they can't share one host JDK in a single run).
    foreach ($ln in @($allNodes | Where-Object { $_.IsLegacy } | Group-Object BuildDir | ForEach-Object { $_.Group[0] })) {
        $lnDir = Join-Path $root $ln.BuildDir
        Write-ScStep "Build legacy $($ln.McVersion) jar(s) ($($ln.BuildDir)/gradlew $($ln.BuildTasks -join ' '))"
        if (-not $Execute) {
            Write-ScDry "would run: $($ln.BuildDir)/gradlew.bat $($ln.BuildTasks -join ' ')  [JAVA_HOME=$(if($ln.JavaHome){$ln.JavaHome}else{'(current)'})]"
            continue
        }
        if (-not $ln.JavaHome) {
            Write-ScWarn "No javaHome configured for legacy $($ln.McVersion) — building with the current JAVA_HOME. Set javaHome in its release.json entry if this fails (1.12.2 needs JDK 25, 1.16.5 needs JDK 21)."
        }
        $savedJavaHome = $env:JAVA_HOME
        Push-Location $lnDir
        try {
            if ($ln.JavaHome) { $env:JAVA_HOME = $ln.JavaHome }
            & (Join-Path $lnDir 'gradlew.bat') @($ln.BuildTasks)
            if ($LASTEXITCODE -ne 0) { throw "legacy $($ln.McVersion) build failed (exit $LASTEXITCODE)." }
        } finally {
            Pop-Location
            $env:JAVA_HOME = $savedJavaHome
        }
        Write-ScOk "Built legacy $($ln.McVersion) jar(s)."
    }
}

# ── Resolve jars ─────────────────────────────────────────────────────────────
# Only the publishing stages consume jars, so a Test-only / Manifest-only / PostFlight-only run
# doesn't demand them — lets `-Only Test -Execute` verify standalone before any jar exists. Missing
# jars fail even a dry run unless -AllowMissingJars (dry runs only) previews the unbuilt plan.
$jarMap = @{}
if ((Want 'GitHub') -or (Want 'Modrinth') -or (Want 'CurseForge')) {
    $jarMap = Test-ScJars -RepoRoot $root -Version $ver -Nodes $nodes -AllowMissing:$AllowMissingJars
    # Held nodes publish nothing, so a missing jar does not stop the run; say whether each was built.
    foreach ($h in $heldNodes) {
        $hj = Get-ScNodeJarPath -RepoRoot $root -Version $ver -Node $h
        if (Test-Path -LiteralPath $hj) { Write-ScInfo "held $($h.Node): jar built ($(Split-Path $hj -Leaf)), not published" }
        else { Write-ScWarn "held $($h.Node): jar NOT built ($(Split-Path $hj -Leaf))" }
    }
}

# ── 1. Manifest (update.json) ────────────────────────────────────────────────
if (Want 'Manifest') {
    Write-ScStep 'Update in-game update manifest (update.json)'
    if ($PromoteRecommended) {
        if ($split.IsStable) { Write-ScInfo '-PromoteRecommended is redundant for a stable build (recommended already advances).' }
        else { Write-ScWarn "-PromoteRecommended: '-recommended' will advance to prerelease $ver on every non-EOL line (players are recommended onto a prerelease)." }
    }
    $null = Update-ScManifest -Config $cfg -ManifestPath $manifestPath -Version $ver -ChangelogBody $body `
                -NodeMcVersions $mcSet -IngameBold $ingameBold -PromoteRecommended:$PromoteRecommended -Write:$Execute
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
        # Every version gets ONE identical changelog (byte-identical — see Get-ScUnifiedStoreChangelog).
        # Modrinth's changelog tab shows a repeated text only on the OLDEST version holding it, so the
        # featured-MC nodes upload FIRST to be the ones that display it.
        $mrCl = Get-ScUnifiedStoreChangelog -ChangelogSplit $clSplit
        foreach ($n in @(@($nodes | Where-Object IsFeatured) + @($nodes | Where-Object { -not $_.IsFeatured }))) {
            $gvs = Get-ScProp $cfg.storeGameVersionsByMc $n.McVersion @($n.McVersion)
            Invoke-ScModrinthUpload -Config $cfg -Title (Get-ScStoreTitle $split $n.McVersion $n.LoaderDisplay) `
                -VersionNumber "$ver+mc$($n.McVersion)-$($n.MrLoader)" -Changelog $mrCl -VersionType $chan.Modrinth `
                -GameVersions @($gvs) -Loader $n.MrLoader -Jar $jarMap[$n.Node] -DependencyKeys $n.Dependencies `
                -ExtraLoaders @(Get-ScExtraLoaders -Config $cfg -Store modrinth -Node $n) -Featured:$n.IsFeatured `
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
                -DependencyKeys $n.Dependencies -ExtraLoaders @(Get-ScExtraLoaders -Config $cfg -Store curseforge -Node $n) `
                -Execute:($Execute -and $cfEnabled)
        }
    }
}

# ── 5. Post-flight: reset changelog + bump version ───────────────────────────
if (Want 'PostFlight') {
    Write-ScStep 'Post-flight: reset changelog + bump version'
    Reset-ScChangelog -RepoRoot $root -ReleasedVersion $ver -Execute:$Execute
    $next = Step-ScVersion -RepoRoot $root -Split $split -NextVersion $NextVersion -Execute:$Execute
    # The version is single-sourced in root gradle.properties (the 1.12.2 and 1.16.5 builds read it from there),
    # so the bump only ever touches this one file alongside the changelog reset.
    Invoke-ScGitCommit -RepoRoot $root -Paths @('changelog.md','gradle.properties') `
        -Message "Reset changelog and bump $ver -> $next" -Execute:$Execute
    Invoke-ScGitPush -RepoRoot $root -Execute:$Execute
}

Write-ScStep ("Done ({0})." -f $(if ($Execute) { 'published' } else { 'dry run — nothing changed' }))
