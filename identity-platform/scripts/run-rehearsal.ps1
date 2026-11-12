param([ValidateSet('keycloak','platform')][string]$Service='keycloak', [string]$RecoveryBackup)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskLocal=Join-Path $taskRoot '.local/rehearsal'
$taskState=Get-Content -LiteralPath (Join-Path $taskLocal 'environment.json') -Raw | ConvertFrom-Json
$taskConfig=Get-Content -LiteralPath (Join-Path $taskLocal 'settings.json') -Raw | ConvertFrom-Json
$taskDatabase='identity'
if($RecoveryBackup) {
    $taskBackup=(Resolve-Path -LiteralPath $RecoveryBackup).Path
    $taskBoundary=[IO.Path]::GetFullPath($taskLocal)+[IO.Path]::DirectorySeparatorChar
    if(!$taskBackup.StartsWith($taskBoundary,[StringComparison]::OrdinalIgnoreCase)){throw 'Recovery backup must be inside the rehearsal directory'}
    $taskReport=Get-Content -LiteralPath (Join-Path $taskBackup 'recovery-report.json') -Raw | ConvertFrom-Json
    $taskDatabase=$taskReport.restoredPostgresDatabase
    if($taskDatabase -notmatch '^identity_recovery_[a-f0-9]{12}$' -or $taskDatabase -ne $taskReport.restoredMysqlDatabase){throw 'Invalid recovered test databases'}
    $taskProperties=Get-Content -LiteralPath (Join-Path $taskLocal 'keycloak.properties') -Raw
    $taskLocal=Join-Path $taskLocal $taskDatabase
    New-Item -ItemType Directory -Path $taskLocal -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $taskLocal 'keycloak.properties'),$taskProperties.Replace('jdbc:postgresql://127.0.0.1:55432/identity',('jdbc:postgresql://127.0.0.1:55432/'+$taskDatabase)),[Text.UTF8Encoding]::new($false))
}
$taskJava=Join-Path $env:JAVA_HOME 'bin/java.exe'
if(!(Test-Path -LiteralPath $taskJava)){throw 'JAVA_HOME must point to Java 21'}
if($Service -eq 'keycloak') {
    $taskClasspath=(Get-Content -LiteralPath (Join-Path $taskRoot '.local/dev-classpath.txt') -Raw).Trim()
    $taskArguments=@('-Dfile.encoding=UTF-8','-Djava.util.logging.manager=org.jboss.logmanager.LogManager',
        ('"-Didentity.local='+$taskLocal.Replace('\','/')+'"'),'-cp',
        ('"'+(Join-Path $taskRoot 'dev-launcher/target/classes').Replace('\','/')+';'+$taskClasspath.Replace('\','/')+'"'),
        'local.identity.IdentityKeycloakDev','start-dev','--http-host=127.0.0.1','--http-port=8280','--hostname=http://127.0.0.1:8280')
} else {
    $taskReady=$false
    for($taskAttempt=0;$taskAttempt -lt 30;$taskAttempt++) {
        try { $taskResponse=Invoke-WebRequest 'http://127.0.0.1:8280/realms/consumer/.well-known/openid-configuration' -TimeoutSec 2; if($taskResponse.StatusCode -eq 200){$taskReady=$true;break} } catch { }
        Start-Sleep -Seconds 1
    }
    if(!$taskReady){throw 'Rehearsal Keycloak must be ready before starting the platform'}
    $taskProperties=@('server.port=8281','server.servlet.session.cookie.name=REHEARSAL_PORTAL_SESSION','server.servlet.session.cookie.secure=false',
        'identity.keycloak-base-url=http://127.0.0.1:8280','identity.other-product=http://127.0.0.1:8282',
        ('identity.consumer-client-secret='+$taskConfig.consumer_web),('identity.operations-client-secret='+$taskConfig.platform_operations),
        ('spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/'+$taskDatabase+'?currentSchema='+$taskState.platformSchema),
        ('spring.datasource.username='+$taskState.platformRole),('spring.datasource.password='+$taskConfig.business_db))
    [IO.File]::WriteAllLines((Join-Path $taskLocal 'platform.properties'),$taskProperties,[Text.UTF8Encoding]::new($false))
    $taskArguments=@('-Dfile.encoding=UTF-8','-jar',('"'+(Join-Path $taskRoot 'platform-server/target/identity-platform-server-1.0.0-SNAPSHOT.jar').Replace('\','/')+'"'),
        ('"--spring.config.additional-location=file:'+(Join-Path $taskLocal 'platform.properties').Replace('\','/')+'"'))
}
$taskArgumentFile=Join-Path $taskLocal ($Service+'.args')
[IO.File]::WriteAllLines($taskArgumentFile,$taskArguments,[Text.UTF8Encoding]::new($false))
$taskProcess=Start-Process -FilePath $taskJava -ArgumentList ('@"'+$taskArgumentFile+'"') -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $taskLocal ($Service+'.out.log')) -RedirectStandardError (Join-Path $taskLocal ($Service+'.err.log'))
$taskProcess.Id | Set-Content -LiteralPath (Join-Path $taskLocal ($Service+'.pid'))
Write-Output "$Service rehearsal process started: $($taskProcess.Id)"
