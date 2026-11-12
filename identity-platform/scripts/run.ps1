param([ValidateSet('keycloak','product','product-b','mock')][string]$Service = 'keycloak')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Set-Location $taskRoot
switch ($Service) {
  'keycloak' {
    $taskClasspath = (Get-Content .local/dev-classpath.txt -Raw).Trim()
    $taskJavaArgs = @('-Dfile.encoding=UTF-8', '-Djava.util.logging.manager=org.jboss.logmanager.LogManager', '-cp', ('"dev-launcher/target/classes;' + $taskClasspath.Replace('\','/') + '"'), 'local.identity.IdentityKeycloakDev')
    [System.IO.File]::WriteAllLines("$taskRoot/.local/dev.args", $taskJavaArgs, [System.Text.UTF8Encoding]::new($false))
    java '@.local/dev.args'
  }
  'mock' { postman simulation run postman/simulations/wecom.sim.yaml --port 9099 --no-history --output ndjson }
  'product-b' { java '-Dfile.encoding=UTF-8' -jar platform-server/target/identity-platform-server-1.0.0-SNAPSHOT.jar "--spring.config.additional-location=file:$taskRoot/.local/demo-product-b.properties" }
  'product' { java '-Dfile.encoding=UTF-8' -jar platform-server/target/identity-platform-server-1.0.0-SNAPSHOT.jar "--spring.config.additional-location=file:$taskRoot/.local/demo-product.properties" }
}
exit $LASTEXITCODE
