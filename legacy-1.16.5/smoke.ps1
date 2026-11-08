<#
.SYNOPSIS
  Boot-smoke a 1.16.5 dev run: start a Gradle run task, wait for a log line, then kill the process tree.

.EXAMPLE
  .\smoke.ps1 -Task :forge:runServer
  .\smoke.ps1 -Task :fabric:runServer
  .\smoke.ps1 -Task :forge:runClient -Gradle '-Pjei'
  .\smoke.ps1 -Task :fabric:runClient

.NOTES
  Server tasks wait for "Done (" (after writing eula.txt and an offline server.properties into the run
  dir); client tasks wait for the title-screen marker below. The run output goes to build\smoke-<task>.log.
  The result line is PASS/FAIL plus any mixin errors found in the log. Exit code 0 = PASS.
#>
param(
    [Parameter(Mandatory = $true)][string]$Task,
    [string]$Pattern,
    [int]$TimeoutSec = 900,
    [string]$Gradle = ''
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $env:SC_JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.2' } else { $env:JAVA_HOME = $env:SC_JAVA_HOME }

$project = ($Task -split ':')[1]
$isServer = $Task -match 'Server$'
if (-not $Pattern) {
    # Server: vanilla's "Done (12.3s)! For help, type "help"". Client: the sound engine starts as the
    # title screen is first shown on both loaders.
    $Pattern = if ($isServer) { 'Done \(' } else { 'Sound engine started' }
}

if ($isServer) {
    $runDir = Join-Path $root "$project\run-server"
    New-Item -ItemType Directory -Force $runDir | Out-Null
    Set-Content -Path (Join-Path $runDir 'eula.txt') -Value 'eula=true' -Encoding ASCII
    $props = Join-Path $runDir 'server.properties'
    if (-not (Test-Path $props)) {
        Set-Content -Path $props -Encoding ASCII -Value @('online-mode=false', 'server-port=25599', 'level-type=flat', 'spawn-npcs=false', 'spawn-animals=false')
    }
}

if (-not $isServer) {
    # Makes MixinSelfCheck force-load every recipe-book mixin target at client start (see that class).
    $env:SC_MIXIN_SELFTEST = '1'
}

$logDir = Join-Path $root 'build'
New-Item -ItemType Directory -Force $logDir | Out-Null
$log = Join-Path $logDir ("smoke-" + ($Task -replace '[:]', '_').Trim('_') + ".log")
$errLog = "$log.err"

$gradleArgs = @($Task, '--console=plain') + ($Gradle -split ' ' | Where-Object { $_ })
$proc = Start-Process -FilePath (Join-Path $root 'gradlew.bat') -ArgumentList $gradleArgs -WorkingDirectory $root `
    -RedirectStandardOutput $log -RedirectStandardError $errLog -PassThru -WindowStyle Hidden

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$hit = $false
while ((Get-Date) -lt $deadline -and -not $proc.HasExited) {
    Start-Sleep -Seconds 3
    if ((Test-Path $log) -and (Select-String -Path $log -Pattern $Pattern -Quiet)) { $hit = $true; break }
}
if (-not $proc.HasExited) {
    & taskkill.exe /T /F /PID $proc.Id | Out-Null
}
Start-Sleep -Seconds 2
$mixinErrors = @()
if (Test-Path $log) {
    $mixinErrors = Select-String -Path $log -Pattern 'Mixin apply failed|MixinApplyError|InvalidMixinException|InvalidInjectionException|Critical injection failure|mixin.*(ERROR|FATAL)' |
        ForEach-Object { $_.Line.Trim() }
}
$selfCheck = $true
if (-not $isServer -and (Test-Path $log)) {
    $selfCheck = [bool](Select-String -Path $log -Pattern 'SprawlCrafting mixin self-check passed' -Quiet)
    if (Select-String -Path $log -Pattern 'SprawlCrafting mixin self-check FAILED' -Quiet) { $selfCheck = $false }
}
$status = if ($hit -and $selfCheck -and $mixinErrors.Count -eq 0) { 'PASS' } else { 'FAIL' }
"$status $Task pattern='$Pattern' reached=$hit selfCheck=$selfCheck log=$log"
$mixinErrors | Select-Object -First 20 | ForEach-Object { "MIXIN: $_" }
if ($status -eq 'PASS') { exit 0 } else { exit 1 }
