<#
.SYNOPSIS
  Run this build's Gradle wrapper on JDK 21 (FG7 / Loom 1.17 need a modern host JDK; the mod itself is
  compiled, tested and run on a Java 8 toolchain). Override the JDK with $env:SC_JAVA_HOME.

.EXAMPLE
  .\gw.ps1 :forge:build :fabric:build
#>
if ($env:SC_JAVA_HOME) { $env:JAVA_HOME = $env:SC_JAVA_HOME } else { $env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.2' }
& (Join-Path $PSScriptRoot 'gradlew.bat') -p $PSScriptRoot @args
exit $LASTEXITCODE
