<template>
  <div class="workspace-shell">
    <header class="workspace-hero">
      <div>
        <p class="eyebrow">CODEBASE AGENT</p>
        <h1>{{ workspaceConfig.title }}</h1>
        <p class="hero-copy">{{ workspaceConfig.description }}</p>
      </div>
      <div class="hero-actions">
        <button class="secondary-btn" @click="goHome">Dashboard</button>
        <button class="secondary-btn" @click="goRepos">Repositories</button>
        <button class="secondary-btn" @click="goEval">Eval Panel</button>
        <button class="secondary-btn" @click="refreshAll">Refresh</button>
      </div>
    </header>

    <section class="workspace-grid">
      <aside class="sidebar panel">
        <div class="panel-head">
          <div>
            <p class="panel-tag">Task Intake</p>
            <h2>Create Task</h2>
          </div>
        </div>

        <form class="task-form" @submit.prevent="submitTask">
          <label class="field">
            <span>Repo</span>
            <select v-model="taskForm.repoId">
              <option value="">Select repo</option>
              <option v-for="repo in repos" :key="repo.repoId || repo.id" :value="repo.repoId || repo.id">
                {{ repo.name }} · {{ repo.indexedStatus }}
              </option>
            </select>
          </label>

          <label class="field">
            <span>Business Type</span>
            <select v-model="taskForm.businessType">
              <option v-for="option in businessTypes" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
          </label>

          <label class="field">
            <span>Title (optional)</span>
            <input v-model.trim="taskForm.title" type="text" placeholder="Leave empty to auto-generate from the goal" />
          </label>

          <label class="field">
            <span>Goal</span>
            <textarea v-model.trim="taskForm.goal" rows="6" :placeholder="workspaceConfig.goalPlaceholder" />
          </label>

          <label class="checkbox-field">
            <input v-model="taskForm.autoApproveLowRisk" type="checkbox" />
            <span>Auto approve low risk</span>
          </label>

          <button class="primary-btn" type="submit" :disabled="creatingTask">
            {{ creatingTask ? 'Creating...' : 'Create Task' }}
          </button>
        </form>

        <div class="template-block">
          <div class="panel-subhead">
            <strong>Quick Templates</strong>
            <span>{{ workspaceConfig.templateCaption }}</span>
          </div>
          <button
            v-for="template in activeQuickTemplates"
            :key="template.goal"
            class="template-btn"
            @click="applyTemplate(template)"
          >
            {{ template.label }}
          </button>
        </div>

        <div class="panel-subhead task-list-head">
          <strong>Task List</strong>
          <span>{{ filteredTasks.length }} tasks</span>
        </div>

        <div class="filters">
          <input v-model.trim="filters.keyword" type="text" placeholder="Search task / repo / goal" />
          <select v-model="filters.status">
            <option value="">All Status</option>
            <option v-for="status in taskStatusOptions" :key="status" :value="status">{{ status }}</option>
          </select>
        </div>

        <div v-if="loadingTasks" class="empty-state">Loading task list...</div>
        <div v-else-if="!filteredTasks.length" class="empty-state">No tasks match the current filters.</div>
        <div v-else class="task-list">
          <button
            v-for="task in filteredTasks"
            :key="task.taskId"
            class="task-card"
            :class="{ active: task.taskId === selectedTaskId }"
            @click="openTask(task.taskId)"
          >
            <div class="task-card-top">
              <strong>{{ task.title }}</strong>
              <span class="status-chip" :class="statusClass(task.status)">{{ task.status }}</span>
            </div>
            <p>{{ task.goal }}</p>
            <div class="task-meta">
              <span>{{ task.businessType || 'GENERAL' }}</span>
              <span>{{ task.repoName || 'No Repo' }}</span>
            </div>
          </button>
        </div>
      </aside>

      <section class="main-stage">
        <div class="detail-topbar panel">
          <div v-if="selectedTask" class="task-header">
            <div>
              <p class="panel-tag">Task Header</p>
              <h2>{{ selectedTask.title }}</h2>
              <p class="goal-copy">{{ selectedTask.goal }}</p>
            </div>
            <div class="task-header-meta">
              <span class="status-chip" :class="statusClass(selectedTask.status)">{{ selectedTask.status }}</span>
              <span class="meta-pill">{{ selectedTask.businessType || 'GENERAL' }}</span>
              <span class="meta-pill">{{ selectedTask.repoName || 'No Repo' }}</span>
            </div>
          </div>
          <div v-else class="empty-state compact-empty">Select a task to inspect the codebase workflow, evidence, and artifacts.</div>

          <div v-if="selectedTask" class="task-actions">
            <button class="primary-btn compact" :disabled="actionLoading || !canStartTask" @click="handleStartTask">Start</button>
            <button class="secondary-btn compact" :disabled="actionLoading || !canPauseTask" @click="handlePauseTask">Pause</button>
            <button class="secondary-btn compact" :disabled="actionLoading || !canResumeTask" @click="handleResumeTask">Resume</button>
            <button class="secondary-btn compact" :disabled="actionLoading || !canReplanTask" @click="handleReplanTask">Replan</button>
            <button class="secondary-btn compact" :disabled="actionLoading || !canReviewTask" @click="handleReviewTask">Reviewer</button>
            <button class="secondary-btn compact" :disabled="actionLoading || !canCancelTask" @click="handleCancelTask">Cancel</button>
          </div>
        </div>

        <div class="detail-grid">
          <section class="panel timeline-panel">
            <div class="panel-head">
              <div>
                <p class="panel-tag">Agent Timeline</p>
                <h3>Execution Flow</h3>
              </div>
              <span>{{ steps.length }} steps</span>
            </div>

            <div v-if="loadingDetail" class="empty-state">Loading task detail...</div>
            <div v-else-if="!selectedTask" class="empty-state">Pick a task from the left list first.</div>
            <div v-else-if="!steps.length" class="empty-state">This task has not generated execution steps yet.</div>
            <div v-else class="timeline-list">
              <button
                v-for="step in steps"
                :key="step.id"
                class="timeline-card"
                :class="{ active: step.id === selectedStepId }"
                @click="selectStep(step.id)"
              >
                <div class="timeline-card-head">
                  <strong>{{ step.stepTitle }}</strong>
                  <span class="status-chip small" :class="statusClass(step.status)">{{ step.status }}</span>
                </div>
                <div class="timeline-meta">
                  <span>{{ step.assignedAgent || step.toolName || 'LegacyWorker' }}</span>
                  <span>{{ formatDuration(step.startedAt, step.finishedAt) }}</span>
                </div>
                <div class="timeline-meta">
                  <span>retry {{ step.retryCount || 0 }}/{{ step.maxRetry || 1 }}</span>
                  <span>confidence {{ formatConfidence(parseStepOutput(step).confidence) }}</span>
                </div>
                <div class="timeline-meta">
                  <span>next {{ parseStepOutput(step).nextAction || '--' }}</span>
                  <span>{{ step.stepType }}</span>
                </div>
              </button>
            </div>
          </section>

          <section class="panel result-panel">
            <div class="panel-head">
              <div>
                <p class="panel-tag">Primary Result</p>
                <h3>{{ primaryArtifactTitle }}</h3>
              </div>
            </div>

            <div v-if="legacyChainWarning" class="empty-state danger-state">
              该任务未进入 Codebase Agent 分析链路，请检查 businessType 路由。
            </div>
            <div v-else-if="!selectedTask" class="empty-state">Select a task to inspect the primary result.</div>
            <div v-else class="result-stack">
              <article v-if="moduleSummaryArtifact?.structuredContent" class="detail-card artifact-highlight">
                <div class="artifact-heading-row">
                  <h4>{{ understandingPrimaryTitle }}</h4>
                  <span v-if="isPartialUnderstandingResult" class="status-chip small pending">PARTIAL</span>
                </div>
                <p>{{ understandingPrimarySummary || '--' }}</p>
                <div v-if="showsUnderstandingModules" class="module-list">
                  <div
                    v-for="module in understandingModules"
                    :key="`${module.name}-${module.responsibility}`"
                    class="module-card"
                  >
                    <strong>{{ module.name }}</strong>
                    <p>{{ module.responsibility || '--' }}</p>
                    <p><strong>Key Files:</strong> {{ formatList(module.keyFiles) }}</p>
                    <p><strong>Evidence:</strong> {{ formatList(module.evidence) }}</p>
                  </div>
                </div>
                <div v-else-if="understandingFlowSteps.length" class="module-list">
                  <div
                    v-for="flowStep in understandingFlowSteps"
                    :key="`${flowStep.step}-${flowStep.description}`"
                    class="module-card"
                  >
                    <strong>{{ flowStep.step }}</strong>
                    <p>{{ flowStep.description || '--' }}</p>
                    <p><strong>Key Files:</strong> {{ formatList(flowStep.keyFiles) }}</p>
                    <p><strong>Evidence:</strong> {{ formatList(flowStep.evidence) }}</p>
                  </div>
                </div>
                <div v-else-if="understandingOperations.length" class="module-list">
                  <div
                    v-for="operation in understandingOperations"
                    :key="`${operation.operation}-${operation.controller}-${operation.service}-${operation.mapper}`"
                    class="module-card"
                  >
                    <strong>{{ operation.operation || 'Operation' }}</strong>
                    <p><strong>Controller:</strong> {{ operation.controller || '--' }}</p>
                    <p><strong>Service:</strong> {{ operation.service || '--' }}</p>
                    <p><strong>Mapper:</strong> {{ operation.mapper || operation.repository || '--' }}</p>
                    <p><strong>Evidence:</strong> {{ formatList(operation.evidence) }}</p>
                  </div>
                </div>
                <div v-else-if="understandingCallChain.length" class="module-list">
                  <div
                    v-for="chainStep in understandingCallChain"
                    :key="`${chainStep.layer}-${chainStep.responsibility}`"
                    class="module-card"
                  >
                    <strong>{{ chainStep.layer }}</strong>
                    <p>{{ chainStep.responsibility || '--' }}</p>
                    <p><strong>Methods:</strong> {{ formatList(chainStep.methods) }}</p>
                    <p><strong>Key Files:</strong> {{ formatList(chainStep.keyFiles) }}</p>
                    <p><strong>Evidence:</strong> {{ formatList(chainStep.evidence) }}</p>
                  </div>
                </div>
                <div v-if="understandingNotes.length" class="notes-card">
                  <strong>{{ understandingNotesTitle }}</strong>
                  <ul>
                    <li v-for="note in understandingNotes" :key="note">{{ note }}</li>
                  </ul>
                </div>
                <div v-if="understandingConfirmedScope.length" class="notes-card">
                  <strong>Confirmed Scope</strong>
                  <ul>
                    <li v-for="item in understandingConfirmedScope" :key="item">{{ item }}</li>
                  </ul>
                </div>
                <div v-if="understandingMissingInfo.length" class="notes-card warning-card">
                  <strong>Missing Info</strong>
                  <ul>
                    <li v-for="item in understandingMissingInfo" :key="item">{{ item }}</li>
                  </ul>
                </div>
                <div v-if="understandingFollowUpQueries.length" class="notes-card">
                  <strong>Suggested Follow-up Queries</strong>
                  <ul>
                    <li v-for="item in understandingFollowUpQueries" :key="item">{{ item }}</li>
                  </ul>
                </div>
              </article>

              <article v-else-if="rootCauseArtifact?.structuredContent" class="detail-card artifact-highlight">
                <h4>Root Cause Summary</h4>
                <p>{{ rootCauseArtifact.structuredContent.summary || '--' }}</p>
                <div v-if="rootCauseArtifact.structuredContent.hypotheses?.length" class="hypothesis-list">
                  <div
                    v-for="hypothesis in rootCauseArtifact.structuredContent.hypotheses"
                    :key="`${hypothesis.cause}-${hypothesis.confidence}`"
                    class="hypothesis-card"
                  >
                    <strong>{{ hypothesis.cause }}</strong>
                    <p><strong>Evidence:</strong> {{ formatList(hypothesis.evidence) }}</p>
                    <p><strong>Confidence:</strong> {{ formatConfidence(hypothesis.confidence) }}</p>
                  </div>
                </div>
              </article>

              <article v-else-if="patchPlanArtifact?.structuredContent" class="detail-card artifact-highlight">
                <h4>Patch Plan Summary</h4>
                <p>{{ patchPlanArtifact.structuredContent.patchPlan || '--' }}</p>
                <p><strong>Files To Change:</strong> {{ formatList(patchPlanArtifact.structuredContent.filesToChange) }}</p>
                <p><strong>Risks:</strong> {{ formatList(patchPlanArtifact.structuredContent.risks) }}</p>
              </article>

              <div v-else class="empty-inline">No primary artifact is available yet.</div>

              <article class="detail-card">
                <div class="panel-subhead">
                  <strong>Artifact Summary</strong>
                  <span>{{ artifacts.length }}</span>
                </div>
                <div v-if="!artifacts.length" class="empty-inline">No artifacts have been generated yet.</div>
                <div v-else class="artifact-list compact-artifact-list">
                  <article v-for="artifact in prioritizedArtifacts" :key="artifact.id" class="artifact-card">
                    <div class="artifact-head">
                      <div>
                        <strong>{{ artifact.artifactType }}</strong>
                        <p>{{ artifact.artifactName }}</p>
                      </div>
                      <span class="meta-pill">{{ artifact.relativePath || 'inline artifact' }}</span>
                    </div>
                    <p class="artifact-description">{{ artifact.description || 'No description.' }}</p>
                    <div class="artifact-actions">
                      <button
                        v-if="artifact.previewable"
                        class="secondary-btn compact"
                        @click="openArtifactPreview(artifact)"
                      >
                        Preview
                      </button>
                      <button class="secondary-btn compact" @click="downloadArtifact(artifact)">Download</button>
                    </div>
                  </article>
                </div>
              </article>
            </div>
          </section>

          <section class="panel inspector-panel">
            <div class="step-inspector">
              <div class="panel-head">
                <div>
                  <p class="panel-tag">Step Detail</p>
                  <h3>{{ selectedStep ? selectedStep.stepTitle : 'Step Summary' }}</h3>
                </div>
              </div>

              <div v-if="!selectedStep" class="empty-state">Select a timeline step to inspect its inputs, outputs, and tool calls.</div>
              <div v-else class="step-detail">
                <div class="detail-row">
                  <span>Assigned Agent</span>
                  <strong>{{ selectedStep.assignedAgent || selectedStep.toolName || 'LegacyWorker' }}</strong>
                </div>
                <div class="detail-row">
                  <span>Status</span>
                  <strong>{{ selectedStep.status }}</strong>
                </div>
                <div class="detail-row">
                  <span>Retry Count</span>
                  <strong>{{ selectedStep.retryCount || 0 }} / {{ selectedStep.maxRetry || 1 }}</strong>
                </div>
                <div class="detail-row">
                  <span>Next Action</span>
                  <strong>{{ selectedStepResult.nextAction || '--' }}</strong>
                </div>
                <div class="detail-row">
                  <span>Failure Reason</span>
                  <strong>{{ selectedStepResult.failureReason || '--' }}</strong>
                </div>

                <details class="detail-card fold-card">
                  <summary>Input Context Summary</summary>
                  <pre>{{ prettyJson(selectedStepInput) }}</pre>
                </details>

                <details class="detail-card fold-card" open>
                  <summary>Agent Output Summary</summary>
                  <p>{{ selectedStepResult.summary || selectedStep.executorOutput || 'No summary available.' }}</p>
                  <pre>{{ prettyJson(selectedStepResult.structuredOutput) }}</pre>
                </details>

                <details class="detail-card fold-card">
                  <summary>Tool Call Records ({{ currentStepToolCalls.length }})</summary>
                  <div v-if="!currentStepToolCalls.length" class="empty-inline">No tool calls were recorded for this step.</div>
                  <div v-else class="tool-list">
                    <div v-for="toolCall in currentStepToolCalls" :key="toolCall.id" class="tool-card">
                      <strong>{{ toolCall.toolName }}</strong>
                      <span class="status-chip small" :class="statusClass(toolCall.status)">{{ toolCall.status }}</span>
                      <p><strong>Input:</strong> {{ toolCall.toolInput || '--' }}</p>
                      <p><strong>Output:</strong> {{ toolCall.toolOutput || '--' }}</p>
                    </div>
                  </div>
                </details>
              </div>
            </div>

            <div class="evidence-panel">
              <div class="panel-head">
                <div>
                  <p class="panel-tag">Evidence</p>
                  <h3>Code Evidence Panel</h3>
                </div>
                <span>{{ selectedEvidenceRefs.length }}</span>
              </div>

              <div v-if="!selectedEvidenceRefs.length" class="empty-state">
                No evidence refs are attached to the selected step. Try switching to CodeSearch, CodeUnderstanding, Diagnosis, or CODE_EVIDENCE.
              </div>
              <div v-else class="evidence-list">
                <article
                  v-for="evidence in selectedEvidenceRefs"
                  :key="evidence.chunkId || `${evidence.filePath}-${evidence.startLine}`"
                  class="evidence-card"
                >
                  <div class="evidence-head">
                    <strong>{{ evidence.filePath }}</strong>
                    <span>{{ evidence.startLine }}-{{ evidence.endLine }}</span>
                  </div>
                  <div class="evidence-meta">
                    <span>score {{ formatConfidence(evidence.score) }}</span>
                    <span>{{ evidence.reason || 'No reason' }}</span>
                  </div>
                  <details class="code-fold">
                    <summary>Code Preview</summary>
                    <pre>{{ evidence.codePreview || evidence.contentPreview || '' }}</pre>
                  </details>
                </article>
              </div>
            </div>
          </section>
        </div>
      </section>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, watch } from 'vue'
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  buildAgentArtifactDownloadUrl,
  buildAgentArtifactPreviewUrl,
  cancelAgentTask,
  createAgentTask,
  getAgentTask,
  listAgentTaskArtifacts,
  listAgentTaskSteps,
  listAgentTaskToolCalls,
  listAgentTasks,
  listRepos,
  pauseAgentTask,
  replanAgentTask,
  resumeAgentTask,
  reviewAgentTask,
  startAgentTask,
  streamAgentTaskEvents
} from '../api'

const router = useRouter()
const route = useRoute()

const businessTypes = [
  { value: 'CODE_UNDERSTANDING', label: 'CODE_UNDERSTANDING' },
  { value: 'BUG_DIAGNOSIS', label: 'BUG_DIAGNOSIS' },
  { value: 'PATCH_SUGGESTION', label: 'PATCH_SUGGESTION' },
  { value: 'TEST_GENERATION', label: 'TEST_GENERATION' }
]

const quickTemplateLibrary = {
  CODE_UNDERSTANDING: [
    { label: 'Analyze overall project structure', businessType: 'CODE_UNDERSTANDING', goal: '请分析这个项目的整体代码结构，说明主要模块和职责，并给出代码证据。' },
    { label: 'Analyze login/auth flow', businessType: 'CODE_UNDERSTANDING', goal: '请分析这个项目的登录认证流程，从登录入口开始说明 Controller、Service、Security 配置如何协作，并给出代码证据。' },
    { label: 'Analyze permission/menu loading flow', businessType: 'CODE_UNDERSTANDING', goal: '请分析权限菜单加载流程，说明相关 Controller、Service、Config 和数据结构，并给出代码证据。' },
    { label: 'Analyze employee management module', businessType: 'CODE_UNDERSTANDING', goal: '请分析员工管理模块的主要职责、接口分层和关键文件，并给出代码证据。' },
    { label: 'Analyze one API call chain', businessType: 'CODE_UNDERSTANDING', goal: '请分析某个接口的调用链，从 Controller 到 Service / Mapper 说明其执行路径，并给出代码证据。' }
  ],
  BUG_DIAGNOSIS: [
    { label: 'Locate a login 500 root cause', businessType: 'BUG_DIAGNOSIS', goal: '定位登录接口 500 的可能原因，并给出代码证据。' },
    { label: 'Find an exception source', businessType: 'BUG_DIAGNOSIS', goal: '分析某个异常可能在哪里抛出、为什么会发生，并给出代码证据。' },
    { label: 'Analyze a token validation issue', businessType: 'BUG_DIAGNOSIS', goal: '请分析 token 校验异常可能和哪些代码有关，并给出代码证据。' }
  ],
  PATCH_SUGGESTION: [
    { label: 'Generate a safe patch plan', businessType: 'PATCH_SUGGESTION', goal: '请基于当前 evidence 生成安全的修复建议，并给出 patch plan。' },
    { label: 'Patch login/auth issue', businessType: 'PATCH_SUGGESTION', goal: '请为登录认证相关问题生成 patch plan，并说明风险和测试建议。' }
  ],
  TEST_GENERATION: [
    { label: 'Suggest regression tests', businessType: 'TEST_GENERATION', goal: '请为当前代码路径生成回归测试建议，并说明覆盖点。' },
    { label: 'Generate endpoint test ideas', businessType: 'TEST_GENERATION', goal: '请为某个接口生成测试建议，包括正常路径、异常路径和边界输入。' }
  ]
}

const workspaceConfigMap = {
  CODE_UNDERSTANDING: {
    title: 'Code Understanding Workspace',
    description: 'Inspect module summaries, code evidence, and architecture notes from the Codebase Agent workflow.',
    goalPlaceholder: '例如：请分析这个项目的整体代码结构，说明主要模块和职责，并给出代码证据。',
    templateCaption: 'Project structure / flow analysis'
  },
  BUG_DIAGNOSIS: {
    title: 'Bug Diagnosis Workspace',
    description: 'Trace code evidence, grounded hypotheses, reviewer decisions, and final root-cause delivery in one place.',
    goalPlaceholder: '例如：登录接口偶尔返回 500，帮我定位可能原因。',
    templateCaption: 'Bug diagnosis / exception analysis'
  },
  PATCH_SUGGESTION: {
    title: 'Patch Suggestion Workspace',
    description: 'Review grounded diagnosis, patch plans, risks, and evidence before proposing a safe code change.',
    goalPlaceholder: '例如：请基于当前 evidence 生成安全的修复建议和 patch plan。',
    templateCaption: 'Patch plan / remediation'
  },
  TEST_GENERATION: {
    title: 'Test Generation Workspace',
    description: 'Organize evidence, candidate test targets, and generated test suggestions around a code task.',
    goalPlaceholder: '例如：请为这个接口生成测试建议，并说明应覆盖哪些分支。',
    templateCaption: 'Regression / test ideas'
  }
}

const taskStatusOptions = ['PENDING', 'PLANNING', 'RUNNING', 'REVIEWING', 'WAITING_APPROVAL', 'BLOCKED', 'SUCCEEDED', 'FAILED', 'CANCELLED']

const repos = ref([])
const tasks = ref([])
const selectedTaskId = ref(null)
const selectedTask = ref(null)
const steps = ref([])
const artifacts = ref([])
const toolCalls = ref([])
const selectedStepId = ref(null)
const loadingTasks = ref(false)
const loadingDetail = ref(false)
const creatingTask = ref(false)
const actionLoading = ref(false)
const taskEventSource = ref(null)
const subscribedTaskId = ref(null)
const taskRefreshInFlight = ref(false)

const filters = ref({
  keyword: '',
  status: ''
})

const taskForm = ref({
  repoId: '',
  businessType: 'BUG_DIAGNOSIS',
  title: '',
  goal: '',
  autoApproveLowRisk: false
})

const currentWorkspaceType = computed(() => selectedTask.value?.businessType || taskForm.value.businessType || 'BUG_DIAGNOSIS')
const workspaceConfig = computed(() => workspaceConfigMap[currentWorkspaceType.value] || workspaceConfigMap.BUG_DIAGNOSIS)
const activeQuickTemplates = computed(() => quickTemplateLibrary[taskForm.value.businessType] || quickTemplateLibrary.BUG_DIAGNOSIS)

const filteredTasks = computed(() => {
  const keyword = filters.value.keyword.toLowerCase()
  return [...tasks.value]
    .filter((task) => !filters.value.status || task.status === filters.value.status)
    .filter((task) => {
      if (!keyword) return true
      return [task.title, task.goal, task.repoName, task.businessType]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(keyword))
    })
    .sort((a, b) => new Date(b.updatedAt || b.createdAt || 0) - new Date(a.updatedAt || a.createdAt || 0))
})

const selectedStep = computed(() => steps.value.find((step) => step.id === selectedStepId.value) || null)
const selectedStepInput = computed(() => parseJson(selectedStep.value?.executorInput))
const selectedStepResult = computed(() => parseJson(selectedStep.value?.executorOutput) || {})

const currentStepToolCalls = computed(() => {
  if (!selectedStep.value) return []
  return toolCalls.value.filter((toolCall) => {
    if (toolCall.stepId != null) {
      return toolCall.stepId === selectedStep.value.id
    }
    return String(toolCall.toolName || '').includes(selectedStep.value.assignedAgent || selectedStep.value.toolName || '')
  })
})

const prioritizedArtifacts = computed(() => {
  const order = ['MODULE_SUMMARY', 'ROOT_CAUSE_REPORT', 'CODE_EVIDENCE', 'PATCH_PLAN', 'TEST_SUGGESTION']
  return [...artifacts.value].sort((a, b) => {
    const left = order.indexOf(a.artifactType)
    const right = order.indexOf(b.artifactType)
    return (left === -1 ? 99 : left) - (right === -1 ? 99 : right)
  })
})

const moduleSummaryArtifact = computed(() => prioritizedArtifacts.value.find((artifact) => artifact.artifactType === 'MODULE_SUMMARY') || null)
const rootCauseArtifact = computed(() => prioritizedArtifacts.value.find((artifact) => artifact.artifactType === 'ROOT_CAUSE_REPORT') || null)
const patchPlanArtifact = computed(() => prioritizedArtifacts.value.find((artifact) => artifact.artifactType === 'PATCH_PLAN') || null)
const selectedPrimaryArtifact = computed(() => moduleSummaryArtifact.value || rootCauseArtifact.value || patchPlanArtifact.value || prioritizedArtifacts.value[0] || null)
const primaryArtifactTitle = computed(() => {
  if (moduleSummaryArtifact.value?.structuredContent?.outputSchema) {
    return moduleSummaryArtifact.value.structuredContent.outputSchema
  }
  return selectedPrimaryArtifact.value?.artifactType || 'Result Summary'
})
const moduleSummaryContent = computed(() => moduleSummaryArtifact.value?.structuredContent || {})
const understandingIntent = computed(() => moduleSummaryContent.value.intent || 'OVERALL_STRUCTURE')
const understandingOutputSchema = computed(() => moduleSummaryContent.value.outputSchema || moduleSummaryContent.value.subType || 'MODULE_SUMMARY')
const understandingModules = computed(() => Array.isArray(moduleSummaryContent.value.modules) ? moduleSummaryContent.value.modules : [])
const understandingFlowSteps = computed(() => Array.isArray(moduleSummaryContent.value.flowSteps) ? moduleSummaryContent.value.flowSteps : [])
const understandingOperations = computed(() => Array.isArray(moduleSummaryContent.value.operations) ? moduleSummaryContent.value.operations : [])
const understandingCallChain = computed(() => Array.isArray(moduleSummaryContent.value.callChain) ? moduleSummaryContent.value.callChain : [])
const showsUnderstandingModules = computed(() => understandingOutputSchema.value === 'MODULE_SUMMARY' || (!understandingFlowSteps.value.length && understandingModules.value.length))
const understandingPrimaryTitle = computed(() => {
  switch (understandingOutputSchema.value) {
    case 'FLOW_SUMMARY':
      return moduleSummaryContent.value.targetModule
        ? `Flow Analysis - ${moduleSummaryContent.value.targetModule}`
        : 'Flow Analysis'
    case 'MODULE_DETAIL':
      return moduleSummaryContent.value.targetModule
        ? `Module Detail - ${moduleSummaryContent.value.targetModule}`
        : 'Module Detail'
    case 'CALL_CHAIN':
      return moduleSummaryContent.value.targetModule
        ? `API Call Chain - ${moduleSummaryContent.value.targetModule}`
        : 'API Call Chain'
    default:
      return 'Project Summary'
  }
})
const understandingPrimarySummary = computed(() => moduleSummaryContent.value.summary || '')
const isPartialUnderstandingResult = computed(() => Boolean(moduleSummaryContent.value.partial) || moduleSummaryContent.value.deliveryMode === 'PARTIAL')
const understandingConfirmedScope = computed(() => Array.isArray(moduleSummaryContent.value.confirmedScope) ? moduleSummaryContent.value.confirmedScope : [])
const understandingMissingInfo = computed(() => Array.isArray(moduleSummaryContent.value.missingInfo) ? moduleSummaryContent.value.missingInfo : [])
const understandingFollowUpQueries = computed(() => Array.isArray(moduleSummaryContent.value.suggestedFollowUpQueries) ? moduleSummaryContent.value.suggestedFollowUpQueries : [])
const understandingNotes = computed(() => {
  if (Array.isArray(moduleSummaryContent.value.architectureNotes) && moduleSummaryContent.value.architectureNotes.length) {
    return moduleSummaryContent.value.architectureNotes
  }
  if (Array.isArray(moduleSummaryContent.value.notesAndRisks) && moduleSummaryContent.value.notesAndRisks.length) {
    return moduleSummaryContent.value.notesAndRisks
  }
  return Array.isArray(moduleSummaryContent.value.riskNotes) ? moduleSummaryContent.value.riskNotes : []
})
const understandingNotesTitle = computed(() => showsUnderstandingModules.value ? 'Architecture Notes' : 'Notes & Risks')

const artifactEvidenceRefs = computed(() => {
  const codeEvidence = prioritizedArtifacts.value.find((artifact) => artifact.artifactType === 'CODE_EVIDENCE')
  return codeEvidence?.evidenceRefs || []
})

const selectedEvidenceRefs = computed(() => {
  if (selectedStep.value?.evidenceRefs?.length) return selectedStep.value.evidenceRefs
  if (selectedStepResult.value?.evidenceRefs?.length) return selectedStepResult.value.evidenceRefs
  return artifactEvidenceRefs.value
})

const codebaseAgents = ['PlannerAgent', 'CodeSearchAgent', 'CodeUnderstandingAgent', 'DiagnosisAgent', 'ReviewerAgent', 'PatchAgent', 'DeliveryAgent']
const legacyChainWarning = computed(() => {
  if (!selectedTask.value || selectedTask.value.businessType !== 'CODE_UNDERSTANDING' || !steps.value.length) {
    return false
  }
  return !steps.value.some((step) => codebaseAgents.includes(step.assignedAgent))
})

const canStartTask = computed(() => selectedTask.value && ['PENDING', 'BLOCKED'].includes(selectedTask.value.status))
const canPauseTask = computed(() => selectedTask.value && selectedTask.value.status === 'RUNNING')
const canResumeTask = computed(() => selectedTask.value && ['BLOCKED', 'WAITING_APPROVAL'].includes(selectedTask.value.status))
const canReplanTask = computed(() => selectedTask.value && ['BLOCKED', 'FAILED', 'RUNNING'].includes(selectedTask.value.status))
const canReviewTask = computed(() => selectedTask.value && ['RUNNING', 'BLOCKED', 'SUCCEEDED'].includes(selectedTask.value.status))
const canCancelTask = computed(() => selectedTask.value && !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(selectedTask.value.status))

const loadRepos = async () => {
  const data = await listRepos()
  repos.value = Array.isArray(data) ? data : []
}

const loadTasks = async ({ silent = false } = {}) => {
  if (!silent) {
    loadingTasks.value = true
  }
  try {
    const data = await listAgentTasks()
    tasks.value = Array.isArray(data) ? data : []
  } finally {
    if (!silent) {
      loadingTasks.value = false
    }
  }
}

const loadTaskDetail = async (taskId, { silent = false } = {}) => {
  if (!taskId) return
  if (!silent) {
    loadingDetail.value = true
  }
  try {
    const [task, stepList, artifactList, toolCallList] = await Promise.all([
      getAgentTask(taskId),
      listAgentTaskSteps(taskId),
      listAgentTaskArtifacts(taskId),
      listAgentTaskToolCalls(taskId)
    ])
    selectedTask.value = task
    steps.value = Array.isArray(stepList) ? stepList : []
    artifacts.value = Array.isArray(artifactList) ? artifactList : []
    toolCalls.value = Array.isArray(toolCallList) ? toolCallList : []
    if (!selectedStepId.value || !steps.value.some((step) => step.id === selectedStepId.value)) {
      selectedStepId.value = steps.value[0]?.id || null
    }
    if (isTerminalStatus(task.status) && taskEventSource.value) {
      taskEventSource.value.close()
      taskEventSource.value = null
      subscribedTaskId.value = null
    }
  } finally {
    if (!silent) {
      loadingDetail.value = false
    }
  }
}

const refreshAll = async () => {
  await Promise.all([loadRepos(), loadTasks()])
  if (selectedTaskId.value) {
    await loadTaskDetail(selectedTaskId.value)
  }
}

const submitTask = async () => {
  if (!taskForm.value.goal) {
    window.alert('Please enter a task goal first.')
    return
  }
  if (taskForm.value.businessType && !taskForm.value.repoId) {
    window.alert('Please select a repo for code-related tasks.')
    return
  }

  creatingTask.value = true
  try {
    const created = await createAgentTask({
      title: taskForm.value.title || undefined,
      goal: taskForm.value.goal,
      repoId: taskForm.value.repoId || undefined,
      businessType: taskForm.value.businessType || undefined,
      autoApproveLowRisk: taskForm.value.autoApproveLowRisk
    })
    taskForm.value.title = ''
    taskForm.value.goal = ''
    await loadTasks()
    await openTask(created.taskId)
    router.replace(`/tasks/${created.taskId}`)
  } catch (error) {
    window.alert(error?.response?.data?.message || error?.message || 'Failed to create task.')
  } finally {
    creatingTask.value = false
  }
}

const openTask = async (taskId) => {
  const normalizedTaskId = Number(taskId)
  if (selectedTaskId.value !== normalizedTaskId) {
    selectedTaskId.value = normalizedTaskId
  }
  await loadTaskDetail(selectedTaskId.value)
  if (!isTerminalStatus(selectedTask.value?.status)) {
    subscribeToTaskEvents(selectedTaskId.value)
  }
  if (route.path !== `/tasks/${selectedTaskId.value}`) {
    router.replace(`/tasks/${selectedTaskId.value}`)
  }
}

const selectStep = (stepId) => {
  selectedStepId.value = stepId
}

const applyTemplate = (template) => {
  taskForm.value.businessType = template.businessType
  taskForm.value.goal = template.goal
}

const handleStartTask = async () => {
  if (!selectedTask.value) return
  actionLoading.value = true
  try {
    await startAgentTask(selectedTask.value.taskId)
    await loadTasks()
    await loadTaskDetail(selectedTask.value.taskId)
  } finally {
    actionLoading.value = false
  }
}

const handlePauseTask = async () => {
  if (!selectedTask.value) return
  actionLoading.value = true
  try {
    await pauseAgentTask(selectedTask.value.taskId)
    await loadTasks()
    await loadTaskDetail(selectedTask.value.taskId)
  } finally {
    actionLoading.value = false
  }
}

const handleResumeTask = async () => {
  if (!selectedTask.value) return
  actionLoading.value = true
  try {
    await resumeAgentTask(selectedTask.value.taskId)
    await loadTasks()
    await loadTaskDetail(selectedTask.value.taskId)
  } finally {
    actionLoading.value = false
  }
}

const handleReplanTask = async () => {
  if (!selectedTask.value) return
  actionLoading.value = true
  try {
    await replanAgentTask(selectedTask.value.taskId)
    await loadTasks()
    await loadTaskDetail(selectedTask.value.taskId)
  } finally {
    actionLoading.value = false
  }
}

const handleReviewTask = async () => {
  if (!selectedTask.value) return
  actionLoading.value = true
  try {
    await reviewAgentTask(selectedTask.value.taskId)
    await loadTasks()
    await loadTaskDetail(selectedTask.value.taskId)
  } finally {
    actionLoading.value = false
  }
}

const handleCancelTask = async () => {
  if (!selectedTask.value) return
  actionLoading.value = true
  try {
    await cancelAgentTask(selectedTask.value.taskId)
    await loadTasks()
    await loadTaskDetail(selectedTask.value.taskId)
  } finally {
    actionLoading.value = false
  }
}

const openArtifactPreview = (artifact) => {
  if (!selectedTask.value) return
  window.open(buildAgentArtifactPreviewUrl(selectedTask.value.taskId, artifact.id), '_blank', 'noopener')
}

const downloadArtifact = (artifact) => {
  if (!selectedTask.value) return
  window.open(buildAgentArtifactDownloadUrl(selectedTask.value.taskId, artifact.id), '_blank', 'noopener')
}

const goHome = () => router.push('/')
const goRepos = () => router.push('/repos')
const goEval = () => router.push('/eval')

const subscribeToTaskEvents = (taskId) => {
  if (subscribedTaskId.value === taskId && taskEventSource.value) {
    return
  }
  if (taskEventSource.value) {
    taskEventSource.value.close()
  }
  const source = streamAgentTaskEvents(taskId)
  source.onmessage = async () => {
    if (selectedTaskId.value === taskId && !taskRefreshInFlight.value) {
      taskRefreshInFlight.value = true
      try {
        await loadTasks({ silent: true })
        await loadTaskDetail(taskId, { silent: true })
      } finally {
        taskRefreshInFlight.value = false
      }
    }
  }
  source.onerror = () => {
    source.close()
    if (subscribedTaskId.value === taskId) {
      taskEventSource.value = null
      subscribedTaskId.value = null
    }
  }
  taskEventSource.value = source
  subscribedTaskId.value = taskId
}

const syncFromRoute = async () => {
  const routeTaskId = route.params.taskId ? Number(route.params.taskId) : null
  const queryRepoId = route.query.repoId ? String(route.query.repoId) : ''
  const queryBusinessType = route.query.businessType ? String(route.query.businessType) : ''

  if (queryRepoId) {
    taskForm.value.repoId = queryRepoId
  }
  if (queryBusinessType && businessTypes.some((option) => option.value === queryBusinessType)) {
    taskForm.value.businessType = queryBusinessType
  }

  if (routeTaskId) {
    await openTask(routeTaskId)
    return
  }

  if (route.path === '/tasks/new') {
    selectedTaskId.value = null
    selectedTask.value = null
    steps.value = []
    artifacts.value = []
    toolCalls.value = []
    selectedStepId.value = null
    return
  }

  if (!selectedTaskId.value && filteredTasks.value.length) {
    await openTask(filteredTasks.value[0].taskId)
  }
}

const parseJson = (value) => {
  if (!value) return {}
  if (typeof value === 'object') return value
  try {
    return JSON.parse(value)
  } catch (error) {
    return { raw: value }
  }
}

const parseStepOutput = (step) => parseJson(step?.executorOutput)

const isTerminalStatus = (status) => ['SUCCEEDED', 'FAILED', 'BLOCKED', 'WAITING_APPROVAL', 'CANCELLED'].includes(status)

const prettyJson = (value) => {
  if (!value || (typeof value === 'object' && !Object.keys(value).length)) return '--'
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}

const formatDuration = (startedAt, finishedAt) => {
  if (!startedAt) return '--'
  const start = new Date(startedAt).getTime()
  const end = finishedAt ? new Date(finishedAt).getTime() : Date.now()
  const diff = Math.max(0, Math.round((end - start) / 1000))
  return `${diff}s`
}

const formatConfidence = (value) => {
  if (value == null || value === '') return '--'
  const number = Number(value)
  if (Number.isNaN(number)) return '--'
  return number.toFixed(2)
}

const formatList = (items) => {
  if (!items || !items.length) return '--'
  return items.join(' / ')
}

const statusClass = (status) => {
  if (['SUCCEEDED', 'APPROVED'].includes(status)) return 'success'
  if (['FAILED', 'CANCELLED', 'REJECTED'].includes(status)) return 'danger'
  if (['RUNNING', 'REVIEWING', 'PLANNING', 'WAITING_APPROVAL'].includes(status)) return 'running'
  return 'pending'
}

watch(currentWorkspaceType, (value) => {
  const title = workspaceConfigMap[value]?.title || workspaceConfigMap.BUG_DIAGNOSIS.title
  document.title = `Codebase Agent - ${title}`
})

watch(
  () => route.fullPath,
  async () => {
    await syncFromRoute()
  }
)

onMounted(async () => {
  await refreshAll()
  await syncFromRoute()
})

onBeforeUnmount(() => {
  if (taskEventSource.value) {
    taskEventSource.value.close()
  }
})
</script>

<style scoped>
.workspace-shell {
  min-height: 100vh;
  padding: 24px;
  color: #edf3ef;
  background:
    radial-gradient(circle at top left, rgba(255, 185, 92, 0.15), transparent 24%),
    radial-gradient(circle at top right, rgba(92, 150, 255, 0.16), transparent 26%),
    linear-gradient(180deg, #09131a 0%, #10202a 48%, #151218 100%);
}

.workspace-hero,
.workspace-grid {
  max-width: 1520px;
  margin: 0 auto;
}

.workspace-hero {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: end;
  padding-bottom: 20px;
}

.eyebrow,
.panel-tag {
  margin: 0;
  color: #ffbf7d;
  font-size: 12px;
  letter-spacing: 0.16em;
}

.workspace-hero h1 {
  margin: 10px 0 0;
  font-size: clamp(2.1rem, 4vw, 3.8rem);
}

.hero-copy {
  max-width: 780px;
  margin-top: 12px;
  color: rgba(237, 243, 239, 0.74);
}

.hero-actions,
.task-actions,
.artifact-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.workspace-grid {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  gap: 18px;
}

.panel {
  border-radius: 24px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.05);
  box-shadow: 0 26px 72px rgba(0, 0, 0, 0.18);
  padding: 20px;
}

.sidebar {
  max-height: calc(100vh - 48px);
  overflow: auto;
}

.main-stage {
  min-width: 0;
  display: grid;
  gap: 18px;
}

.detail-topbar {
  display: grid;
  gap: 16px;
}

.detail-grid {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr) minmax(360px, 0.95fr);
  gap: 18px;
  align-items: start;
}

.result-panel,
.timeline-panel,
.inspector-panel {
  min-height: 720px;
}

.result-panel,
.inspector-panel {
  display: grid;
  gap: 18px;
}

.panel-head,
.panel-subhead,
.task-card-top,
.timeline-card-head,
.evidence-head,
.artifact-head,
.detail-row,
.task-header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.panel-head h2,
.panel-head h3,
.workspace-hero h1 {
  line-height: 1;
}

.panel-head h2,
.panel-head h3 {
  margin: 8px 0 0;
}

.panel-subhead {
  margin: 16px 0 10px;
  color: rgba(237, 243, 239, 0.72);
}

.task-form,
.template-block,
.task-list,
.timeline-list,
.step-detail,
.evidence-list,
.artifact-list,
.tool-list,
.result-stack {
  display: grid;
  gap: 12px;
}

.field,
.checkbox-field {
  display: grid;
  gap: 8px;
}

.field input,
.field textarea,
.field select,
.filters input,
.filters select {
  width: 100%;
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(10, 15, 22, 0.46);
  color: #edf3ef;
  padding: 12px 14px;
}

.checkbox-field {
  grid-template-columns: 18px 1fr;
  align-items: center;
}

.primary-btn,
.secondary-btn,
.template-btn,
.task-card,
.timeline-card {
  cursor: pointer;
}

.primary-btn,
.secondary-btn {
  min-height: 46px;
  padding: 0 16px;
  border-radius: 14px;
  font-weight: 700;
}

.primary-btn {
  border: none;
  color: #13202a;
  background: linear-gradient(135deg, #ffd26e, #ff905d);
}

.secondary-btn {
  border: 1px solid rgba(255, 255, 255, 0.14);
  color: #edf3ef;
  background: rgba(255, 255, 255, 0.06);
}

.compact {
  min-height: 34px;
  padding: 0 12px;
  border-radius: 12px;
}

.template-btn,
.task-card,
.timeline-card,
.detail-card,
.evidence-card,
.artifact-card,
.tool-card {
  border-radius: 18px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(10, 16, 23, 0.42);
}

.template-btn {
  padding: 12px 14px;
  color: inherit;
  text-align: left;
}

.filters {
  display: grid;
  grid-template-columns: 1.2fr 0.8fr;
  gap: 10px;
}

.task-card,
.timeline-card {
  display: grid;
  gap: 8px;
  width: 100%;
  padding: 14px;
  color: inherit;
  text-align: left;
}

.task-card.active,
.timeline-card.active {
  border-color: rgba(255, 210, 110, 0.56);
  background: linear-gradient(180deg, rgba(255, 210, 110, 0.14), rgba(255, 144, 93, 0.08));
}

.task-card p,
.goal-copy,
.artifact-description,
.detail-card p,
.tool-card p,
.module-card p,
.notes-card li {
  color: rgba(237, 243, 239, 0.74);
}

.task-meta,
.timeline-meta,
.evidence-meta,
.task-header-meta {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  color: rgba(237, 243, 239, 0.66);
  font-size: 0.9rem;
}

.meta-pill,
.status-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 28px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.status-chip.small {
  min-height: 24px;
  padding: 0 10px;
}

.meta-pill,
.pending {
  background: rgba(255, 210, 110, 0.14);
  color: #ffd26e;
}

.success {
  background: rgba(111, 214, 128, 0.15);
  color: #92e2a2;
}

.running {
  background: rgba(115, 173, 255, 0.16);
  color: #a7cbff;
}

.danger {
  background: rgba(255, 140, 126, 0.18);
  color: #ffbcaf;
}

.task-header {
  align-items: start;
}

.task-header h2 {
  margin: 8px 0 0;
}

.detail-row {
  padding: 10px 12px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.04);
}

.detail-card,
.evidence-card,
.artifact-card,
.tool-card {
  padding: 14px;
}

.artifact-highlight,
.fold-card {
  overflow: hidden;
}

.module-list,
.hypothesis-list,
.artifact-list.compact-artifact-list {
  display: grid;
  gap: 10px;
}

.module-card,
.hypothesis-card,
.notes-card {
  padding: 12px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.04);
}

.artifact-heading-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.warning-card {
  border: 1px solid rgba(255, 210, 110, 0.18);
  background: rgba(255, 210, 110, 0.08);
}

.notes-card ul {
  margin: 10px 0 0 18px;
  padding: 0;
}

.step-inspector,
.evidence-panel {
  display: grid;
  gap: 12px;
}

.detail-card pre,
.evidence-card pre,
.code-fold pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'Consolas', 'SFMono-Regular', monospace;
  font-size: 12px;
  line-height: 1.6;
  color: #d9efe1;
}

.code-fold,
.fold-card {
  display: grid;
  gap: 10px;
}

.code-fold summary,
.fold-card summary {
  cursor: pointer;
  font-weight: 700;
}

.code-fold pre {
  max-height: 240px;
  overflow: auto;
  margin-top: 8px;
}

.artifact-actions {
  margin-top: 10px;
}

.empty-state,
.empty-inline,
.compact-empty {
  padding: 18px;
  border-radius: 18px;
  border: 1px dashed rgba(255, 255, 255, 0.12);
  color: rgba(237, 243, 239, 0.64);
  background: rgba(255, 255, 255, 0.03);
}

.danger-state {
  border-color: rgba(255, 140, 126, 0.32);
  color: #ffbcaf;
}

@media (max-width: 1440px) {
  .detail-grid {
    grid-template-columns: 280px minmax(0, 1fr);
  }

  .inspector-panel {
    grid-column: 1 / -1;
    min-height: auto;
  }
}

@media (max-width: 1120px) {
  .workspace-grid,
  .detail-grid {
    grid-template-columns: 1fr;
  }

  .sidebar {
    max-height: none;
  }

  .timeline-panel,
  .result-panel,
  .inspector-panel {
    min-height: auto;
  }
}

@media (max-width: 760px) {
  .workspace-shell {
    padding: 16px;
  }

  .workspace-hero {
    flex-direction: column;
    align-items: stretch;
  }

  .filters {
    grid-template-columns: 1fr;
  }
}
</style>
