# Dot-source this script: . ./scripts/Use-LocalEnvironment.ps1
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path $PSScriptRoot -Parent
foreach ($relativePath in @('infra/.env', '.env.local')) {
    $configPath = Join-Path $repositoryRoot $relativePath
    if (-not (Test-Path -LiteralPath $configPath)) { continue }
    foreach ($line in Get-Content -LiteralPath $configPath) {
        if ($line -match '^\s*([A-Z][A-Z0-9_]*)=(.*)$') {
            $variableName = $Matches[1]
            $variableValue = $Matches[2].Trim().Trim('"').Trim("'")
            [Environment]::SetEnvironmentVariable($variableName, $variableValue, 'Process')
        }
    }
}
Write-Host '已载入本地环境变量；未显示凭证。此设置仅影响当前进程及其子进程。'

