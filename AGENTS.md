# Codebase Agent 前后端核心改造文档（给 Codex 使用）

## 0. 当前目标

时间有限，不建议把项目继续扩展成“万能 Agent 平台”。当前最核心目标是：

> 将现有任务驱动型 Agent 工作台，最小改造成一个面向代码仓库理解、Bug 定位与修复建议生成的 Codebase Agent。

这份文档只保留最核心、最能支撑面试追问的改造内容。后续优化项单独放在最后。

---

## 1. 项目最终定位

### 1.1 一句话定位

本项目是一个面向代码仓库理解、Bug 定位与研发任务推进的任务驱动型 AI Agent 工作台。

它不是聊天机器人，而是围绕一个研发任务完成：

```text
Repo 接入
  -> 代码索引
  -> 任务创建
  -> Planner 拆解
  -> CodeSearchAgent 检索代码证据
  -> DiagnosisAgent 分析原因
  -> ReviewerAgent 复审证据
  -> Artifact 交付
  -> Follow-up 继续推进
```

### 1.2 面试表达

```text
我做的是一个 Codebase Agent，主要解决开发者在代码仓库中理解模块、定位 Bug、生成修复建议效率低的问题。

系统不是简单把问题丢给大模型，而是先把 GitHub 仓库索引成代码知识库，再通过任务驱动的方式让 Planner 拆解步骤，由 CodeSearchAgent 检索代码证据，DiagnosisAgent 分析 root cause，ReviewerAgent 检查输出是否有证据支撑，最后以 Artifact 形式交付 Root Cause Report 或 Patch Plan。
```

---

## 2. 为什么选这个业务场景

选择“代码仓库理解 + Bug 定位”的原因：

1. 数据源明确：GitHub repo。
2. 任务目标明确：理解模块、定位异常、生成修复建议。
3. 天然需要 RAG：需要从真实代码中检索证据。
4. 天然需要 Agent：Bug 定位是多步骤任务，不适合单轮问答。
5. 容易做评估：可以评估检索是否命中、答案是否有代码证据、root cause 是否正确。
6. 能回答面经高频问题：RAG、memory、上下文压缩、多 Agent、失败恢复、评估指标。

不建议继续讲“通用 Agent 平台”，因为业务边界太宽，面试官会追问：用户是谁、数据从哪里来、指标怎么定义、为什么一定需要 Agent。

---

## 3. 时间有限时的最小可演示闭环

先完成下面这个闭环即可，不要一开始做所有功能：

```text
1. 添加 GitHub repo
2. clone repo 到本地 workspace
3. 扫描代码文件
4. 生成 code chunk
5. 建立简单检索能力：关键词检索优先，向量检索可作为增强
6. 创建 Bug 定位任务时绑定 repo
7. Planner 生成固定 3~4 个步骤
8. CodeSearchAgent 检索相关代码
9. DiagnosisAgent 基于代码证据分析可能原因
10. ReviewerAgent 检查是否有 evidence_refs
11. 输出 Root Cause Report artifact
12. 前端展示 Agent Timeline + Evidence Panel + Artifact
```

---

## 4. 后端核心改造

### 4.1 Repo 管理模块

#### 目标

支持用户输入 GitHub repo URL，系统 clone 到本地，并记录索引状态。

#### 数据表：repo

```sql
CREATE TABLE repo (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    url TEXT NOT NULL,
    branch VARCHAR(128),
    local_path TEXT NOT NULL,
    indexed_status VARCHAR(32) NOT NULL,
    file_count INT DEFAULT 0,
    chunk_count INT DEFAULT 0,
    last_indexed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
```

indexed_status：

```text
PENDING
CLONED
INDEXING
INDEXED
FAILED
```

#### API

```http
POST /api/repos
GET /api/repos
POST /api/repos/{repoId}/index
```

`POST /api/repos` Request：

```json
{
  "url": "https://github.com/example/project.git",
  "branch": "main"
}
```

Response：

```json
{
  "repoId": "repo_xxx",
  "name": "project",
  "status": "CLONED"
}
```

#### 实现建议

原型阶段可以直接调用 git CLI：

```bash
git clone --depth=1 --branch <branch> <url> workspace/repos/<repoId>
```

必要限制：

```text
1. repo URL 必须校验，只允许 https://github.com/ 开头
2. clone 设置超时时间
3. 禁止把用户输入直接拼接 shell 命令
4. 限制 repo 大小或文件数量
5. 忽略 .git、node_modules、target、dist、build 等目录
```

---

### 4.2 代码扫描与 Chunk

#### 支持文件类型

```text
.java
.py
.ts
.tsx
.js
.jsx
.go
.md
.yml
.yaml
.sql
.properties
.xml
.json
```

#### 忽略目录

```text
.git
node_modules
target
dist
build
.idea
.vscode
coverage
```

#### 数据表：code_chunk

```sql
CREATE TABLE code_chunk (
    id VARCHAR(64) PRIMARY KEY,
    repo_id VARCHAR(64) NOT NULL,
    file_path TEXT NOT NULL,
    language VARCHAR(64),
    symbol_name VARCHAR(255),
    chunk_type VARCHAR(64),
    start_line INT,
    end_line INT,
    content TEXT NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    token_count INT,
    created_at TIMESTAMP NOT NULL
);
```

chunk_type：

```text
FILE
CLASS
FUNCTION
CONFIG
DOC
```

#### 最小 Chunk 策略

```text
1. Java / TS / JS / Python：优先用正则按 class / function / method 切
2. Markdown：按标题切
3. 配置文件：按整个文件切
4. 如果 chunk 超过阈值，再按行数二次切分
```

面试解释：

```text
我没有简单按固定 token 切代码，因为代码语义通常以函数、类为单位。固定 token 可能会把函数切断，导致检索结果缺少上下文。初版用正则按结构切，后续可以升级到 Tree-sitter / AST。
```

---

### 4.3 检索能力：先做关键词，后做向量

时间有限时建议优先顺序：

```text
P0：关键词检索 / grep / SQL LIKE
P1：简单 BM25
P2：Embedding + 向量检索
P3：Rerank
```

面试时说明：

```text
当前版本先实现了关键词和文件级检索，能保证代码符号、异常名、接口路径这类强特征可命中。向量检索和 rerank 是下一阶段优化，用来提升自然语言问题到代码片段的语义召回。
```

#### API：代码搜索

```http
POST /api/repos/{repoId}/search
```

Request：

```json
{
  "query": "登录接口 500 token 校验异常",
  "topK": 10
}
```

Response：

```json
{
  "results": [
    {
      "chunkId": "chunk_xxx",
      "filePath": "src/main/java/.../LoginController.java",
      "symbolName": "login",
      "startLine": 35,
      "endLine": 78,
      "score": 0.86,
      "reason": "命中 login、token、500 等关键词",
      "contentPreview": "..."
    }
  ]
}
```

#### 检索失败 fallback

```text
1. query rewrite：把用户问题改写成关键词列表
2. 扩大 topK
3. 精确 grep：类名、函数名、异常名、接口路径
4. 如果仍失败，返回 missing_info 给 Reviewer
```

---

### 4.4 Task 绑定 Repo 与业务类型

Task 增加字段：

```text
repo_id
business_type
```

business_type：

```text
CODE_UNDERSTANDING
BUG_DIAGNOSIS
PATCH_SUGGESTION
TEST_GENERATION
```

创建任务 API 增强：

```http
POST /api/tasks
```

Request：

```json
{
  "repoId": "repo_xxx",
  "businessType": "BUG_DIAGNOSIS",
  "goal": "登录接口偶尔返回 500，帮我定位可能原因"
}
```

---

### 4.5 EvidenceRef：证据引用结构

```json
{
  "repoId": "repo_xxx",
  "chunkId": "chunk_xxx",
  "filePath": "src/main/java/.../AuthFilter.java",
  "startLine": 42,
  "endLine": 58,
  "score": 0.86,
  "reason": "这里 token 为空时没有兜底处理"
}
```

用途：

```text
1. 给 DiagnosisAgent 提供代码证据
2. 给 ReviewerAgent 判断结论是否 grounded
3. 给前端 Evidence Panel 展示
4. 给最终 Artifact 引用
```

面试解释：

```text
我要求 Agent 的关键结论必须绑定 EvidenceRef，也就是文件路径、行号、chunk 和命中原因。这样可以降低 hallucination，Reviewer 也能根据 evidence_refs 判断结果是否可交付。
```

---

### 4.6 受控多角色 Agent 协作

核心原则：不要做开放式多 Agent 自由聊天，做 Orchestrator 驱动的受控多角色协作。

第一版只需要 4 个 Agent：

```text
PlannerAgent
CodeSearchAgent
DiagnosisAgent
ReviewerAgent
```

PatchAgent 和 DeliveryAgent 可以后续优化。

#### Agent 接口

```java
public interface Agent {
    String name();
    AgentResult run(AgentContext context);
}
```

#### AgentContext

```java
public class AgentContext {
    private String taskId;
    private String stepId;
    private String repoId;
    private String userGoal;
    private List<StepSummary> previousSteps;
    private List<EvidenceRef> evidenceRefs;
    private Map<String, Object> memory;
}
```

#### AgentResult

```java
public class AgentResult {
    private boolean success;
    private String summary;
    private Map<String, Object> structuredOutput;
    private List<EvidenceRef> evidenceRefs;
    private double confidence;
    private NextAction nextAction;
    private String failureReason;
}
```

#### NextAction

```java
public enum NextAction {
    CONTINUE,
    RETRY,
    REPLAN,
    NEED_HUMAN_APPROVAL,
    DELIVER,
    FAIL
}
```

#### PlannerAgent

职责：

```text
1. 理解用户目标
2. 生成固定任务步骤
3. 为每个步骤分配 Agent
```

Bug 定位任务默认步骤：

```json
[
  {
    "name": "检索相关代码",
    "assignedAgent": "CodeSearchAgent"
  },
  {
    "name": "分析可能原因",
    "assignedAgent": "DiagnosisAgent"
  },
  {
    "name": "复审证据与结论",
    "assignedAgent": "ReviewerAgent"
  },
  {
    "name": "生成最终交付物",
    "assignedAgent": "DeliveryAgent"
  }
]
```

#### CodeSearchAgent

职责：

```text
1. 根据 userGoal 生成搜索关键词
2. 调用 /api/repos/{repoId}/search 或 CodeSearchService
3. 返回 evidence_refs
4. 如果没有结果，返回 RETRY 或 REPLAN
```

输出示例：

```json
{
  "success": true,
  "summary": "命中 5 个登录和 token 校验相关代码片段",
  "evidenceRefs": [
    {
      "filePath": "src/main/java/.../AuthFilter.java",
      "startLine": 42,
      "endLine": 58,
      "reason": "命中 token validation"
    }
  ],
  "confidence": 0.82,
  "nextAction": "CONTINUE"
}
```

#### DiagnosisAgent

职责：

```text
1. 基于用户问题和 evidence_refs 分析 root cause
2. 输出假设、证据和置信度
3. 判断是否需要更多检索
```

输出示例：

```json
{
  "hypotheses": [
    {
      "cause": "token 为空时未做兜底处理，可能导致空指针或未捕获异常",
      "evidence": ["AuthFilter.java:42-58"],
      "confidence": 0.78
    }
  ],
  "needMoreSearch": false
}
```

#### ReviewerAgent

职责：

```text
1. 检查 DiagnosisAgent 的结论是否引用 evidence_refs
2. 检查是否回答用户问题
3. 检查是否存在无证据猜测
4. 决定是否通过、retry、replan 或人工确认
```

最小规则：

```text
1. 没有 evidence_refs：不通过，REPLAN
2. evidence_refs 少于 1：不通过，RETRY
3. 结论没有引用文件路径 / 行号：不通过，REPLAN
4. 有证据、有原因、有风险提示：通过，DELIVER
```

---

### 4.7 Orchestrator 状态机

目标：由 Orchestrator 控制 Agent 调度，而不是让多个 Agent 自由聊天。

伪代码：

```java
while (!task.isTerminal()) {
    Step step = task.nextRunnableStep();
    Agent agent = agentRegistry.get(step.getAssignedAgent());

    AgentContext context = contextBuilder.build(task, step);
    AgentResult result = agent.run(context);

    step.applyResult(result);
    eventStore.append(task.getId(), step.getId(), result);

    switch (result.getNextAction()) {
        case CONTINUE:
            task.moveToNextStep();
            break;
        case RETRY:
            retryStep(step);
            break;
        case REPLAN:
            replanTask(task, result);
            break;
        case NEED_HUMAN_APPROVAL:
            pauseForApproval(task);
            break;
        case DELIVER:
            deliver(task);
            break;
        case FAIL:
            failTask(task, result.getFailureReason());
            break;
    }
}
```

必须加的限制：

```text
step.maxRetry
任务级 maxRound
tokenBudget，可先只做字段记录
toolCallBudget，可先只做字段记录
```

面试解释：

```text
我没有做开放式多 Agent，而是做状态机驱动的受控协作。因为代码任务更关注稳定性、证据链和可评估性。开放式多 Agent 容易循环、上下文膨胀、成本失控，也很难评估每个环节。
```

---

## 5. Memory / Context 最小设计

### 5.1 Memory 分层

```text
短期记忆：当前 task 的 step history、evidence_refs、agent summaries
长期记忆：repo code chunks、历史任务 artifact、历史 bug case
```

### 5.2 当前必须实现的最小版本

```text
1. task step history 存数据库
2. 每个 AgentResult 存 summary
3. evidence_refs 存在 step 或 artifact 中
4. ContextBuilder 给下游 Agent 只传：用户目标 + 上一步 summary + evidence_refs + 必要代码片段
```

### 5.3 上下文压缩策略

```text
1. 用户原始问题不压缩
2. evidence_refs 不压缩
3. 代码片段只传 topK
4. 上游 LLM 输出只传 summary，不传完整文本
5. 超过 tokenBudget 时，优先丢弃低分 chunk 和冗余 agent 输出
```

面试表达：

```text
我的上下文不是把完整历史都塞给模型，而是通过 ContextBuilder 组装。用户目标和 evidence_refs 优先级最高，LLM 中间输出会压缩成 summary，因为这部分冗余最大，也最容易造成上下文污染。
```

---

## 6. Artifact 最小增强

新增 artifact 类型：

```text
ROOT_CAUSE_REPORT
CODE_EVIDENCE
PATCH_PLAN
TEST_SUGGESTION
MODULE_SUMMARY
```

时间有限时至少实现：

```text
ROOT_CAUSE_REPORT
CODE_EVIDENCE
```

Root Cause Report 结构：

```json
{
  "summary": "登录接口 500 可能与 token 校验异常有关",
  "hypotheses": [
    {
      "cause": "token 为空未兜底",
      "evidence": ["AuthFilter.java:42-58"],
      "confidence": 0.78
    }
  ],
  "risk": "需要结合运行日志进一步确认",
  "nextSteps": ["补充 token 为空的单测", "检查异常处理链路"]
}
```

---

## 7. 前端核心改造

前端设计目标：

> 不要让它看起来像聊天页，要让它看起来像“代码任务作战室”。

核心展示四件事：

```text
Repo 数据源
Agent 执行过程
代码证据链
最终交付物
```

### 7.1 页面路由

最小需要：

```text
/                  Dashboard
/repos             仓库列表
/tasks/new         创建任务
/tasks/:taskId     任务详情
```

### 7.2 Dashboard

文案：

```text
Codebase Agent Workspace
面向代码仓库理解、Bug 定位与修复建议生成的任务驱动型 AI Agent 工作台
```

指标卡片：

```text
Repos
Indexed Files
Code Chunks
Completed Tasks
Reviewer Blocks
Artifacts
```

### 7.3 Repositories 页面

功能：

```text
1. 输入 GitHub repo URL
2. 输入 branch
3. Clone Repo
4. Start Indexing
5. 显示 repo 列表和索引状态
```

Repo 卡片字段：

```text
repo name
url
branch
indexed_status
file_count
chunk_count
last_indexed_at
```

按钮：

```text
Index / Re-index
Create Task
```

### 7.4 创建任务页

必须绑定 repo。

字段：

```text
选择 repo
任务类型
任务描述
启用 Reviewer
```

任务类型：

```text
代码理解
Bug 定位
修复建议
测试生成
```

快捷模板：

```text
解释登录模块的调用链
定位登录接口 500 的可能原因
分析某个异常在哪里抛出
给某个 Bug 生成修复建议
为某个函数生成测试建议
```

### 7.5 任务详情页：最重要

建议三栏布局：

```text
┌─────────────────────────────────────────────────────┐
│ Task Header：任务名 / Repo / 状态 / Run / Retry       │
├───────────────┬────────────────────┬────────────────┤
│ Agent Timeline│ Step Detail         │ Evidence Panel  │
│               │                    │ Artifact Panel  │
└───────────────┴────────────────────┴────────────────┘
```

左侧 Agent Timeline 展示：

```text
PlannerAgent
CodeSearchAgent
DiagnosisAgent
ReviewerAgent
Delivery
```

每个 step 显示：

```text
status
assignedAgent
duration
confidence
nextAction
retryCount
```

中间 Step Detail 展示：

```text
Step 目标
Assigned Agent
输入上下文摘要
工具调用记录
Agent 输出 summary
failureReason
nextAction
```

右侧 Evidence Panel 展示：

```text
filePath
startLine-endLine
score
reason
codePreview
```

右侧 Artifact Panel 展示：

```text
ROOT_CAUSE_REPORT
CODE_EVIDENCE
PATCH_PLAN，可后续
TEST_SUGGESTION，可后续
```

---

## 8. 必须能回答的面经问题与项目对应点

### 8.1 你的业务场景是什么？

回答：

```text
代码仓库理解与 Bug 定位。用户选择 GitHub repo 后创建研发任务，系统检索真实代码证据，分析 root cause，并输出结构化 artifact。
```

项目支撑：

```text
repo 表
code_chunk 表
Task.repo_id
EvidenceRef
Root Cause Report artifact
```

### 8.2 RAG 怎么设计？

回答：

```text
我把 repo 扫描成 code chunks，初版按 class / function / file 切。检索先做关键词和符号匹配，后续扩展向量召回和 rerank。代码场景不能只依赖向量，因为函数名、异常名、接口路径这类关键词非常重要。
```

项目支撑：

```text
RepoFileScanner
CodeChunker
CodeSearchService
/api/repos/{repoId}/search
```

### 8.3 检索失败怎么办？

回答：

```text
先 query rewrite，再扩大 topK，然后 fallback 到关键词 / grep。如果仍然缺少证据，CodeSearchAgent 会返回 missing_info，Reviewer 不允许无证据结论交付，会触发 retry / replan / human approval。
```

项目支撑：

```text
CodeSearchAgent
NextAction.RETRY
NextAction.REPLAN
ReviewerAgent
EvidenceRef
```

### 8.4 Memory 怎么设计？

回答：

```text
分短期和长期。短期 memory 是当前 task 的 step history、Agent summary、evidence_refs；长期 memory 是 repo code chunks 和历史 artifact。下游 Agent 不读取完整历史，而是由 ContextBuilder 组装必要上下文。
```

项目支撑：

```text
Step history
AgentResult.summary
EvidenceRef
code_chunk
Artifact
ContextBuilder
```

### 8.5 上下文过长怎么办？

回答：

```text
用户原始问题和 evidence_refs 优先保留；代码片段只传 topK；上游 LLM 输出压缩成 summary；超过预算时优先丢弃低分 chunk 和冗余中间输出。
```

项目支撑：

```text
tokenBudget 字段
ContextBuilder
summary memory
topK evidence selection
```

### 8.6 多 Agent 怎么协作？

回答：

```text
我做的是受控多角色 Agent 协作，不是开放式自由对话。Orchestrator 状态机调度 Planner、CodeSearch、Diagnosis、Reviewer。每个 Agent 有明确职责，输出 AgentResult，由 nextAction 决定继续、重试、重规划或交付。
```

项目支撑：

```text
Agent interface
AgentContext
AgentResult
NextAction
Orchestrator
Step.assigned_agent
```

### 8.7 为什么不做真正开放式多 Agent？

回答：

```text
开放式多 Agent 更适合研究型探索任务，但代码 Bug 定位更关注稳定性、证据链和可评估性。开放式协作容易出现循环调用、上下文膨胀、结果冲突、成本不可控，也很难定位失败环节。所以我选择 Orchestrator 驱动的受控协作。
```

项目支撑：

```text
状态机调度
step.maxRetry
task.maxRound
Reviewer gate
EvidenceRef
```

### 8.8 怎么评估效果？

回答：

```text
我设计了小规模离线评估集，分模块理解、函数定位、Bug root cause、Patch 建议几类。对比 LLM only、RAG only、Agent + RAG + Reviewer，指标包括 Recall@K、Evidence Grounding Rate、Root Cause Correctness、End-to-End Success Rate。
```

第一版可以先做手工表格或 JSON 文件，不一定要完整后台。

---

## 9. Codex 最小任务拆分

按这个顺序交给 Codex，优先完成 P0。

### P0-1：新增 Repo 管理

```text
新增 repo 实体、repo 表、RepoController、RepoService。
支持 POST /api/repos clone GitHub repo 到 workspace/repos/{repoId}。
支持 GET /api/repos 查询列表。
```

验收：

```text
能添加 repo
能看到 repo 状态
repo 本地路径存在
```

### P0-2：代码扫描与 Chunk

```text
实现 RepoFileScanner 和 CodeChunker。
扫描支持的代码文件，过滤无关目录，生成 code_chunk 记录。
```

验收：

```text
能统计 file_count
能生成 chunk_count
chunk 包含 filePath、startLine、endLine、content、symbolName
```

### P0-3：代码搜索接口

```text
实现 CodeSearchService 和 POST /api/repos/{repoId}/search。
先用关键词 / LIKE / grep 检索 code_chunk，返回 topK。
```

验收：

```text
搜索 login / token / exception 可以返回相关文件和代码片段。
```

### P0-4：Task 绑定 Repo

```text
扩展 Task，增加 repoId 和 businessType。
创建任务时可选择 repo 和任务类型。
```

验收：

```text
任务详情能展示 repo 信息和业务类型。
```

### P0-5：EvidenceRef 和 Artifact 增强

```text
新增 EvidenceRef 结构。
Step / AgentResult / Artifact 支持 evidenceRefs。
新增 ROOT_CAUSE_REPORT 和 CODE_EVIDENCE artifact 类型。
```

验收：

```text
最终 artifact 能展示文件路径、行号、代码证据和原因。
```

### P0-6：实现最小多角色 Agent

```text
新增 Agent、AgentContext、AgentResult、NextAction。
实现 PlannerAgent、CodeSearchAgent、DiagnosisAgent、ReviewerAgent。
Orchestrator 根据 step.assignedAgent 调用 Agent。
```

验收：

```text
Bug 定位任务能自动执行：Planner -> CodeSearch -> Diagnosis -> Reviewer -> Delivery。
Reviewer 能阻止没有 evidenceRefs 的结论直接交付。
```

### P0-7：前端 Repos + Task Detail 改造

```text
新增 Repositories 页面。
创建任务页增加 repo 和 businessType。
任务详情页改成三栏：Agent Timeline、Step Detail、Evidence/Artifact Panel。
```

验收：

```text
前端能展示 repo 索引状态、任务执行步骤、代码证据和最终 Root Cause Report。
```

---

## 10. 后续优化项，不放进第一期

### 10.1 向量检索 + Rerank

```text
EmbeddingService
pgvector / Chroma / Milvus
Vector Search
BM25 + Vector 双路召回
Cross-encoder rerank
```

### 10.2 AST 级 Chunk

```text
Tree-sitter
JavaParser
函数调用链分析
import / dependency graph
```

### 10.3 PatchAgent

```text
生成 patch diff
修改文件预览
风险分析
单测生成
```

### 10.4 Eval 面板

```text
eval_case
eval_run
Recall@K
Evidence Grounding Rate
End-to-End Success Rate
Latency / Token Cost
```

### 10.5 GitHub 深度集成

```text
GitHub token
私有仓库
Issue 读取
PR 创建
Commit diff 分析
```

### 10.6 更高级多 Agent

```text
Agent Registry
Capability-based routing
局部开放式协作
Agent debate
Simulation
```

注意：不要在面试里说已经完成这些，只说是后续优化方向。

---

## 11. 最终交付标准

第一期完成后，系统至少能演示这个流程：

```text
1. 添加一个 GitHub repo
2. 点击 Index，生成 code chunks
3. 创建任务：登录接口偶尔 500，帮我定位原因
4. Planner 生成步骤
5. CodeSearchAgent 检索 login / auth / token 相关代码
6. DiagnosisAgent 输出可能 root cause
7. ReviewerAgent 检查 evidence_refs
8. 输出 Root Cause Report artifact
9. 前端展示代码证据：文件路径、行号、代码片段、原因
```

只要这条链路跑通，你就能回答面经里最关键的问题：

```text
业务是什么？
数据从哪来？
为什么需要 Agent？
RAG 怎么做？
检索失败怎么办？
Memory 怎么设计？
上下文怎么压缩？
多 Agent 怎么协作？
为什么不做开放式多 Agent？
怎么评估？
```

---

## 12. 给 Codex 的总提示词

可以直接复制给 Codex：

```text
请基于当前项目，将通用任务驱动 Agent 工作台最小改造成 Codebase Agent。

第一期目标不是做完整商业产品，而是打通一个可面试展示的闭环：GitHub repo 接入 -> 代码扫描与 chunk -> 代码搜索 -> 任务绑定 repo -> Planner / CodeSearchAgent / DiagnosisAgent / ReviewerAgent 受控协作 -> Root Cause Report artifact -> 前端展示 Agent Timeline、Evidence Panel 和 Artifact。

优先完成 P0：
1. Repo 管理：POST /api/repos clone GitHub 仓库，GET /api/repos 查询。
2. 代码扫描与 chunk：扫描 .java/.py/.ts/.js/.md/.yml 等文件，过滤 .git/node_modules/target/dist/build。
3. 搜索接口：POST /api/repos/{repoId}/search，先用关键词检索 code_chunk 返回 topK。
4. Task 扩展：新增 repoId 和 businessType。
5. EvidenceRef：所有关键结论必须能引用 filePath、startLine、endLine、chunkId、reason。
6. 多角色 Agent：实现 Agent、AgentContext、AgentResult、NextAction，新增 PlannerAgent、CodeSearchAgent、DiagnosisAgent、ReviewerAgent。
7. Reviewer 规则：没有 evidenceRefs 的诊断结果不能直接交付，必须 RETRY / REPLAN。
8. Artifact：新增 ROOT_CAUSE_REPORT 和 CODE_EVIDENCE。
9. 前端：新增 Repositories 页面；创建任务时选择 repo 和任务类型；任务详情页改为三栏布局：Agent Timeline、Step Detail、Evidence/Artifact Panel。

不要优先做开放式多 Agent、PR 自动提交、复杂评估面板、AST chunk、向量库和 rerank。这些放到后续优化。
```
