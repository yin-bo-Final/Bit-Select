[CmdletBinding()]
param([string]$JavaHome, [string]$NodeInterpreter)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-Check([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "IDEA configuration regression: $Message" }
}
function Read-Xml([string]$Path) {
    $document = [Xml.XmlDocument]::new()
    $document.LoadXml([IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8))
    return ,$document
}
function Assert-NoSecrets([string]$Content) {
    foreach ($secret in $fakeSecrets) {
        Assert-Check (-not $Content.Contains($secret)) 'Fixture credential leaked; value suppressed.'
    }
}
function Invoke-Generator([switch]$Update) {
    try { $output = (& $fixtureGenerator @generatorParameters -UpdateExistingConfigurations:$Update *>&1 | Out-String) }
    catch {
        $safeMessage = $_.Exception.Message
        foreach ($secret in $fakeSecrets) { $safeMessage = $safeMessage.Replace($secret, '[redacted]') }
        throw "Fixture generator failed: $safeMessage"
    }
    Assert-NoSecrets $output
}
function Resolve-FixturePath([string]$Value) {
    $resolved = [IO.Path]::GetFullPath($Value.Replace('$PROJECT_DIR$', $fixtureRoot))
    Assert-Check ($resolved.StartsWith($fixtureRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) 'Configured file escapes the fixture.'
    Assert-Check (Test-Path -LiteralPath $resolved -PathType Leaf) 'Configured environment or data file does not exist.'
    return $resolved
}
function Read-LaunchEnvironment([Xml.XmlElement]$Configuration) {
    $environment = @{ MYSQL_PASSWORD = 'old-inherited-password'; SILICONFLOW_API_KEY = 'old-inherited-key'; PORT = '9997'; AI_PORT = '9997' }
    $files = @($Configuration.SelectNodes("option[@name='envFilePaths']/option"))
    Assert-Check ($files.Count -eq 2) 'Existing launch item must reference both environment files.'
    foreach ($file in $files) {
        $path = Resolve-FixturePath $file.GetAttribute('value')
        foreach ($line in [IO.File]::ReadAllLines($path, [Text.Encoding]::UTF8)) {
            if ($line -match '^\s*([A-Z][A-Z0-9_]*)=(.*)$') { $environment[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'") }
        }
    }
    foreach ($entry in $Configuration.SelectNodes('envs/env')) { $environment[$entry.GetAttribute('name')] = $entry.GetAttribute('value') }
    return ,$environment
}
function Get-Snapshot {
    $snapshot = @{}
    foreach ($file in Get-ChildItem -LiteralPath $fixtureRoot -Recurse -File -Filter '*.xml' -Force) {
        $content = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8)
        Assert-NoSecrets $content
        $snapshot[$file.FullName.Substring($fixtureRoot.Length)] = $content
    }
    return ,$snapshot
}

$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixtureName = 'BitSelect IDEA fixture ' + [Guid]::NewGuid().ToString('N')
$fixtureRoot = [IO.Path]::GetFullPath((Join-Path $temporaryRoot $fixtureName))
$fixtureGenerator = Join-Path $fixtureRoot 'scripts/New-IdeaRunConfigurations.ps1'
$workspacePath = Join-Path $fixtureRoot '.idea/workspace.xml'
$utf8 = [Text.UTF8Encoding]::new($false)
$fakeSecrets = @(1..3 | ForEach-Object { 'fixture-secret-' + [Guid]::NewGuid().ToString('N') })
$applications = @(
    @{ module = 'catalog-service'; main = 'com.bitselect.catalog.CatalogApplication'; port = '8082'; portVariable = 'PORT' },
    @{ module = 'commerce-service'; main = 'com.bitselect.commerce.CommerceApplication'; port = '8081'; portVariable = 'PORT' },
    @{ module = 'ai-service'; main = 'com.bitselect.ai.AiApplication'; port = '8083'; portVariable = 'AI_PORT' },
    @{ module = 'gateway'; main = 'com.bitselect.gateway.GatewayApplication'; port = '8080'; portVariable = 'PORT' }
)
$generatorParameters = @{}
if ($JavaHome) { $generatorParameters.JavaHome = $JavaHome }
if ($NodeInterpreter) { $generatorParameters.NodeInterpreter = $NodeInterpreter }
try {
    foreach ($directory in @('scripts', '.idea', 'infra', 'data/ranking')) { New-Item -ItemType Directory -Path (Join-Path $fixtureRoot $directory) -Force | Out-Null }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'New-IdeaRunConfigurations.ps1') -Destination $fixtureGenerator
    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'infra/.env'), "MYSQL_PASSWORD=$($fakeSecrets[0])`nPORT=9999`nAI_PORT=9999`n", $utf8)
    [IO.File]::WriteAllText((Join-Path $fixtureRoot '.env.local'), "MYSQL_PASSWORD=$($fakeSecrets[1])`nSILICONFLOW_API_KEY=$($fakeSecrets[2])`n", $utf8)
    foreach ($path in @('data/products.json', 'data/ranking/model.json')) { [IO.File]::WriteAllText((Join-Path $fixtureRoot $path), '{}', $utf8) }
    $template = @'
<configuration name="Legacy {MODULE}" type="SpringBootApplicationConfigurationType" factoryName="Spring Boot" temporary="true">
  <module name="{MODULE}" /><option name="SPRING_BOOT_MAIN_CLASS" value="{MAIN}" />
  <option name="WORKING_DIRECTORY" value="$MODULE_WORKING_DIR$" />
  <option name="envFilePaths"><option value="$PROJECT_DIR$/old.env" /></option>
  <option name="VM_PARAMETERS" value="-Xmx768m -Dfixture.flag=preserve-me" /><option name="CUSTOM_SETTING" value="preserve-option" />
  <envs><env name="{PORT_VARIABLE}" value="9998" /><env name="CATALOG_SEED_PATH" value="old/catalogue.json" /><env name="AI_RANKING_MODEL" value="old/ranking.json" /><env name="CUSTOM_SETTING" value="preserve-env" /></envs>
</configuration>
'@
    $entries = foreach ($app in $applications) { $template.Replace('{MODULE}', $app.module).Replace('{MAIN}', $app.main).Replace('{PORT_VARIABLE}', $app.portVariable) }
    $external = '<configuration name="External project" type="SpringBootApplicationConfigurationType"><module name="external-service" /><option name="SPRING_BOOT_MAIN_CLASS" value="com.external.ExternalApplication" /><option name="CUSTOM_SETTING" value="do-not-touch" /></configuration>'
    [IO.File]::WriteAllText($workspacePath, ('<project><component name="RunManager">' + ($entries -join "`n") + $external + '</component></project>'), $utf8)
    $original = Read-Xml $workspacePath
    # Mix absent bindings and stale bindings to cover both insertion and replacement.
    foreach ($selector in @(
        "//configuration[@name='Legacy catalog-service']/option[@name='envFilePaths']",
        "//configuration[@name='Legacy gateway']/option[@name='WORKING_DIRECTORY']",
        "//configuration[@name='Legacy commerce-service']/envs/env[@name!='CUSTOM_SETTING']"
    )) { foreach ($node in @($original.SelectNodes($selector))) { [void]$node.ParentNode.RemoveChild($node) } }
    $before = $original.OuterXml
    [IO.File]::WriteAllText($workspacePath, $before, $utf8)
    $externalBefore = $original.SelectSingleNode("//configuration[@name='External project']").OuterXml
    Invoke-Generator
    Assert-Check ([IO.File]::ReadAllText($workspacePath, [Text.Encoding]::UTF8) -ceq $before) 'Default generation must not touch workspace.xml.'
    [void](Get-Snapshot)
    $firstSnapshot = $null
    foreach ($run in 1..2) {
        Invoke-Generator -Update
        $workspace = Read-Xml $workspacePath
        Assert-Check ($workspace.SelectNodes('//configuration').Count -eq 5) 'Existing launch items were duplicated or removed.'
        foreach ($app in $applications) {
            $configuration = $workspace.SelectSingleNode("//configuration[@name='Legacy $($app.module)']")
            Assert-Check ($null -ne $configuration) "Missing existing launch item: $($app.module)."
            $environment = Read-LaunchEnvironment $configuration
            Assert-Check ($environment.MYSQL_PASSWORD -ceq $fakeSecrets[1]) 'Dotenv order did not replace the inherited database password.'
            Assert-Check ($environment.SILICONFLOW_API_KEY -ceq $fakeSecrets[2]) 'Dotenv did not replace the inherited API key.'
            Assert-Check ($environment[$app.portVariable] -ceq $app.port) "Explicit service port must override dotenv and inherited values: $($app.module)."
            foreach ($key in @('CATALOG_SEED_PATH', 'AI_RANKING_MODEL')) { [void](Resolve-FixturePath $environment[$key]) }
            Assert-Check ($configuration.SelectSingleNode("option[@name='WORKING_DIRECTORY']").GetAttribute('value') -ceq '$PROJECT_DIR$') 'Existing launch item must use the repository root.'
            Assert-Check ($configuration.SelectSingleNode("option[@name='VM_PARAMETERS']").GetAttribute('value') -ceq '-Xmx768m -Dfixture.flag=preserve-me') 'Personal VM options were changed.'
            Assert-Check ($configuration.SelectSingleNode("option[@name='CUSTOM_SETTING']").GetAttribute('value') -ceq 'preserve-option') 'Personal configuration option was changed.'
            Assert-Check ($environment.CUSTOM_SETTING -ceq 'preserve-env') 'Personal environment setting was changed.'
        }
        Assert-Check ($workspace.SelectSingleNode("//configuration[@name='External project']").OuterXml -ceq $externalBefore) 'External project launch item was changed.'
        $snapshot = Get-Snapshot
        if ($run -eq 1) { $firstSnapshot = $snapshot }
        else {
            Assert-Check ($snapshot.Count -eq $firstSnapshot.Count) 'Repeated generation changed the XML file count.'
            foreach ($path in $firstSnapshot.Keys) {
                Assert-Check ($snapshot.ContainsKey($path) -and $snapshot[$path] -ceq $firstSnapshot[$path]) "Repeated generation changed $path."
            }
        }
    }
    Write-Host 'PASS: default leaves workspace unchanged; migration repairs all four existing Spring Boot launches.'
    Write-Host 'PASS: dotenv precedence, explicit ports, data paths, personal settings and external configuration verified.'
    Write-Host 'PASS: XML and output contain no fixture credentials; repeated generation is stable.'
}
finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        $resolved = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $fixtureRoot).Path)
        $expected = [IO.Path]::GetFullPath((Join-Path $temporaryRoot $fixtureName))
        $boundary = $temporaryRoot.TrimEnd([char[]]@('\', '/')) + [IO.Path]::DirectorySeparatorChar
        if ($resolved -cne $expected -or -not $resolved.StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing cleanup outside the expected temporary fixture.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
