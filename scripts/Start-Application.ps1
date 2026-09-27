[CmdletBinding()]
param(
    [switch]$Build,
    [switch]$Frontend,
    [switch]$SkipAi,
    [ValidateSet('dev','production')][string]$FrontendMode = 'dev',
    [string]$JavaHome,
    [string]$MavenCommand = 'mvn.cmd'
)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Use-LocalEnvironment.ps1')
$env:CATALOG_SEED_PATH = Join-Path $repositoryRoot 'data/products.json'
if (-not $env:AI_RANKING_MODEL) { $env:AI_RANKING_MODEL = Join-Path $repositoryRoot 'data/ranking/model.json' }
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
$env:JAVA_HOME = $JavaHome
if ($Build) {
    & $MavenCommand -B -ntp -f (Join-Path $repositoryRoot 'backend/pom.xml') verify
    if ($LASTEXITCODE -ne 0) { throw '后端构建或测试失败，未启动应用。' }
}
$localDirectory = Join-Path $repositoryRoot '.local'
$logDirectory = Join-Path $localDirectory 'logs'
$runtimeDirectory = Join-Path $localDirectory 'runtime'
New-Item -ItemType Directory -Force $logDirectory, $runtimeDirectory | Out-Null
$registry = Join-Path $localDirectory 'application-processes.json'
$entries = @()
if (Test-Path -LiteralPath $registry) { $entries = @(Get-Content -LiteralPath $registry -Raw | ConvertFrom-Json) }

function Test-RegisteredProcess($Entry) {
    $process = Get-Process -Id $Entry.pid -ErrorAction SilentlyContinue
    $detail = Get-CimInstance Win32_Process -Filter "ProcessId=$($Entry.pid)" -ErrorAction SilentlyContinue
    return $null -ne $process -and $null -ne $detail -and
        $process.StartTime.ToUniversalTime().Ticks -eq ([datetime]$Entry.startedUtc).ToUniversalTime().Ticks -and
        $detail.ExecutablePath -eq $Entry.executable -and
        $detail.CommandLine -and
        $detail.CommandLine.IndexOf($Entry.marker, [StringComparison]::OrdinalIgnoreCase) -ge 0
}

function Test-ListeningPort([int]$Port) {
    $socket = [Net.Sockets.TcpClient]::new()
    try { $connection = $socket.ConnectAsync('127.0.0.1', $Port); return $connection.Wait(300) -and $socket.Connected }
    catch { return $false } finally { $socket.Dispose() }
}

$services = @(@{name='catalog-service';port=8082;heap='256m'}, @{name='commerce-service';port=8081;heap='256m'})
if (-not $SkipAi) { $services += @{name='ai-service';port=8083;heap='512m'} }
$services += @{name='gateway';port=8080;heap='256m'}
foreach ($service in $services) {
    $existing = $entries | Where-Object service -EQ $service.name | Select-Object -Last 1
    if ($existing -and (Test-RegisteredProcess $existing)) { Write-Host "$($service.name) 已运行，保留该进程。"; continue }
    if (Test-ListeningPort $service.port) { throw "端口 $($service.port) 已被其他进程占用；本脚本不会停止该进程。" }
    $target = Join-Path $repositoryRoot "backend/$($service.name)/target"
    $jar = Get-ChildItem -LiteralPath $target -Filter '*.jar' | Where-Object Name -NotMatch '(sources|javadoc|original)' | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if (-not $jar) { throw "缺少 $($service.name) 的运行包，请先执行 -Build。" }
    $serviceRuntime = Join-Path $runtimeDirectory $service.name
    New-Item -ItemType Directory -Force $serviceRuntime | Out-Null
    $runtimeJar = Join-Path $serviceRuntime ("{0}-{1}.jar" -f $service.name, [DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff'))
    Copy-Item -LiteralPath $jar.FullName -Destination $runtimeJar
    $process = Start-Process -FilePath $java -ArgumentList @('-Xms64m', "-Xmx$($service.heap)", '-XX:ActiveProcessorCount=4', '-jar', ('"' + $runtimeJar + '"')) -WorkingDirectory $repositoryRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDirectory "$($service.name).out.log") -RedirectStandardError (Join-Path $logDirectory "$($service.name).err.log") -PassThru
    $entries = @($entries | Where-Object service -NE $service.name) + [pscustomobject]@{service=$service.name;pid=$process.Id;startedUtc=$process.StartTime.ToUniversalTime().ToString('o');executable=$java;marker=$runtimeJar;root=$repositoryRoot}
    ConvertTo-Json -InputObject @($entries) -Depth 4 | Set-Content -LiteralPath $registry -Encoding utf8
    Write-Host "$($service.name) 已启动，端口 $($service.port)，日志位于 .local/logs。"
}
if ($Frontend) {
    $existing = $entries | Where-Object service -EQ 'web' | Select-Object -Last 1
    if ($existing -and (Test-RegisteredProcess $existing)) { Write-Host 'web 已运行，保留该进程。'; return }
    if (Test-ListeningPort 3000) { throw '端口 3000 已被其他进程占用；本脚本不会停止该进程。' }
    $node = (Get-Command node.exe -ErrorAction Stop).Source
    $webRoot = Join-Path $repositoryRoot 'web'
    $next = Join-Path $webRoot 'node_modules/next/dist/bin/next'
    if (-not (Test-Path -LiteralPath $next)) { throw '前端依赖未安装，请先在 web 目录执行 npm ci。' }
    if ($FrontendMode -eq 'production' -and -not (Test-Path -LiteralPath (Join-Path $webRoot '.next/BUILD_ID'))) { throw '生产前端尚未构建，请先在 web 目录执行 npm run build。' }
    $command = if ($FrontendMode -eq 'dev') { 'dev' } else { 'start' }
    $process = Start-Process -FilePath $node -ArgumentList @(('"' + $next + '"'),$command,'--hostname','127.0.0.1','--port','3000') -WorkingDirectory $webRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDirectory 'web.out.log') -RedirectStandardError (Join-Path $logDirectory 'web.err.log') -PassThru
    $entries = @($entries | Where-Object service -NE 'web') + [pscustomobject]@{service='web';pid=$process.Id;startedUtc=$process.StartTime.ToUniversalTime().ToString('o');executable=$node;marker=$next;root=$repositoryRoot}
    ConvertTo-Json -InputObject @($entries) -Depth 4 | Set-Content -LiteralPath $registry -Encoding utf8
    Write-Host '前端已启动：http://localhost:3000。'
}
Write-Host '应用已创建进程；首次启动和迁移需要数秒。可通过 smoke-test.py 检查真实业务就绪。'
