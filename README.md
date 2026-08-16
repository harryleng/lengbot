<h1 align="center">LengBot</h1>

<p align="center">
  <strong>基于 Spring Cloud Alibaba + AgentScope Java 的企业级 AI Agent 平台</strong>
</p>

<p align="center">
  <a href="#快速启动">快速启动</a> ·
  <a href="#仓库结构">仓库结构</a> ·
  <a href="#api-概览">API 概览</a> ·
  <a href="http://localhost:8082/swagger-ui.html">Swagger UI</a>
</p>

---

## 项目介绍

LengBot 是基于 [AgentScope Java](https://github.com/agentscope-ai/agentscope) + [Spring Cloud Alibaba](https://sca.aliyun.com/) 的企业级 AI Agent 平台，为 Java 开发者提供 Agent 构建、Workflow 编排、RAG、Tool/MCP/Skill、SubAgent 协作、评测和可观测能力。

平台采用"单体部署、模块化边界、渐进式演进"的架构：先以清晰的模块契约解决复杂度，再在有明确容量或独立发布需求时考虑分布式拆分。后端默认使用 Nacos 作为注册与配置中心（本地开发可关闭），通过 AgentScope Java 提供 ReAct Agent 内核与多模型对接能力。

## 核心能力

| 能力域 | 已支持能力 |
| --- | --- |
| Agent 与会话 | Agent 版本管理、流式对话、会话管理、消息收藏/反馈/搜索、资源提及、长期记忆、附件与产物管理 |
| Workflow | 可视化 DAG、条件/分类/检索/工具/脚本/人工确认等节点、嵌套工作流、变量引用、节点韧性、调试与回放 |
| Tool 与扩展 | 内置/API 工具、JSON Schema、工具调用记录、工具限流、MCP（stdio/SSE/Streamable HTTP）、Skill、SubAgent |
| RAG 与图谱 | 文档解析、OCR、分块、向量与关键词检索、Rerank、QA Pair、知识图谱、语义搜索和 RAG 评测 |
| 模型与 Prompt | OpenAI 兼容模型、DashScope、DeepSeek、Ollama 等提供商，动态模型路由与 Prompt 版本管理 |
| 运营与可观测 | LLM Trace、工具调用记录、实时日志、Dashboard、Token 用量、任务中心与失败任务治理 |
| 安全与权限 | Sa-Token 认证、角色权限、API Key 作用域/配额、敏感词拦截和工具调用安全校验 |

## 系统架构

```text
Vue 3 + Ant Design Vue
        │ HTTP / SSE
        ▼
lengbot-server                    HTTP 入口、配置、拦截器
        ▼
lengbot-agent                     Agent / Chat 运行时（AgentScope HarnessAgent）
        ▼
lengbot-workflow                  Workflow DSL、图校验、节点执行
        ▼
lengbot-tool                      Tool / MCP / Skill / SubAgent
        ▼
lengbot-knowledge                 RAG、文档、图谱、评测
        ▼
lengbot-ai                        模型工厂、Prompt、LLM Trace
        ▼
lengbot-platform                  用户、任务、系统配置、API Key、Dashboard
        ▼
lengbot-framework → lengbot-common
```

依赖方向固定为：

```text
common → framework → platform → ai → knowledge → tool → workflow → agent → server
```

下层不依赖上层；跨模块通过接口或 Port 通信，不直接依赖其他模块的实现类。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.3.6、Spring Cloud Alibaba 2023.0.1.0（Nacos）、AgentScope Java 2.0.1、MyBatis-Plus 3.5.9 |
| 前端 | Vue 3、Vite 6、Ant Design Vue 4、Vue Flow、Pinia、pnpm、TailwindCSS 4 |
| 数据 | PostgreSQL 15 + pgvector、Redis 7、Milvus、Neo4j、MinIO |
| 协议与治理 | SSE、MCP、Sa-Token 1.39、SpringDoc OpenAPI 2.6、Redis Stream |
| 文档解析 | Apache Tika 3.1、Apache PDFBox 3.0 |

## 快速启动

环境要求：JDK 17、Maven 3.9+、Node.js 20+、pnpm 9+、PostgreSQL 15 + pgvector、Redis 7，以及至少一个模型 API Key。

```bash
# 1. 初始化数据库
psql -U postgres -h localhost -f sql/init.sql

# 2. 设置模型 API Key（PowerShell 示例）
$env:DASHSCOPE_API_KEY = "sk-xxx"
# 或使用 OpenAI 兼容接口
$env:OPENAI_API_KEY = "sk-xxx"

# 3. 构建并启动后端
mvn clean install -DskipTests
cd lengbot-server
mvn spring-boot:run

# 4. 启动前端
cd ../lengbot-ui
pnpm install
pnpm dev
```

- 前端默认地址：`http://localhost:5174`
- 后端地址：`http://localhost:8082`
- Swagger UI：`http://localhost:8082/swagger-ui.html`

> 本地开发无需启动 Nacos，`application.yml` 中已默认关闭 Nacos 注册与配置中心。生产环境可通过环境变量 `NACOS_ADDR` 和 `NACOS_NAMESPACE` 启用。

## 数据库

| 场景 | 执行方式 |
| --- | --- |
| 新部署 | 执行 `sql/init.sql`，包含完整的表结构和初始数据 |
| 后续开发 | 新迁移放在 `sql/`，命名为 `YYYY-MM-DD-NNN.sql` |

数据库默认名为 `lightbot`，可通过环境变量 `LENGBOT_DB_NAME` 修改。需要预先安装 pgvector 扩展用于向量存储。

## API 概览

服务启动后可以通过 Swagger UI 查看实时 OpenAPI 定义。主要资源前缀如下：

| 资源 | 前缀 |
| --- | --- |
| 认证与用户 | `/api/auth`、`/api/admin`、`/api/user/memories`、`/api/user/preferences` |
| Agent 与会话 | `/api/agents`、`/api/chat`、`/api/chat/sessions` |
| Workflow 与任务 | `/api/agents/{agentId}/workflow`、`/api/tasks` |
| 知识与图谱 | `/api/knowledge`、`/api/graph`、`/api/documents` |
| Tool 与扩展 | `/api/tools`、`/api/mcp-servers`、`/api/skills`、`/api/subagents` |
| 模型与 Prompt | `/api/model-providers`、`/api/models`、`/api/prompts` |
| 评测与观测 | `/api/eval/*`、`/api/observability`、`/api/logs`、`/api/dashboard` |

## 仓库结构

```text
lengbot/
├── lengbot-common/       # Result、枚举、公共类型、任务异常
├── lengbot-framework/    # Spring 配置、Redis/MinIO/CORS 等技术封装
├── lengbot-platform/     # 用户、任务、系统配置、API Key、Dashboard
├── lengbot-ai/           # 模型工厂、Prompt、LLM Trace
├── lengbot-knowledge/    # RAG、文档、图谱、评测
├── lengbot-tool/         # Tool、MCP、Skill、SubAgent
├── lengbot-workflow/     # Workflow DSL、节点处理器、图校验
├── lengbot-agent/        # Agent/Chat 运行时（AgentScope HarnessAgent）
├── lengbot-server/       # REST 入口与应用装配
├── lengbot-ui/           # Vue 3 前端
├── sql/                  # 数据库初始化脚本
├── docker/               # Docker 配置
└── pom.xml               # Maven 父 POM
```

## 开发与构建

```bash
# 后端构建
mvn clean install -DskipTests

# 前端检查与构建
cd lengbot-ui
pnpm lint:check
pnpm build
```

- 提交遵循 Conventional Commits，例如：`feat(workflow): 新增人工确认节点`。
- SQL 变更使用 `sql/YYYY-MM-DD-NNN.sql` 命名规范。
