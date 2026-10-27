# Python 沙箱部署与验证

应用通过内部 HTTP 调用 runner，runner 使用 Docker 创建独立执行容器。`run_python` 可读取授权附件并生成产物；产物页也可运行代码并下载输出。同一运行共享工作目录，不同运行隔离。

## 启用

使用 Linux Docker Engine（需支持 volume-subpath，建议 26+）。执行镜像必须在 runner 所连接的同一个 Docker daemon 上。下面命令在仓库的 `docs/dev-ops/server` 目录执行：

1. 在私有 `.env` 中设置随机 `SANDBOX_TOKEN`（至少 24 字符）和 `TAGENT_SANDBOX_ENABLED=true`。可用 `openssl rand -hex 32` 生成令牌；妥善保存，不提交 Git。Compose 将同一令牌传给应用和 runner。
2. 构建执行镜像和 runner：

   ```bash
   docker compose --profile build-only build sandbox-python sandbox-runner
   ```

3. 确认已完成 [V071–V075](../docs/dev-ops/V2_UPGRADE.md#v220-升级) 并构建新版应用后启动：

   ```bash
   docker compose up -d --no-deps sandbox-runner app
   ```

4. 用普通账户生成并执行一段打印 `6 * 7` 的 Python，确认返回 `42`，再生成一个 `/output/check.txt` 并下载核对内容。查看 runner 日志及 Prometheus `up{job="sandbox-runner"}`。

应用配置为 `agent.sandbox.*`，默认关闭；容器内 runner 地址为 `http://sandbox-runner:8000`。不要把 8000 端口发布到公网。runner 持有 Docker socket，仅允许受控应用访问；执行容器不挂载该 socket。

## 执行限制与存储

- 执行容器以非 root 身份运行，关闭网络，根文件系统只读，默认 512 MB 内存；应用默认执行 60 秒，runner 最长接受 120 秒。
- 默认 3 个 worker，队列等待 60 秒，按用户轮流调度。运行目录默认上限 512 MB，宿主磁盘默认至少保留 512 MB；每 100 ms 检查并在结束后复查，超限返回 `DISK_LIMIT`，清理本运行临时目录。轮询存在短时超出窗口，不等同文件系统硬配额。
- `/input` 提供授权输入，`/workspace` 存运行临时文件，`/output` 收集输出；不允许以路径逃逸访问其他运行。临时结果默认保留 15 分钟、运行目录默认 2 小时；应用已保存的产物按应用存储管理。
- 默认最多 20 个输出文件、单个 10 MB、合计 20 MB；日志最多 20,000 字符。配置见 `runner/settings.py`，不要把临时卷当作永久文件存储。
- Prometheus 抓取 runner `/metrics`；Grafana 规则包含不可达、排队、繁忙、执行中止、磁盘限制。通知接收渠道需由部署者配置。

## 本地测试

```bash
PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python -m pytest sandbox-runner/tests/test_disk_limits.py sandbox-runner/tests/test_scheduler.py -q
```

`tests/test_security_live.py` 需要显式配置独立测试 runner；磁盘上限测试使用小容量测试实例。不要在生产 runner 上做资源耗尽测试。
