# 百晓通 · 架构手册（forge-handbook）

> 本手册是 forge 工部（回路 B）各岗的输入依赖，也是 AI 编程工具施工时的全局约束。
> 单一真源：本文件随架构演进人工维护，改架构必须同步改手册（评审 checklist 项）。
> 章节号即锚点：工部各岗与施工包以「手册 §N」引用本文件。

## 1. 模块地图与包约定

<!-- STACK:BEGIN -->
现有技术栈（施工必须贴合，不得另起炉灶）：
- 前端：React 18 + Vite 6 单页应用，无路由库，手机端为主场景（视口自适应），PC 端窄栏居中
- 后端：Java 17 + Spring Boot 3.4，Spring Data JPA（ddl-auto=update），REST 接口 JWT 鉴权，SSE 流式输出
- 数据库：PostgreSQL + pgvector（向量检索）
- LLM：智谱 GLM（langchain4j 官方集成，glm-4.7 主力 / glm-4-flash 意图路由），AiServices 接口多 agent 编排 + ChatMemory 托管上下文
- 已有能力体系（涉及以下能力时必须复用现有实现，不得另建）：
  手机号登录即注册、会话与消息持久化（换设备不丢）、主对话代理多 agent 访谈（意图分层路由 / 需求澄清共创 / 规格定稿）、
  功能卡看板流转与文档产出、法学助手（法条 / 真题 RAG 三路检索、法考刷题与学情画像、主观题批改）、
  每日一签、修为墙、LLM 用量埋点与 Prometheus 指标
<!-- STACK:END -->

| 包 | 职责 | 关键类 |
|---|---|---|
| `idea` | 主回路流程：主对话访谈/需求澄清双官/规格定稿/看板/许愿池/PRD 格式化/刷题编排 | IdeaChatService、DiscoveryService、LawQuizFlowService、IdeaPrdService、IdeaWishService |
| `law` | 法学域：RAG 三路检索/出题/判分/画像/主观题批改/embedding 客户端 | LawSearchService、LawAgentService、LawQuestionGenService、LawSubjectiveJudgeService、LawProfileService |
| `routing` | 意图路由：通道注册表 + 四层裁决 | ChannelRegistry、ChannelSpec、RoutingService |
| `llm` | 模型接入与可观测：langchain4j 官方智谱客户端/用量埋点 | ZhipuAiChatModel、ZhipuUsageListener、LlmUsage、LlmUsageRecorder |
| `idea/forge/law 各自 agent 子包` | agent 契约：AiServices 接口（@SystemMessage 人设 + POJO 出参，JSON 绑定由框架负责）+ Factory 懒装配 | IdeaAgentFactory、ForgeAgentFactory、LawAgentFactory |
| `auth` | 手机号登录即注册 + JWT | AuthService、JwtAuthFilter |
| `user` | 用户实体 | UserEntity、UserRepository |
| `features` | 独立小功能（每日一签） | FortuneService 等 |
| `controller` | 根级接口（健康检查） | HealthController |
| `config` | 三档模型/安全/可观测装配 | AiModelConfig、SecurityConfig、ObservabilityConfig |
| `forge`（规划中） | 回路 B 工部：黑板/五岗/工单状态机/执行器 | 见《造场流水线改造方案》§7 |

包约定铁律：
- 依赖方向单向：`routing → llm/agent`、`law → llm/agent`、`idea → law/routing/llm/agent`；禁止反向依赖与跨域横向依赖（law 不 import idea，反之可以）
- `chat`、`orchestrator` 为历史废弃空包，禁止新增代码（待删除）
- 新领域能力开新包，不往 idea 里塞（idea 已瘦身过一次：LawQuizFlowService 拆分先例）
- 前端源码在 `web/src`，无路由库，视图由 App.jsx state 切换

## 2. 道场接入配方（意图路由）

新能力（道场/agent）接入路由 = 在 `ChannelRegistry` 注册一条 `ChannelSpec`，路由代码零改动：

```java
new ChannelSpec(
    "medicine",            // id：英文小写，全局唯一
    "医学助手",              // name
    "回答医学用药/症状咨询问题", // routeDescription：给 L2 名册的一句话职责
    List.of("症状", "用药"),   // signals：L1 信号词（命中即进本通道）
    List.of("给我开药方"),      // strongSignals：L0.5 强意图词（命中无视粘性直达）
    List.of(new ChannelSpec.Example("头疼吃什么药", "medicine"))) // examples：L2 few-shot
```

四层裁决（见 RoutingService）：
- **L0.5 强意图直达**：命中任一通道 strongSignals → 直接返回该通道（零模型、无视粘性；「继续出」等按钮词必须放这层）
- **L1 信号词**：零信号 → 留在默认通道；命中本通道 signals → 续通道；命中他通道 signals → 进 L2
- **L2 灰区模型**：glm-4-flash，输入=最近 6 轮对话背景+注册表名册，拿不准返回默认通道
- **L3 兜底**：解析失败/超时 → 粘住上一轮通道

信号词纪律：
- 唤起词（strongSignals）必须在全部通道的 signals ∪ strongSignals 集合内全局唯一，子串碰撞会误切（「出题」撞法学与医学的教训）
- 信号词写「用户会打的字」：含插字变体（「出几道」「几道题」），不写完整句子
- 冲突只检测不自动消解（SignalConflictTool 报告，人工调词）——v1.2 决策

## 3. SSE 事件协议与消息 role 约定

流式接口统一返回 `SseEmitter`（禁止 Flux 包装 ResponseEntity），手动订阅逐 token send + flush，`subscribeOn(Schedulers.boundedElastic())` 防阻塞 Servlet 线程；响应头注入 `X-Accel-Buffering: no`；Nginx `/api` 块 `proxy_buffering off`。

事件协议（`IdeaChatService.StreamEvent(type, content)`）：

| type | content | 发送方 |
|---|---|---|
| `thinking` | 推演阶段一句话（前端面板展示） | 主聊流/刷题流 |
| `chunk` | 增量文本 token | 所有流式作答 |
| `card` | 功能卡 DTO JSON | 规格定稿成功 |
| `question` | 题目 DTO JSON（逐题生成逐题下发） | 刷题流 |
| `design` | 设计简报信封 JSON（营造图面板数据源） | 发现模式轮 |
| `done` | null | 所有流收口 |
| `error` | 错误文案 | 所有流 |

`idea_messages.role` 约定（消息持久化，换设备恢复依据）：

| role | content | 语义 |
|---|---|---|
| `user` / `assistant` | 原文 | 对话正文（讲评也是 assistant） |
| `card` | 卡片 id 字符串 | 该位置展示功能卡 |
| `question` | 题目 DTO JSON | 该位置展示题卡（按 id 查答题记录恢复状态） |
| `counsel` | 设计简报信封 JSON | 需求澄清存档（前端不直接展示，营造图面板按需读） |

## 4. 数据表 / DDL / JPA 约定

现状表（全部 JPA ddl-auto=update 自动管理，无 Flyway）：
`users`、`idea_sessions`、`idea_messages`、`idea_cards`、`idea_likes`、`idea_thanks`、`idea_fortune_draws`、`law_articles`、`law_questions`、`law_notes`（空壳）、`law_answer_records`、`llm_usage_log`

约定：
- 建表：实体加 `@Table(name="蛇形复数")`，主键 `Long id` 自增，时间列 `createdAt LocalDateTime` 默认 `now()`
- **加列必须带默认值或可空**——PG 对 NOT NULL 加列的限制是本项目踩过的坑（`IdeaSessionEntity` 教训）
- 幂等锚点：导入类数据表以 `content_hash` 唯一约束实现幂等更新（law_articles/law_questions 同款）
- jsonb 列存结构化产物：`scoring_points`（采分点）、`details`（逐点判定）、`design_json`（方案卷轴）
- 向量列：`embedding vector(1024)`（千问 qwen3.7-text-embedding-flash，1024 维）
- 手工 DDL 逃生口：`docker exec isor-pg psql -U postgres -d mengx`（数据库容器名 isor-pg）

## 5. 前端事件分发表与组件挂载约定

- 视图切换：App.jsx 内 state（无路由库）；新视图 = 新 state 值 + 新页面组件挂载
- SSE 消费：`web/src/api.js` 的 fetch + ReadableStream 手写（不走 EventSource，因需 POST + JWT 头）
- 页面：`Chat.jsx`（主对话访谈+刷题+营造图面板）、`Board.jsx`（看板）；气泡/输入区/ThinkingPanel 等子组件两线复用
- 样式：`App.css` 单文件，白灰简约风（背景 #f5f5f7、主黑 #1d1d1f）；输入框字号固定 16px 防 iOS 聚焦缩放；PC 端消息列 820px 窄栏居中；固定宽度弹层用视口相对宽度
- localStorage 只存轻指针（会话 id、登录凭据），消息数据一律后端拉取

## 6. 模型三档与调用规范

| 档 | Bean | 模型 | 用途 |
|---|---|---|---|
| 流式档 | StreamingChatModel | glm-4.7 | 前台主对话代理/法学助手角色扮演 |
| 同步档 | ChatModel @Primary | glm-4.7 | 内部结构化 agent（需求澄清双官/机会观察器/卡片装配器/法学出题判卷/PRD 格式化器） |
| 路由档 | ChatModel "routeChatModel" | glm-4-flash | 意图路由等轻量分类 |

调用规范：
- 全部经 langchain4j 官方社区客户端 `ZhipuAiChatModel`/`ZhipuAiStreamingChatModel`（builder 全局注入 `thinking:{"type":"disabled"}`——4.7 是思考型，不关首延迟 30s+；maxRetries=2 限流退避重试；流式已输出后断流不重试）
- 结构化 agent 用 langchain4j `AiServices.create(接口.class, chatModel)`：人设写接口 @SystemMessage、出参用 record（JSON 绑定框架内置），由各域 *AgentFactory 双检锁懒装配；业务类保留失败 try-catch 兜底；长 prompt 负面指令多的场景禁用 flash 档（指令遵循差）
- 用量打标：调用前 `LlmUsage.set("scene")` + finally clear；scene 清单：`chat`（主聊流式兜底）/`route`/`extractor`/`counsel`/`quiz_gen`/`grade`/`sync`，forge 岗用 `forge.<岗名>` 前缀（varchar(32)）
- 超时：模型调用由 `ai.model.timeout-seconds` 控制；embedding 批量 16 条/批 + 限速退避

## 7. 配置与密钥外置约定

- 密钥与连接串一律环境变量注入，禁止入仓库：`MENGX_LLM_API_KEY`（智谱）、`MENGX_LLM_EMBEDDING_KEY`（千问）、`MENGX_JWT_SECRET`、`MENGX_DB_URL`/`MENGX_DB_USER`/`MENGX_DB_PASS`
- 本地开发：`application-local.yml`（不入库）+ `local` profile
- 管理端口 9913 只绑 127.0.0.1（Prometheus 指标/健康检查，公网不可达）
- 部署：推 main 触发 GitHub Actions → 构建 jar → scp 服务器 → systemd 重启（`deploy/` 目录）
