#!/bin/bash

# Prometheus指标模拟生成脚本
# 用途：生成模拟的Prometheus格式指标数据，供Node Exporter的textfile收集器采集
# 作者：AI Agent
# 使用方法：./generate-metrics.sh

# 指标文件路径
METRICS_FILE="/tmp/custom_metrics.prom"

# 生成随机数的函数（兼容macOS）
generate_random() {
    local min=$1
    local max=$2
    echo $(( RANDOM % (max - min + 1) + min ))
}

# 生成随机小数的函数
generate_random_decimal() {
    local min=$1
    local max=$2
    local decimal_places=${3:-2}
    # 将小数转换为整数进行计算
    local min_int=$(echo "$min * 100" | bc | cut -d. -f1)
    local max_int=$(echo "$max * 100" | bc | cut -d. -f1)
    local random_int=$(generate_random $min_int $max_int)
    echo "scale=$decimal_places; $random_int/100" | bc
}

# 检查必要的命令是否存在
check_dependencies() {
    local missing_deps=()
    
    command -v bc >/dev/null 2>&1 || missing_deps+=("bc")
    
    if [ ${#missing_deps[@]} -ne 0 ]; then
        echo "❌ 缺少必要的命令: ${missing_deps[*]}"
        echo "请安装缺少的命令后重试"
        exit 1
    fi
    
    echo "✅ 依赖检查通过"
}

# 初始化检查
echo "=== Prometheus指标模拟生成器 ==="
echo "开始时间: $(date)"
echo "指标文件路径: $METRICS_FILE"
check_dependencies

# 生成模拟指标
generate_metrics() {
    cat > $METRICS_FILE << EOF
# HELP http_server_requests_seconds HTTP请求响应时间
# TYPE http_server_requests_seconds histogram
http_server_requests_seconds_bucket{exception="None",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/lock_market_pay_order",le="0.001"} $(generate_random 50 100)
http_server_requests_seconds_bucket{exception="None",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/lock_market_pay_order",le="0.01"} $(generate_random 100 300)
http_server_requests_seconds_bucket{exception="None",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/lock_market_pay_order",le="0.1"} $(generate_random 300 800)
http_server_requests_seconds_bucket{exception="None",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/lock_market_pay_order",le="1.0"} $(generate_random 800 1200)
http_server_requests_seconds_bucket{exception="None",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/lock_market_pay_order",le="+Inf"} $(generate_random 1200 1500)
http_server_requests_seconds_sum{exception="None",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/lock_market_pay_order"} $(generate_random_decimal 5 50)
http_server_requests_seconds_count{exception="None",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/lock_market_pay_order"} $(generate_random 1000 5000)

http_server_requests_seconds_bucket{exception="None",method="GET",outcome="SUCCESS",status="200",uri="/api/v1/group_buy/progress",le="0.001"} $(generate_random 100 200)
http_server_requests_seconds_bucket{exception="None",method="GET",outcome="SUCCESS",status="200",uri="/api/v1/group_buy/progress",le="0.01"} $(generate_random 200 500)
http_server_requests_seconds_bucket{exception="None",method="GET",outcome="SUCCESS",status="200",uri="/api/v1/group_buy/progress",le="0.1"} $(generate_random 500 1000)
http_server_requests_seconds_bucket{exception="None",method="GET",outcome="SUCCESS",status="200",uri="/api/v1/group_buy/progress",le="1.0"} $(generate_random 1000 1500)
http_server_requests_seconds_bucket{exception="None",method="GET",outcome="SUCCESS",status="200",uri="/api/v1/group_buy/progress",le="+Inf"} $(generate_random 1500 2000)
http_server_requests_seconds_sum{exception="None",method="GET",outcome="SUCCESS",status="200",uri="/api/v1/group_buy/progress"} $(generate_random_decimal 2 20)
http_server_requests_seconds_count{exception="None",method="GET",outcome="SUCCESS",status="200",uri="/api/v1/group_buy/progress"} $(generate_random 2000 8000)

http_server_requests_seconds_bucket{exception="None",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/team/create",le="0.001"} $(generate_random 30 80)
http_server_requests_seconds_bucket{exception="None",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/team/create",le="0.01"} $(generate_random 80 200)
http_server_requests_seconds_bucket{exception="None",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/team/create",le="0.1"} $(generate_random 200 500)
http_server_requests_seconds_bucket{exception="None",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/team/create",le="1.0"} $(generate_random 500 800)
http_server_requests_seconds_bucket{exception="None",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/team/create",le="+Inf"} $(generate_random 800 1000)
http_server_requests_seconds_sum{exception="None",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/team/create"} $(generate_random_decimal 3 30)
http_server_requests_seconds_count{exception="None",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/team/create"} $(generate_random 500 2000)

# HELP http_server_requests_seconds_max HTTP请求最大响应时间
# TYPE http_server_requests_seconds_max gauge
http_server_requests_seconds_max{exception="None",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/lock_market_pay_order"} $(generate_random_decimal 0.1 2.5)
http_server_requests_seconds_max{exception="None",method="GET",outcome="SUCCESS",status="200",uri="/api/v1/group_buy/progress"} $(generate_random_decimal 0.05 1.8)
http_server_requests_seconds_max{exception="None",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/team/create"} $(generate_random_decimal 0.08 2.0)

# HELP jvm_memory_used_bytes JVM内存使用量
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="PS Eden Space"} $(generate_random 100000000 500000000)
jvm_memory_used_bytes{area="heap",id="PS Old Gen"} $(generate_random 200000000 800000000)
jvm_memory_used_bytes{area="heap",id="PS Survivor Space"} $(generate_random 10000000 50000000)
jvm_memory_used_bytes{area="nonheap",id="Metaspace"} $(generate_random 50000000 150000000)
jvm_memory_used_bytes{area="nonheap",id="Code Cache"} $(generate_random 20000000 80000000)

# HELP jvm_memory_max_bytes JVM内存最大值
# TYPE jvm_memory_max_bytes gauge
jvm_memory_max_bytes{area="heap",id="PS Eden Space"} 715653120
jvm_memory_max_bytes{area="heap",id="PS Old Gen"} 1431655765
jvm_memory_max_bytes{area="heap",id="PS Survivor Space"} 71565312
jvm_memory_max_bytes{area="nonheap",id="Metaspace"} -1
jvm_memory_max_bytes{area="nonheap",id="Code Cache"} 251658240

# HELP jvm_gc_pause_seconds GC暂停时间
# TYPE jvm_gc_pause_seconds histogram
jvm_gc_pause_seconds_bucket{action="end of minor GC",cause="Allocation Failure",le="0.001"} $(generate_random 10 50)
jvm_gc_pause_seconds_bucket{action="end of minor GC",cause="Allocation Failure",le="0.01"} $(generate_random 50 150)
jvm_gc_pause_seconds_bucket{action="end of minor GC",cause="Allocation Failure",le="0.1"} $(generate_random 150 300)
jvm_gc_pause_seconds_bucket{action="end of minor GC",cause="Allocation Failure",le="+Inf"} $(generate_random 300 400)
jvm_gc_pause_seconds_sum{action="end of minor GC",cause="Allocation Failure"} $(generate_random_decimal 0.5 5.0)
jvm_gc_pause_seconds_count{action="end of minor GC",cause="Allocation Failure"} $(generate_random 300 400)

# HELP system_cpu_usage 系统CPU使用率
# TYPE system_cpu_usage gauge
system_cpu_usage $(generate_random_decimal 0.1 0.8)

# HELP process_cpu_usage 进程CPU使用率
# TYPE process_cpu_usage gauge
process_cpu_usage $(generate_random_decimal 0.05 0.6)

# HELP jvm_threads_live JVM活跃线程数
# TYPE jvm_threads_live gauge
jvm_threads_live $(generate_random 20 80)

# HELP jvm_threads_peak JVM峰值线程数
# TYPE jvm_threads_peak gauge
jvm_threads_peak $(generate_random 80 150)

# HELP group_buy_active_teams 活跃拼团数量
# TYPE group_buy_active_teams gauge
group_buy_active_teams $(generate_random 10 100)

# HELP group_buy_completed_teams 已完成拼团数量
# TYPE group_buy_completed_teams counter
group_buy_completed_teams $(generate_random 500 2000)

# HELP market_pay_orders_total 营销支付订单总数
# TYPE market_pay_orders_total counter
market_pay_orders_total{status="CREATE"} $(generate_random 100 500)
market_pay_orders_total{status="PAID"} $(generate_random 800 3000)
market_pay_orders_total{status="CANCEL"} $(generate_random 50 200)

# HELP group_buy_participants 拼团参与人数
# TYPE group_buy_participants gauge
group_buy_participants $(generate_random 50 500)

# HELP database_connections_active 数据库活跃连接数
# TYPE database_connections_active gauge
database_connections_active{pool="HikariPool-1"} $(generate_random 5 20)

# HELP database_connections_max 数据库最大连接数
# TYPE database_connections_max gauge
database_connections_max{pool="HikariPool-1"} 20

# HELP redis_connections_active Redis活跃连接数
# TYPE redis_connections_active gauge
redis_connections_active $(generate_random 2 10)

# HELP application_ready_time 应用启动时间
# TYPE application_ready_time gauge
application_ready_time{main_application_class="cn.tagent.dev.tech.Application"} $(generate_random_decimal 8.0 15.0)
EOF

    # 设置文件权限，确保Node Exporter可以读取
    chmod 644 $METRICS_FILE
    
    # 验证文件是否生成成功
    if [ -f "$METRICS_FILE" ]; then
        echo "$(date): ✅ Generated metrics to $METRICS_FILE with proper permissions"
        echo "$(date): File size: $(ls -lh $METRICS_FILE | awk '{print $5}')"
    else
        echo "$(date): ❌ Failed to create metrics file"
        return 1
    fi
}

# 信号处理函数
cleanup() {
    echo ""
    echo "$(date): 🛑 接收到停止信号，正在清理..."
    if [ -f "$METRICS_FILE" ]; then
        echo "$(date): 🗑️  删除指标文件: $METRICS_FILE"
        rm -f "$METRICS_FILE"
    fi
    echo "$(date): ✅ 清理完成，脚本已停止"
    exit 0
}

# 注册信号处理
trap cleanup SIGINT SIGTERM

echo ""
echo "🚀 开始生成模拟指标数据..."
echo "💡 按 Ctrl+C 停止脚本"
echo "📊 指标更新间隔: 15秒"
echo ""

# 持续生成指标
while true; do
    if generate_metrics; then
        echo "$(date): 📈 指标数据更新成功"
    else
        echo "$(date): ⚠️  指标数据更新失败"
    fi
    sleep 15  # 每15秒更新一次
done