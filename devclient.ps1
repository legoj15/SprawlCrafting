<#
.SYNOPSIS
  Start one Stonecutter node's dev client (:<node>:runClient) for an interactive McDevBridge session and keep it
  up, holding the machine-wide smoke lock for as long as it runs. Agent tool; smoke.ps1 is the boot-and-kill twin.

.DESCRIPTION
  Every dev client running McDevBridge writes the ONE ~/.mcdevbridge/bridge.json, so two clients at once make
  both sessions read the wrong game. The McDevBridge repo's tools/SmokeLock.ps1 is the machine-wide lock for
  that: this script takes it (failing at once, naming the holder, if a live run has it), starts the client, and
  releases the lock when the client's Gradle process ends. Run it in the background (the PowerShell tool's
  run_in_background) and drive the client with McDevBridge (tools/mcbridge.ps1 or its HTTP API).

  To stop: taskkill /T /F /PID <the gradlew cmd pid printed on the first line>; the lock is released when the
  script ends.

.PARAMETER Node
  The node project name, e.g. 1.21.1-neoforge; or forge-1.12.2 (its own build, host JDK 25), or
  legacy-1.16.5:forge / legacy-1.16.5:fabric (the legacy build's loader module).
.PARAMETER Gradle
  Extra Gradle arguments, space separated, e.g. '-Psc.sophisticated=true -Psc.world=SophTest'
  (-Psc.sophisticated adds Sophisticated Core/Storage/Backpacks to the runtime on the nodes that carry that compat;
  -Psc.world quick-plays a save folder name from the node's run\saves, no spaces).
.PARAMETER Log
  File for the Gradle output (default build\devclient-<node>.log). Game output is in the node's run\logs\latest.log.

.EXAMPLE
  .\devclient.ps1 -Node 1.21.1-neoforge -Gradle '-Psc.sophisticated=true -Psc.world=SophTest'
  .\devclient.ps1 -Node forge-1.12.2 -Gradle '-Psc.jei=true'
#>
param(
    [Parameter(Mandatory = $true)][string]$Node,
    [string]$Gradle = '',
    [string]$Log
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$tools = if ($env:MCDEVBRIDGE_TOOLS) { $env:MCDEVBRIDGE_TOOLS } else { 'E:\GitHub\McDevBridge\tools' }
. (Join-Path $tools 'SmokeLock.ps1')
# The legacy lines are self-contained Gradle builds in their own folders: run their runClient there.
$projDir = $root
$task = ":${Node}:runClient"
$defaultJdk = 'C:\Program Files\Java\jdk-21.0.2'
if ($Node -eq 'forge-1.12.2') {
    $projDir = Join-Path $root 'forge-1.12.2'; $task = 'runClient'
    $defaultJdk = (Get-ChildItem 'C:\Program Files\Java' -Directory -Filter 'jdk-25*' | Select-Object -First 1).FullName
} elseif ($Node -like 'legacy-1.16.5:*') {
    $projDir = Join-Path $root 'legacy-1.16.5'; $task = ":$($Node.Split(':')[1]):runClient"
}
if (-not $env:SC_JAVA_HOME) { $env:JAVA_HOME = $defaultJdk } else { $env:JAVA_HOME = $env:SC_JAVA_HOME }
if (-not $Log) {
    New-Item -ItemType Directory -Force (Join-Path $root 'build') | Out-Null
    $Log = Join-Path $root "build\devclient-$($Node.Replace(':','-')).log"
}

$null = Enter-SmokeLock -Owner 'SprawlCrafting devclient.ps1' -Repo $root -Detail "dev client $Node"
try {
    $gradleArgs = @($task, '--console=plain') + ($Gradle -split ' ' | Where-Object { $_ })
    $proc = Start-Process -FilePath (Join-Path $projDir 'gradlew.bat') -ArgumentList $gradleArgs -WorkingDirectory $projDir `
        -RedirectStandardOutput $Log -RedirectStandardError "$Log.err" -PassThru -WindowStyle Hidden
    "gradlew cmd pid $($proc.Id); gradle output $Log"
    $proc.WaitForExit()
} finally {
    Exit-SmokeLock
}
