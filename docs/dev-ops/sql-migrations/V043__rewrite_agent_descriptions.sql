-- V043: 重写 agent 描述，使其体现"已配备工具"，让统一路由能据此判断"本次问题可能缺哪类工具能力"。
-- 本项目 SQL 迁移按手动/幂等脚本管理；重复执行只是把描述刷成同一份，安全。
-- 注意：g-search(5004) 当前为禁用状态，因此下列"已配备工具"只列实际启用的 MCP 能力。

UPDATE ai_agent SET description = '生活服务 — 日常生活：天气查询、穿衣建议、出行安排、习惯养成、时间管理、情绪调节、目标规划、生活窍门。已配备工具：天气预报查询、数学计算。' WHERE agent_id = '8001';
UPDATE ai_agent SET description = '生活服务 — 美食烹饪：菜谱推荐、烹饪步骤、食材搭配、营养分析、热量查询、饮食建议。已配备工具：菜谱生成与改写、食物营养与热量查询。' WHERE agent_id = '8002';
UPDATE ai_agent SET description = '生活服务 — 阅读相关：书籍推荐、书单整理、书评摘要、读书笔记、论文搜索、阅读方法。已配备工具：arXiv 学术论文检索。' WHERE agent_id = '8003';
UPDATE ai_agent SET description = '生活服务 — 健身运动：训练计划、增肌减脂、动作指导、饮食搭配、卡路里计算、运动营养。已配备工具：Strava 运动数据查询、食物营养与热量查询。' WHERE agent_id = '8004';
UPDATE ai_agent SET description = '语言服务 — 翻译润色：中英翻译、多语种翻译、文章润色、语法纠错、风格改写、术语查询。已配备工具：百度翻译、LanguageTool 语法检查。' WHERE agent_id = '8005';
UPDATE ai_agent SET description = '生活服务 — 日程管理：日历安排、任务清单、周计划月计划、时间管理、待办事项、会议安排。已配备工具：日历事件管理、待办任务管理、天气预报查询。' WHERE agent_id = '8006';
UPDATE ai_agent SET description = '生活服务 — 理财记账：收支分析、预算制定、股票基金分析、投资建议、理财规划、复利计算。已配备工具：股票行情查询、数学计算、百度联网搜索。' WHERE agent_id = '8007';
UPDATE ai_agent SET description = '教育服务 — 学习规划：学习路线制定、技术栈学习、资源推荐、技能提升、开源项目与论文推荐。已配备工具：GitHub 仓库与代码搜索、arXiv 论文检索、百度联网搜索。' WHERE agent_id = '8008';
UPDATE ai_agent SET description = '创作服务 — 创意写作：小说创作、文案撰写、剧本编写、诗歌创作、文章代写、文本润色。已配备工具：AI 图片生成与编辑、LanguageTool 语法检查。' WHERE agent_id = '8009';
UPDATE ai_agent SET description = '生活服务 — 旅行规划：旅游攻略、行程安排、景点推荐、路线规划、旅行预算、目的地推荐。已配备工具：高德地图（地点与 POI 搜索、驾车/步行/公交路线、距离测算、地理编码、城市天气）、天气预报查询、数学计算。' WHERE agent_id = '8010';
UPDATE ai_agent SET description = '通用问答 — 闲聊问候、事实问答、代码片段、翻译、格式化等一次回答即可的任务。已配备工具：本地文件读写、百度联网搜索。' WHERE agent_id = '8011';
UPDATE ai_agent SET description = '通用问答 — 需要深入分析、多步推理、多维度对比、方案设计等需要反复思考的任务。未预置专用工具，可按需补挂外部工具。' WHERE agent_id = '8012';
UPDATE ai_agent SET description = '通用问答 — 需要按阶段依次执行的复合任务，如先调研再分析再输出、先读代码再改代码再写测试。未预置专用工具，可按需补挂外部工具。' WHERE agent_id = '8013';
UPDATE ai_agent SET description = '技术运维 — 技术博客：撰写技术文章、发布到 CSDN、微信公众号通知、代码示例。已配备工具：CSDN 发帖、微信公众号消息通知。' WHERE agent_id = '8014';
UPDATE ai_agent SET description = '技术运维 — 日志分析：错误统计、根因分析、调用量统计、异常排查、性能分析。已配备工具：Elasticsearch 日志检索与查询。' WHERE agent_id = '8015';
UPDATE ai_agent SET description = '技术运维 — 监控运维：监控面板查询、告警分析、性能瓶颈定位、资源使用监控、运维报告生成。已配备工具：Grafana 监控（仪表盘、数据源、Prometheus/Loki 查询、告警、事件）。' WHERE agent_id = '8016';
