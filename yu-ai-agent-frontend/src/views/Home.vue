<template>
  <div class="page-shell">
    <header class="hero">
      <div class="hero-copy">
        <p class="eyebrow">CODEBASE AGENT</p>
        <h1>Codebase Agent Workspace</h1>
        <p class="hero-description">
          面向代码仓库理解、Bug 定位与修复建议生成的任务驱动型 AI Agent 工作台
        </p>
      </div>
      <div class="hero-actions">
        <button class="primary-btn" @click="goToRepos">Open Repositories</button>
        <button class="secondary-btn" @click="goToTaskCreate">Create Bug Diagnosis Task</button>
        <button class="secondary-btn" @click="goToEval">Open Eval Panel</button>
      </div>
    </header>

    <section class="stats-grid">
      <article v-for="metric in metrics" :key="metric.label" class="stat-card">
        <span class="stat-label">{{ metric.label }}</span>
        <strong>{{ metric.value }}</strong>
        <small>{{ metric.hint }}</small>
      </article>
    </section>

    <section class="content-grid">
      <article class="panel">
        <div class="panel-head">
          <div>
            <p class="panel-tag">Repositories</p>
            <h2>Repo Data Source</h2>
          </div>
          <button class="text-btn" @click="goToRepos">Manage Repo</button>
        </div>
        <div v-if="loading" class="empty-state">正在加载仓库数据...</div>
        <div v-else-if="!repos.length" class="empty-state">
          还没有接入任何代码仓库，先添加一个 GitHub Repo 开始构建 Codebase Agent。
        </div>
        <div v-else class="repo-list">
          <button
            v-for="repo in recentRepos"
            :key="repo.repoId || repo.id"
            class="repo-card"
            @click="openRepoTaskFlow(repo)"
          >
            <div class="repo-top">
              <strong>{{ repo.name }}</strong>
              <span class="status-chip" :class="repoStatusClass(repo.indexedStatus)">
                {{ repo.indexedStatus }}
              </span>
            </div>
            <p>{{ repo.url }}</p>
            <div class="repo-meta">
              <span>{{ repo.branch || 'main' }}</span>
              <span>{{ repo.fileCount || 0 }} files</span>
              <span>{{ repo.chunkCount || 0 }} chunks</span>
            </div>
          </button>
        </div>
      </article>

      <article class="panel">
        <div class="panel-head">
          <div>
            <p class="panel-tag">Tasks</p>
            <h2>Bug Diagnosis Missions</h2>
          </div>
          <button class="text-btn" @click="goToTasks">Open Task Detail</button>
        </div>
        <div v-if="loading" class="empty-state">正在加载任务数据...</div>
        <div v-else-if="!tasks.length" class="empty-state">
          还没有任何任务。建议从 BUG_DIAGNOSIS 模板开始，绑定已索引仓库运行完整链路。
        </div>
        <div v-else class="task-list">
          <button
            v-for="task in recentTasks"
            :key="task.taskId"
            class="task-card"
            @click="openTask(task.taskId)"
          >
            <div class="task-top">
              <strong>{{ task.title }}</strong>
              <span class="status-chip" :class="taskStatusClass(task.status)">{{ task.status }}</span>
            </div>
            <p>{{ task.goal }}</p>
            <div class="task-meta">
              <span>{{ task.businessType || 'GENERAL' }}</span>
              <span>{{ task.repoName || 'No Repo' }}</span>
              <span>{{ formatDate(task.updatedAt || task.createdAt) }}</span>
            </div>
          </button>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listAgentTasks, listRepos } from '../api'

const router = useRouter()

const loading = ref(false)
const repos = ref([])
const tasks = ref([])

const recentRepos = computed(() =>
  [...repos.value]
    .sort((a, b) => new Date(b.updatedAt || b.createdAt || 0) - new Date(a.updatedAt || a.createdAt || 0))
    .slice(0, 4)
)

const recentTasks = computed(() =>
  [...tasks.value]
    .sort((a, b) => new Date(b.updatedAt || b.createdAt || 0) - new Date(a.updatedAt || a.createdAt || 0))
    .slice(0, 5)
)

const metrics = computed(() => {
  const repoCount = repos.value.length
  const indexedFiles = repos.value.reduce((sum, repo) => sum + Number(repo.fileCount || 0), 0)
  const codeChunks = repos.value.reduce((sum, repo) => sum + Number(repo.chunkCount || 0), 0)
  const completedTasks = tasks.value.filter((task) => task.status === 'SUCCEEDED').length
  const reviewerBlocks = tasks.value.filter((task) =>
    ['BLOCKED', 'WAITING_APPROVAL'].includes(task.status) ||
    ['REPLAN_TASK', 'MANUAL_FIX', 'STRENGTHEN_HANDOFF'].includes(task.reviewSuggestedAction)
  ).length
  const artifacts = tasks.value.reduce((sum, task) => sum + Number(task.artifactCount || 0), 0)

  return [
    { label: 'Repos', value: repoCount, hint: '已接入代码仓库' },
    { label: 'Indexed Files', value: indexedFiles, hint: '扫描后的代码文件数' },
    { label: 'Code Chunks', value: codeChunks, hint: '可检索代码片段' },
    { label: 'Completed Tasks', value: completedTasks, hint: '已完成任务' },
    { label: 'Reviewer Blocks', value: reviewerBlocks, hint: 'Reviewer 阻断次数' },
    { label: 'Artifacts', value: artifacts, hint: '当前交付物总数' }
  ]
})

const loadData = async () => {
  loading.value = true
  try {
    const [repoList, taskList] = await Promise.all([listRepos(), listAgentTasks()])
    repos.value = Array.isArray(repoList) ? repoList : []
    tasks.value = Array.isArray(taskList) ? taskList : []
  } finally {
    loading.value = false
  }
}

const goToRepos = () => router.push('/repos')
const goToTasks = () => router.push('/tasks')
const goToTaskCreate = () => router.push('/tasks/new')
const goToEval = () => router.push('/eval')

const openTask = (taskId) => {
  router.push(`/tasks/${taskId}`)
}

const openRepoTaskFlow = (repo) => {
  router.push({
    path: '/tasks/new',
    query: {
      repoId: repo.repoId || repo.id,
      businessType: 'BUG_DIAGNOSIS'
    }
  })
}

const formatDate = (value) => {
  if (!value) return '--'
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

const repoStatusClass = (status) => {
  if (status === 'INDEXED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'INDEXING' || status === 'CLONED') return 'running'
  return 'pending'
}

const taskStatusClass = (status) => {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED' || status === 'CANCELLED') return 'danger'
  if (status === 'RUNNING' || status === 'REVIEWING' || status === 'PLANNING') return 'running'
  return 'pending'
}

onMounted(loadData)
</script>

<style scoped>
.page-shell {
  min-height: 100vh;
  padding: 32px;
  color: #eef3ec;
  background:
    radial-gradient(circle at top left, rgba(255, 176, 81, 0.18), transparent 28%),
    radial-gradient(circle at top right, rgba(103, 148, 255, 0.15), transparent 30%),
    linear-gradient(180deg, #091219 0%, #0f1c24 52%, #151318 100%);
}

.hero,
.content-grid,
.stats-grid {
  max-width: 1380px;
  margin: 0 auto;
}

.hero {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: end;
  padding-bottom: 28px;
}

.eyebrow,
.panel-tag {
  margin: 0;
  color: #ffbf7d;
  font-size: 12px;
  letter-spacing: 0.16em;
}

.hero h1 {
  margin: 10px 0 0;
  font-size: clamp(2.4rem, 4vw, 4.4rem);
  line-height: 0.94;
}

.hero-description {
  max-width: 680px;
  margin: 14px 0 0;
  font-size: 1.05rem;
  color: rgba(238, 243, 236, 0.76);
}

.hero-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.primary-btn,
.secondary-btn,
.text-btn {
  cursor: pointer;
  font-weight: 700;
}

.primary-btn,
.secondary-btn {
  min-height: 48px;
  padding: 0 18px;
  border-radius: 14px;
}

.primary-btn {
  border: none;
  color: #132028;
  background: linear-gradient(135deg, #ffd26e, #ff8f5f);
}

.secondary-btn {
  border: 1px solid rgba(255, 255, 255, 0.14);
  color: #eef3ec;
  background: rgba(255, 255, 255, 0.06);
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 14px;
  padding-bottom: 18px;
}

.stat-card,
.panel,
.repo-card,
.task-card {
  border-radius: 22px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.05);
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.2);
}

.stat-card {
  display: grid;
  gap: 8px;
  padding: 18px;
}

.stat-card strong {
  font-size: 2rem;
}

.stat-label,
.stat-card small,
.repo-card p,
.task-card p {
  color: rgba(238, 243, 236, 0.72);
}

.content-grid {
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  gap: 18px;
}

.panel {
  padding: 22px;
}

.panel-head,
.repo-top,
.task-top {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: center;
}

.panel-head {
  margin-bottom: 14px;
}

.panel-head h2 {
  margin: 8px 0 0;
}

.text-btn {
  border: none;
  background: transparent;
  color: #ffd26e;
}

.repo-list,
.task-list {
  display: grid;
  gap: 12px;
}

.repo-card,
.task-card {
  display: grid;
  gap: 10px;
  width: 100%;
  padding: 16px;
  color: inherit;
  text-align: left;
}

.repo-meta,
.task-meta {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  color: rgba(238, 243, 236, 0.66);
  font-size: 0.9rem;
}

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

.pending {
  background: rgba(255, 210, 110, 0.16);
  color: #ffd26e;
}

.empty-state {
  padding: 20px;
  border-radius: 18px;
  border: 1px dashed rgba(255, 255, 255, 0.12);
  color: rgba(238, 243, 236, 0.64);
  background: rgba(255, 255, 255, 0.03);
}

@media (max-width: 1180px) {
  .stats-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .content-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 820px) {
  .page-shell {
    padding: 20px;
  }

  .hero {
    flex-direction: column;
    align-items: stretch;
  }

  .stats-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
