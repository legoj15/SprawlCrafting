# ScRelease.psm1 — SprawlCrafting release-automation library.
#
# Pure-logic + publishing helpers for cutting a release across GitHub, Modrinth and CurseForge from a
# single input (`version` in the root gradle.properties) plus the per-node Stonecutter configs.
# Mirrors the testing/ harness idiom (PowerShell module + JSON config; tokens via env vars).
#
# Adapted from BuildCraft's BcRelease.psm1, with SprawlCrafting-specific differences:
#   * SprawlCrafting ships TWO loaders per MC version (fabric + neoforge), so every store upload is
#     PER-NODE and tags its own loader — BuildCraft was NeoForge-only with one universal jar per MC.
#   * Every node gets the SAME full changelog (general lines + its own {mc}-tagged lines). There is no
#     "primary vs backport" framing: all six nodes are built from one shared source and are equals.
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
    $nodes = @()
    foreach ($dir in (Get-ChildItem -Directory (Join-Path $RepoRoot 'versions') | Sort-Object Name)) {
        $pp = Join-Path $dir.FullName 'gradle.properties'
        if (-not (Test-Path $pp)) { continue }
        $p = Read-ScProperties $pp
        $loader = $dir.Name.Substring($dir.Name.LastIndexOf('-') + 1)   # 'fabric' | 'neoforge'
        $cfName = switch ($loader) { 'fabric' { 'Fabric' } 'neoforge' { 'NeoForge' } default { $loader } }
        $nodes += [pscustomobject]@{
            Node          = $dir.Name
            McVersion     = $p['minecraft_version']     # also the jar +mc tag and the CF/MR game version
            Loader        = $loader                     # canonical lower-case loader
            MrLoader      = $loader                     # Modrinth loader name (lower-case)
            CfLoaderName  = $cfName                     # CurseForge loader entry name (capitalised)
            LoaderDisplay = $cfName                     # human-facing loader label (store titles)
            Java          = if ($p.ContainsKey('java_version')) { $p['java_version'] } elseif ($rootProps.ContainsKey('java_version')) { $rootProps['java_version'] } else { $null }
            IsFeatured    = ($p['minecraft_version'] -eq $featuredMc)
        }
    }
    if ($featuredMc -and -not ($nodes | Where-Object IsFeatured)) {
        Write-ScWarn "featuredMc '$featuredMc' from config matches no shipping node — nothing will be marked featured on Modrinth."
    }
    return $nodes
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

function Test-ScJars {
    # Resolve every node's jar. Returns { Map = node->path; Missing = path[] }. Does NOT throw — the
    # orchestrator warns (dry run) or fails (execute) so a dry run can preview the plan unbuilt.
    param([Parameter(Mandatory)][string]$RepoRoot, [Parameter(Mandatory)][string]$Version, [Parameter(Mandatory)]$Nodes)
    $map = @{}
    $missing = @()
    foreach ($n in $Nodes) {
        $jar = Get-ScJarPath -RepoRoot $RepoRoot -Version $Version -McVersion $n.McVersion -Loader $n.MrLoader
        $map[$n.Node] = $jar
        if (-not (Test-Path $jar)) { $missing += $jar }
    }
    return [pscustomobject]@{ Map = $map; Missing = @($missing) }
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
    $body = if ($lines.Count -gt 1) { $lines[1..($lines.Count - 1)] } else { @() }
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

# Store changelog (CurseForge/Modrinth) for a given MC version: the general (untagged) lines plus that
# version's own {mc}-tagged lines, in document order, tag stripped. Every SprawlCrafting node gets its
# full changelog this way (no primary/backport distinction — all versions are first-class). Another
# version's exclusive {mc} line never leaks here. Neutral fallback only if there are no lines at all.
function Get-ScStoreChangelogForMc {
    param([Parameter(Mandatory)]$ChangelogSplit, [Parameter(Mandatory)][string]$McVersion)
    $lines = @($ChangelogSplit.Entries | Where-Object { $null -eq $_.Tag -or $_.Tag -eq $McVersion } | ForEach-Object Line)
    if (-not $lines.Count) { return "Release for Minecraft $McVersion. See the GitHub release page for details." }
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
            # shipped on this line yet (still in the initial prerelease run-up).
            if ($split.IsStable -or (-not $curStable)) { $pp.Value = $Version }
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
    & gh release view $tag --repo $Config.github.repo *> $null
    if ($LASTEXITCODE -eq 0) { throw "GitHub release '$tag' already exists. Delete it or bump the version." }
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

# ─── CurseForge ──────────────────────────────────────────────────────────────
# CurseForge models EVERYTHING you can tag a file with — Minecraft versions, the modloader, the Java
# tier, and the Client/Server "environment" indicator — as ordinary entries in ONE flat
# /api/game/versions list, differing only by their gameVersionTypeID. You collect each entry's numeric
# .id and post them together in metadata.gameVersions. Presence of the Client id AND the Server id
# populates the desktop-app environment indicator (and CF requires an environment tag for all MC-mod
# files from 2026-07-15 onward).
#
# This is the PURE resolver: given CF's already-fetched version + version-type lists, it maps the
# desired names to ids. Minecraft version(s) and the loader are matched by name. Environment
# ("Client"/"Server") and the Java tier are matched WITHIN their version TYPE (located by the type's
# stable slug 'environment*' / 'java*') so a bare name like "Server" can't grab an unrelated id. A
# missing required name throws; a missing Java tier is a recorded warning (CF curates the Java list by
# hand and lags new JDKs). Returns { Ids = int[]; Warnings = string[] }.
function Select-ScCurseForgeVersionIds {
    param(
        [Parameter(Mandatory)]$Versions, [Parameter(Mandatory)]$Types,
        [Parameter(Mandatory)][string]$McVersion, [string[]]$Extra = @(),
        [string]$LoaderName, [string[]]$Environments = @(), [string]$JavaName
    )
    $typeIdsForSlug = { param([string]$Pattern) @($Types | Where-Object { $_.slug -like $Pattern } | ForEach-Object id) }
    $envTypeIds  = & $typeIdsForSlug 'environment*'
    $javaTypeIds = & $typeIdsForSlug 'java*'

    $ids      = [System.Collections.Generic.List[int]]::new()
    $warnings = [System.Collections.Generic.List[string]]::new()

    # Minecraft version(s) + loader — required, matched by name.
    $required = @($McVersion) + $Extra
    if ($LoaderName) { $required += $LoaderName }
    foreach ($name in ($required | Select-Object -Unique)) {
        $hit = $Versions | Where-Object { $_.name -eq $name } | Select-Object -First 1
        if (-not $hit) {
            if ($name -eq $LoaderName) { throw "CurseForge has no loader named '$name'." }
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
function Resolve-ScCurseForgeVersionIds {
    param([Parameter(Mandatory)]$Config, [Parameter(Mandatory)][string]$Token,
          [Parameter(Mandatory)][string]$McVersion, [string[]]$Extra = @(),
          [Parameter(Mandatory)][string]$LoaderName, [string[]]$Environments = @(), [string]$JavaName)
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
        [switch]$IncludeJei, [switch]$Execute
    )
    $metaObj = [ordered]@{
        changelog     = $Changelog
        changelogType = 'markdown'
        displayName   = $Title
        releaseType   = $ReleaseType
    }
    if ($IncludeJei -and (Get-ScProp $Config.curseforge 'jeiSlug')) {
        $metaObj.relations = @{ projects = @(@{ slug = $Config.curseforge.jeiSlug; type = 'optionalDependency' }) }
    }
    # The Java toolchain version (e.g. "25") becomes CF's tier NAME "Java 25" — capital J, one space, bare major.
    $javaName = if ($JavaVersion) { "Java $JavaVersion" } else { $null }

    Write-ScInfo "CF [$McVersion/$LoaderName]  `"$Title`"  ($ReleaseType)  <- $(Split-Path $Jar -Leaf)"
    Write-ScInfo "    env=$($Environments -join '+')  java=$(if($javaName){$javaName}else{'(none)'})"
    if (-not $Execute) {
        $names = @($McVersion, $LoaderName) + $ExtraGameVersions + $Environments
        if ($javaName) { $names += $javaName }
        $metaObj.gameVersionNames = $names
        Write-ScDry "POST .../projects/$($Config.curseforge.projectId)/upload-file  metadata=$((ConvertTo-Json $metaObj -Depth 6 -Compress))"
        return
    }
    $token = [Environment]::GetEnvironmentVariable($Config.curseforge.tokenEnvVar)
    if ("$($Config.curseforge.projectId)" -like 'REPLACE_ME*') { throw 'CurseForge projectId is not set in release.json.' }
    if (-not $token) { throw "CurseForge token env var '$($Config.curseforge.tokenEnvVar)' is not set." }
    $metaObj.gameVersions = Resolve-ScCurseForgeVersionIds -Config $Config -Token $token -McVersion $McVersion `
        -Extra $ExtraGameVersions -LoaderName $LoaderName -Environments $Environments -JavaName $javaName
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
    param([Parameter(Mandatory)][string]$McVersion, [Parameter(Mandatory)][string]$Loader, [string]$UserAgent, [string]$ApiBase = 'https://api.modrinth.com')
    $h = if ($UserAgent) { @{ 'User-Agent' = $UserAgent } } else { @{} }
    $gv = Invoke-RestMethod -Uri "$ApiBase/v2/tag/game_version" -Headers $h -SkipHeaderValidation
    if (-not ($gv.version | Where-Object { $_ -eq $McVersion })) {
        throw "Modrinth has no game_version '$McVersion' yet — cannot upload."
    }
    $ld = Invoke-RestMethod -Uri "$ApiBase/v2/tag/loader" -Headers $h -SkipHeaderValidation
    if (-not ($ld.name | Where-Object { $_ -eq $Loader })) { throw "Modrinth loader '$Loader' not found." }
}

function Invoke-ScModrinthUpload {
    param(
        [Parameter(Mandatory)]$Config, [Parameter(Mandatory)][string]$Title,
        [Parameter(Mandatory)][string]$VersionNumber, [Parameter(Mandatory)][string]$Changelog,
        [Parameter(Mandatory)][string]$VersionType, [Parameter(Mandatory)][string[]]$GameVersions,
        [Parameter(Mandatory)][string]$Loader, [Parameter(Mandatory)][string]$Jar,
        [switch]$IncludeJei, [switch]$Featured,
        [string]$ApiBase = 'https://api.modrinth.com', [switch]$Execute
    )
    $deps = @()
    if ($IncludeJei -and (Get-ScProp $Config.modrinth 'jeiProjectId')) {
        $deps += @{ project_id = $Config.modrinth.jeiProjectId; version_id = $null; file_name = $null; dependency_type = 'optional' }
    }
    $dataObj = [ordered]@{
        name           = $Title
        version_number = $VersionNumber
        changelog      = $Changelog
        dependencies   = $deps
        game_versions  = $GameVersions
        version_type   = $VersionType
        loaders        = @($Loader)
        featured       = [bool]$Featured
        status         = 'listed'
        project_id     = $Config.modrinth.projectId
        file_parts     = @('file')
        primary_file   = 'file'
    }
    $data = ConvertTo-Json $dataObj -Depth 6 -Compress

    Write-ScInfo "MR [$VersionNumber]  `"$Title`"  ($VersionType)  <- $(Split-Path $Jar -Leaf)"
    if (-not $Execute) {
        Write-ScDry "POST $ApiBase/v2/version  data=$data"
        return
    }
    $token = [Environment]::GetEnvironmentVariable($Config.modrinth.tokenEnvVar)
    if ("$($Config.modrinth.projectId)" -like 'REPLACE_ME*') { throw 'Modrinth projectId is not set in release.json.' }
    if (-not $token) { throw "Modrinth token env var '$($Config.modrinth.tokenEnvVar)' is not set." }
    Test-ScModrinthVocab -McVersion $GameVersions[0] -Loader $Loader -UserAgent $Config.modrinth.userAgent -ApiBase $ApiBase
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
