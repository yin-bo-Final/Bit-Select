[CmdletBinding()]
param([string]$Distribution = 'Ubuntu')
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$linuxRoot = (& wsl -d $Distribution -- wslpath -a $repositoryRoot).Trim()
& wsl -d $Distribution -- docker compose --env-file "$linuxRoot/infra/.env" -f "$linuxRoot/infra/compose.yml" --profile full stop
if ($LASTEXITCODE -ne 0) { throw '停止容器失败。' }
$keeperPath = Join-Path $repositoryRoot '.local/wsl-keeper.pid'
if (Test-Path -LiteralPath $keeperPath) {
    $keeperId = [int](Get-Content -LiteralPath $keeperPath -Raw).Trim()
    $keeperProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$keeperId" -ErrorAction SilentlyContinue
    if ($null -ne $keeperProcess -and $keeperProcess.Name -eq 'wsl.exe' -and $keeperProcess.CommandLine -match '-- sleep infinity') {
        Stop-Process -Id $keeperId
    }
    Remove-Item -LiteralPath $keeperPath
}
Write-Host '仅停止本项目容器，所有数据卷均已保留。'
