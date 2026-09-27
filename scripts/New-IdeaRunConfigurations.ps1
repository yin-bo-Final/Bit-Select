[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$outputDirectory = Join-Path $repositoryRoot '.idea/runConfigurations'
New-Item -ItemType Directory -Force $outputDirectory | Out-Null
$applications = @{
    'catalog-service' = 'com.bitselect.catalog.CatalogApplication'
    'commerce-service' = 'com.bitselect.commerce.CommerceApplication'
    'gateway' = 'com.bitselect.gateway.GatewayApplication'
    'ai-service' = 'com.bitselect.ai.AiApplication'
}
$template = @'
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="Bit Select - {MODULE}" type="Application" factoryName="Application">
    <option name="MAIN_CLASS_NAME" value="{MAIN}" />
    <module name="{MODULE}" />
    <option name="WORKING_DIRECTORY" value="$PROJECT_DIR$" />
    <option name="VM_PARAMETERS" value="-Xms64m -Xmx512m -XX:ActiveProcessorCount=4" />
    <envs>
      <env name="CATALOG_SEED_PATH" value="$PROJECT_DIR$/data/products.json" />
      <env name="AI_RANKING_MODEL" value="$PROJECT_DIR$/data/ranking/model.json" />
    </envs>
    <method v="2"><option name="Make" enabled="true" /></method>
  </configuration>
</component>
'@
foreach ($module in $applications.Keys) {
    $outputPath = Join-Path $outputDirectory "Bit_Select_$($module.Replace('-','_')).xml"
    if (Test-Path -LiteralPath $outputPath) { Write-Host "$module 已有本地运行配置，保留。"; continue }
    $content = $template.Replace('{MODULE}', $module).Replace('{MAIN}', $applications[$module])
    [IO.File]::WriteAllText($outputPath, $content, [Text.UTF8Encoding]::new($false))
}
Write-Host '已生成本地 IDEA 运行配置，不含密钥。请将项目 SDK 设为 21 并配置本地环境变量。'
