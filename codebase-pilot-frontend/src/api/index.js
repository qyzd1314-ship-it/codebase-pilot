import axios from 'axios'

const API_BASE_URL = import.meta.env.PROD ? '/api' : 'http://localhost:8123/api'

const request = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000
})

export const connectSSE = (url, params = {}, onMessage, onError) => {
  const queryString = Object.keys(params)
    .map(key => `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`)
    .join('&')

  const fullUrl = queryString ? `${API_BASE_URL}${url}?${queryString}` : `${API_BASE_URL}${url}`
  const eventSource = new EventSource(fullUrl)

  eventSource.onmessage = event => {
    if (onMessage) {
      onMessage(event.data)
    }
  }

  eventSource.onerror = error => {
    if (onError) {
      onError(error)
    }
    eventSource.close()
  }

  return eventSource
}

export const chatWithLoveApp = (message, chatId) => connectSSE('/ai/love_app/chat/sse', { message, chatId })

export const chatWithManus = (message, sessionId) => connectSSE('/ai/manus/chat', { message, sessionId })

export const listManusSessions = async (params = {}) =>
  (await request.get('/ai/manus/sessions', { params })).data

export const getManusSession = async (sessionId) => (await request.get(`/ai/manus/sessions/${sessionId}`)).data

export const listManusSessionMessages = async (sessionId) =>
  (await request.get(`/ai/manus/sessions/${sessionId}/messages`)).data

export const renameManusSession = async (sessionId, payload = {}) =>
  (await request.post(`/ai/manus/sessions/${sessionId}/rename`, payload)).data

export const updateManusSessionTags = async (sessionId, payload = {}) =>
  (await request.post(`/ai/manus/sessions/${sessionId}/tags`, payload)).data

export const archiveManusSession = async (sessionId) =>
  (await request.post(`/ai/manus/sessions/${sessionId}/archive`)).data

export const activateManusSession = async (sessionId) =>
  (await request.post(`/ai/manus/sessions/${sessionId}/activate`)).data

export const clearManusSession = async (sessionId) =>
  (await request.post(`/ai/manus/sessions/${sessionId}/clear`)).data

export const pinManusSession = async (sessionId) =>
  (await request.post(`/ai/manus/sessions/${sessionId}/pin`)).data

export const unpinManusSession = async (sessionId) =>
  (await request.post(`/ai/manus/sessions/${sessionId}/unpin`)).data

export const batchArchiveManusSessions = async (sessionIds = []) =>
  (await request.post('/ai/manus/sessions/batch/archive', { sessionIds })).data

export const batchActivateManusSessions = async (sessionIds = []) =>
  (await request.post('/ai/manus/sessions/batch/activate', { sessionIds })).data

export const batchPinManusSessions = async (sessionIds = []) =>
  (await request.post('/ai/manus/sessions/batch/pin', { sessionIds })).data

export const batchUnpinManusSessions = async (sessionIds = []) =>
  (await request.post('/ai/manus/sessions/batch/unpin', { sessionIds })).data

export const listManusApprovals = async (sessionId) =>
  (await request.get('/ai/manus/approvals', { params: { sessionId } })).data

export const listManusEvents = async (sessionId) =>
  (await request.get('/ai/manus/events', { params: { sessionId } })).data

export const listManusToolCalls = async (sessionId) =>
  (await request.get('/ai/manus/tool-calls', { params: { sessionId } })).data

export const approveManusTool = async (sessionId, toolName, payload = {}) =>
  (await request.post(`/ai/manus/approvals/${sessionId}/${toolName}/approve`, payload)).data

export const rejectManusTool = async (sessionId, toolName, payload = {}) =>
  (await request.post(`/ai/manus/approvals/${sessionId}/${toolName}/reject`, payload)).data

export const listAgentTasks = async (params = {}) => (await request.get('/agent/tasks', { params })).data

export const listRepos = async () => (await request.get('/repos')).data

export const getRepo = async (repoId) => (await request.get(`/repos/${repoId}`)).data

export const createRepo = async (payload) => (await request.post('/repos', payload)).data

export const indexRepo = async (repoId) => (await request.post(`/repos/${repoId}/index`)).data

export const searchRepo = async (repoId, payload) => (await request.post(`/repos/${repoId}/search`, payload)).data

export const runEvalReport = async () => (await request.post('/evals/run')).data

export const getAgentTaskOverview = async (params = {}) =>
  (await request.get('/agent/tasks/overview', { params })).data

export const getAgentTask = async (taskId) => (await request.get(`/agent/tasks/${taskId}`)).data

export const createAgentTask = async (payload) => (await request.post('/agent/tasks', payload)).data

export const startAgentTask = async (taskId) => (await request.post(`/agent/tasks/${taskId}/start`)).data

export const duplicateAgentTask = async (taskId) => (await request.post(`/agent/tasks/${taskId}/duplicate`)).data

export const createAgentFollowUpTask = async (taskId) => (await request.post(`/agent/tasks/${taskId}/follow-up`)).data

export const createAgentFollowUpTaskAndStart = async (taskId) =>
  (await request.post(`/agent/tasks/${taskId}/follow-up-and-start`)).data

export const pauseAgentTask = async (taskId) => (await request.post(`/agent/tasks/${taskId}/pause`)).data

export const resumeAgentTask = async (taskId) => (await request.post(`/agent/tasks/${taskId}/resume`)).data

export const reviewAgentTask = async (taskId) => (await request.post(`/agent/tasks/${taskId}/review`)).data

export const replanAgentTask = async (taskId) => (await request.post(`/agent/tasks/${taskId}/replan`)).data

export const confirmAgentTaskPlan = async (taskId) =>
  (await request.post(`/agent/tasks/${taskId}/confirm-plan`)).data

export const cancelAgentTask = async (taskId) => (await request.post(`/agent/tasks/${taskId}/cancel`)).data

export const listAgentTaskSteps = async (taskId) => (await request.get(`/agent/tasks/${taskId}/steps`)).data

export const retryAgentTaskStep = async (taskId, stepId) =>
  (await request.post(`/agent/tasks/${taskId}/steps/${stepId}/retry`)).data

export const listAgentTaskApprovals = async (taskId) => (await request.get(`/agent/tasks/${taskId}/approvals`)).data

export const listAgentTaskToolCalls = async (taskId) => (await request.get(`/agent/tasks/${taskId}/tool-calls`)).data

export const listAgentTaskArtifacts = async (taskId) => (await request.get(`/agent/tasks/${taskId}/artifacts`)).data

export const buildAgentArtifactPreviewUrl = (taskId, artifactId) =>
  `${API_BASE_URL}/agent/tasks/${taskId}/artifacts/${artifactId}/content`

export const buildAgentArtifactDownloadUrl = (taskId, artifactId) =>
  `${API_BASE_URL}/agent/tasks/${taskId}/artifacts/${artifactId}/download`

export const approveAgentApproval = async (approvalId, payload = {}) =>
  (await request.post(`/agent/approvals/${approvalId}/approve`, payload)).data

export const rejectAgentApproval = async (approvalId, payload = {}) =>
  (await request.post(`/agent/approvals/${approvalId}/reject`, payload)).data

export const streamAgentTaskEvents = (taskId) => connectSSE(`/agent/tasks/${taskId}/events/stream`)

export default {
  chatWithLoveApp,
  chatWithManus,
  listManusSessions,
  getManusSession,
  listManusSessionMessages,
  renameManusSession,
  updateManusSessionTags,
  archiveManusSession,
  activateManusSession,
  clearManusSession,
  pinManusSession,
  unpinManusSession,
  batchArchiveManusSessions,
  batchActivateManusSessions,
  batchPinManusSessions,
  batchUnpinManusSessions,
  listManusApprovals,
  listManusEvents,
  listManusToolCalls,
  approveManusTool,
  rejectManusTool,
  listRepos,
  getRepo,
  createRepo,
  indexRepo,
  searchRepo,
  runEvalReport,
  listAgentTasks,
  getAgentTaskOverview,
  getAgentTask,
  createAgentTask,
  startAgentTask,
  duplicateAgentTask,
  createAgentFollowUpTask,
  createAgentFollowUpTaskAndStart,
  pauseAgentTask,
  resumeAgentTask,
  reviewAgentTask,
  replanAgentTask,
  confirmAgentTaskPlan,
  cancelAgentTask,
  listAgentTaskSteps,
  retryAgentTaskStep,
  listAgentTaskApprovals,
  listAgentTaskToolCalls,
  listAgentTaskArtifacts,
  buildAgentArtifactPreviewUrl,
  buildAgentArtifactDownloadUrl,
  approveAgentApproval,
  rejectAgentApproval,
  streamAgentTaskEvents
}
