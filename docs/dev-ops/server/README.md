# 单机部署

此目录独立于已有开发 Compose。包含应用、MySQL、Redis、PGVector、Elasticsearch、Logstash、Kibana、Prometheus、Grafana、Jaeger、Node Exporter、Grafana MCP、CSDN MCP、微信 MCP、点评 MCP 和 MarkItDown。CSDN 和微信 MCP 通过 `optional-publish` profile 按需启动；三个数据库管理工具通过 `maintenance` profile 按需启动，仅绑定服务器回环地址。

## 已完成的账户规则

- MySQL `admin_user.role` 是角色的权威来源：`ADMIN` 或 `USER`。V061 只提升原有 `admin`，不会重设其密码、创建默认管理员或把其他旧用户变成管理员。
- 首页支持注册，新注册用户固定为 USER；新密码使用 BCrypt，旧账户在成功登录时升级密码散列。
- 登录会话通过 Spring Session 存入 Redis，命名空间 `tagent:login`、闲置过期时间 8 小时。会话包含 `userId`、`username`、`role`、`isAdmin` 和 Spring Security 上下文。浏览器只保存 HttpOnly 会话 Cookie；localStorage 仅用于界面显示，不参与鉴权。
- 每次已登录请求重新核对 MySQL 中的状态与角色；禁用账户、角色改变会使旧会话失效。退出登录删除会话。POST 等写请求需要 CSRF token。
- 普通用户可聊天、使用自己的记忆/RAG、创建后台任务、使用 **EvalOps**，也可在“我的 Agent”自行搭建 Fixed / Auto / Flow Agent，按 MCP 连接绑定并在运行时按需加载工具。EvalOps 数据集及其版本、运行、结果、Judge 按所有者隔离。
- 新后台任务默认自动路由，不继承聊天页面选中的 Agent；任务卡片可独立指定 Agent。自动任务每次触发时，用实际行动指令进入统一路由。
- 已有任务保留原执行方式。在任务中心选择“自动”并保存，再确认启用，即可切换。无需数据库迁移。触发记录的实际 Agent/策略取自运行快照，快照过期后会显示暂无路由记录。
- 登录用户可通过 Observe 和 MCP 观测查看自己的数据；管理员可显式切换全站范围。网站统计、Agent 管理配置、管理 API、外部观测控制台只允许 ADMIN。隐藏入口和后端校验同时生效。
- V061 同时记录旧会话归属，后续创建会话时原子登记，防止猜测其他用户的 sessionId；复合会话 ID 和 runId 也要校验所有者。

## 容量边界

默认配置为各容器设置内存上限，并缩小 JVM 堆、数据库连接池与线程池。容器上限不等于常驻用量，也不代表宿主机的实际剩余容量。部署后应验证文档导入、并发 Agent、EvalOps 和 stdio MCP 同时工作时的内存、延迟及 OOM 状态；不要把 swap 当作正常可用内存。

## 本机准备

1. 停止应用并备份当前 MySQL，检查目标库已有迁移。旧环境先检查重复用户名，按顺序仅执行缺失的 V061、V062、V063；**各脚本只执行一次，已有字段或部分失败时不能重跑整文件。** V062 前确认启用的 `admin` 管理员及旧配置归属；V063 前核对待公开的全部符合条件管理员原始 Agent 和 11 个 MCP ID。完整说明见 [个人 Agent 工作区迁移顺序](../USER_AGENT_WORKSPACE.md#本地与生产迁移顺序)。新导出应包含这些结构；已有服务器升级时直接迁移目标库，不重新导入开发库。
2. 仓库根目录打包：`mvn -pl ai-agent-station-study-app -am "-Dmaven.test.skip=true" package`。先完成测试，再用此命令跳过测试打包；在开发机/CI 构建，不在小内存服务器上跑 Maven。
3. 导出当前 MySQL 和 PGVector：`python docs/dev-ops/server/export_data.py`。可以通过 `--mysql`、`--postgres` 指定容器名。导出包含业务数据和第三方凭证，只保存在已忽略的 `import/`；脚本拒绝覆盖已有 SQL 文件。
4. 检查 `import/mcp-migration-report.json`，修复阻塞项后重新运行 `prepare_mcp.py`。转换 `cmd /c`、Windows 脚本路径、容器内服务地址，移除本机回环代理。filesystem MCP 只允许 `/app/data/workspace`。生成的是服务器 SQL，不修改开发机 MCP 配置。
5. 将 JAR、构建所需的项目文件、本目录及私有导出通过 SSH 传到服务器。若有本地图片/附件，另外迁移项目 `data/` 中对应目录到 `app-data` 卷；OSS 资源则保留原配置和访问凭证。Redis 中旧运行快照不默认搬迁，用户重新登录。

## 迁移任务与可选 MCP

导入后、启动应用前，检查 `ai_background_task` 和 `ai_agent_task_schedule`：先暂停不准备在新环境执行的任务，避免复制的定时任务自动触发。确认每项任务及其凭证后再启用。

CSDN 和微信 MCP 默认不启动。使用示例数据时，将 `ai_client_tool_mcp` 中对应 `mcp_id`（示例为 `5001`、`5002`）的 `status` 设为 `0`；自定义数据应按实际工具 ID 核对。准备好镜像与凭证后，可通过 `docker compose --profile optional-publish up -d mcp-server-csdn-app mcp-server-weixin-app` 启动，再启用对应工具。

Grafana MCP 需要在 Grafana 创建服务账户及 token，填写 `GRAFANA_SERVICE_ACCOUNT_TOKEN`；只读查询可使用 Viewer 角色。模板使用国内镜像地址；应用 APT 和 MarkItDown pip 下载源分别可通过构建参数 `DEBIAN_MIRROR`、`PIP_INDEX_URL` 调整。

## 服务器准备

使用支持 Docker Engine / Compose v2 的 Linux。安装 Python 3，配置 Docker；按 Elasticsearch 要求设置宿主机 `vm.max_map_count=262144`。准备域名和有效 HTTPS 证书，放到 `certs/fullchain.pem`、`certs/privkey.pem`。

复制 `.env.example` 为 `.env`，设置域名、随机数据库密码、模型/Embedding 凭证及实际需要的第三方工具凭证。文件权限设为仅部署账号可读。**聊天模型及多数 MCP 的凭证还来自 MySQL 导出，不是只改 `.env` 就完成迁移。**

云防火墙只开放 80、443；SSH 端口仅允许自己的来源地址。Compose 不发布数据库、MCP、应用及 Actuator 端口。服务器中内部管理端口 9091 只开放 health/Prometheus 给容器网络，公网入口拒绝 `/actuator`。

在本目录执行：

```bash
python3 preflight.py
bash bootstrap.sh
docker compose ps
docker compose stats --no-stream
```

预检不会输出密钥，缺域名/凭证/证书/导出或存在无效 MCP 配置时会停止。首次初始化只向空数据库导入；不要用 `docker compose down -v`，这会删除持久化数据。

## 访问入口

| 路径 | 权限 |
| --- | --- |
| `/index.html` | 注册和登录 |
| `/memory.html` | 登录用户的记忆 |
| `/eval.html` | 登录用户的 EvalOps |
| `/user-agents.html` | 登录用户的 Agent 搭建与 MCP 连接管理 |
| `/observe.html` | 登录用户自己的 LLM 观测；管理员可切换全站范围 |
| `/observe-mcp.html` | 登录用户自己的 MCP 观测；管理员可切换全站范围 |
| `/admin-stats.html` | 管理员，网站 PV / UV / 新增注册统计 |
| `/agent-config.html` | 管理员，Agent 配置 |
| `/ops/grafana/` | admin 会话校验后通过 Grafana auth proxy 登录 |
| `/ops/kibana/` | admin 会话校验 |
| `/ops/jaeger/` | admin 会话校验 |
| `/ops/prometheus/` | admin 会话校验 |

数据库维护工具可通过 `docker compose --profile maintenance up -d phpmyadmin pgadmin redis-admin` 启动，使用 SSH 隧道访问，保留各自数据库认证。它们没有可绕过应用鉴权的公网端口。

## 上线验证

- `.env` 中的域名、证书、模型出口连通性，以及第三方 MCP 授权必须落到实际环境后验证。点评 MCP 还依赖独立的 HMDP 后端；本仓库包含 MCP 适配器，不包含那套业务后端。设置 `HMDP_API_URL` 为它的实际可达地址。
- Google Calendar 凭证转换后保存在私有 `import/calendar/`。授权 token 需要单独迁移或重新授权；该目录必须允许容器 uid 1000 更新 token，客户端密钥文件不等同于已完成用户授权。
- 只复制 JAR 时，EvalOps 能评测，但源码指纹/Tag 功能会标记 `CAPTURE_UNAVAILABLE`；需要该功能时，在服务器另备与构建一致的 Git checkout、安装 git，并配置 `eval.ops.git.repository-path`。不可把不匹配的代码目录当成当前构建证据。
- 当前镜像沿用项目既有组件系列；发布前验证拉取与构建、版本兼容性。不同网络和已有数据版本仍需分别验证，镜像成功启动不等于 MCP 业务调用成功。
- 新注册普通用户：能使用公共 Agent 副本、自行搭建 Agent、查看自己的 LLM/MCP 观测并创建自己的评测集；不能读取或修改他人的配置、评测集、会话、记忆，也不能访问全站观测、网站统计或管理 API。admin 保持原账号密码登录。
- 验证 SSE 持续输出、刷新重连、取消、退出后的旧 Cookie 失效，以及应用重启后 Redis 登录会话仍能恢复。检查 `docker inspect` 的 OOMKilled 和日志，确认容量余量后邀请用户。

参考：登录会话采用 [Spring Security 显式持久化](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)；MarkItDown 的 [官方 HTTP/SSE 启动说明](https://github.com/microsoft/markitdown/tree/main/packages/markitdown-mcp)用于内部容器服务。

## 后续更新

### 管理员网站统计（PV、估算 UV、新增注册）

`/admin-stats.html` 显示今日指标和最近 7／30 个北京时间自然日的趋势，接口
`GET /api/v1/observe/site-stats?days=7` 仅 ADMIN 可访问。没有新增数据库表：

- PV：GoAccess 汇总首页 `/`、`/index.html`（含查询参数）的 GET 200／304 请求。排除 API、静态资源、管理页面及 GoAccess 能识别的爬虫，管理员自己的访问也计入。
- 估算 UV：GoAccess 按当日 IP + User-Agent 去重。不是实名人数，不把每日 UV 求和当成跨日去重访客；完全命中浏览器缓存的访问无法从日志统计。
- 新增注册：直接按 `admin_user.create_time` 查询现存账户；包括管理员创建/导入的账户，保留原创建日期，删除账户会影响历史数。部署模板的应用时区为 `Asia/Shanghai`，历史 DATETIME 也需遵循此口径。

应用写 COMBINED 格式到 `/app/data/access-logs/access.YYYY-MM-DD.log`，保留 30 天。
采集脚本首次运行（或从旧版升级）重算最近 30 天的可用日志；此后只重算今天，跨天后补算昨天，停机恢复后补算仍有日志的未完成日期。历史文件仅检查大小和修改时间，未变化就复用每日统计；若存在延迟写入或历史日志被修改，会重新计算该日。未完成日期的日志丢失时保留旧报告并报错，不把缺失数据当成零。
每日 PV、UV 与已完成日期的文件指纹一起原子保存到 `/app/data/site-stats/report.json`，失败不会推进进度，重复运行不会累加；保留最近 30 个自然日。UV 始终按整天去重，不累加各批次 UV。不在静态目录发布原始 IP、请求参数或 GoAccess 全量报告。正常输出末尾 `scanned 1 daily log files` 表示本次只读取了一天的日志。
没有日志文件的日期显示 `–`，有日志但无合格首页访问的日期显示 0。首日只有启用访问日志之后的数据，历史不足时不伪造零值。
采集失败保留上一份文件，超过 15 分钟界面提示过期；注册数据源失败独立显示不可用。

在服务器更新应用 JAR 后，**只上传本次采集脚本和两个 systemd 文件**，不要覆盖已配置的 `.env`、Compose、IK 镜像或云配置。
旧部署确认 `application-cloud.yml` 现有 `server` 下包含本模板的 `tomcat.accesslog` 配置（不要新增第二个 `server` 节点）。
若已有访问日志则无需改配置。应用以 `/app` 为工作目录；其它运行方式可以通过 `observe.site-stats.report-path` 指定文件路径。

服务器 `/opt/tagent/docs/dev-ops/server` 下执行：

```bash
sudo apt-get update && sudo apt-get install -y goaccess
sudo python3 collect_site_stats.py
sudo install -m 644 tagent-site-stats.service tagent-site-stats.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now tagent-site-stats.timer
sudo systemctl start tagent-site-stats.service
sudo systemctl status tagent-site-stats.timer --no-pager
```

使用已有 Linux Docker 主机上的 GoAccess（已按 Ubuntu 24.04 的 1.8.1 接口实现）。脚本自动从当前 app 容器查找 `/app/data` 对应的数据卷路径；不硬编码 Compose 卷名。
systemd 服务使用 root 读取该数据卷，配有进程锁和 280 秒执行上限；若项目路径不同，先修改 service 的两个路径。
排错：`sudo journalctl -u tagent-site-stats.service -n 30 --no-pager`。首次手动采集成功后，刷新 `/admin-stats.html`；打开首页后等下一次采集验证 PV 增加。
普通用户访问统计接口应返回 403，未登录返回 401。页面的 LLM 时间窗口与网站统计的自然日范围互相独立。

本机测试：`python -m unittest discover -s docs/dev-ops/server -p test_collect_site_stats.py`。
不再使用时 `sudo systemctl disable --now tagent-site-stats.timer`；不删除应用数据卷。

备份服务器数据库、`.env` 和挂载的数据卷。在开发机测试并打包新 JAR，经 SSH 上传后，在服务器本目录执行 `docker compose build app` 和 `docker compose up -d --no-deps app`，随后检查日志、登录和 SSE。数据库迁移按版本单独执行；更新应用时不要再次导入旧数据库，也不要删除数据卷。保留上一版镜像便于回退；涉及数据库变更时需另外评估回退兼容性。
