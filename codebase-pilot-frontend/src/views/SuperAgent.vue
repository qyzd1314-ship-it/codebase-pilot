<template>
  <div class="super-agent-shell">
    <header class="topbar">
      <div>
        <p class="eyebrow">SUPER AGENT</p>
        <h1>聊天式 Agent 工作台</h1>
      </div>
      <div class="topbar-actions">
        <button class="nav-btn" @click="goBack">返回首页</button>
        <button class="nav-btn ghost" @click="createFreshSession">新建会话</button>
        <button class="nav-btn ghost" @click="refreshSessionPanels">刷新面板</button>
      </div>
    </header>

    <main class="super-agent-grid">
      <section class="side-panel">
        <div class="panel-card">
          <div class="panel-head">
            <h2>当前会话</h2>
            <span :class="['status-chip', connectionStatus]">{{ statusLabel }}</span>
          </div>

          <label class="muted" for="session-display-name">会话名称</label>
          <div class="session-name-row">
            <input
              id="session-display-name"
              v-model="sessionDisplayNameInput"
              class="session-name-input"
              type="text"
              maxlength="128"
              placeholder="给这个会话起个名字"
            />
            <button class="nav-btn ghost" :disabled="sessionActionLoading" @click="saveSessionDisplayName">
              {{ sessionActionLoading ? '处理中...' : '保存名称' }}
            </button>
          </div>

          <label class="muted" for="session-tags">会话标签</label>
          <div class="session-name-row">
            <input
              id="session-tags"
              v-model="sessionTagsInput"
              class="session-name-input"
              type="text"
              placeholder="例如：产品化, 核心, 后端"
            />
            <button class="nav-btn ghost" :disabled="sessionActionLoading" @click="saveSessionTags">
              {{ sessionActionLoading ? '处理中...' : '保存标签' }}
            </button>
          </div>

          <div v-if="currentSession?.tags?.length" class="tag-list">
            <button
              v-for="tag in currentSession.tags"
              :key="tag"
              class="tag-chip"
              type="button"
              @click="applyTagFilter(tag)"
            >
              {{ tag }}
            </button>
          </div>

          <p class="muted">会话 ID</p>
          <code class="session-code">{{ manusSessionId }}</code>
          <p class="muted">
            消息数 {{ currentSession?.messageCount ?? 0 }} · 状态 {{ currentSession?.status || 'ACTIVE' }}
            · {{ currentSession?.pinned ? '已置顶' : '未置顶' }}
          </p>
          <p class="muted">
            这里已经支持恢复、审批、工具调用、时间线、检索、排序、置顶、批量管理和标签组织。
          </p>

          <div class="session-management-actions">
            <button class="nav-btn ghost" :disabled="sessionActionLoading" @click="togglePinSession()">
              {{ sessionActionLoading ? '处理中...' : currentSession?.pinned ? '取消置顶' : '置顶会话' }}
            </button>
            <button class="nav-btn ghost" :disabled="sessionActionLoading" @click="toggleArchiveSession">
              {{ sessionActionLoading ? '处理中...' : currentSession?.status === 'ARCHIVED' ? '恢复会话' : '归档会话' }}
            </button>
            <button class="danger-btn" :disabled="sessionActionLoading" @click="clearCurrentSession">
              {{ sessionActionLoading ? '处理中...' : '清空会话' }}
            </button>
          </div>
        </div>

        <div class="panel-card">
          <div class="panel-head">
            <h2>历史会话</h2>
            <span>{{ sessionList.length }}</span>
          </div>

          <div class="session-filter-bar">
            <input
              v-model="sessionKeyword"
              class="session-filter-input"
              type="text"
              placeholder="搜索名称、ID、工作区、标签"
              @input="handleSessionFilterChange"
            />
            <select v-model="sessionStatusFilter" class="session-filter-select" @change="handleSessionFilterChange">
              <option value="ALL">全部状态</option>
              <option value="ACTIVE">活跃</option>
              <option value="ARCHIVED">已归档</option>
              <option value="CLEARED">已清空</option>
            </select>
          </div>

          <div class="session-filter-bar compact">
            <select v-model="sessionSortBy" class="session-filter-select" @change="handleSessionFilterChange">
              <option value="LAST_ACTIVE_DESC">最近活跃</option>
              <option value="MESSAGE_COUNT_DESC">消息数最多</option>
              <option value="CREATED_DESC">最近创建</option>
              <option value="NAME_ASC">名称 A-Z</option>
            </select>
            <input
              v-model="sessionTagFilter"
              class="session-filter-input"
              type="text"
              placeholder="按标签筛选"
              @input="handleSessionFilterChange"
            />
          </div>

          <div class="session-filter-summary">
            <span>{{ sessionFilterSummary }}</span>
            <button v-if="hasSessionFilters" class="inline-link-btn" @click="resetSessionFilters">重置</button>
          </div>

          <div v-if="selectedSessionIds.length" class="batch-toolbar">
            <span>已选择 {{ selectedSessionIds.length }} 个会话</span>
            <div class="batch-actions">
              <button class="nav-btn ghost" :disabled="sessionActionLoading" @click="runBatchAction('pin')">
                批量置顶
              </button>
              <button class="nav-btn ghost" :disabled="sessionActionLoading" @click="runBatchAction('unpin')">
                取消置顶
              </button>
              <button class="nav-btn ghost" :disabled="sessionActionLoading" @click="runBatchAction('archive')">
                批量归档
              </button>
              <button class="nav-btn ghost" :disabled="sessionActionLoading" @click="runBatchAction('activate')">
                批量恢复
              </button>
              <button class="inline-link-btn" :disabled="sessionActionLoading" @click="clearSelection">
                清空选择
              </button>
            </div>
          </div>

          <div v-if="!sessionList.length" class="empty-state">没有匹配的历史会话。</div>
          <div v-else class="session-list">
            <label
              v-for="session in sessionList"
              :key="session.sessionId"
              class="session-item"
              :class="{
                active: session.sessionId === manusSessionId,
                pinned: session.pinned,
                selected: selectedSessionIds.includes(session.sessionId)
              }"
            >
              <div class="session-item-top">
                <input
                  class="session-checkbox"
                  type="checkbox"
                  :checked="selectedSessionIds.includes(session.sessionId)"
                  @change="toggleSessionSelection(session.sessionId)"
                />
                <button class="session-open-btn" @click.stop="switchSession(session.sessionId)">
                  <div class="session-item-head">
                    <strong>{{ session.displayName || session.sessionId }}</strong>
                    <button
                      class="pin-toggle-btn"
                      :class="{ active: session.pinned }"
                      :disabled="sessionActionLoading"
                      @click.stop="togglePinSession(session)"
                    >
                      {{ session.pinned ? '已置顶' : '置顶' }}
                    </button>
                  </div>
                  <small class="session-id-text">{{ session.sessionId }}</small>
                  <div v-if="session.tags?.length" class="tag-list compact">
                    <button
                      v-for="tag in session.tags"
                      :key="`${session.sessionId}-${tag}`"
                      class="tag-chip"
                      type="button"
                      @click.stop="applyTagFilter(tag)"
                    >
                      {{ tag }}
                    </button>
                  </div>
                  <p>{{ session.messageCount || 0 }} 条消息 · {{ session.status || 'ACTIVE' }}</p>
                  <small>{{ formatDateTime(session.lastActiveAt || session.updatedAt || session.createdAt) }}</small>
                </button>
              </div>
            </label>
          </div>
        </div>

        <div class="panel-card">
          <div class="panel-head">
            <h2>工具审批</h2>
            <span>{{ approvals.length }}</span>
          </div>
          <div v-if="!approvals.length" class="empty-state">当前没有审批项。</div>
          <div v-else class="approval-list">
            <article v-for="approval in approvals" :key="approval.toolName" class="approval-card">
              <div class="approval-head">
                <strong>{{ approval.toolName }}</strong>
                <span :class="['badge', badgeClass(approval.status)]">
                  {{ approvalStatusText[approval.status] || approval.status }}
                </span>
              </div>
              <p>{{ approval.reason || '该工具需要人工确认。' }}</p>
              <p v-if="approval.decisionNote" class="meta-text">说明：{{ approval.decisionNote }}</p>
              <div v-if="approval.status === 'PENDING'" class="approval-actions">
                <button class="primary-btn" :disabled="approvalLoading" @click="approveTool(approval.toolName)">
                  {{ approvalLoading ? '处理中...' : '批准并继续' }}
                </button>
                <button class="danger-btn" :disabled="approvalLoading" @click="rejectTool(approval.toolName)">
                  {{ approvalLoading ? '处理中...' : '拒绝' }}
                </button>
              </div>
            </article>
          </div>
        </div>
      </section>

      <section class="chat-panel">
        <div class="panel-card chat-card">
          <div class="panel-head">
            <h2>对话区</h2>
            <span>{{ messages.length }} 条消息</span>
          </div>
          <ChatRoom
            :messages="messages"
            :connection-status="connectionStatus"
            ai-type="super"
            @send-message="sendMessage"
          />
        </div>
      </section>

      <section class="detail-panel">
        <div class="panel-card">
          <div class="panel-head">
            <h2>工具调用</h2>
            <span>{{ toolCalls.length }} calls</span>
          </div>
          <div v-if="!toolCalls.length" class="empty-state">会话里还没有工具调用记录。</div>
          <div v-else class="tool-call-list">
            <article v-for="toolCall in toolCalls" :key="toolCall.id" class="tool-call-card">
              <div class="tool-call-head">
                <div>
                  <strong>{{ toolCall.toolName }}</strong>
                  <p>{{ toolCall.toolCategory }} · 风险 {{ toolCall.riskLevel }}</p>
                </div>
                <span :class="['badge', toolCall.success ? 'status-success' : 'status-danger']">
                  {{ toolCall.success ? 'SUCCESS' : 'FAILED' }}
                </span>
              </div>
              <p><strong>请求：</strong>{{ toolCall.requestPayload || '无' }}</p>
              <p><strong>结果：</strong>{{ toolCall.responsePayload || toolCall.errorMessage || '无返回' }}</p>
              <small>{{ formatDateTime(toolCall.startedAt) }}</small>
            </article>
          </div>
        </div>

        <div class="panel-card">
          <div class="panel-head">
            <h2>会话时间线</h2>
            <span>{{ timelineEvents.length }} events</span>
          </div>
          <div v-if="!timelineEvents.length" class="empty-state">发送第一条消息后，这里会出现执行轨迹。</div>
          <div v-else class="timeline-list">
            <article v-for="item in timelineEvents" :key="item.id" class="timeline-item">
              <div class="timeline-dot" />
              <div class="timeline-body">
                <div class="timeline-head">
                  <strong>{{ item.title || item.eventType }}</strong>
                  <small>{{ formatDateTime(item.createdAt) }}</small>
                </div>
                <p class="timeline-type">{{ item.eventType }}</p>
                <p>{{ item.content || '无附加说明。' }}</p>
              </div>
            </article>
          </div>
        </div>
      </section>
    </main>

    <div class="footer-container">
      <AppFooter />
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import AppFooter from '../components/AppFooter.vue'
import {
  activateManusSession,
  approveManusTool,
  archiveManusSession,
  batchActivateManusSessions,
  batchArchiveManusSessions,
  batchPinManusSessions,
  batchUnpinManusSessions,
  chatWithManus,
  clearManusSession,
  getManusSession,
  listManusApprovals,
  listManusEvents,
  listManusSessionMessages,
  listManusSessions,
  listManusToolCalls,
  pinManusSession,
  renameManusSession,
  rejectManusTool,
  unpinManusSession,
  updateManusSessionTags
} from '../api'

useHead({
  title: '聊天式 Agent 工作台 - Yu AI Agent',
  meta: [
    {
      name: 'description',
      content: '支持恢复、审批、工具调用、时间线、筛选、排序、批量操作和标签组织的聊天式 Agent 工作台。'
    }
  ]
})

const approvalStatusText = {
  PENDING: '待审批',
  APPROVED: '已批准',
  REJECTED: '已拒绝'
}

const route = useRoute()
const router = useRouter()

const messages = ref([])
const connectionStatus = ref('disconnected')
const manusSessionId = ref(route.query.sessionId || `manus-ui-${Date.now()}`)
const sessionList = ref([])
const currentSession = ref(null)
const approvalItems = ref([])
const timelineItems = ref([])
const toolCallItems = ref([])
const approvalLoading = ref(false)
const sessionActionLoading = ref(false)
const sessionDisplayNameInput = ref(manusSessionId.value)
const sessionTagsInput = ref('')
const sessionKeyword = ref('')
const sessionStatusFilter = ref('ALL')
const sessionSortBy = ref('LAST_ACTIVE_DESC')
const sessionTagFilter = ref('')
const selectedSessionIds = ref([])

let eventSource = null
let sessionFilterTimer = null

const approvals = computed(() => approvalItems.value)
const timelineEvents = computed(() => timelineItems.value)
const toolCalls = computed(() => toolCallItems.value)

const hasSessionFilters = computed(() =>
  Boolean(sessionKeyword.value.trim())
  || sessionStatusFilter.value !== 'ALL'
  || sessionSortBy.value !== 'LAST_ACTIVE_DESC'
  || Boolean(sessionTagFilter.value.trim())
)

const sessionFilterSummary = computed(() => {
  const parts = []
  if (sessionKeyword.value.trim()) {
    parts.push(`关键词: ${sessionKeyword.value.trim()}`)
  }
  if (sessionStatusFilter.value !== 'ALL') {
    parts.push(`状态: ${sessionStatusFilter.value}`)
  }
  if (sessionTagFilter.value.trim()) {
    parts.push(`标签: ${sessionTagFilter.value.trim()}`)
  }
  if (sessionSortBy.value !== 'LAST_ACTIVE_DESC') {
    parts.push(`排序: ${sessionSortBy.value}`)
  }
  return parts.length ? parts.join(' · ') : '显示全部历史会话，置顶会话会固定在最前面'
})

const statusLabel = computed(() => {
  if (connectionStatus.value === 'connecting') return '运行中'
  if (connectionStatus.value === 'error') return '异常'
  return '空闲'
})

const normalizeTags = (value) =>
  value
    .split(/[，,]/)
    .map(tag => tag.trim())
    .filter(Boolean)
    .slice(0, 12)

const addMessage = (content, isUser, type = '') => {
  messages.value.push({
    content,
    isUser,
    type,
    time: Date.now()
  })
}

const clearSelection = () => {
  selectedSessionIds.value = []
}

const toggleSessionSelection = (sessionId) => {
  if (selectedSessionIds.value.includes(sessionId)) {
    selectedSessionIds.value = selectedSessionIds.value.filter(id => id !== sessionId)
  } else {
    selectedSessionIds.value = [...selectedSessionIds.value, sessionId]
  }
}

const loadHistoryMessages = async () => {
  const history = await listManusSessionMessages(manusSessionId.value)
  if (!Array.isArray(history) || !history.length) {
    messages.value = []
    return false
  }
  messages.value = history.map((item, index) => ({
    content: item.content,
    isUser: item.role === 'user',
    type: item.messageType || (item.role === 'user' ? 'user-question' : 'ai-answer'),
    time: item.createdAt || Date.now() - (history.length - index) * 1000
  }))
  return true
}

const syncRouteSession = () => {
  router.replace({
    path: '/super-agent',
    query: { sessionId: manusSessionId.value }
  })
}

const refreshSessionList = async () => {
  sessionList.value = await listManusSessions({
    keyword: sessionKeyword.value.trim() || undefined,
    status: sessionStatusFilter.value === 'ALL' ? undefined : sessionStatusFilter.value,
    sortBy: sessionSortBy.value,
    tag: sessionTagFilter.value.trim() || undefined
  })
  selectedSessionIds.value = selectedSessionIds.value.filter(id =>
    sessionList.value.some(session => session.sessionId === id)
  )
}

const refreshCurrentSession = async () => {
  try {
    currentSession.value = await getManusSession(manusSessionId.value)
    sessionDisplayNameInput.value = currentSession.value?.displayName || manusSessionId.value
    sessionTagsInput.value = (currentSession.value?.tags || []).join(', ')
  } catch (error) {
    currentSession.value = null
    sessionDisplayNameInput.value = manusSessionId.value
    sessionTagsInput.value = ''
  }
}

const refreshApprovals = async () => {
  approvalItems.value = await listManusApprovals(manusSessionId.value)
}

const refreshTimeline = async () => {
  timelineItems.value = await listManusEvents(manusSessionId.value)
}

const refreshToolCalls = async () => {
  toolCallItems.value = await listManusToolCalls(manusSessionId.value)
}

const refreshSessionPanels = async () => {
  await Promise.all([
    refreshSessionList(),
    refreshCurrentSession(),
    loadHistoryMessages(),
    refreshApprovals(),
    refreshTimeline(),
    refreshToolCalls()
  ])
}

const queueSessionSearch = () => {
  if (sessionFilterTimer) {
    clearTimeout(sessionFilterTimer)
  }
  sessionFilterTimer = setTimeout(() => {
    refreshSessionList()
    sessionFilterTimer = null
  }, 250)
}

const handleSessionFilterChange = () => {
  queueSessionSearch()
}

const applyTagFilter = async (tag) => {
  sessionTagFilter.value = tag
  await refreshSessionList()
}

const resetSessionFilters = async () => {
  sessionKeyword.value = ''
  sessionStatusFilter.value = 'ALL'
  sessionSortBy.value = 'LAST_ACTIVE_DESC'
  sessionTagFilter.value = ''
  await refreshSessionList()
}

const closeStream = () => {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

const parseStructuredMessage = (data, prefix) => {
  if (!data?.startsWith(prefix)) {
    return null
  }
  try {
    return JSON.parse(data.slice(prefix.length))
  } catch (error) {
    return null
  }
}

const flushBubble = (bufferState, text) => {
  if (!text) return
  if (bufferState.currentAiMessageIndex === null) {
    messages.value.push({
      content: text,
      isUser: false,
      type: 'ai-answer',
      time: Date.now()
    })
    bufferState.currentAiMessageIndex = messages.value.length - 1
  } else {
    messages.value[bufferState.currentAiMessageIndex].content += text
  }
  bufferState.messageBuffer = []
}

const startStreamingMessage = (message, options = {}) => {
  const { silentUserMessage = false } = options
  closeStream()
  if (!silentUserMessage) {
    addMessage(message, true, 'user-question')
  }
  connectionStatus.value = 'connecting'

  const bufferState = {
    currentAiMessageIndex: null,
    messageBuffer: []
  }
  const sentenceEndings = ['。', '！', '？', '.', '!', '?']

  eventSource = chatWithManus(message, manusSessionId.value)
  eventSource.onmessage = async (event) => {
    const data = event.data || ''

    const approvalPayload = parseStructuredMessage(data, 'APPROVAL_REQUIRED|')
    if (approvalPayload) {
      addMessage(
        `工具 ${approvalPayload.toolName} 需要审批${approvalPayload.reason ? `：${approvalPayload.reason}` : '。'}`,
        false,
        'ai-answer'
      )
      connectionStatus.value = 'disconnected'
      closeStream()
      await refreshSessionPanels()
      return
    }

    const rejectedPayload = parseStructuredMessage(data, 'APPROVAL_REJECTED|')
    if (rejectedPayload) {
      addMessage(
        `工具 ${rejectedPayload.toolName} 已被拒绝，本轮不会执行。${rejectedPayload.decisionNote ? `说明：${rejectedPayload.decisionNote}` : ''}`,
        false,
        'ai-answer'
      )
      connectionStatus.value = 'disconnected'
      closeStream()
      await refreshSessionPanels()
      return
    }

    bufferState.messageBuffer.push(data)
    const combinedText = bufferState.messageBuffer.join('')
    const lastChar = data.charAt(data.length - 1)
    const shouldFlush =
      sentenceEndings.includes(lastChar) || combinedText.length > 40 || data.includes('\n\n')
    if (shouldFlush) {
      flushBubble(bufferState, combinedText)
    }
  }

  eventSource.onerror = async () => {
    connectionStatus.value = 'error'
    closeStream()
    if (bufferState.messageBuffer.length > 0) {
      flushBubble(bufferState, bufferState.messageBuffer.join(''))
    }
    await refreshSessionPanels()
  }
}

const sendMessage = (message) => {
  startStreamingMessage(message)
}

const approveTool = async (toolName) => {
  approvalLoading.value = true
  try {
    await approveManusTool(manusSessionId.value, toolName, {
      approvedBy: 'web-user',
      decisionNote: 'Approved from SuperAgent page.'
    })
    await refreshSessionPanels()
    addMessage(`已批准工具 ${toolName}，智能体正在继续执行。`, false, 'ai-final')
    startStreamingMessage('continue', { silentUserMessage: true })
  } finally {
    approvalLoading.value = false
  }
}

const rejectTool = async (toolName) => {
  approvalLoading.value = true
  try {
    await rejectManusTool(manusSessionId.value, toolName, {
      approvedBy: 'web-user',
      decisionNote: 'Rejected from SuperAgent page.'
    })
    await refreshSessionPanels()
    addMessage(`已拒绝工具 ${toolName}。你可以补充要求，智能体会尝试其他路径。`, false, 'ai-final')
  } finally {
    approvalLoading.value = false
  }
}

const saveSessionDisplayName = async () => {
  sessionActionLoading.value = true
  try {
    currentSession.value = await renameManusSession(manusSessionId.value, {
      displayName: sessionDisplayNameInput.value
    })
    await refreshSessionList()
    addMessage(`会话名称已更新为 ${currentSession.value.displayName}。`, false, 'ai-final')
  } finally {
    sessionActionLoading.value = false
  }
}

const saveSessionTags = async () => {
  sessionActionLoading.value = true
  try {
    currentSession.value = await updateManusSessionTags(manusSessionId.value, {
      tags: normalizeTags(sessionTagsInput.value)
    })
    sessionTagsInput.value = (currentSession.value.tags || []).join(', ')
    await refreshSessionList()
    addMessage('会话标签已更新。', false, 'ai-final')
  } finally {
    sessionActionLoading.value = false
  }
}

const togglePinSession = async (session = null) => {
  const targetSession = session || currentSession.value
  if (!targetSession?.sessionId) return
  sessionActionLoading.value = true
  try {
    const updatedSession = targetSession.pinned
      ? await unpinManusSession(targetSession.sessionId)
      : await pinManusSession(targetSession.sessionId)
    if (targetSession.sessionId === manusSessionId.value) {
      currentSession.value = updatedSession
    }
    await refreshSessionList()
    if (targetSession.sessionId !== manusSessionId.value) {
      await refreshCurrentSession()
    }
    addMessage(
      updatedSession.pinned
        ? `会话 ${updatedSession.displayName || updatedSession.sessionId} 已置顶。`
        : `会话 ${updatedSession.displayName || updatedSession.sessionId} 已取消置顶。`,
      false,
      'ai-final'
    )
  } finally {
    sessionActionLoading.value = false
  }
}

const runBatchAction = async (action) => {
  if (!selectedSessionIds.value.length) return
  sessionActionLoading.value = true
  try {
    if (action === 'archive') {
      await batchArchiveManusSessions(selectedSessionIds.value)
      addMessage(`已批量归档 ${selectedSessionIds.value.length} 个会话。`, false, 'ai-final')
    } else if (action === 'activate') {
      await batchActivateManusSessions(selectedSessionIds.value)
      addMessage(`已批量恢复 ${selectedSessionIds.value.length} 个会话。`, false, 'ai-final')
    } else if (action === 'pin') {
      await batchPinManusSessions(selectedSessionIds.value)
      addMessage(`已批量置顶 ${selectedSessionIds.value.length} 个会话。`, false, 'ai-final')
    } else if (action === 'unpin') {
      await batchUnpinManusSessions(selectedSessionIds.value)
      addMessage(`已批量取消置顶 ${selectedSessionIds.value.length} 个会话。`, false, 'ai-final')
    }
    clearSelection()
    await refreshSessionPanels()
  } finally {
    sessionActionLoading.value = false
  }
}

const toggleArchiveSession = async () => {
  sessionActionLoading.value = true
  try {
    currentSession.value = currentSession.value?.status === 'ARCHIVED'
      ? await activateManusSession(manusSessionId.value)
      : await archiveManusSession(manusSessionId.value)
    await refreshSessionList()
    addMessage(
      currentSession.value?.status === 'ARCHIVED'
        ? `会话 ${currentSession.value.displayName || manusSessionId.value} 已归档。`
        : `会话 ${currentSession.value?.displayName || manusSessionId.value} 已恢复为活跃状态。`,
      false,
      'ai-final'
    )
  } finally {
    sessionActionLoading.value = false
  }
}

const clearCurrentSession = async () => {
  if (!window.confirm('确认清空当前会话的聊天记录、审批、时间线和工具调用记录吗？')) return
  sessionActionLoading.value = true
  try {
    currentSession.value = await clearManusSession(manusSessionId.value)
    messages.value = []
    approvalItems.value = []
    timelineItems.value = []
    toolCallItems.value = []
    sessionDisplayNameInput.value = currentSession.value?.displayName || manusSessionId.value
    sessionTagsInput.value = ''
    await refreshSessionList()
    addMessage('当前会话已清空，现在可以从一个干净的上下文重新开始。', false, 'ai-final')
  } finally {
    sessionActionLoading.value = false
  }
}

const switchSession = async (sessionId) => {
  if (!sessionId || sessionId === manusSessionId.value) return
  closeStream()
  manusSessionId.value = sessionId
  syncRouteSession()
  await refreshSessionPanels()
  addMessage(`已切换到历史会话 ${currentSession.value?.displayName || sessionId}。你可以继续发送新消息。`, false, 'ai-final')
}

const createFreshSession = async () => {
  closeStream()
  manusSessionId.value = `manus-ui-${Date.now()}`
  messages.value = []
  currentSession.value = null
  sessionDisplayNameInput.value = manusSessionId.value
  sessionTagsInput.value = ''
  approvalItems.value = []
  timelineItems.value = []
  toolCallItems.value = []
  clearSelection()
  addMessage('我是 AI 超级智能体，可以调用受控工具来完成更复杂的任务。', false)
  syncRouteSession()
  await refreshSessionList()
}

const badgeClass = (status) => {
  if (status === 'APPROVED') return 'status-success'
  if (status === 'REJECTED') return 'status-danger'
  return 'status-pending'
}

const formatDateTime = (value) => {
  if (!value) return '--'
  return new Date(value).toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

const goBack = () => {
  router.push('/')
}

watch(
  () => route.query.sessionId,
  async (sessionId) => {
    if (sessionId && sessionId !== manusSessionId.value) {
      manusSessionId.value = sessionId
      await refreshSessionPanels()
      addMessage(`已载入历史会话 ${currentSession.value?.displayName || sessionId}。你可以继续发送新消息。`, false, 'ai-final')
    }
  }
)

onMounted(async () => {
  syncRouteSession()
  await refreshSessionPanels()
  if (!messages.value.length) {
    addMessage('我是 AI 超级智能体，可以调用受控工具来完成更复杂的任务。', false)
  }
})

onBeforeUnmount(() => {
  if (sessionFilterTimer) clearTimeout(sessionFilterTimer)
  closeStream()
})
</script>

<style scoped>
.super-agent-shell {
  min-height: 100vh;
  color: #f5efe6;
  background:
    radial-gradient(circle at top left, rgba(77, 133, 255, 0.18), transparent 30%),
    radial-gradient(circle at bottom right, rgba(255, 130, 98, 0.14), transparent 32%),
    linear-gradient(180deg, #0f1620 0%, #101824 48%, #141118 100%);
}

.topbar {
  display: flex;
  justify-content: space-between;
  align-items: end;
  gap: 18px;
  max-width: 1440px;
  margin: 0 auto;
  padding: 40px 24px 22px;
}

.eyebrow {
  margin: 0;
  color: #ffbe86;
  font-size: 12px;
  letter-spacing: 0.16em;
}

.topbar h1 {
  margin: 8px 0 0;
  font-size: clamp(2rem, 3.5vw, 3.2rem);
  line-height: 1;
}

.topbar-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.nav-btn,
.primary-btn,
.danger-btn,
.session-filter-select,
.pin-toggle-btn,
.session-open-btn,
.tag-chip {
  min-height: 42px;
  padding: 0 16px;
  border-radius: 14px;
  font-weight: 600;
}

.nav-btn,
.session-filter-select {
  color: #f5efe6;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(255, 255, 255, 0.05);
}

.nav-btn.ghost,
.tag-chip {
  background: rgba(255, 187, 115, 0.08);
}

.primary-btn {
  border: none;
  color: #171717;
  background: linear-gradient(135deg, #ffd56f, #ff935a);
}

.danger-btn {
  border: 1px solid rgba(255, 142, 130, 0.2);
  color: #fff2f0;
  background: rgba(255, 142, 130, 0.16);
}

.pin-toggle-btn {
  min-height: 30px;
  padding: 0 12px;
  border: 1px solid rgba(255, 213, 111, 0.25);
  background: rgba(255, 213, 111, 0.08);
  color: #ffd56f;
}

.pin-toggle-btn.active {
  color: #171717;
  background: linear-gradient(135deg, #ffd56f, #ffb14a);
}

.super-agent-grid {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr) 380px;
  gap: 18px;
  max-width: 1440px;
  margin: 0 auto;
  padding: 0 24px 32px;
}

.side-panel,
.chat-panel,
.detail-panel {
  min-width: 0;
}

.panel-card {
  border-radius: 24px;
  border: 1px solid rgba(255, 255, 255, 0.11);
  background: rgba(255, 255, 255, 0.05);
  box-shadow: 0 22px 72px rgba(0, 0, 0, 0.2);
  padding: 20px;
}

.side-panel,
.detail-panel {
  display: grid;
  gap: 16px;
  align-content: start;
}

.chat-card {
  min-height: 760px;
}

.panel-head,
.approval-head,
.tool-call-head,
.timeline-head,
.session-item-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.panel-head h2 {
  margin: 0;
}

.muted,
.approval-card p,
.tool-call-card p,
.timeline-body p,
.timeline-type,
.meta-text,
.session-item p,
.session-item small,
.session-filter-summary,
.batch-toolbar {
  color: rgba(245, 239, 230, 0.72);
}

.session-name-row,
.session-management-actions,
.session-filter-bar,
.session-filter-summary,
.batch-toolbar,
.batch-actions,
.tag-list {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.session-management-actions {
  margin-top: 14px;
}

.session-filter-bar {
  margin-top: 14px;
}

.session-filter-bar.compact {
  margin-top: 10px;
}

.session-filter-summary,
.batch-toolbar {
  margin-top: 10px;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
}

.batch-toolbar {
  padding: 12px;
  border-radius: 14px;
  background: rgba(255, 213, 111, 0.08);
  border: 1px solid rgba(255, 213, 111, 0.12);
}

.tag-list {
  margin-top: 10px;
}

.tag-list.compact {
  margin-top: 8px;
}

.tag-chip {
  min-height: 32px;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid rgba(77, 133, 255, 0.18);
  color: #dfe8ff;
  background: rgba(77, 133, 255, 0.15);
  font-size: 12px;
  cursor: pointer;
}

.session-name-input,
.session-filter-input {
  flex: 1;
  min-width: 0;
  min-height: 42px;
  padding: 0 14px;
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(9, 14, 20, 0.46);
  color: #f5efe6;
}

.session-filter-select {
  min-width: 120px;
}

.session-name-input:focus,
.session-filter-input:focus,
.session-filter-select:focus {
  outline: 1px solid rgba(255, 213, 111, 0.5);
}

.inline-link-btn {
  padding: 0;
  border: none;
  background: transparent;
  color: #ffd56f;
  cursor: pointer;
}

.session-code {
  display: block;
  margin: 8px 0 14px;
  padding: 10px 12px;
  border-radius: 14px;
  background: rgba(9, 14, 20, 0.52);
  color: #ffd56f;
  word-break: break-all;
}

.status-chip,
.badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.status-chip.disconnected,
.status-chip.error,
.badge.status-danger {
  color: #351512;
  background: #ff8e82;
}

.status-chip.connecting,
.badge.status-success {
  color: #162514;
  background: #9fe870;
}

.badge.status-pending {
  color: #30261a;
  background: #ffd99a;
}

.session-list,
.approval-list,
.tool-call-list,
.timeline-list {
  display: grid;
  gap: 12px;
  margin-top: 16px;
}

.session-item,
.approval-card,
.tool-call-card,
.timeline-item {
  padding: 14px;
  border-radius: 16px;
  border: 1px solid transparent;
  background: rgba(9, 14, 20, 0.46);
}

.session-item {
  display: block;
}

.session-item.active {
  border-color: rgba(255, 213, 111, 0.52);
  background: linear-gradient(180deg, rgba(255, 213, 111, 0.12), rgba(255, 147, 90, 0.08));
}

.session-item.pinned {
  box-shadow: inset 0 0 0 1px rgba(255, 213, 111, 0.2);
}

.session-item.selected {
  border-color: rgba(159, 232, 112, 0.42);
}

.session-item-top {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.session-checkbox {
  margin-top: 12px;
}

.session-open-btn {
  padding: 0;
  border: none;
  background: transparent;
  color: inherit;
  text-align: left;
  width: 100%;
}

.session-item strong {
  display: block;
  color: #ffd56f;
}

.session-item p,
.session-item small {
  display: block;
  margin-top: 8px;
}

.session-id-text {
  margin-top: 6px;
  color: rgba(245, 239, 230, 0.48);
  word-break: break-all;
}

.approval-actions {
  display: flex;
  gap: 10px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.tool-call-card strong {
  color: #ffd56f;
}

.tool-call-card p,
.tool-call-card small {
  display: block;
  margin-top: 8px;
  line-height: 1.7;
}

.timeline-item {
  display: grid;
  grid-template-columns: 12px minmax(0, 1fr);
  gap: 14px;
}

.timeline-dot {
  width: 12px;
  height: 12px;
  margin-top: 8px;
  border-radius: 50%;
  background: linear-gradient(135deg, #ffd56f, #ff935a);
  box-shadow: 0 0 0 6px rgba(255, 147, 90, 0.12);
}

.timeline-body {
  min-width: 0;
}

.timeline-body p {
  margin: 8px 0 0;
  line-height: 1.7;
}

.timeline-type {
  margin: 6px 0 0;
  font-size: 12px;
  letter-spacing: 0.06em;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100px;
  margin-top: 16px;
  border-radius: 18px;
  background: rgba(9, 14, 20, 0.3);
  color: rgba(245, 239, 230, 0.72);
}

.footer-container {
  margin-top: auto;
}

@media (max-width: 1280px) {
  .super-agent-grid {
    grid-template-columns: 1fr;
  }

  .chat-card {
    min-height: auto;
  }
}

@media (max-width: 768px) {
  .topbar {
    padding: 28px 16px 18px;
  }

  .super-agent-grid {
    padding: 0 16px 24px;
  }

  .panel-head,
  .approval-head,
  .tool-call-head,
  .timeline-head,
  .session-item-head,
  .session-item-top,
  .batch-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .session-filter-summary {
    align-items: flex-start;
  }
}
</style>
