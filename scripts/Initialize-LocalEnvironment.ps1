[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$destination = Join-Path $repositoryRoot 'infra/.env'
if (Test-Path -LiteralPath $destination) {
    Write-Host '本地中间件配置已存在，保留已有凭证。'
    return
}
$template = Get-Content -LiteralPath (Join-Path $repositoryRoot 'infra/.env.example') -Raw
foreach ($variable in @('MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'REDIS_PASSWORD', 'SENTINEL_PASSWORD', 'RUSTFS_SECRET_KEY', 'NEO4J_PASSWORD', 'ADMIN_PASSWORD')) {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $value = [Convert]::ToHexString($bytes).ToLowerInvariant()
    $template = [regex]::Replace($template, "(?m)^$variable=.*$", "$variable=$value")
}
[IO.File]::WriteAllText($destination, $template, [Text.UTF8Encoding]::new($false))
Write-Host '已生成 infra/.env；凭证只保存在本地且不会打印。'
