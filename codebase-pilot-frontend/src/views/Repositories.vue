<template>
  <div class="repos-shell">
    <header class="repos-hero">
      <div>
        <p class="eyebrow">CODEBASE AGENT</p>
        <h1>Repositories</h1>
        <p class="hero-copy">
          把 GitHub 仓库接入到 Codebase Agent，完成 Clone、Index、Create Task 的最小闭环。
        </p>
      </div>
      <div class="hero-actions">
        <button class="secondary-btn" @click="goHome">Dashboard</button>
        <button class="secondary-btn" @click="goTasks">Task Workspace</button>
      </div>
    </header>

    <section class="page-grid">
      <article class="panel form-panel">
        <div class="panel-head">
          <div>
            <p class="panel-tag">Repo Intake</p>
            <h2>Clone GitHub Repo</h2>
          </div>
        </div>

        <form class="repo-form" @submit.prevent="submitRepo">
          <label class="field">
            <span>GitHub Repo URL</span>
            <input
              v-model.trim="repoForm.url"
              type="url"
              placeholder="https://github.com/owner/project.git"
            />
          </label>

          <label class="field">
            <span>Branch</span>
            <input v-model.trim="repoForm.branch" type="text" placeholder="main" />
          </label>

          <button class="primary-btn" type="submit" :disabled="submitting">
            {{ submitting ? 'Cloning...' : 'Clone Repo' }}
          </button>
        </form>

        <p v-if="notice" class="notice">{{ notice }}</p>
      </article>

      <article class="panel list-panel">
        <div class="panel-head">
          <div>
            <p class="panel-tag">Repo Status</p>
            <h2>Source Repositories</h2>
          </div>
          <button class="text-btn" @click="loadRepos">Refresh</button>
        </div>

        <div v-if="loading" class="empty-state">正在加载仓库列表...</div>
        <div v-else-if="!repos.length" class="empty-state">
          当前还没有仓库，先添加一个 GitHub Repo。
        </div>
        <div v-else class="repo-table">
          <div class="repo-table-head">
            <span>Repo</span>
            <span>Status</span>
            <span>Files</span>
            <span>Chunks</span>
            <span>Updated</span>
            <span>Actions</span>
          </div>

          <div v-for="repo in repos" :key="repo.repoId || repo.id" class="repo-row">
            <div class="repo-main">
              <strong>{{ repo.name }}</strong>
              <p>{{ repo.url }}</p>
              <small>Branch: {{ repo.branch || 'main' }}</small>
            </div>
            <span class="status-chip" :class="statusClass(repo.indexedStatus)">
              {{ repo.indexedStatus }}
            </span>
            <span>{{ repo.fileCount || 0 }}</span>
            <span>{{ repo.chunkCount || 0 }}</span>
            <span>{{ formatDate(repo.lastIndexedAt || repo.updatedAt || repo.createdAt) }}</span>
            <div class="row-actions">
              <button class="secondary-btn compact" :disabled="busyRepoId === currentRepoId(repo)" @click="triggerIndex(repo)">
                {{ busyRepoId === currentRepoId(repo) ? 'Indexing...' : repo.chunkCount ? 'Re-index' : 'Index' }}
              </button>
              <button class="secondary-btn compact" @click="createBugTask(repo)">Create Task</button>
            </div>
          </div>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createRepo, indexRepo, listRepos } from '../api'

const router = useRouter()

const loading = ref(false)
const submitting = ref(false)
const busyRepoId = ref('')
const notice = ref('')
const repos = ref([])
const repoForm = ref({
  url: '',
  branch: 'main'
})

const currentRepoId = (repo) => repo.repoId || repo.id

const loadRepos = async () => {
  loading.value = true
  try {
    const data = await listRepos()
    repos.value = Array.isArray(data) ? data : []
  } finally {
    loading.value = false
  }
}

const submitRepo = async () => {
  if (!repoForm.value.url) {
    window.alert('请先输入 GitHub Repo URL。')
    return
  }

  submitting.value = true
  notice.value = ''
  try {
    const created = await createRepo({
      url: repoForm.value.url,
      branch: repoForm.value.branch || 'main'
    })
    notice.value = `仓库 ${created.name || created.repoId} 已创建，当前状态：${created.indexedStatus || created.status || 'CLONED'}`
    repoForm.value.url = ''
    repoForm.value.branch = 'main'
    await loadRepos()
  } catch (error) {
    window.alert(error?.response?.data?.message || error?.message || '创建仓库失败。')
  } finally {
    submitting.value = false
  }
}

const triggerIndex = async (repo) => {
  const repoId = currentRepoId(repo)
  busyRepoId.value = repoId
  try {
    await indexRepo(repoId)
    await loadRepos()
  } catch (error) {
    window.alert(error?.response?.data?.message || error?.message || '索引失败。')
  } finally {
    busyRepoId.value = ''
  }
}

const createBugTask = (repo) => {
  router.push({
    path: '/tasks/new',
    query: {
      repoId: currentRepoId(repo),
      businessType: 'BUG_DIAGNOSIS'
    }
  })
}

const formatDate = (value) => {
  if (!value) return '--'
  return new Date(value).toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

const statusClass = (status) => {
  if (status === 'INDEXED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'INDEXING' || status === 'CLONED') return 'running'
  return 'pending'
}

const goHome = () => router.push('/')
const goTasks = () => router.push('/tasks')

onMounted(loadRepos)
</script>

<style scoped>
.repos-shell {
  min-height: 100vh;
  padding: 30px;
  color: #eff3ee;
  background:
    radial-gradient(circle at top left, rgba(255, 176, 81, 0.16), transparent 26%),
    radial-gradient(circle at bottom right, rgba(84, 154, 255, 0.13), transparent 30%),
    linear-gradient(180deg, #0a1219 0%, #10202a 55%, #171119 100%);
}

.repos-hero,
.page-grid {
  max-width: 1380px;
  margin: 0 auto;
}

.repos-hero {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: end;
  padding-bottom: 24px;
}

.eyebrow,
.panel-tag {
  margin: 0;
  color: #ffbf7d;
  font-size: 12px;
  letter-spacing: 0.16em;
}

.repos-hero h1 {
  margin: 10px 0 0;
  font-size: clamp(2.1rem, 4vw, 3.6rem);
}

.hero-copy {
  max-width: 720px;
  margin-top: 12px;
  color: rgba(239, 243, 238, 0.74);
}

.hero-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.page-grid {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 18px;
}

.panel {
  border-radius: 24px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.05);
  box-shadow: 0 26px 72px rgba(0, 0, 0, 0.2);
  padding: 22px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.panel-head h2 {
  margin: 8px 0 0;
}

.repo-form {
  display: grid;
  gap: 14px;
}

.field {
  display: grid;
  gap: 8px;
}

.field input {
  width: 100%;
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(7, 14, 20, 0.45);
  color: #eff3ee;
  padding: 12px 14px;
}

.primary-btn,
.secondary-btn,
.text-btn {
  cursor: pointer;
  font-weight: 700;
}

.primary-btn,
.secondary-btn {
  min-height: 46px;
  padding: 0 16px;
  border-radius: 14px;
}

.primary-btn {
  border: none;
  color: #15202a;
  background: linear-gradient(135deg, #ffd26e, #ff905d);
}

.secondary-btn {
  border: 1px solid rgba(255, 255, 255, 0.14);
  color: #eff3ee;
  background: rgba(255, 255, 255, 0.06);
}

.secondary-btn.compact {
  min-height: 34px;
  padding: 0 12px;
  border-radius: 12px;
}

.text-btn {
  border: none;
  background: transparent;
  color: #ffd26e;
}

.notice,
.repo-main p,
.repo-main small {
  color: rgba(239, 243, 238, 0.72);
}

.repo-table {
  display: grid;
  gap: 12px;
}

.repo-table-head,
.repo-row {
  display: grid;
  grid-template-columns: minmax(0, 2.2fr) 0.9fr 0.7fr 0.7fr 0.9fr 1.1fr;
  gap: 12px;
  align-items: center;
}

.repo-table-head {
  padding: 0 10px;
  color: rgba(239, 243, 238, 0.6);
  font-size: 0.9rem;
}

.repo-row {
  padding: 16px;
  border-radius: 18px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(10, 16, 24, 0.44);
}

.repo-main {
  min-width: 0;
}

.repo-main strong {
  display: block;
}

.repo-main p {
  margin: 8px 0 6px;
  word-break: break-all;
}

.row-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
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
  color: rgba(239, 243, 238, 0.64);
  background: rgba(255, 255, 255, 0.03);
}

@media (max-width: 1200px) {
  .page-grid {
    grid-template-columns: 1fr;
  }

  .repo-table-head {
    display: none;
  }

  .repo-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .repos-shell {
    padding: 18px;
  }

  .repos-hero {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
