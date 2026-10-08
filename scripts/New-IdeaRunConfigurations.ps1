[CmdletBinding()]
param(
    [string]$JavaHome,
    [string]$NodeInterpreter,
    [switch]$UpdateExistingConfigurations
)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$outputDirectory = Join-Path $repositoryRoot '.idea/runConfigurations'
if (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot 'infra/.env'))) {
    throw '缺少 infra/.env。请先运行 scripts/Initialize-LocalEnvironment.ps1 或启动本地中间件。'
}

if (-not $JavaHome) {
    $candidates = @($env:JAVA_HOME)
    $jdkDirectory = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path -LiteralPath $jdkDirectory) {
        $candidates += @(Get-ChildItem -LiteralPath $jdkDirectory -Directory | Where-Object Name -Match '21' | Select-Object -ExpandProperty FullName)
    }
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $candidateJava = Join-Path $candidate 'bin/java.exe'
        if (Test-Path -LiteralPath $candidateJava) {
            $version = (& $candidateJava --version 2>&1 | Select-Object -First 1).ToString()
            if ($version -match '\b21[.\s]') { $JavaHome = $candidate; break }
        }
    }
}
if (-not $JavaHome) { throw '未找到 JDK 21。请设置 JAVA_HOME，或使用 -JavaHome 指定目录。' }
$java = Join-Path $JavaHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw '指定的 JavaHome 中不存在 java.exe。' }
$version = (& $java --version 2>&1 | Select-Object -First 1).ToString()
if ($version -notmatch '\b21[.\s]') { throw '本项目要求使用 Java 21。' }
if (-not $NodeInterpreter) { $NodeInterpreter = (Get-Command node.exe -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $NodeInterpreter -PathType Leaf)) { throw '未找到 Node.js 解释器，请使用 -NodeInterpreter 指定 node.exe。' }

function ConvertTo-XmlValue([string]$Value) {
    return [Security.SecurityElement]::Escape($Value)
}

function Write-RunConfiguration([string]$FileName, [string]$Content) {
    # 仅刷新本脚本管理的配置；不读取或复制私密环境文件的内容。
    [xml]$document = $Content
    [IO.File]::WriteAllText((Join-Path $outputDirectory $FileName), $Content, [Text.UTF8Encoding]::new($false))
}

New-Item -ItemType Directory -Force $outputDirectory | Out-Null
$environmentFiles = @('$PROJECT_DIR$/infra/.env')
if (Test-Path -LiteralPath (Join-Path $repositoryRoot '.env.local')) {
    $environmentFiles += '$PROJECT_DIR$/.env.local'
}
$environmentOptions = ($environmentFiles | ForEach-Object { '      <option value="' + (ConvertTo-XmlValue $_) + '" />' }) -join "`n"
$applications = @(
    @{module='catalog-service';main='com.bitselect.catalog.CatalogApplication';port='8082';portVariable='PORT';heap='256m'},
    @{module='commerce-service';main='com.bitselect.commerce.CommerceApplication';port='8081';portVariable='PORT';heap='256m'},
    @{module='ai-service';main='com.bitselect.ai.AiApplication';port='8083';portVariable='AI_PORT';heap='512m'},
    @{module='gateway';main='com.bitselect.gateway.GatewayApplication';port='8080';portVariable='PORT';heap='256m'}
)
$template = @'
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="Bit Select - {MODULE}" type="Application" factoryName="Application">
    <option name="MAIN_CLASS_NAME" value="{MAIN}" />
    <module name="{MODULE}" />
    <option name="ALTERNATIVE_JRE_PATH" value="{JAVA_HOME}" />
    <option name="ALTERNATIVE_JRE_PATH_ENABLED" value="true" />
    <option name="WORKING_DIRECTORY" value="$PROJECT_DIR$" />
    <option name="VM_PARAMETERS" value="-Xms64m -Xmx{HEAP} -XX:ActiveProcessorCount=4" />
    <option name="envFilePaths">
{ENVIRONMENT_FILES}
    </option>
    <envs>
      <env name="{PORT_VARIABLE}" value="{PORT}" />
      <env name="CATALOG_SEED_PATH" value="$PROJECT_DIR$/data/products.json" />
      <env name="AI_RANKING_MODEL" value="$PROJECT_DIR$/data/ranking/model.json" />
    </envs>
    <method v="2"><option name="Make" enabled="true" /></method>
  </configuration>
</component>
'@
foreach ($application in $applications) {
    $content = $template.Replace('{JAVA_HOME}', (ConvertTo-XmlValue $JavaHome.Replace('\', '/'))).Replace('{ENVIRONMENT_FILES}', $environmentOptions)
    $content = $content.Replace('{MODULE}', $application.module).Replace('{MAIN}', $application.main).Replace('{HEAP}', $application.heap)
    $content = $content.Replace('{PORT_VARIABLE}', $application.portVariable).Replace('{PORT}', $application.port)
    Write-RunConfiguration "Bit_Select_$($application.module.Replace('-', '_')).xml" $content
}

$frontendTemplate = @'
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="Bit Select - web" type="js.build_tools.npm">
    <package-json value="$PROJECT_DIR$/web/package.json" />
    <command value="run" />
    <scripts><script value="dev" /></scripts>
    <arguments value="-- --port 3000" />
    <node-interpreter value="{NODE}" />
    <envs><env name="API_GATEWAY_URL" value="http://127.0.0.1:8080" /></envs>
    <method v="2" />
  </configuration>
</component>
'@
Write-RunConfiguration 'Bit_Select_web.xml' ($frontendTemplate.Replace('{NODE}', (ConvertTo-XmlValue $NodeInterpreter.Replace('\', '/'))))

foreach ($includeFrontend in @($false, $true)) {
    $name = if ($includeFrontend) { '全部应用' } else { '全部后端' }
    $fileName = if ($includeFrontend) { 'Bit_Select_All_Applications.xml' } else { 'Bit_Select_All_Backend.xml' }
    $entries = @($applications | ForEach-Object { '    <toRun name="Bit Select - ' + $_.module + '" type="Application" />' })
    if ($includeFrontend) { $entries += '    <toRun name="Bit Select - web" type="js.build_tools.npm" />' }
    $content = @'
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="Bit Select - {NAME}" type="CompoundRunConfigurationType">
{ENTRIES}
    <method v="2" />
  </configuration>
</component>
'@
    Write-RunConfiguration $fileName ($content.Replace('{NAME}', $name).Replace('{ENTRIES}', ($entries -join "`n")))
}

$workspacePath = Join-Path $repositoryRoot '.idea/workspace.xml'
if ($UpdateExistingConfigurations -and (Test-Path -LiteralPath $workspacePath)) {
    $workspace = [xml]::new()
    $workspace.PreserveWhitespace = $true
    $workspace.Load($workspacePath)
    $updatedCount = 0
    foreach ($configuration in $workspace.SelectNodes('/project/component[@name="RunManager"]/configuration[@type="SpringBootApplicationConfigurationType"]')) {
        $mainOption = $configuration.SelectSingleNode('option[@name="SPRING_BOOT_MAIN_CLASS"]')
        $moduleOption = $configuration.SelectSingleNode('module')
        if ($null -eq $mainOption -or $null -eq $moduleOption) { continue }
        $application = $applications | Where-Object { $_.main -eq $mainOption.GetAttribute('value') -and $_.module -eq $moduleOption.GetAttribute('name') } | Select-Object -First 1
        if ($null -eq $application) { continue }
        $before = $configuration.OuterXml
        [xml]$reference = Get-Content -LiteralPath (Join-Path $outputDirectory "Bit_Select_$($application.module.Replace('-', '_')).xml") -Raw
        foreach ($optionName in @('WORKING_DIRECTORY', 'envFilePaths')) {
            $replacement = $workspace.ImportNode($reference.SelectSingleNode("/component/configuration/option[@name='$optionName']"), $true)
            $existing = $configuration.SelectSingleNode("option[@name='$optionName']")
            if ($null -eq $existing) { $null = $configuration.AppendChild($replacement) }
            else { $null = $configuration.ReplaceChild($replacement, $existing) }
        }
        $environment = $configuration.SelectSingleNode('envs')
        if ($null -eq $environment) {
            $environment = $workspace.CreateElement('envs')
            $null = $configuration.AppendChild($environment)
        }
        foreach ($variable in $reference.SelectNodes('/component/configuration/envs/env')) {
            $replacement = $workspace.ImportNode($variable, $true)
            $existing = $environment.SelectSingleNode("env[@name='$($variable.GetAttribute('name'))']")
            if ($null -eq $existing) { $null = $environment.AppendChild($replacement) }
            else { $null = $environment.ReplaceChild($replacement, $existing) }
        }
        if ($configuration.OuterXml -ne $before) { $updatedCount++ }
    }
    if ($updatedCount -gt 0) {
        $localDirectory = Join-Path $repositoryRoot '.local'
        New-Item -ItemType Directory -Force $localDirectory | Out-Null
        $backup = Join-Path $localDirectory 'idea-workspace.before-run-fix.xml'
        if (-not (Test-Path -LiteralPath $backup)) { Copy-Item -LiteralPath $workspacePath -Destination $backup }
        $settings = [Xml.XmlWriterSettings]::new()
        $settings.Encoding = [Text.UTF8Encoding]::new($false)
        $settings.Indent = $false
        $settings.NewLineHandling = [Xml.NewLineHandling]::None
        $writer = [Xml.XmlWriter]::Create($workspacePath, $settings)
        try { $workspace.Save($writer) } finally { $writer.Dispose() }
    }
    Write-Host "已为 $updatedCount 个现有 Spring Boot 启动项更新环境文件、工作目录和数据路径；其他个人设置保留。"
}
Write-Host '已更新 7 个本地 IDEA 运行配置：4 个后端、前端、全部后端和全部应用。'
Write-Host '后端使用 Java 21 和 IDEA 原生环境文件支持（2025.3+），无需安装 .env 插件。'
Write-Host '配置只引用环境文件路径，不含凭证；.idea/ 已被 Git 忽略。'
Write-Host '先启动 WSL Docker 中间件，再在 IDEA 选择 Bit Select - 全部应用；原有运行进程需先停止。'
if (-not $UpdateExistingConfigurations) {
    Write-Host '若要补齐 AiApplication 等已有启动项，请先关闭本项目，再使用 -UpdateExistingConfigurations 执行脚本并重新打开项目。'
}
