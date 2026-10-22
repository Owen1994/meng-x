# 法学助手 RAG 后续工作（W2-W4）与调优参考

> 更新时间：2026-10-22。W1（数据入库 + 三路检索 + RAG 问答）已全部完成并端到端验证通过，本文档承载后续迭代设计与调优参考。

## 一、W1 已交付状态（当前基线）

### 数据层
- 三张表（`law_articles` / `law_questions` / `law_notes`），实体在 `server/src/main/java/com/mengx/server/law/`
- `law_articles` 已入库 **18 部 / 4042 条 / 100% 向量化**：
  - 实体法：民法典 1260、刑法 505、公司法 266（2023 新版）、证券法 226、保险法 185、宪法 143、破产法 136、立法法 120、票据法 110、合伙企业法 109
  - 程序法：刑诉法 307、民诉法 306（2023 新版）、行诉法 103、行政复议法 90（2023 新版）
  - 司法解释：担保制度 71、合同编通则 69、民法典时间效力 28、公司法时间效力 8
- embedding：千问 `qwen3.7-text-embedding-flash`（1024 维），配置 `mengx.law.embedding-*`（`application-local.yml` 存 key，生产用 `MENGX_EMBEDDING_API_KEY` 注入）

### 检索层（LawSearchService）
三路融合，任一路失败降级不阻断问答：
1. **编号正则**（exact，置顶）：`民法典第311条` → `(law_code, article_no_int)` 精确命中；支持俗称别名（民诉法/刑诉法/行诉法/破产法/担保制度解释）与中文数字条号（第三百一十一条）
2. **向量语义**（主力）：千问 embedding 余弦近邻（`<=>`），RRF 融合
3. **trgm 词汇**（补充）：`word_similarity > 0.4`，捞字面术语命中（如「表见代理」命中合同编通则解释）
- RRF 融合（K=60），编号命中条目不参与 RRF 累加、直接置顶；TOP 5 输出

### 问答层
- 法学分支（IdeaChatService）：检索 → SSE `citations` 事件（先于正文）→ 检索上下文注入法学人设 system 尾部 → 流式作答
- 前端 Chat.jsx：引用徽章渲染（紫色胶囊，hover 显示条文摘要 80 字）
- 意图路由（route()）：glm-4.7 二分类 + 通道粘性，法学/点子铺自动分发

### 导入管道
- `POST /api/law/import`（JWT）：`{"type":"statute","law":"民法典"}` 单部 或 `{"laws":[...]}` 批量（依次执行，单部失败不影响其余）
- `GET /api/law/import/status`：进度（lawIndex/laws/total/imported/skipped/failed）
- 幂等：content_hash 未变跳过；修订条按 `(law_code, article_no)` upsert
- 数据源：`D:\code\project\Laws`（LawRefBook 仓库，`mengx.law.data-dir` 配置，生产 `MENGX_LAW_DATA_DIR`）
- 新版优先：同名多版本取文件名日期最新（公司法 2023 版 266 条 ✓）

## 二、W2 出题（law-6）

**目标**：法学助手聊天内出题练习（非独立页面，对话式出题）。

设计要点：
- `LawQuestionGenService`（新）：基于检索上下文出题——用户说「帮我出几道善意取得的题」→ 检索法条 → glm-4.7 生成题目
- 输出结构（存 `law_questions` 表，source=generated）：
  - `stem`（题干）、`options`（jsonb 四选一）、`answer`（答案）、`analysis`（解析）、`knowledge_points[]`（知识点标签）、`difficulty`（1-5）、`source`（generated/exam）、`year`（真题年份）
- 真题导入格式（`type=question`）：真实题库存 PDF/结构化数据 → 解析入库，`content_hash` 幂等
- 出题 prompt 约束：法考风格、单选题为主、干扰项要有区分度、解析引用法条编号
- 触发方式：意图路由识别「出题/练习」意图，或在法学模式下用户明确要求

## 三、W2 答题（law-7）

设计要点：
- 对话式答题：题目以卡片气泡形式展示（复用 card 事件模式或新 type=question 事件），用户回复选项字母
- 判分：选项比对（客观题）+ 主观题 LLM 批改（采分点匹配，W4）
- `law_answer_records` 表（新）：`user_id / question_id / user_answer / is_correct / answered_at / time_cost`
- 答后即时反馈：对错 + 解析 + 法条引用
- 前端：答题交互视图（选项点击/字母输入）、解析展示

## 四、W3 画像（law-8）

设计要点：
- 掌握度计算：按知识点（knowledge_points）聚合正确率 + 艾宾浩斯遗忘曲线（时间衰减）
- `LawProfileService`（新）：掌握度 = 近期正确率 × 时间衰减因子
- 仪表盘视图：各科掌握度、薄弱知识点、刷题建议（前端新页面或 Chat 入口）
- 周报生成：定时（或手动触发）汇总本周练习数据，LLM 生成备考建议
- 出题推荐：出题时接入画像——薄弱知识点加权出题

## 五、W4 Agent 与优化（law-9 / law-10）

设计要点：
- 意图路由扩展：出题/批改/法条查询细分意图（仍走 route() 分层架构，不扁平多分类）
- 法条辨析模式：「第311条和第312条什么区别」→ 双条检索 + 对比 prompt
- 主观题批改：采分点匹配（题目带采分点 → 用户答案逐点比对 → 得分 + 补充建议）
- Caffeine 热点缓存：高频法条向量/检索结果缓存（`embedding` 单条 ~200ms，缓存查询文本→hits 映射，TTL 1h）
- rerank：召回 top 10 → LLM rerank 到 top 5（可选，先看效果）
- 刑法修正案、更多司法解释按需导入（导入 API 已支持）

## 六、部署上线清单（服务器侧）

1. **PG 换 pgvector**：服务器 postgres 镜像 → `pgvector/pgvector`（同版本，保留数据卷），`CREATE EXTENSION vector; CREATE EXTENSION pg_trgm;`（参照本地操作）
2. **环境变量**：`meng-x.env` 增加 `MENGX_EMBEDDING_API_KEY`（千问 MaaS key）
3. **数据同步**：服务器 clone LawRefBook/Laws 或打包传输，`MENGX_LAW_DATA_DIR` 指向，push main 后 CI 构建部署，然后手动触发 `POST /api/law/import` 批量导入（18 部清单见本文档一）
4. **依赖**：pom 已含 hibernate-vector；GitHub Actions 无需改
5. 注意：`application-local.yml` 不进 git（jar 不含），生产 key 全走环境变量 ✓

## 七、调优可关注点（已知弱点）

| 现象 | 原因 | 可选优化 |
|---|---|---|
| 向量召回偶有噪声（「民法典第311条讲什么」召回 1131/1041 等弱相关条） | 查询文本口语化，embedding 与条文原文语义距离散 | 接受（exact 置顶保底）；或 LLM rerank（W4） |
| trgm 对非字面术语失效（「善意取得」「自甘风险」都不在条文原文中） | 术语与法条用词差异，固有局限 | 主力靠向量；可加「术语→条文」映射表（法考高频术语人工标注） |
| 引用徽章刷新后不恢复 | citations 不落库（idea_messages 只存文本） | 可接受（正文含《法名》第X条）；如需恢复可在 assistant 消息 jsonb 扩展字段 |
| 长问题 embedding 召回偏题 | 整句嵌入稀释关键概念 | 出题/检索前用 LLM 抽取查询关键词（W4 可做） |
| 编号路依赖法名文本出现 | 「那第20条呢」的指代无法解析 | 结合 history 上一轮法名（W2 时顺手加） |

## 八、关键文件索引

```
server/src/main/java/com/mengx/server/law/
├── LawAgentService.java      # 法学人设 + RAG 注入 + 意图路由
├── LawSearchService.java     # 三路融合检索（编号/向量/trgm + RRF）
├── LawEmbeddingClient.java   # 千问 embedding-3 客户端（16/批+限速+退避）
├── LawArticleParser.java     # markdown 法条解析（编/章/节/条 + 日期）
├── LawArticleEntity/Repository / LawQuestion* / LawNote*
├── LawImportService.java     # 手动批量导入（异步+进度+幂等）
├── LawImportController.java  # POST /api/law/import + status
└── ChineseNumeral.java       # 中文条号解析
```
