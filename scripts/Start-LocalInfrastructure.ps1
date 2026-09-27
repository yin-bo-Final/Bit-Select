[CmdletBinding()]
param(
    [ValidateSet('core', 'ai', 'full')][string]$Profile = 'core',
    [string]$Distribution = 'Ubuntu',
    [switch]$SkipWait
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Initialize-LocalEnvironment.ps1')
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$localDirectory = Join-Path $repositoryRoot '.local'
New-Item -ItemType Directory -Force $localDirectory | Out-Null
$keeperPath = Join-Path $localDirectory 'wsl-keeper.pid'
$keeperRunning = $false
if (Test-Path -LiteralPath $keeperPath) {
    $keeperId = [int](Get-Content -LiteralPath $keeperPath -Raw).Trim()
    $keeperProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$keeperId" -ErrorAction SilentlyContinue
    $keeperRunning = $null -ne $keeperProcess -and $keeperProcess.Name -eq 'wsl.exe' -and $keeperProcess.CommandLine -match '-- sleep infinity'
}
if (-not $keeperRunning) {
    $keeperProcess = Start-Process -FilePath wsl.exe -ArgumentList @('-d', $Distribution, '--', 'sleep', 'infinity') -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath $keeperPath -Value $keeperProcess.Id
}
$linuxRoot = (& wsl -d $Distribution -- wslpath -a $repositoryRoot).Trim()
if ($LASTEXITCODE -ne 0) { throw '无法访问 WSL，请先启动指定发行版。' }
$compose = @('-d', $Distribution, '--', 'docker', 'compose', '--project-directory', "$linuxRoot/infra", '--env-file', "$linuxRoot/infra/.env", '-f', "$linuxRoot/infra/compose.yml")
if ($Profile -ne 'core') { $compose += @('--profile', $Profile) }
$compose += @('up', '-d', '--build')
if (-not $SkipWait) { $compose += @('--wait', '--wait-timeout', '300') }
& wsl @compose
if ($LASTEXITCODE -ne 0) { throw '中间件启动失败，请检查对应容器日志；未删除任何卷。' }
Write-Host "比特严选 $Profile 中间件已启动。"
