#Requires -Version 7.0
<#
.SYNOPSIS
    Token-free unit tests for the CurseForge game-version-id resolver (Select-ScCurseForgeVersionIds).

.DESCRIPTION
    The risky part of the CF upload is mapping names -> numeric ids: environment ("Client"/"Server")
    and Java tags must be scoped to their version TYPE (by slug), because a bare name like "Server"
    legitimately recurs under unrelated types, a missing Java tier must be a soft skip — not a hard
    failure or a guessed id — and the extra loaders (Quilt on the fabric files) must resolve like the
    primary loader. These cases can't be exercised by a dry run (it only previews names), so this
    harness feeds the PURE resolver hand-built CF responses and asserts the ids.

    Exits 0 on all-pass, 1 on any failure. No token / no network.
#>
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'modules\ScRelease.psm1') -Force

# ─── Representative CurseForge /api/game/version-types and /api/game/versions ──
# Type ids are deliberately "random" numbers — the resolver must key off slug, never the number.
$types = @(
    [pscustomobject]@{ id = 6103; name = 'Minecraft 26.3';  slug = 'minecraft-26-3' }
    [pscustomobject]@{ id = 6165; name = 'Minecraft 1.16.5'; slug = 'minecraft-1-16-5' }
    [pscustomobject]@{ id = 4000; name = 'Modloader';        slug = 'modloader' }
    [pscustomobject]@{ id = 7000; name = 'Java';             slug = 'java' }
    [pscustomobject]@{ id = 9000; name = 'Environment';      slug = 'environment' }
    [pscustomobject]@{ id = 8000; name = 'Server Software';  slug = 'server-software' }  # the collision type
)
$versions = @(
    [pscustomobject]@{ id = 103; gameVersionTypeID = 6103; name = '26.3';    slug = '26-3' }
    [pscustomobject]@{ id = 165; gameVersionTypeID = 6165; name = '1.16.5';  slug = '1-16-5' }
    # Loaders all live under the Modloader type — Quilt (the fabric nodes' extra loader) included.
    [pscustomobject]@{ id = 200; gameVersionTypeID = 4000; name = 'NeoForge'; slug = 'neoforge' }
    [pscustomobject]@{ id = 201; gameVersionTypeID = 4000; name = 'Fabric';   slug = 'fabric' }
    [pscustomobject]@{ id = 202; gameVersionTypeID = 4000; name = 'Forge';    slug = 'forge' }
    [pscustomobject]@{ id = 203; gameVersionTypeID = 4000; name = 'Quilt';    slug = 'quilt' }
    [pscustomobject]@{ id = 300; gameVersionTypeID = 7000; name = 'Java 21';  slug = 'java-21' }
    # NOTE: deliberately NO "Java 25" — exercises the soft skip.
    [pscustomobject]@{ id = 400; gameVersionTypeID = 9000; name = 'Client';   slug = 'client' }
    [pscustomobject]@{ id = 401; gameVersionTypeID = 9000; name = 'Server';   slug = 'server' }
    # COLLISION: a DIFFERENT "Server" under the non-environment "server-software" type. A name-only
    # match could grab id=999; the resolver must return the environment one (id=401).
    [pscustomobject]@{ id = 999; gameVersionTypeID = 8000; name = 'Server';   slug = 'server-sw' }
)

$fail = 0
function Assert { param([string]$Name, [bool]$Cond, [string]$Detail)
    if ($Cond) { Write-Host "  [PASS] $Name" -ForegroundColor Green }
    else { $script:fail++; Write-Host "  [FAIL] $Name — $Detail" -ForegroundColor Red } }

Write-Host 'Select-ScCurseForgeVersionIds' -ForegroundColor Cyan

# 1. Modern fabric node (26.3, Fabric + Quilt extra loader, Java 25 missing): MC + both loaders + both
#    env, java soft-skipped with a warning. Quilt resolves under the Modloader type like Fabric does.
$r = Select-ScCurseForgeVersionIds -Versions $versions -Types $types -McVersion '26.3' `
        -LoaderName @('Fabric','Quilt') -Environments @('Client','Server') -JavaName 'Java 25'
Assert 'fabric+quilt: MC+Fabric+Quilt+client+server ids, java skipped' `
    (($r.Ids | Sort-Object) -join ',' -eq '103,201,203,400,401') "got [$($r.Ids -join ',')]"
Assert 'fabric+quilt: Server resolves to ENV id 401 not collision 999' ($r.Ids -contains 401 -and $r.Ids -notcontains 999) "got [$($r.Ids -join ',')]"
Assert 'fabric+quilt: exactly one warning (missing Java 25)' ($r.Warnings.Count -eq 1 -and $r.Warnings[0] -match 'Java 25') "warnings=[$($r.Warnings -join '|')]"

# 2. Legacy node (1.16.5, Forge — no extra loader there, Java 21 PRESENT): java id included, no warning.
$r2 = Select-ScCurseForgeVersionIds -Versions $versions -Types $types -McVersion '1.16.5' `
        -LoaderName @('Forge') -Environments @('Client','Server') -JavaName 'Java 21'
Assert 'legacy-forge: includes Java 21 id 300' ($r2.Ids -contains 300) "got [$($r2.Ids -join ',')]"
Assert 'legacy-forge: no warnings' ($r2.Warnings.Count -eq 0) "warnings=[$($r2.Warnings -join '|')]"
Assert 'legacy-forge: full id set 165,202,300,400,401' (($r2.Ids | Sort-Object) -join ',' -eq '165,202,300,400,401') "got [$($r2.Ids -join ',')]"

# 3. No Java requested at all → no java id, no warning.
$r3 = Select-ScCurseForgeVersionIds -Versions $versions -Types $types -McVersion '26.3' -LoaderName @('NeoForge') -Environments @('Client','Server')
Assert 'no-java: only 103,200,400,401' (($r3.Ids | Sort-Object) -join ',' -eq '103,200,400,401') "got [$($r3.Ids -join ',')]"
Assert 'no-java: no warnings' ($r3.Warnings.Count -eq 0) "warnings=[$($r3.Warnings -join '|')]"

# 4. Single environment (client-only hypothetical) → only the Client id.
$r4 = Select-ScCurseForgeVersionIds -Versions $versions -Types $types -McVersion '26.3' -LoaderName @('NeoForge') -Environments @('Client')
Assert 'client-only: has 400, lacks 401' ($r4.Ids -contains 400 -and $r4.Ids -notcontains 401) "got [$($r4.Ids -join ',')]"

# 4b. A single (non-array) loader name still binds: a Forge file gets Forge's id (202), never NeoForge's (200).
$rf = Select-ScCurseForgeVersionIds -Versions $versions -Types $types -McVersion '26.3' -LoaderName 'Forge' -Environments @('Client','Server')
Assert 'forge: loader id 202, not NeoForge 200' (($rf.Ids | Sort-Object) -join ',' -eq '103,202,400,401') "got [$($rf.Ids -join ',')]"

# 5. Required failures throw.
function ThrowsLike { param([scriptblock]$S, [string]$Pat) try { & $S | Out-Null; return $false } catch { return ($_.Exception.Message -match $Pat) } }
Assert 'missing MC version throws' (ThrowsLike { Select-ScCurseForgeVersionIds -Versions $versions -Types $types -McVersion '27.0' -LoaderName @('NeoForge') } 'no game version named') 'expected throw'
Assert 'missing extra loader throws' (ThrowsLike { Select-ScCurseForgeVersionIds -Versions $versions -Types $types -McVersion '26.3' -LoaderName @('Fabric','LiteLoader') -Environments @('Client','Server') } 'no loader named') 'expected throw'
Assert 'missing environment throws' (ThrowsLike { Select-ScCurseForgeVersionIds -Versions $versions -Types $types -McVersion '26.3' -LoaderName @('NeoForge') -Environments @('Client','Bogus') } "no 'Bogus' entry") 'expected throw'

if ($fail -eq 0) { Write-Host "`nALL PASS" -ForegroundColor Green; exit 0 }
else { Write-Host "`n$fail FAILED" -ForegroundColor Red; exit 1 }
