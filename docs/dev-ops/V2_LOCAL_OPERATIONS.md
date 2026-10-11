# v2 本地迁移、恢复与上线预检

本说明介绍本地隔离验证工具；生产升级顺序见 [v2 升级指南](V2_UPGRADE.md)。这些工具不连接生产服务器、不发布应用、不替换现有配置。`server/v2_ops.py` 只接受本机 Docker 中的 `tagent-v2-tests-mysql` 和 `tagent_workspace_isolation_test`，不是生产部署脚本。不会自动发现数据库，也不会使用开发库兜底。恢复目标固定为新的 `tagent_v2_restore_test`；目标已存在时拒绝覆盖。

## 迁移记录

在仓库根目录运行（Python 3.10+、Docker CLI；不需要额外 Python 包）：

```powershell
python docs/dev-ops/server/v2_ops.py manifest
python docs/dev-ops/server/v2_ops.py status
```

默认范围为 V064–V069。`manifest` 只读文件；`status` 在专用测试库建立迁移历史表后读取记录。校验和按 UTF-8 去 BOM、统一 LF 后的 SHA-256 计算，换行格式不会误报，内容变更会拒绝。原有 SQL 文件和服务器 `application-cloud.yml` 不会被修改。

新测试库已经具备 V063 以前的基础结构时，可用 `migrate` 执行 V064–V069。已经手工执行这些版本的测试库应先核对实际结构，再显式使用 `baseline`：它核验所建表/列及旧 Agent 版本回填条件，记录当前文件校验和。Baseline 是操作者确认的历史接管，不证明历史上执行过的文件与当前字节完全相同；不能拿它消除来源不明的历史差异。

```powershell
python docs/dev-ops/server/v2_ops.py baseline
python docs/dev-ops/server/v2_ops.py migrate
```

运行记录保存在 `tagent_schema_history`，逐句断点保存在 `tagent_schema_step`。MySQL DDL 会隐式提交，因此失败不宣称整份脚本回滚。明确的 SQL 错误修复依赖条件后，可以 `migrate --retry-failed` 继续同一校验和版本：跳过已成功的普通语句，并重放连接内变量/预处理语句。不要直接编辑失败版本绕过校验；确需修订时先分析断点和已提交结构，再通过后续迁移修复。

网络中断、进程消失等不能确定最后一条语句是否已提交的场景，脚本拒绝自动续跑。需人工检查断点、结构与数据后决定修复方案。并发操作有本地进程锁；旧的无历史迁移脚本不提供这些保障，不应与本工具同时运行。

## MySQL 备份及隔离恢复

```powershell
python docs/dev-ops/server/v2_ops.py backup --backup-dir "$env:TEMP\tagent-v2-backup-new"
python docs/dev-ops/server/v2_ops.py restore-test --backup-dir "$env:TEMP\tagent-v2-backup-new"
```

输出 `mysql.sql.gz` 和含校验和的 `manifest.json`。备份目录必须是新目录；恢复先验证来源和摘要，拒绝含 `USE`、数据库切换或删除数据库指令的输入。密码通过测试容器已有环境传入，不进入命令行或报告。备份包含账户、加密凭据等私有数据，只应保存在个人受控目录，不能提交仓库或公开发布。

此命令仅备份 MySQL。完整回退还需要同一停写点的 PostgreSQL 向量库、Elasticsearch 数据/插件词典、应用 `/app/data`（原文件、附件、统计）、旧镜像/JAR、配置和 `MODEL_SECRET_MASTER_KEY`。已加密模型凭据必须保留原主密钥；不能在重启时随机生成。旧 JAR 不保证兼容 v2 新数据，不应在新数据仍可写时直接切回旧版本。

隔离演练应验证失败 SQL 的续跑断点、校验和变更拒绝、恢复后业务记录与迁移历史一致，以及源库不受影响。MySQL 隔离恢复通过不等于 PostgreSQL、Elasticsearch 和应用文件的全栈恢复已经验证；真实数据恢复需独立演练。

## HTTPS 与密钥预检

只读检查准备目录；既有 `application-cloud.yml` 和 `nginx.conf` 保持原样：

```powershell
python docs/dev-ops/server/v2_preflight.py --root docs/dev-ops/server --jar PATH_TO_BUILT_JAR
```

检查域名、证书 SAN/有效期、随机 32 字节 Base64 主密钥、持久卷、Compose 暴露端口、Secure Cookie、JAR 页面。`--no-compose` 只用于没有 Docker 的文件检查，不代表端口环境已经验证。`--sanitized-jar` 检查准备发布的最终 JAR 已排除私有 dev/test/prod 配置。脚本不打印密钥、不启动服务、不迁移数据库；失败项需要部署前逐项配置，不能因为本机未填生产域名而跳过正式环境检查。

生成**新的** nginx 配置用于审阅，输出文件已存在时拒绝覆盖：

```powershell
python docs/dev-ops/server/v2_preflight.py --render-nginx "$env:TEMP\tagent-nginx-v2.conf" --domain YOUR_APPROVED_DOMAIN
```

将示例域名替换为实际已准备的域名。模板保留运维管理入口鉴权，拒绝未知 Host，配置 80 到 HTTPS 跳转和 SSE 非缓冲转发；只适用于客户端直接到本机 nginx 的部署。如前方另有 CDN/LB，应先明确可信代理链后再配置真实 IP，不应直接信任任意来访的转发头。

模板测试使用临时自签名证书和本地 `nginx:1.28-alpine` 执行 `nginx -t`，容器 `--network none`、不映射端口。上线前仍需实际证书/私钥匹配检查、域名解析与备案状态、HTTPS 登录和 SSE/取消/重连、备份恢复及真实提供商连通性验证。

## 本地回归

```powershell
python -m unittest discover -s docs/dev-ops/server -p test_v2_ops.py -v
```

默认只运行离线单元测试。真实隔离 MySQL 演练须显式设置 `TAGENT_OPS_INTEGRATION=1`；恢复目标已存在时不会覆盖，复跑应先检查旧演练产物并显式安排清理。HTTPS 测试须 `TAGENT_HTTPS_TEMPLATE_TEST=1`、本地已缓存 nginx 镜像及带 `cryptography` 的 Python；不会自动拉镜像或激活域名。
