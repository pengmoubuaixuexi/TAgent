# TAgent 功能演示（2026-10 重录精选）

由 Codex、Muse 两批录制择优，并替换为通过限定范围审核的最新补录。共 **34 段：32 段功能演示、2 段工程实测**，保留原页面、模型回答与工具结果；全部为无音轨字幕录屏。

视频存放于 [v2.2.1 GitHub Release](https://github.com/pengmoubuaixuexi/TAgent/releases/tag/v2.2.1)，点击下表观看或下载 MP4。建议先看 **04 → 05 → 07 → 09a → 17 → 18**。

[字幕与范围说明下载](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/tagent-demo-subtitles-and-notes.zip) · [校验清单](catalog-20261030.json)

## 功能演示

|编号|演示内容|来源|时长|范围说明|
|---|---|---|---:|---|
|01|[注册登录与账号隔离](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/01-account-isolation.mp4)|Codex|2:41|普通账号/管理员与两个演示账号；不代表所有接口安全审计|
|02a|[Fixed规范语义问答](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/02a-fixed-qa.mp4)|Codex|1:15|可作为原02a的正常功能片；私人Pro Agent，原始Q31，不称公共测试Agent首答已修复。|
|02b|[Auto自主学习规划](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/02b-auto-planning.mp4)|Codex|4:20|展示Auto执行；书目不是逐本核验的采购清单|
|02c|[Flow旅行规划与真实工具结果](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/02c-flow-travel.mp4)|Codex|15:59|行程仍有避峰时刻等瑕疵；仅展示Flow机制，不当出行承诺|
|03b|[旅行自动路由](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/03b-travel-routing.mp4)|Muse|8:54|有工具失败；旅行数值不宣称全部独立核实|
|03c|[学习任务自动路由](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/03c-learning-routing.mp4)|Codex|3:37|规划草案，不是每一本资料均经全文核验|
|04|[创建个人Agent](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/04-personal-agent.mp4)|Muse|2:59|主链单回答节点；不宣称录到了绑定MCP或Advisor|
|05|[草稿保存、版本比较与恢复](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/05-agent-versions.mp4)|Muse|2:51|本短版不含节点调试；调试全片另放问题记录|
|06|[动态发现与补充工具](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/06-dynamic-tools.mp4)|Codex|1:59|部分外部文章未逐篇全文审阅；公开URL脱敏缺陷见原review|
|07|[主动追问与同一运行恢复](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/07-ask-user.mp4)|Codex|0:55|仅证明交互与条件传入，不背书旧完整行程|
|08|[Flow计划编辑与确认](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/08-plan-review.mp4)|Muse|10:07|只证明编辑与执行，不把部署方案当审校过的操作手册|
|09a|[已有结果后的运行中引导](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/09a-steer.mp4)|Muse|3:10|同一原Run，明确省略前置对话和等待；不伪装首次即时生成|
|09b|[基于阶段性结果立即回答](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/09b-answer-now.mp4)|Muse|3:18|提前汇总不是剩余步骤全部完成；答案属于阶段性结果|
|10|[指定步骤重做（方案级）](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/10-step-redo.mp4)|Codex|1:05|补足真实来源和步骤选择；初稿与旧命令仍有错，最终交付仅为方案级说明。省略中间等待，末段只读展开同一Run终答，不是部署教程或部署验收。|
|11a|[执行中主动取消](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/11a-cancel.mp4)|Codex|1:20|使用Codex11c原片对应Run，不与11b假称同一次任务|
|11b|[刷新、断网与后台续作](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/11b-reconnect.mp4)|Muse|2:46|证明续作机制；离线模拟凭录制记录，不由静态画面单独证明|
|12|[跨会话记忆与管理](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/12-memory.mp4)|Codex|4:16|画像演示事实不代表所有记忆自动提取都正确|
|13|[三文档检索与逐句引用](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/13-rag-three-documents.mp4)|Codex|1:16|三份文档检索、逐句摘录与引用；录制早于最终 RAG 修复部署，不作为修复上线后的验收片。|
|14|[来源展开与Evidence Map](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/14-evidence-map.mp4)|Muse|5:14|两块未核验；不是全文事实核查通过，也没有inspect_tool_result演示|
|14b|[工具结果引用与分页读取](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/14b-tool-result-inspection.mp4)|Codex|2:19|与原14来源与Evidence Map搭配；本片是POI摘要及inspect_tool_result，不等于Evidence Map全文核验。|
|15|[图片上传与粘贴（SQL结构）](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/15-image-upload-paste.mp4)|Codex|0:51|和原15b图片URL片搭配；准确核对表名、主键、索引及列序，不包含来源文件名精确OCR。|
|15b|[图片URL识别补充](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/15b-image-url.mp4)|Muse|2:58|换了图片；不表示原SQL双下划线错误或原失败地址已恢复|
|16|[附件读取、分页与历史附件](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/16-attachments.mp4)|Codex|3:10|未绑定ChatMemory；证明附件索引，不能泛化所有记忆淘汰策略|
|17|[产物下载、手改与跨轮修订](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/17-artifact-revisions.mp4)|Codex|1:43|短Markdown；不泛化所有文件格式或多人并发编辑|
|18|[Python计算、中文图表与下载](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/18-python-charts.mp4)|Codex|1:30|本版只含原正常Run；源完整诊断片仍保留|
|18b|[缺列文件拒绝编造](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/18b-missing-column-validation.mp4)|Muse|1:07|来自独立Muse账号和Run；主要是读列后拒绝计算，不称Python执行报错|
|19|[定时提醒与服务器本地文件监视](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/19-scheduled-tasks.mp4)|Codex|2:16|监视对象为服务器专用文件，不是普通用户产物或附件；时间显示差异仍记录|
|20|[只读搜索工具审批](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/20-read-only-approval.mp4)|Codex|1:51|仅搜索审批；不宣称测试高危写副作用|
|21|[个人模型连接、用量与故障切换](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/21-personal-models.mp4)|Codex|2:20|只测型号不存在；Java措辞有简化；不代表修复后新建竞态与401全面验证|
|21b|[新建即时检测与401切换](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/21b-new-model-health-failover.mp4)|Codex|1:19|和原21保存绑定、用量及400故障片搭配；新增即时检测和两次独立Run的401候选选择，未改平台凭据。|
|22|[运行历史与完整轨迹](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/22-run-history-traces.mp4)|Codex|1:44|保留原错误回答作诊断，不把完成当正确|
|23|[用量观测与管理员统计](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/23-usage-observability.mp4)|Codex|2:39|录制时旧额度预留说明后来已修正；UI额度为当时临时值|

## 工程实测与改进

以下两段展示真实评分、问题、对比与采纳过程，不能作为所有答案正确或首次运行全部成功的背书。

|编号|演示内容|来源|时长|范围说明|
|---|---|---|---:|---|
|24|[反馈与两题评测实测](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/24-evalops-field-test.mp4)|Codex|2:49|规则分与Judge不代表答案全正确；Java与路由评分缺陷保留|
|25|[复盘、修复、对比与采纳](https://github.com/pengmoubuaixuexi/TAgent/releases/download/v2.2.1/25-improvement-field-test.mp4)|Codex|4:27|回答仅部分改善；不伪称高分即正确或首次自动全成功|

## 阅读边界

- 01–25 是主题编号，部分有子段；03a 食谱按用户要求跳过。
- 14 的部分核验不等于全文审计通过；14b 展示 POI 结构化投影及原文分页，不声称所有返回都超过通用截断阈值。
- 15 展示 SQL 图片的结构识别；不声称精确文件名 OCR 的双下划线误读已修复。
- 19 展示定时任务与服务器本地文件监视，不冒充普通用户附件/产物监视；20 只展示搜索工具审批，不冒充高危写入验收。
- 13 只展示三文档检索与引用；录制早于最终修复部署。视频中历史通知、失败尝试与模型表述以当次录制为准，不代表当前仍存在同样故障。
- 各片保留已披露的剪辑与等待省略；两批不同账号、不同运行不拼成同一条任务。模型生成完成不等于内容无误。

## 历史录屏

本目录原有的 16 段 MP4/WebM 属于早期录屏，保留用于历史追溯。当前演示入口以上表新合集为准；旧文件不再作为推荐演示。

[历史录屏与旧脚本说明](legacy-README.md)
