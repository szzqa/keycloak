param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    python scripts/identity_admin.py prepare
    if ($LASTEXITCODE) { throw 'Local configuration failed' }
    docker compose -p identity-platform --env-file .local/compose.env up -d --wait
    if ($LASTEXITCODE) { throw 'Infrastructure startup failed' }
    if (-not $SkipBuild) {
        $env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8'
        mvn -B -ntp install
        if ($LASTEXITCODE) { throw 'Maven build failed' }
    }
    mvn -B -ntp -f dev-launcher/pom.xml dependency:build-classpath "-Dmdep.outputFile=$taskRoot/.local/dev-classpath.txt"
    if ($LASTEXITCODE) { throw 'Classpath resolution failed' }
} finally { Pop-Location }
