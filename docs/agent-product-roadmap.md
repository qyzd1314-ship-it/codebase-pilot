# Yu AI Agent 产品级改造路线图

## 1. 目标

将当前项目从“演示型聊天 + 工具调用”升级为“任务驱动、可审计、可恢复、可扩展”的 Agent 工作台。

目标产品形态：

- 用户输入的是任务目标，不只是聊天消息
- Agent 先产出计划，再执行步骤
- 高风险操作需要审批
- 所有步骤、工具调用、产物、失败原因都可追踪
- 任务支持暂停、重试、恢复
- 后续支持多 Agent 协作、自动化调度、插件化工具扩展

当前项目可复用资产：

- 后端已有聊天应用骨架：`LoveApp`
- 后端已有 Agent 循环骨架：`BaseAgent` / `ReActAgent` / `ToolCallAgent` / `YuManus`
- 后端已有工具注册机制：`ToolRegistration`
- 前端已有基础聊天工作区：`Home` / `LoveMaster` / `SuperAgent` / `ChatRoom`
- 已接入 SSE、Tool Calling、MCP、RAG 等方向能力

当前主要缺口：

- 没有任务模型，只有聊天入口
- 没有任务、步骤、工具调用、产物的持久化
- 没有审批流与权限边界
- 没有工作区隔离
- 没有 Planner / Worker / Reviewer 分层
- 前端没有任务工作台
- 没有评估、审计、回放能力

## 2. 产品级目标架构

建议将系统拆成 7 个模块：

1. `agent-api`
   - 对外提供任务、会话、审批、文件、执行日志接口
2. `agent-runtime`
   - 负责任务执行、状态流转、步骤调度、超时控制、失败恢复
3. `agent-planner`
   - 负责目标拆解、计划生成、步骤重规划
4. `agent-executor`
   - 负责执行工具调用、文件生成、网页检索、命令执行
5. `agent-memory`
   - 负责短期上下文、长期记忆、任务历史、用户偏好
6. `agent-safety`
   - 负责权限判断、审批拦截、敏感工具策略、工作区隔离
7. `agent-console`
   - 前端工作台，展示计划、执行过程、工具日志和交付物

逻辑流建议：

1. 用户提交任务目标
2. Planner 生成执行计划
3. 用户确认计划或系统自动确认低风险计划
4. Runtime 按步骤调度 Executor
5. Safety 层在高风险步骤前创建审批
6. 执行结果写入步骤日志、工具调用日志、产物表
7. Reviewer 判断是否达成目标
8. 输出最终交付结果

## 3. 分阶段路线图

### 阶段 1：从聊天升级到任务执行骨架

目标：

- 建立任务模型
- 建立步骤执行记录
- 前端从聊天页升级到任务页
- 给高风险工具加审批

交付结果：

- 用户可以创建任务
- 任务有状态、计划、步骤日志
- shell / 文件写入 / 下载等操作受控
- 前端能看到任务时间线和执行过程

### 阶段 2：稳定执行与可恢复

目标：

- 任务、步骤、工具调用、产物持久化
- 支持暂停、继续、取消、失败重试
- 引入 Planner / Worker / Reviewer 三角色

交付结果：

- Agent 执行链路更加稳定
- 失败后可恢复
- 用户可以接管、调整计划后继续执行

### 阶段 3：平台化与扩展能力

目标：

- 工作区隔离
- 多 Agent 协作
- 技能包 / 工具包扩展
- 自动化调度
- 评估与回放

交付结果：

- 可以演进成通用 Agent 平台
- 可以对接不同业务场景，而不止当前 demo

## 4. 数据库设计

建议优先引入 MySQL 或 PostgreSQL 作为业务主库。向量库继续保留 PGVector 方案。

### 4.1 核心表

#### `agent_task`

用于保存任务主信息。

字段建议：

- `id` bigint pk
- `task_no` varchar(64) unique
- `user_id` varchar(64)
- `title` varchar(255)
- `goal` text
- `task_type` varchar(64)
- `status` varchar(32)
- `priority` int
- `workspace_id` bigint
- `conversation_id` varchar(128)
- `current_step_seq` int
- `plan_summary` text
- `final_result` longtext
- `error_message` text
- `created_at` datetime
- `updated_at` datetime
- `started_at` datetime
- `finished_at` datetime

状态建议：

- `PENDING`
- `PLANNING`
- `WAITING_APPROVAL`
- `RUNNING`
- `BLOCKED`
- `SUCCEEDED`
- `FAILED`
- `CANCELLED`

#### `agent_task_step`

用于保存计划和执行步骤。

字段建议：

- `id` bigint pk
- `task_id` bigint
- `step_seq` int
- `step_title` varchar(255)
- `step_type` varchar(64)
- `status` varchar(32)
- `planner_output` text
- `executor_input` longtext
- `executor_output` longtext
- `retry_count` int
- `requires_approval` tinyint
- `started_at` datetime
- `finished_at` datetime
- `created_at` datetime
- `updated_at` datetime

#### `agent_tool_call`

用于保存每次工具调用细节。

字段建议：

- `id` bigint pk
- `task_id` bigint
- `step_id` bigint
- `tool_name` varchar(128)
- `tool_category` varchar(64)
- `risk_level` varchar(32)
- `request_payload` longtext
- `response_payload` longtext
- `success` tinyint
- `error_message` text
- `started_at` datetime
- `finished_at` datetime
- `created_at` datetime

#### `agent_artifact`

用于保存任务产物。

字段建议：

- `id` bigint pk
- `task_id` bigint
- `step_id` bigint
- `artifact_type` varchar(64)
- `name` varchar(255)
- `storage_path` varchar(512)
- `preview_content` longtext
- `mime_type` varchar(128)
- `size_bytes` bigint
- `created_at` datetime

#### `agent_approval`

用于保存审批项。

字段建议：

- `id` bigint pk
- `task_id` bigint
- `step_id` bigint
- `approval_type` varchar(64)
- `title` varchar(255)
- `reason` text
- `payload` longtext
- `status` varchar(32)
- `decision_by` varchar(64)
- `decision_note` text
- `created_at` datetime
- `decided_at` datetime

状态建议：

- `PENDING`
- `APPROVED`
- `REJECTED`
- `EXPIRED`

#### `agent_task_event`

用于前端时间线和审计回放。

字段建议：

- `id` bigint pk
- `task_id` bigint
- `step_id` bigint null
- `event_type` varchar(64)
- `event_level` varchar(32)
- `event_content` longtext
- `metadata_json` longtext
- `created_at` datetime

#### `agent_workspace`

用于定义 Agent 可操作的工作区。

字段建议：

- `id` bigint pk
- `name` varchar(255)
- `root_path` varchar(512)
- `write_enabled` tinyint
- `shell_enabled` tinyint
- `network_enabled` tinyint
- `created_at` datetime
- `updated_at` datetime

### 4.2 可选表

- `agent_user_profile`：用户偏好与记忆摘要
- `agent_skill`：技能包注册
- `agent_prompt_template`：系统提示词模板
- `agent_eval_record`：评估记录

## 5. 后端接口设计

建议新增 `/agent/tasks` 作为主入口，保留当前 `/ai` 兼容调试用途。

### 5.1 任务接口

#### `POST /agent/tasks`

创建任务。

请求体建议：

```json
{
  "title": "为我的项目生成改造方案",
  "goal": "分析当前项目并生成产品级 agent 改造路线图",
  "taskType": "GENERAL_AGENT",
  "workspaceId": 1,
  "autoApproveLowRisk": true
}
```

响应体建议：

```json
{
  "taskId": 1001,
  "status": "PENDING"
}
```

#### `GET /agent/tasks/{taskId}`

获取任务详情。

#### `GET /agent/tasks`

分页查询任务列表，支持状态筛选。

#### `POST /agent/tasks/{taskId}/plan`

生成或重生成计划。

#### `POST /agent/tasks/{taskId}/start`

启动任务执行。

#### `POST /agent/tasks/{taskId}/pause`

暂停任务。

#### `POST /agent/tasks/{taskId}/resume`

恢复任务。

#### `POST /agent/tasks/{taskId}/cancel`

取消任务。

#### `POST /agent/tasks/{taskId}/retry`

失败后重试任务或指定步骤。

### 5.2 步骤接口

#### `GET /agent/tasks/{taskId}/steps`

获取步骤列表。

#### `POST /agent/tasks/{taskId}/steps/{stepId}/retry`

重试某一步。

#### `POST /agent/tasks/{taskId}/steps/{stepId}/skip`

跳过某一步。

### 5.3 审批接口

#### `GET /agent/tasks/{taskId}/approvals`

获取审批列表。

#### `POST /agent/approvals/{approvalId}/approve`

通过审批。

#### `POST /agent/approvals/{approvalId}/reject`

拒绝审批。

### 5.4 事件流接口

#### `GET /agent/tasks/{taskId}/events/stream`

使用 SSE 推送任务事件：

- 任务状态变化
- 步骤开始/结束
- 工具调用开始/结束
- 审批创建
- 最终结果生成

### 5.5 产物接口

#### `GET /agent/tasks/{taskId}/artifacts`

查看产物列表。

#### `GET /agent/artifacts/{artifactId}/download`

下载产物。

## 6. 前端设计

建议保留首页，但重构为任务工作台入口，而不是两个简单聊天卡片。

### 6.1 页面规划

#### `TaskWorkbench`

主工作台页面。

布局建议：

- 左栏：任务列表
- 中栏：任务详情、计划、步骤时间线
- 右栏：工具调用、审批、产物、日志
- 底部：任务输入框

#### `TaskDetail`

展示：

- 目标
- 当前状态
- 当前执行步骤
- 最终结果
- 执行摘要

#### `PlanPanel`

展示：

- 计划摘要
- 步骤清单
- 每步状态
- 编辑计划按钮

#### `EventTimeline`

展示：

- 任务开始
- 计划生成
- 工具调用
- 审批创建
- 步骤完成
- 最终交付

#### `ApprovalPanel`

展示待审批动作：

- 工具名
- 风险等级
- 执行参数
- 影响范围
- 通过 / 拒绝

#### `ArtifactPanel`

展示：

- 生成文件
- 预览
- 下载

### 6.2 前端状态管理

建议引入：

- `taskStore`
- `stepStore`
- `approvalStore`
- `artifactStore`
- `eventStore`

即便先不引入 Pinia，也建议先做模块化 API 和页面状态分层。

### 6.3 前端交互原则

- 输入的是任务目标，不是普通消息
- 先看计划，再执行
- 执行过程持续可见
- 高风险动作必须明确提示
- 失败后用户可以重试或调整
- 最终突出“交付物”，而不是最后一条聊天文本

## 7. 后端类设计

建议保留现有 agent 包，但按职责拆分。

### 7.1 domain

- `AgentTask`
- `AgentTaskStep`
- `AgentToolCall`
- `AgentApproval`
- `AgentArtifact`
- `AgentTaskEvent`
- `AgentWorkspace`

### 7.2 controller

- `AgentTaskController`
- `AgentStepController`
- `AgentApprovalController`
- `AgentArtifactController`
- `AgentEventController`

### 7.3 service

- `AgentTaskService`
- `AgentPlanService`
- `AgentExecutionService`
- `AgentApprovalService`
- `AgentArtifactService`
- `AgentEventService`
- `AgentWorkspaceService`

### 7.4 runtime

- `AgentRuntimeEngine`
- `AgentTaskScheduler`
- `AgentStepExecutor`
- `AgentExecutionContext`
- `AgentStateMachine`

### 7.5 agent

- `PlannerAgent`
- `WorkerAgent`
- `ReviewerAgent`
- `ConversationAgent`

### 7.6 tool

将工具统一抽象成元数据更完整的定义：

- `AgentTool`
- `AgentToolDefinition`
- `AgentToolExecutor`
- `AgentToolRegistry`
- `AgentToolRiskEvaluator`
- `AgentToolApprovalInterceptor`

### 7.7 safety

- `PermissionPolicy`
- `WorkspacePolicy`
- `CommandPolicy`
- `FileAccessPolicy`
- `ApprovalPolicy`

## 8. 工具体系改造建议

当前工具最大问题不是数量，而是缺少治理能力。

建议每个工具增加以下属性：

- `name`
- `category`
- `riskLevel`
- `requiresApproval`
- `readOnly`
- `allowedPathScope`
- `timeoutMs`

工具分级建议：

### 低风险

- 只读文件
- 搜索
- 网页抓取
- 读取任务上下文

### 中风险

- 创建文件
- 下载文件
- 生成 PDF

### 高风险

- shell 命令执行
- 覆盖文件
- 删除文件
- 对工作区进行批量修改

## 9. 安全设计

这是项目从 demo 走向产品必须优先补齐的部分。

### 9.1 配置安全

- 去掉代码仓库中的明文 API Key
- 改为环境变量或本地私有配置文件
- 区分 dev / test / prod

### 9.2 工具执行安全

- shell 默认关闭
- 高风险命令必须审批
- 命令白名单或模式匹配
- 文件操作限制到 workspace root

### 9.3 接口安全

- CORS 限制来源
- 增加认证鉴权
- 审计高风险操作

## 10. 评估与可观测性

建议从阶段 2 开始补：

- 每任务 token 用量
- 每步耗时
- 工具成功率
- 审批拒绝率
- 任务完成率
- 失败原因分类
- 用户满意度

日志侧建议区分：

- 业务事件日志
- 模型请求日志
- 工具调用日志
- 安全拦截日志

## 11. 第一阶段详细施工清单

这是接下来建议真正开始做的顺序。

### 11.1 后端第一批改造

1. 新增任务领域模型
   - `AgentTask`
   - `AgentTaskStep`
   - `AgentApproval`
   - `AgentTaskEvent`

2. 新增任务接口
   - 创建任务
   - 查询任务
   - 启动任务
   - SSE 订阅任务事件

3. 新增基础执行引擎
   - 从 `BaseAgent` 抽离 runtime 概念
   - 执行时落步骤日志

4. 给工具补元数据和审批拦截
   - 先拦截 `shell`
   - 再拦截 `writeFile`
   - 再拦截 `downloadResource`

5. 引入最小持久化方案
   - 第一版可先用 JPA + MySQL
   - 如果你希望更快推进，也可以先用内存仓储接口 + H2 验证流程

### 11.2 前端第一批改造

1. 首页改成任务入口
2. 新增任务工作台页面
3. 接入任务列表、任务详情、步骤时间线
4. 接入 SSE 事件流
5. 增加审批卡片和执行日志面板

### 11.3 第一阶段完成标准

- 不再只是聊天
- 可以创建并执行任务
- 执行步骤可见
- 高风险工具受控
- 结果可追踪

## 12. 推荐的下一步实现顺序

按落地效率排序，建议我们接下来这样做：

1. 先落数据库和后端任务模型
2. 再补任务 API 和最小执行引擎
3. 再重构前端成任务工作台
4. 再给工具加审批和安全边界
5. 最后把 Planner / Worker / Reviewer 拆出来

如果按这个顺序推进，项目会比较稳，而且每一步都有可见成果。
