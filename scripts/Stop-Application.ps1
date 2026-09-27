[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$registry = Join-Path $repositoryRoot '.local/application-processes.json'
if (-not (Test-Path -LiteralPath $registry)) { Write-Host '没有本项目启动脚本登记的进程。'; return }
$entries = @(Get-Content -LiteralPath $registry -Raw | ConvertFrom-Json)
foreach ($entry in $entries) {
    if ($entry.root -ne $repositoryRoot) { Write-Warning '登记记录属于其他工作目录，已跳过。'; continue }
    $process = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
    $detail = Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)" -ErrorAction SilentlyContinue
    if (-not $process -or -not $detail) { continue }
    if ($process.StartTime.ToUniversalTime().Ticks -ne ([datetime]$entry.startedUtc).ToUniversalTime().Ticks -or
        $detail.ExecutablePath -ne $entry.executable -or
        -not $detail.CommandLine -or
        $detail.CommandLine.IndexOf($entry.marker, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        Write-Warning "$($entry.service) 的 PID 身份不匹配，未停止该进程。"
        continue
    }
    # Next.js may fork a server child. Stop only descendants whose executable and
    # command line both resolve to the registered executable and this repository.
    $queue = [Collections.Generic.Queue[int]]::new()
    $queue.Enqueue([int]$entry.pid)
    $descendants = @()
    while ($queue.Count -gt 0) {
        $parentId = $queue.Dequeue()
        foreach ($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$parentId")) {
            if ($child.ExecutablePath -eq $entry.executable -and $child.CommandLine -and $child.CommandLine.IndexOf($repositoryRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                $descendants += $child
                $queue.Enqueue([int]$child.ProcessId)
            }
        }
    }
    [array]::Reverse($descendants)
    foreach ($child in $descendants) {
        $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($child.ProcessId)" -ErrorAction SilentlyContinue
        if ($current -and $current.CreationDate -eq $child.CreationDate -and $current.CommandLine -eq $child.CommandLine) { Stop-Process -Id $child.ProcessId -ErrorAction SilentlyContinue }
    }
    Stop-Process -Id $entry.pid -ErrorAction SilentlyContinue
    Write-Host "已停止 $($entry.service)。"
}
# Retain registry and log files as local audit evidence. Subsequent starts replace stale entries.
