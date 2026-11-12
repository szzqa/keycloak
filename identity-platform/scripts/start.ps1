#requires -Version 7.0
param([switch]$SkipBuild, [switch]$WithSecondProduct)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
& "$PSScriptRoot/setup.ps1" -SkipBuild:$SkipBuild
if ($LASTEXITCODE) { throw 'Setup failed' }
function Start-IdentityService([string]$service) {
  $pidFile = "$taskRoot/.local/$service.pid"
  if (Test-Path -LiteralPath $pidFile) {
    $savedId = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    if ($savedId -match '^\d+$') {
      $taskProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$savedId" -ErrorAction SilentlyContinue
      $expectedScript = [System.IO.Path]::GetFullPath("$PSScriptRoot/run.ps1")
      if ($taskProcess -and $taskProcess.CommandLine.Replace('/','\').Contains($expectedScript) -and $taskProcess.CommandLine -like "*-Service $service*") {
        Write-Host "$service already running"; return
      }
    }
  }
  $port = @{mock=9099;keycloak=8180;product=8081;'product-b'=8082}[$service]
  if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Port $port is occupied; stop its foreground terminal or IDEA instance before starting $service." }
  $process = Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList @('-NoProfile','-File',"`"$PSScriptRoot/run.ps1`"",'-Service',$service) -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput "$taskRoot/.local/$service.out.log" -RedirectStandardError "$taskRoot/.local/$service.err.log" -PassThru
  $process.Id | Set-Content -LiteralPath $pidFile
}
Start-IdentityService 'mock'
function Wait-IdentityService([string]$name, [string]$url, [int]$attempts = 30) {
  for ($i = 0; $i -lt $attempts; $i++) {
    try { $null = Invoke-RestMethod $url -TimeoutSec 2 -NoProxy; return } catch { Start-Sleep -Seconds 2 }
  }
  throw "$name startup timeout; inspect .local/$name.*.log"
}
Wait-IdentityService 'mock' 'http://127.0.0.1:9099/wecom/__health'
Start-IdentityService 'keycloak'
Wait-IdentityService 'keycloak' 'http://127.0.0.1:8180/realms/master/.well-known/openid-configuration' 90
Push-Location $taskRoot
try {
  python scripts/identity_admin.py initialize; if ($LASTEXITCODE) { throw 'Realm initialization failed' }
  python scripts/platform_setup.py; if ($LASTEXITCODE) { throw 'Scoped platform client initialization failed' }
} finally { Pop-Location }
Start-IdentityService 'product'
Wait-IdentityService 'product' 'http://127.0.0.1:8081/'
Push-Location $taskRoot
try { python scripts/platform_setup.py --register-application; if ($LASTEXITCODE) { throw 'TapCanvas application registration failed' } } finally { Pop-Location }
if ($WithSecondProduct) {
  Start-IdentityService 'product-b'
  Wait-IdentityService 'product-b' 'http://127.0.0.1:8082/'
}
Write-Host 'Product http://127.0.0.1:8081 | Mail http://127.0.0.1:8025 | Keycloak http://127.0.0.1:8180'
