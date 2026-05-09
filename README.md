# codebase-pilot

面向代码仓库理解、Bug 定位与修复建议生成的任务驱动型 AI Agent 工作台。

`codebase-pilot` 不是通用聊天机器人，而是一个围绕研发任务推进的 Codebase Agent。系统会接入 GitHub 仓库，完成代码扫描与检索，再通过受控多 Agent 协作输出带证据链的分析结论，适合演示代码理解、根因分析和任务编排能力。

## 项目定位

一句话描述：

> 一个面向代码仓库理解、Bug 定位与修复建议生成的任务驱动型 AI Agent Workspace。

核心链路：

```text
GitHub Repo 接入
  -> 代码扫描与 Chunk
  -> 代码检索
  -> 创建研发任务
  -> Planner 拆解步骤
  -> CodeSearchAgent 检索证据
  -> DiagnosisAgent 分析 root cause
  -> ReviewerAgent 审核 evidence grounding
  -> 生成 Artifact
```

## 这个项目解决什么问题

在实际开发中，下面这些事情通常都很耗时：

- 快速理解一个陌生仓库的模块边界
- 从自然语言问题定位到相关代码片段
- 对 Bug 原因形成可追溯的分析结论
- 将排查过程沉淀成结构化产物，而不是只停留在聊天记录里

`codebase-pilot` 的目标，就是把这类任务从“纯问答”升级成“任务驱动 + 证据驱动 + 可审查”的 Agent 工作流。

## 核心能力

- GitHub 仓库接入与本地工作区管理
- 代码文件扫描、Chunk 切分与索引
- 基于关键词 / 代码符号的代码搜索
- 任务绑定仓库与业务类型
- 受控多 Agent 编排，而不是开放式自由对话
- EvidenceRef 证据引用，要求关键结论绑定文件与行号
- Artifact 交付，包括 Root Cause Report、Code Evidence 等
- 前端任务作战室视图，展示 Timeline、Evidence Panel、Artifact

## Agent 设计

当前版本聚焦最小闭环，采用 Orchestrator 驱动的受控协作：

- `PlannerAgent`
  负责理解用户目标、拆解任务步骤、分配执行角色
- `CodeSearchAgent`
  负责根据任务目标生成搜索关键词并召回相关代码证据
- `DiagnosisAgent`
  负责基于 evidence refs 分析可能 root cause，形成假设和风险说明
- `ReviewerAgent`
  负责检查结论是否有证据支撑，阻止无依据结论直接交付

这样的设计比开放式多 Agent 更适合代码诊断场景，因为它更稳定、更容易评估，也更容易解释失败原因。

## Evidence-Driven 输出

项目要求关键结论必须尽量绑定证据引用，例如：

```json
{
  "repoId": "repo_xxx",
  "chunkId": "chunk_xxx",
  "filePath": "src/main/java/.../AuthFilter.java",
  "startLine": 42,
  "endLine": 58,
  "score": 0.86,
  "reason": "token 为空时没有兜底处理"
}
```

这套约束主要用于：

- 降低 hallucination
- 支撑 Reviewer 审核
- 支撑前端 Evidence Panel 展示
- 支撑最终 Artifact 的可追溯性

## 当前演示闭环

第一期可演示的最小闭环是：

1. 添加一个 GitHub repo
2. Clone 到本地 workspace
3. 扫描代码文件并生成 code chunks
4. 创建一个 Bug 定位任务并绑定 repo
5. Planner 生成步骤
6. CodeSearchAgent 检索登录 / token / exception 等相关代码
7. DiagnosisAgent 输出可能 root cause
8. ReviewerAgent 检查 evidence refs 是否充分
9. 输出 Root Cause Report artifact
10. 前端展示 Agent Timeline、Evidence Panel 和 Artifact

## 技术栈

后端：

- Java 21
- Spring Boot 3
- Spring AI
- LangChain4j
- Spring Data JPA
- Flyway
- H2 / PostgreSQL
- PGVector

前端：

- Vue 3
- Vite
- Vue Router
- Axios

## 项目结构

```text
codebase-pilot/
├─ src/                         # Java 后端
├─ codebase-pilot-frontend/    # Vue 前端
├─ docs/                        # 项目说明与设计文档
├─ scripts/                     # 本地脚本
├─ Dockerfile
└─ pom.xml
```

说明：

- 前端目录已经统一为 `codebase-pilot-frontend`
- 后端主工程名已经切换为 `codebase-pilot`

## 快速开始

### 1. 克隆仓库

```bash
git clone https://github.com/qyzd1314-ship-it/codebase-pilot.git
cd codebase-pilot
```

### 2. 启动后端

如果你使用 H2 本地模式：

```bash
./mvnw spring-boot:run
```

Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

默认应用名：

```text
codebase-pilot
```

H2 本地数据目录：

```text
./data/codebase-pilot
```

### 3. 启动前端

```bash
cd codebase-pilot-frontend
npm install
npm run dev
```

### 4. 常用文档

- [项目定位与改造路线](./docs/agent-project-positioning-summary.md)
- [数据库说明](./docs/database.md)
- [Postgres 操作说明](./docs/database-operations.md)
- [PGVector 说明](./docs/pgvector.md)


## 后续方向

当前仓库优先聚焦 P0 闭环，后续可继续演进：

- 向量检索与 rerank
- AST / Tree-sitter 级 chunk
- PatchAgent 与 patch diff 生成
- Eval 面板与离线评估集
- GitHub issue / PR 深度集成
- 更细粒度的 capability-based agent routing


