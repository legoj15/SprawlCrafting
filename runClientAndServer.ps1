# Launches a node's dedicated SERVER and CLIENT side-by-side in a split-pane Windows Terminal,
# so their logs stay separate in one clean window. For hands-on testing of a single permutation.
#
#   ./runClientAndServer.ps1                      # 26.1.2-neoforge (the current port target)
#   ./runClientAndServer.ps1 -Node 1.21.1-fabric
#   ./runClientAndServer.ps1 -Node 1.21.1-neoforge
#
# For automated PASS/FAIL across ALL permutations, use ./testing/Invoke-NodeTests.ps1 instead.
param([string]$Node = '26.1.2-neoforge')

Write-Output "Launching Server + Client for node :$Node in a split-pane Windows Terminal..."

# Unquoted commands so wt doesn't misparse them; --title names each pane.
wt --title "Server $Node" -d . pwsh.exe -NoExit -Command ".\gradlew :${Node}:runServer" `
    `; split-pane --title "Client $Node" -d . pwsh.exe -NoExit -Command ".\gradlew :${Node}:runClient"
