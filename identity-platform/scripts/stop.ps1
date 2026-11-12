param([ValidateSet('all','keycloak','product-b','product','consumer','workforce','mock')][string]$Service = 'all')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$services = if ($Service -eq 'all') { @('product-b','product','consumer','workforce','keycloak','mock') } else { @($Service) }
foreach ($taskService in $services) {
    $pidFile = Join-Path $taskRoot ".local/$taskService.pid"
    if (-not (Test-Path -LiteralPath $pidFile)) { Write-Host "$taskService has no managed process; stop its foreground terminal or IDEA run."; continue }
    $savedId = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    if ($savedId -notmatch '^\d+$') { continue }
    $taskProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$savedId" -ErrorAction SilentlyContinue
    if (-not $taskProcess) { continue }
    # Stop only a hidden run.ps1 wrapper created for this workspace, never arbitrary port owners or IDEA.
    $expectedScript = [System.IO.Path]::GetFullPath("$PSScriptRoot/run.ps1")
    if (-not $taskProcess.CommandLine.Replace('/','\').Contains($expectedScript) -or $taskProcess.CommandLine -notlike "*-Service $taskService*") {
        Write-Host "Skipping unrecognized process for $taskService"; continue
    }
    & taskkill.exe /PID $taskProcess.ProcessId /T /F | Out-Null
    if ($LASTEXITCODE) { throw "Could not stop $taskService" }
}
