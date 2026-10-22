# 点子铺（meng-x）

> 仙官「百晓仙君」坐镇的创意收集智能体——聊出来的点子炼成功能卡，好点子被真的做出来；内嵌法学助手，法律咨询与法考备考一站搞定。

## 这是什么

一个自我生长的产品：用户提的创意直接在本应用实现，形成「点子 → 功能卡 → 实现 → 新能力」的闭环。

- **仙官访谈**：与百晓仙君流式聊天（SSE 逐字渲染），聊透的点子自动炼成功能卡（四要素：谁在用/什么时机/要什么反应/没有它怎么办；应用级点子走营造图九格设计稿，双星锦囊逐轮共创）
- **功能卡看板**：新芽 → 确认 → 采纳/婉拒 → 已实现，状态流转 + 修为墙 + 导出施工文档
- **法学助手**：RAG 三路融合检索（编号正则/向量/pg_trgm），法条+历年真题（2002-2024，4000+ 道）双库支撑，引用徽章可溯源
- **法考刷题**：客观题（单选/多选/不定项轮换）试卷模式批量判分 + 主观题（案例分析）AI 判卷官逐采分点批改，用户画像弱点加权出题，学情汇报
- **每日一签**：当日幂等抽取

详细设计见 [docs/架构文档.md](docs/架构文档.md)。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 17 · Spring Boot 3.4 · Spring Security(JWT) |
| 前端 | React 18 · Vite，白灰简约风，手机/PC 自适应 |
| 数据库 | PostgreSQL + pgvector + pg_trgm |
| LLM | 智谱 GLM（三档分工：glm-4.7 前台流式/结构化 agent，glm-4-flash 意图路由） |
| 观测 | LLM 用量埋点 + Prometheus 指标 + traceId 日志全链路 |

## 目录结构

```
├── server/          # Spring Boot 后端（jar + systemd 部署）
├── web/             # React 前端
├── deploy/          # 部署脚本（nginx.conf / systemd service / 服务器初始化）
├── docs/            # 产品设计 / 架构文档 / 法学助手设计
├── scripts/         # CI 脚本
└── miniprogram/     # 小程序壳（已放弃，保留配置）
```

## 快速开始（本地开发）

前置：JDK 17、Node 18+、Docker（跑 PostgreSQL）、智谱 API Key。

```bash
# 1. 数据库（pgvector + pg_trgm 扩展）
docker run -d --name isor-pg -e POSTGRES_PASSWORD=postgres -p 5432:5432 pgvector/pgvector:pg16
docker exec isor-pg psql -U postgres -c "CREATE EXTENSION IF NOT EXISTS vector; CREATE EXTENSION IF NOT EXISTS pg_trgm; CREATE DATABASE mengx;"

# 2. 后端：server/src/main/resources/application-local.yml 填入密钥（不入库）
#    MENGX_LLM_API_KEY / MENGX_LLM_EMBEDDING_KEY
cd server && mvn spring-boot:run -Dspring-boot.run.profiles=local

# 3. 前端
cd web && npm install && npm run dev
```

登录即注册：任意手机号 + 密码，首次登录自动建号。

## 部署

推送 `main` 分支触发 GitHub Actions：构建 → scp → systemd 重启。服务器初始化见 [deploy/README.md](deploy/README.md)。

Nginx 反代 `/api` 时必须关闭缓冲（SSE 流式），配置见 [deploy/nginx.conf](deploy/nginx.conf)。

## 环境变量（生产）

| 变量 | 用途 |
|---|---|
| `MENGX_DB_PASSWORD` | PostgreSQL 密码 |
| `MENGX_LLM_API_KEY` | 智谱 API Key |
| `MENGX_LLM_EMBEDDING_KEY` | 千问 embedding API Key |
| `MENGX_JWT_SECRET` | JWT 签名密钥 |

## 数据备份

法条/真题等 RAG 数据可通过导入管道重建（`POST /api/law/import`），全量备份脚本见 `data/backup/`（不入库，本地留存）。

