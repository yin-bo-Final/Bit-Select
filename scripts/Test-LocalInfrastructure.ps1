[CmdletBinding()]
param([ValidateSet('core','ai','full')][string]$Profile = 'core', [string]$Distribution = 'Ubuntu')
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$linuxRoot = (& wsl -d $Distribution -- wslpath -a $repositoryRoot).Trim()
$compose = @('-d', $Distribution, '--', 'docker', 'compose', '--env-file', "$linuxRoot/infra/.env", '-f', "$linuxRoot/infra/compose.yml")
if ($Profile -ne 'core') { $compose += @('--profile', $Profile) }
& wsl @compose ps
if ($LASTEXITCODE -ne 0) { throw '无法读取容器状态。' }
$ports = @(13306,16379,18848,19848,19876,20911)
if ($Profile -ne 'core') { $ports += @(19000,19001,19530,17474,17687,19998) }
if ($Profile -eq 'full') { $ports += 18880 }
$failed = @()
foreach ($port in $ports) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync('127.0.0.1', $port)
        if (-not $task.Wait(3000) -or -not $client.Connected) { throw 'Connection timed out' }
        Write-Host "Windows localhost:$port 可以访问"
    } catch { $failed += $port } finally { $client.Dispose() }
}
if ($failed.Count -gt 0) { throw "Windows 无法连接端口：$($failed -join ', ')。检查 WSL localhost 转发。" }

