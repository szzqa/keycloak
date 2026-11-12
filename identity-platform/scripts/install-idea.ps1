$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $taskRoot '..'))
$runDir = Join-Path $repoRoot '.run'
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
foreach ($service in @('Keycloak','Platform','Product B')) {
    $main = if ($service -eq 'Keycloak') { 'local.identity.IdentityKeycloakDev' } else { 'local.identity.demo.PlatformApplication' }
    $module = if ($service -eq 'Keycloak') { 'identity-dev-launcher' } else { 'identity-platform-server' }
    $parameters = if ($service -eq 'Keycloak') { '' } else { '--spring.config.additional-location=file:./.local/demo-product.properties' }
    if ($service -eq 'Product B') { $parameters = '--spring.config.additional-location=file:./.local/demo-product-b.properties' }
    $vm = if ($service -eq 'Keycloak') { '-Dfile.encoding=UTF-8 -Djava.util.logging.manager=org.jboss.logmanager.LogManager' } else { '-Dfile.encoding=UTF-8' }
    $xml = @'
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="Identity {0}" type="Application" factoryName="Application">
    <option name="MAIN_CLASS_NAME" value="{1}" />
    <module name="{2}" />
    <option name="PROGRAM_PARAMETERS" value="{3}" />
    <option name="VM_PARAMETERS" value="{4}" />
    <option name="WORKING_DIRECTORY" value="$PROJECT_DIR$/identity-platform" />
    <shortenClasspath name="ARGS_FILE" />
    <method v="2"><option name="Make" enabled="true" /></method>
  </configuration>
</component>
'@ -f $service,$main,$module,$parameters,$vm
    [System.IO.File]::WriteAllText((Join-Path $runDir "Identity $service.run.xml"), $xml, [System.Text.UTF8Encoding]::new($false))
}
foreach ($retired in @('Consumer','Workforce','Product')) {
    $retiredFile = Join-Path $runDir "Identity $retired.run.xml"
    if (Test-Path -LiteralPath $retiredFile) {
        [xml]$retiredConfig = Get-Content -LiteralPath $retiredFile -Raw
        if ($retiredConfig.SelectSingleNode('//option[@name="MAIN_CLASS_NAME"]').value -eq 'local.identity.demo.DemoApplication') {
            Remove-Item -LiteralPath $retiredFile
        }
    }
}
Write-Host 'IDEA configurations installed: Identity Keycloak, Identity Platform, Identity Product B (optional SSO demo)'
