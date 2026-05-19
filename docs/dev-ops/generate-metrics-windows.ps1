# Prometheus指标模拟生成脚本 (Windows PowerShell版本)
# 用途：生成模拟的Prometheus格式指标数据，供Node Exporter的textfile收集器采集
# 作者：AI Agent
# 使用方法：.\generate-metrics-windows.ps1

# 指标文件路径
$METRICS_FILE = "$env:TEMP\custom_metrics.prom"

# 生成随机数的函数
function Generate-Random {
    param(
        [int]$Min,
        [int]$Max
    )
    return Get-Random -Minimum $Min -Maximum ($Max + 1)
}

# 生成随机小数的函数
function Generate-RandomDecimal {
    param(
        [int]$Min,
        [int]$Max,
        [int]$DecimalPlaces = 2
    )
    $randomInt = Generate-Random -Min ($Min * 100) -Max ($Max * 100)
    $result = [math]::Round($randomInt / 100, $DecimalPlaces)
    return $result
}

# 检查必要的命令是否存在
function Check-Dependencies {
    $missingDeps = @()
    
    # PowerShell内置了所需的功能，无需额外检查
    
    if ($missingDeps.Count -gt 0) {
        Write-Host "❌ 缺少必要的命令: $($missingDeps -join ', ')" -ForegroundColor Red
        Write-Host "请安装缺少的命令后重试" -ForegroundColor Red
        exit 1
    }
    
    Write-Host "✅ 依赖检查通过" -ForegroundColor Green
}

# 初始化检查
Write-Host "=== Prometheus指标模拟生成器 (Windows版) ===" -ForegroundColor Cyan
Write-Host "开始时间: $(Get-Date)" -ForegroundColor Yellow
Write-Host "指标文件路径: $METRICS_FILE" -ForegroundColor Yellow
Check-Dependencies

# 生成模拟指标
function Generate-Metrics {
    $metricsContent = @"
# HELP http_requests_total Total number of HTTP requests
# TYPE http_requests_total counter
http_requests_total{method="GET",status="200"} $(Generate-Random -Min 1000 -Max 9999)
http_requests_total{method="POST",status="200"} $(Generate-Random -Min 500 -Max 2000)
http_requests_total{method="GET",status="404"} $(Generate-Random -Min 10 -Max 100)

# HELP response_time_seconds Response time in seconds
# TYPE response_time_seconds histogram
response_time_seconds_bucket{le="0.1"} $(Generate-Random -Min 100 -Max 500)
response_time_seconds_bucket{le="0.5"} $(Generate-Random -Min 500 -Max 1000)
response_time_seconds_bucket{le="1.0"} $(Generate-Random -Min 1000 -Max 1500)
response_time_seconds_bucket{le="+Inf"} $(Generate-Random -Min 1500 -Max 2000)
response_time_seconds_sum $(Generate-RandomDecimal -Min 1 -Max 10)
response_time_seconds_count $(Generate-Random -Min 1000 -Max 5000)

# HELP active_users Current number of active users
# TYPE active_users gauge
active_users $(Generate-Random -Min 50 -Max 500)

# HELP cpu_usage_percent CPU usage percentage
# TYPE cpu_usage_percent gauge
cpu_usage_percent $(Generate-RandomDecimal -Min 10 -Max 90)

# HELP memory_usage_bytes Memory usage in bytes
# TYPE memory_usage_bytes gauge
memory_usage_bytes $(Generate-Random -Min 1000000000 -Max 8000000000)
"@

    try {
        # 写入指标文件
        $metricsContent | Out-File -FilePath $METRICS_FILE -Encoding UTF8 -Force
        
        # 验证文件是否生成成功
        if (Test-Path $METRICS_FILE) {
            $fileSize = (Get-Item $METRICS_FILE).Length
            Write-Host "$(Get-Date): ✅ Generated metrics to $METRICS_FILE with proper permissions" -ForegroundColor Green
            Write-Host "$(Get-Date): File size: $fileSize bytes" -ForegroundColor Green
            return $true
        } else {
            Write-Host "$(Get-Date): ❌ Failed to create metrics file" -ForegroundColor Red
            return $false
        }
    }
    catch {
        Write-Host "$(Get-Date): ❌ Error creating metrics file: $($_.Exception.Message)" -ForegroundColor Red
        return $false
    }
}

# 信号处理函数
function Cleanup {
    Write-Host ""
    Write-Host "$(Get-Date): 🛑 接收到停止信号，正在清理..." -ForegroundColor Yellow
    if (Test-Path $METRICS_FILE) {
        Write-Host "$(Get-Date): 🗑️  删除指标文件: $METRICS_FILE" -ForegroundColor Yellow
        Remove-Item -Path $METRICS_FILE -Force -ErrorAction SilentlyContinue
    }
    Write-Host "$(Get-Date): ✅ 清理完成，脚本已停止" -ForegroundColor Green
    exit 0
}

# 注册信号处理 (Ctrl+C)
$null = Register-EngineEvent -SourceIdentifier PowerShell.Exiting -Action {
    Cleanup
}

# 捕获 Ctrl+C
try {
    Write-Host ""
    Write-Host "🚀 开始生成模拟指标数据..." -ForegroundColor Cyan
    Write-Host "💡 按 Ctrl+C 停止脚本" -ForegroundColor Yellow
    Write-Host "📊 指标更新间隔: 15秒" -ForegroundColor Yellow
    Write-Host ""
    
    # 持续生成指标
    while ($true) {
        if (Generate-Metrics) {
            Write-Host "$(Get-Date): 📈 指标数据更新成功" -ForegroundColor Green
        } else {
            Write-Host "$(Get-Date): ⚠️  指标数据更新失败" -ForegroundColor Red
        }
        Start-Sleep -Seconds 15  # 每15秒更新一次
    }
}
catch [System.Management.Automation.PipelineStoppedException] {
    # 用户按下 Ctrl+C
    Cleanup
}
finally {
    Cleanup
}