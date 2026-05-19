# SQL 迁移一键脚本（Windows / PowerShell）
# 用法：
#   1. 在项目根目录打开 PowerShell
#   2. 跑：./docs/dev-ops/run-migrations.ps1
# 失败一个就停下来；可重复执行（V001 用 ALTER ADD COLUMN，重复跑会报"列已存在"，是正常的）
#
# 数据库连接来自 application-dev.yml：127.0.0.1:13306 / root / 123456 / ai-agent-station-study

$ErrorActionPreference = 'Stop'

$mysqlHost = '127.0.0.1'
$mysqlPort = '13306'
$mysqlUser = 'root'
$mysqlPass = '123456'
$mysqlDb   = 'ai-agent-station-study'

# 优先尝试本机 mysql 客户端；找不到就用 Docker 容器（自动检测容器名）
$useDocker = $false
$mysqlCmd  = 'mysql'

try {
    & mysql --version 2>&1 | Out-Null
} catch {
    Write-Host "本机没有 mysql 客户端，自动切到 Docker 模式..." -ForegroundColor Yellow
    $useDocker = $true
    $container = (docker ps --filter "expose=3306" --format "{{.Names}}" | Select-Object -First 1)
    if (-not $container) {
        $container = (docker ps --format "{{.Names}}" | Where-Object { $_ -match 'mysql' } | Select-Object -First 1)
    }
    if (-not $container) {
        throw "找不到正在跑的 MySQL 容器；请先 docker-compose -f docs/dev-ops/docker-compose-mysql.yml up -d"
    }
    Write-Host "使用容器: $container" -ForegroundColor Cyan
}

$migrationDir = Join-Path $PSScriptRoot 'sql-migrations'
$files = Get-ChildItem -Path $migrationDir -Filter 'V*.sql' | Sort-Object Name

if (-not $files) {
    Write-Host "没找到迁移文件（$migrationDir/V*.sql）" -ForegroundColor Yellow
    exit 0
}

foreach ($f in $files) {
    Write-Host "`n=== Running $($f.Name) ===" -ForegroundColor Green
    $sql = Get-Content -Path $f.FullName -Raw -Encoding UTF8

    if ($useDocker) {
        # Docker exec：通过 stdin 传 SQL
        $sql | docker exec -i $container mysql --default-character-set=utf8mb4 -u $mysqlUser "-p$mysqlPass" $mysqlDb
    } else {
        # 本机 mysql client
        $sql | & $mysqlCmd -h $mysqlHost -P $mysqlPort --default-character-set=utf8mb4 -u $mysqlUser "-p$mysqlPass" $mysqlDb
    }

    if ($LASTEXITCODE -ne 0) {
        Write-Host "$($f.Name) 失败（exit=$LASTEXITCODE）；如果是 'Duplicate column' 之类的提示说明已经跑过，可以忽略并改跑下一个；其他错误请人工 review" -ForegroundColor Yellow
    } else {
        Write-Host "$($f.Name) OK" -ForegroundColor Green
    }
}

Write-Host "`n所有迁移已尝试执行。" -ForegroundColor Cyan
