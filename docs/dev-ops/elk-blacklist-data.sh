#!/bin/bash

# 模拟向Elasticsearch写入拼团项目黑名单限流数据的脚本
# 使用方法: ./elk-blacklist-data.sh

# Elasticsearch配置
ES_HOST="localhost:9200"
INDEX_NAME="group-buy-market-log-$(date +%Y.%m.%d)"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}开始向Elasticsearch模拟写入拼团项目黑名单限流数据...${NC}"
echo -e "${YELLOW}目标索引: ${INDEX_NAME}${NC}"
echo -e "${YELLOW}ES地址: ${ES_HOST}${NC}"

# 检查Elasticsearch连接
echo "检查Elasticsearch连接..."
if ! curl -s "http://${ES_HOST}/_cluster/health" > /dev/null; then
    echo -e "${RED}错误: 无法连接到Elasticsearch (${ES_HOST})${NC}"
    echo "请确保Elasticsearch服务正在运行"
    exit 1
fi
echo -e "${GREEN}Elasticsearch连接正常${NC}"

# 模拟用户数据
USERS=("user001" "user002" "user003" "user004" "user005" "user006" "user007" "user008" "user009" "user010")
IPS=("192.168.1.100" "192.168.1.101" "192.168.1.102" "10.0.0.50" "10.0.0.51" "172.16.0.100" "172.16.0.101" "203.0.113.10" "203.0.113.11" "198.51.100.20")
USER_AGENTS=("Mozilla/5.0 (iPhone; CPU iPhone OS 14_0 like Mac OS X)" "Mozilla/5.0 (Android 10; Mobile)" "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")
GROUP_BUY_PRODUCTS=("product_001" "product_002" "product_003" "product_004" "product_005")
LIMIT_REASONS=("访问频率过高" "恶意刷单" "异常IP访问" "超过每日限制" "黑名单用户")
LIMIT_TYPES=("rate_limit" "frequency_limit" "ip_blacklist" "daily_limit" "user_blacklist")

# 生成随机时间戳（最近24小时内）
generate_timestamp() {
    local now=$(date +%s)
    local random_offset=$((RANDOM % 86400))  # 24小时内随机
    local timestamp=$((now - random_offset))
    date -r $timestamp -Iseconds
}

# 生成随机日志级别
generate_log_level() {
    local levels=("ERROR" "WARN" "INFO")
    echo "${levels[$((RANDOM % ${#levels[@]}))]}"
}

# 生成模拟限流日志数据
generate_log_data() {
    local user_id="${USERS[$((RANDOM % ${#USERS[@]}))]}"
    local ip="${IPS[$((RANDOM % ${#IPS[@]}))]}"
    local user_agent="${USER_AGENTS[$((RANDOM % ${#USER_AGENTS[@]}))]}"
    local product="${GROUP_BUY_PRODUCTS[$((RANDOM % ${#GROUP_BUY_PRODUCTS[@]}))]}"
    local limit_reason="${LIMIT_REASONS[$((RANDOM % ${#LIMIT_REASONS[@]}))]}"
    local limit_type="${LIMIT_TYPES[$((RANDOM % ${#LIMIT_TYPES[@]}))]}"
    local timestamp=$(generate_timestamp)
    local log_level=$(generate_log_level)
    local request_count=$((RANDOM % 100 + 50))  # 50-149次请求
    local limit_threshold=$((RANDOM % 50 + 20))  # 20-69的限制阈值
    
    cat << EOF
{
  "@timestamp": "${timestamp}",
  "level": "${log_level}",
  "logger": "com.tagent.security.RateLimitFilter",
  "thread": "http-nio-8080-exec-$((RANDOM % 10 + 1))",
  "message": "用户访问拼团项目被限流 - 用户ID: ${user_id}, 产品: ${product}, 原因: ${limit_reason}, IP: ${ip}, 请求次数: ${request_count}, 限制阈值: ${limit_threshold}",
  "application": "group-buy-market",
  "environment": "production",
  "service": "group-buy-service",
  "user_id": "${user_id}",
  "ip_address": "${ip}",
  "user_agent": "${user_agent}",
  "product_id": "${product}",
  "limit_type": "${limit_type}",
  "limit_reason": "${limit_reason}",
  "request_count": ${request_count},
  "limit_threshold": ${limit_threshold},
  "action": "blocked",
  "endpoint": "/api/group-buy/join",
  "method": "POST",
  "status_code": 429,
  "response_time": $((RANDOM % 100 + 10)),
  "session_id": "session_$(date +%s)_${RANDOM}",
  "trace_id": "trace_$(date +%s)_${RANDOM}",
  "tags": ["限流", "黑名单", "拼团", "安全"]
}
EOF
}

# 批量写入数据
echo "开始生成并写入模拟数据..."
for i in {1..50}; do
    echo -n "写入第 $i 条数据... "
    
    # 生成日志数据
    log_data=$(generate_log_data)
    
    # 写入到Elasticsearch
    response=$(curl -s -X POST "http://${ES_HOST}/${INDEX_NAME}/_doc" \
        -H "Content-Type: application/json" \
        -d "$log_data")
    
    # 检查写入结果
    if echo "$response" | grep -q '"result":"created"'; then
        echo -e "${GREEN}成功${NC}"
    else
        echo -e "${RED}失败${NC}"
        echo "响应: $response"
    fi
    
    # 随机延迟，模拟真实场景
    sleep 0.$((RANDOM % 5 + 1))
done

echo -e "${GREEN}数据写入完成！${NC}"
echo "查看索引信息:"
curl -s "http://${ES_HOST}/${INDEX_NAME}/_count" | jq .

echo -e "${YELLOW}可以使用以下命令查看写入的数据:${NC}"
echo "curl -X GET \"http://${ES_HOST}/${INDEX_NAME}/_search?pretty&size=5\""
echo -e "${YELLOW}或者在Kibana中查看索引: ${INDEX_NAME}${NC}"