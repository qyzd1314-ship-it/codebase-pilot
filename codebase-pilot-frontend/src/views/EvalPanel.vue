<template>
  <div class="eval-shell">
    <header class="eval-hero">
      <div>
        <p class="eyebrow">EVAL WORKSPACE</p>
        <h1>Codebase Agent Eval Panel</h1>
        <p class="hero-copy">
          Review retrieval quality, diagnosis grounding, reviewer gates, and patch outputs in one place.
          This page is designed to support clear interview walkthroughs of how the agent is evaluated.
        </p>
      </div>
      <div class="hero-actions">
        <button class="secondary-btn" @click="goHome">Dashboard</button>
        <button class="secondary-btn" @click="goTasks">Tasks</button>
        <button class="primary-btn" :disabled="loading" @click="loadReport">
          {{ loading ? 'Running Eval...' : 'Run Eval' }}
        </button>
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
      <article class="panel strategy-panel">
        <div class="panel-head">
          <div>
            <p class="panel-tag">Strategy Compare</p>
            <h2>LLM vs RAG vs Reviewer</h2>
          </div>
          <span>{{ strategyReports.length }} strategies</span>
        </div>
        <div v-if="loading" class="empty-state">Running eval and collecting strategy metrics...</div>
        <div v-else-if="errorMessage" class="empty-state danger-state">{{ errorMessage }}</div>
        <div v-else-if="!strategyReports.length" class="empty-state">No eval report is loaded yet.</div>
        <div v-else class="strategy-grid">
          <article v-for="strategy in strategyReports" :key="strategy.strategy" class="strategy-card">
            <div class="strategy-head">
              <strong>{{ strategy.strategy }}</strong>
              <span class="status-chip pending">{{ strategy.totalCases }} case</span>
            </div>
            <div class="strategy-metrics">
              <div class="metric-chip">
                <span>Recall@5</span>
                <strong>{{ formatDecimal(strategy.recallAt5) }}</strong>
              </div>
              <div class="metric-chip">
                <span>Grounding</span>
                <strong>{{ formatDecimal(strategy.evidenceGroundingRate) }}</strong>
              </div>
              <div class="metric-chip">
                <span>JSON</span>
                <strong>{{ formatDecimal(strategy.jsonParseSuccessRate) }}</strong>
              </div>
              <div class="metric-chip">
                <span>Reviewer</span>
                <strong>{{ formatDecimal(strategy.reviewerPassRate) }}</strong>
              </div>
              <div class="metric-chip">
                <span>Latency</span>
                <strong>{{ formatLatency(strategy.averageLatencyMs) }}</strong>
              </div>
              <div class="metric-chip">
                <span>Tokens</span>
                <strong>{{ formatInteger(strategy.averageTokenCost) }}</strong>
              </div>
            </div>
          </article>
        </div>
      </article>

      <article class="panel report-panel">
        <div class="panel-head">
          <div>
            <p class="panel-tag">Eval Report</p>
            <h2>Run Metadata</h2>
          </div>
        </div>
        <div v-if="report" class="report-meta">
          <div class="meta-row">
            <span>Total Cases</span>
            <strong>{{ report.totalCases }}</strong>
          </div>
          <div class="meta-row">
            <span>Total Runs</span>
            <strong>{{ report.totalRuns }}</strong>
          </div>
          <div class="meta-row">
            <span>Report Path</span>
            <strong class="mono">{{ report.reportPath || '--' }}</strong>
          </div>
          <div class="meta-row">
            <span>JSON Parse Success</span>
            <strong>{{ formatDecimal(report.jsonParseSuccessRate) }}</strong>
          </div>
          <div class="meta-row">
            <span>Average Latency</span>
            <strong>{{ formatLatency(report.averageLatencyMs) }}</strong>
          </div>
          <div class="meta-row">
            <span>Average Token Cost</span>
            <strong>{{ formatInteger(report.averageTokenCost) }}</strong>
          </div>
        </div>
        <div v-else class="empty-state">Run eval to generate a fresh report.</div>
      </article>
    </section>

    <section class="workspace-grid">
      <aside class="panel case-panel">
        <div class="panel-head">
          <div>
            <p class="panel-tag">Eval Cases</p>
            <h2>Per Case Results</h2>
          </div>
          <span>{{ caseResults.length }} rows</span>
        </div>

        <div class="filters">
          <input v-model.trim="filters.keyword" type="text" placeholder="Search case / strategy / question" />
          <select v-model="filters.strategy">
            <option value="">All Strategies</option>
            <option v-for="strategy in strategyOptions" :key="strategy" :value="strategy">{{ strategy }}</option>
          </select>
        </div>

        <div v-if="loading" class="empty-state">Loading per-case results...</div>
        <div v-else-if="!filteredResults.length" class="empty-state">No cases match the current filters.</div>
        <div v-else class="case-list">
          <button
            v-for="item in filteredResults"
            :key="caseRowKey(item)"
            class="case-card"
            :class="{ active: selectedCaseKey === caseRowKey(item) }"
            @click="selectCase(item)"
          >
            <div class="case-card-head">
              <strong>{{ item.caseId }}</strong>
              <span
                class="status-chip"
                :class="item.reviewerPassed ? 'success' : item.reviewerAction === 'REPLAN' ? 'danger' : 'pending'"
              >
                {{ item.strategy }}
              </span>
            </div>
            <p class="question-copy">{{ item.question }}</p>
            <div class="case-meta">
              <span>{{ item.caseType }}</span>
              <span>retrieval {{ item.retrievalHit ? 'hit' : 'miss' }}</span>
              <span>review {{ reviewerFlag(item) }}</span>
            </div>
            <div class="case-metrics">
              <span>Tokens {{ formatInteger(item.totalTokens) }}</span>
              <span>Latency {{ formatLatency(item.latencyMs) }}</span>
            </div>
          </button>
        </div>
      </aside>

      <section class="detail-stage">
        <article class="panel summary-panel">
          <div class="panel-head">
            <div>
              <p class="panel-tag">Case Summary</p>
              <h2>{{ selectedCase?.caseId || 'Select a case row' }}</h2>
            </div>
            <span v-if="selectedCase" class="status-chip" :class="selectedCase.expectedFilesHit ? 'success' : 'danger'">
              {{ selectedCase.strategy }}
            </span>
          </div>

          <div v-if="!selectedCase" class="empty-state">
            Pick a case from the left panel to inspect retrieval matches, diagnosis grounding, reviewer decisions, and patch output.
          </div>
          <div v-else class="summary-grid">
            <div class="summary-card">
              <span>User Question</span>
              <strong>{{ selectedCase.question }}</strong>
            </div>
            <div class="summary-card">
              <span>Expected Files</span>
              <strong>{{ formatList(selectedCase.expectedFiles) }}</strong>
            </div>
            <div class="summary-card">
              <span>Retrieved Files</span>
              <strong>{{ formatList(selectedCase.retrievedFiles) }}</strong>
            </div>
            <div class="summary-card">
              <span>Diagnosis Summary</span>
              <strong>{{ selectedCase.diagnosisSummary || '--' }}</strong>
            </div>
            <div class="summary-card">
              <span>Reviewer</span>
              <strong>{{ selectedCase.reviewerAction || '--' }} / {{ reviewerFlag(selectedCase) }}</strong>
            </div>
            <div class="summary-card">
              <span>Token Cost</span>
              <strong>{{ formatInteger(selectedCase.totalTokens) }}</strong>
            </div>
          </div>
        </article>

        <article class="panel compare-panel">
          <div class="panel-head">
            <div>
              <p class="panel-tag">Strategy Compare</p>
              <h2>Same Case, Different Strategies</h2>
            </div>
            <span>{{ selectedCaseComparisons.length }}</span>
          </div>
          <div v-if="!selectedCaseComparisons.length" class="empty-state">No strategy comparison is available for this case.</div>
          <div v-else class="compare-grid">
            <article v-for="item in selectedCaseComparisons" :key="caseRowKey(item)" class="compare-card">
              <div class="compare-head">
                <strong>{{ item.strategy }}</strong>
                <span class="status-chip" :class="item.reviewerPassed ? 'success' : item.reviewerAction === 'REPLAN' ? 'danger' : 'pending'">
                  {{ reviewerFlag(item) }}
                </span>
              </div>
              <p>Recall@5: {{ formatDecimal(item.recallAt5) }}</p>
              <p>Grounding: {{ formatDecimal(item.evidenceGroundingRate) }}</p>
              <p>JSON: {{ item.jsonParseSuccess ? 'success' : 'failed' }}</p>
              <p>Patch: {{ item.patchGenerated ? 'generated' : 'no patch' }}</p>
            </article>
          </div>
        </article>

        <div class="detail-grid">
          <article class="panel">
            <div class="panel-head">
              <div>
                <p class="panel-tag">Retrieved Chunks</p>
                <h2>Search Evidence</h2>
              </div>
              <span>{{ selectedCase?.retrievedChunks?.length || 0 }}</span>
            </div>
            <div v-if="!selectedCase?.retrievedChunks?.length" class="empty-state">No retrieved chunks are available for this case.</div>
            <div v-else class="chunk-list">
              <article
                v-for="chunk in selectedCase.retrievedChunks"
                :key="chunk.chunkId || `${chunk.filePath}-${chunk.startLine}`"
                class="chunk-card"
              >
                <div class="chunk-head">
                  <strong>{{ chunk.filePath }}</strong>
                  <span>{{ chunk.startLine }}-{{ chunk.endLine }}</span>
                </div>
                <div class="chunk-meta">
                  <span>{{ chunk.symbolName || 'symbol --' }}</span>
                  <span>score {{ formatDecimal(chunk.score) }}</span>
                </div>
                <p>{{ chunk.reason || '--' }}</p>
                <pre>{{ chunk.contentPreview || '--' }}</pre>
              </article>
            </div>
          </article>

          <article class="panel">
            <div class="panel-head">
              <div>
                <p class="panel-tag">Evidence Refs</p>
                <h2>Grounding Anchors</h2>
              </div>
              <span>{{ selectedCase?.evidenceRefs?.length || 0 }}</span>
            </div>
            <div v-if="!selectedCase?.evidenceRefs?.length" class="empty-state">No grounded evidence refs are attached to this result.</div>
            <div v-else class="evidence-list">
              <article
                v-for="evidence in selectedCase.evidenceRefs"
                :key="evidence.chunkId || `${evidence.filePath}-${evidence.startLine}`"
                class="evidence-card"
              >
                <strong>{{ evidence.filePath }}:{{ evidence.startLine }}-{{ evidence.endLine }}</strong>
                <p>{{ evidence.reason || '--' }}</p>
                <pre>{{ evidence.codePreview || '--' }}</pre>
              </article>
            </div>
          </article>

          <article class="panel">
            <div class="panel-head">
              <div>
                <p class="panel-tag">DiagnosisAgent</p>
                <h2>Diagnosis Output</h2>
              </div>
            </div>
            <div v-if="!selectedCase" class="empty-state">Select a case to inspect diagnosis output.</div>
            <div v-else class="json-card">
              <div class="detail-row">
                <span>needMoreSearch</span>
                <strong>{{ selectedCase.needMoreSearch ?? '--' }}</strong>
              </div>
              <pre>{{ prettyJson(selectedCase.diagnosisOutput) }}</pre>
            </div>
          </article>

          <article class="panel">
            <div class="panel-head">
              <div>
                <p class="panel-tag">ReviewerAgent</p>
                <h2>Reviewer Output</h2>
              </div>
            </div>
            <div v-if="!selectedCase" class="empty-state">Select a case to inspect reviewer output.</div>
            <div v-else class="json-card">
              <div class="detail-row">
                <span>Action</span>
                <strong>{{ selectedCase.reviewerAction || '--' }}</strong>
              </div>
              <div class="detail-row">
                <span>Reason</span>
                <strong>{{ selectedCase.reviewerReason || '--' }}</strong>
              </div>
              <pre>{{ prettyJson(selectedCase.reviewerOutput) }}</pre>
            </div>
          </article>

          <article class="panel patch-panel">
            <div class="panel-head">
              <div>
                <p class="panel-tag">PatchAgent</p>
                <h2>Patch Output</h2>
              </div>
            </div>
            <div v-if="!selectedCase" class="empty-state">Select a case to inspect patch output.</div>
            <div v-else-if="!selectedCase.patchGenerated && !hasPatchOutput(selectedCase)" class="empty-state">
              No patch output is available for this strategy result.
            </div>
            <div v-else class="json-card">
              <div class="detail-row">
                <span>Patch Generated</span>
                <strong>{{ selectedCase.patchGenerated ? 'true' : 'false' }}</strong>
              </div>
              <div class="detail-row">
                <span>Files To Change</span>
                <strong>{{ formatList(selectedCase.filesToChange) }}</strong>
              </div>
              <pre>{{ prettyJson(selectedCase.patchOutput) }}</pre>
            </div>
          </article>
        </div>
      </section>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { runEvalReport } from '../api'

const router = useRouter()

const loading = ref(false)
const errorMessage = ref('')
const report = ref(null)
const selectedCaseKey = ref('')
const filters = ref({
  keyword: '',
  strategy: ''
})

const strategyReports = computed(() => report.value?.strategyReports || [])
const caseResults = computed(() => report.value?.perCaseResults || [])
const strategyOptions = computed(() => strategyReports.value.map(item => item.strategy))

const filteredResults = computed(() => {
  const keyword = filters.value.keyword.toLowerCase()
  return caseResults.value.filter((item) => {
    if (filters.value.strategy && item.strategy !== filters.value.strategy) {
      return false
    }
    if (!keyword) {
      return true
    }
    return [
      item.caseId,
      item.question,
      item.caseType,
      item.strategy,
      ...(item.expectedFiles || []),
      ...(item.retrievedFiles || [])
    ]
      .filter(Boolean)
      .some(value => String(value).toLowerCase().includes(keyword))
  })
})

const selectedCase = computed(() =>
  filteredResults.value.find(item => caseRowKey(item) === selectedCaseKey.value) ||
  caseResults.value.find(item => caseRowKey(item) === selectedCaseKey.value) ||
  null
)

const selectedCaseComparisons = computed(() => {
  if (!selectedCase.value) {
    return []
  }
  return caseResults.value.filter(item => item.caseId === selectedCase.value.caseId)
})

const metrics = computed(() => {
  if (!report.value) {
    return [
      { label: 'Total Cases', value: '--', hint: 'How many eval cases are loaded' },
      { label: 'Recall@5', value: '--', hint: 'Retrieval hit quality' },
      { label: 'Evidence Grounding Rate', value: '--', hint: 'How grounded the diagnosis is' },
      { label: 'JSON Parse Success Rate', value: '--', hint: 'Structured output reliability' },
      { label: 'Average Latency', value: '--', hint: 'Mean end-to-end runtime' },
      { label: 'Average Token Cost', value: '--', hint: 'Mean token usage' },
      { label: 'Reviewer Pass Rate', value: '--', hint: 'How often reviewer approves' }
    ]
  }

  const reviewerPassRate = strategyReports.value.length
    ? average(strategyReports.value.map(item => Number(item.reviewerPassRate || 0)))
    : 0

  return [
    { label: 'Total Cases', value: report.value.totalCases, hint: 'How many eval cases are loaded' },
    { label: 'Recall@5', value: formatDecimal(report.value.recallAt5), hint: 'Retrieval hit quality' },
    { label: 'Evidence Grounding Rate', value: formatDecimal(report.value.evidenceGroundingRate), hint: 'How grounded the diagnosis is' },
    { label: 'JSON Parse Success Rate', value: formatDecimal(report.value.jsonParseSuccessRate), hint: 'Structured output reliability' },
    { label: 'Average Latency', value: formatLatency(report.value.averageLatencyMs), hint: 'Mean end-to-end runtime' },
    { label: 'Average Token Cost', value: formatInteger(report.value.averageTokenCost), hint: 'Mean token usage' },
    { label: 'Reviewer Pass Rate', value: formatDecimal(reviewerPassRate), hint: 'How often reviewer approves' }
  ]
})

const loadReport = async () => {
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await runEvalReport()
    report.value = data
    const first = data?.perCaseResults?.[0]
    if (first) {
      selectedCaseKey.value = caseRowKey(first)
    }
  } catch (error) {
    errorMessage.value = error?.response?.data?.message || error?.message || 'Eval run failed.'
  } finally {
    loading.value = false
  }
}

const selectCase = (item) => {
  selectedCaseKey.value = caseRowKey(item)
}

const caseRowKey = (item) => `${item.caseId}-${item.strategy}`

const reviewerFlag = (item) => {
  if (item?.reviewerPassed === true) return 'passed'
  if (item?.reviewerPassed === false) return 'blocked'
  return 'n/a'
}

const hasPatchOutput = (item) => item?.patchOutput && Object.keys(item.patchOutput).length > 0

const formatDecimal = (value) => {
  if (value == null || Number.isNaN(Number(value))) return '--'
  return Number(value).toFixed(2)
}

const formatInteger = (value) => {
  if (value == null || Number.isNaN(Number(value))) return '--'
  return Math.round(Number(value)).toString()
}

const formatLatency = (value) => {
  if (value == null || Number.isNaN(Number(value))) return '--'
  return `${Math.round(Number(value))} ms`
}

const formatList = (items) => {
  if (!items || !items.length) return '--'
  return items.join(' / ')
}

const average = (values) => {
  if (!values.length) return 0
  return values.reduce((sum, current) => sum + current, 0) / values.length
}

const prettyJson = (value) => {
  if (!value || (typeof value === 'object' && !Object.keys(value).length)) return '--'
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}

const goHome = () => router.push('/')
const goTasks = () => router.push('/tasks')

onMounted(loadReport)
</script>

<style scoped>
.eval-shell {
  min-height: 100vh;
  padding: 24px;
  color: #edf3ef;
  background:
    radial-gradient(circle at top left, rgba(255, 186, 96, 0.14), transparent 24%),
    radial-gradient(circle at top right, rgba(111, 173, 255, 0.14), transparent 26%),
    linear-gradient(180deg, #09131a 0%, #10212b 48%, #151218 100%);
}

.eval-hero,
.stats-grid,
.content-grid,
.workspace-grid {
  max-width: 1480px;
  margin: 0 auto;
}

.eval-hero {
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

.eval-hero h1 {
  margin: 10px 0 0;
  font-size: clamp(2.1rem, 4vw, 3.8rem);
}

.hero-copy {
  max-width: 820px;
  margin-top: 12px;
  color: rgba(237, 243, 239, 0.74);
}

.hero-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 14px;
  padding-bottom: 18px;
}

.content-grid {
  display: grid;
  grid-template-columns: 1.35fr 0.8fr;
  gap: 18px;
  padding-bottom: 18px;
}

.workspace-grid {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 18px;
}

.detail-stage,
.detail-grid,
.case-list,
.strategy-grid,
.compare-grid,
.chunk-list,
.evidence-list {
  display: grid;
  gap: 12px;
}

.detail-stage {
  min-width: 0;
}

.detail-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.panel,
.stat-card,
.strategy-card,
.case-card,
.summary-card,
.metric-chip,
.compare-card,
.chunk-card,
.evidence-card,
.json-card,
.report-panel,
.strategy-panel,
.case-panel,
.summary-panel,
.compare-panel {
  border-radius: 22px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.05);
  box-shadow: 0 26px 72px rgba(0, 0, 0, 0.18);
}

.panel,
.report-panel,
.strategy-panel,
.case-panel,
.summary-panel,
.compare-panel {
  padding: 20px;
}

.stat-card {
  display: grid;
  gap: 8px;
  padding: 18px;
}

.stat-card strong {
  font-size: 1.8rem;
}

.stat-label,
.stat-card small,
.question-copy,
.chunk-card p,
.evidence-card p {
  color: rgba(237, 243, 239, 0.72);
}

.panel-head,
.strategy-head,
.case-card-head,
.summary-grid,
.compare-head,
.chunk-head,
.detail-row,
.meta-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.panel-head {
  margin-bottom: 14px;
}

.panel-head h2 {
  margin: 8px 0 0;
}

.report-meta,
.strategy-metrics,
.summary-grid,
.report-panel,
.json-card {
  display: grid;
  gap: 12px;
}

.strategy-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.strategy-card,
.case-card,
.summary-card,
.compare-card,
.chunk-card,
.evidence-card,
.json-card,
.metric-chip {
  padding: 14px;
}

.strategy-metrics {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.metric-chip,
.summary-card {
  background: rgba(10, 16, 23, 0.42);
}

.metric-chip span,
.summary-card span,
.meta-row span,
.case-meta,
.case-metrics,
.chunk-meta {
  color: rgba(237, 243, 239, 0.66);
  font-size: 0.9rem;
}

.case-panel {
  min-height: 920px;
}

.filters {
  display: grid;
  grid-template-columns: 1.2fr 0.8fr;
  gap: 10px;
  margin-bottom: 14px;
}

.filters input,
.filters select {
  width: 100%;
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(10, 15, 22, 0.46);
  color: #edf3ef;
  padding: 12px 14px;
}

.case-card {
  display: grid;
  gap: 8px;
  width: 100%;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.case-card.active {
  border-color: rgba(255, 210, 110, 0.56);
  background: linear-gradient(180deg, rgba(255, 210, 110, 0.14), rgba(255, 144, 93, 0.08));
}

.case-meta,
.case-metrics,
.chunk-meta {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.summary-card {
  display: grid;
  gap: 8px;
}

.compare-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
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

.danger {
  background: rgba(255, 140, 126, 0.18);
  color: #ffbcaf;
}

.pending {
  background: rgba(255, 210, 110, 0.16);
  color: #ffd26e;
}

.primary-btn,
.secondary-btn {
  min-height: 46px;
  padding: 0 16px;
  border-radius: 14px;
  font-weight: 700;
  cursor: pointer;
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

.detail-row,
.meta-row {
  padding: 10px 12px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.04);
}

.chunk-card pre,
.evidence-card pre,
.json-card pre,
.mono {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'Consolas', 'SFMono-Regular', monospace;
  font-size: 12px;
  line-height: 1.6;
  color: #d9efe1;
}

.empty-state {
  padding: 18px;
  border-radius: 18px;
  border: 1px dashed rgba(255, 255, 255, 0.12);
  color: rgba(237, 243, 239, 0.64);
  background: rgba(255, 255, 255, 0.03);
}

.danger-state {
  border-color: rgba(255, 140, 126, 0.3);
  color: #ffbcaf;
}

@media (max-width: 1400px) {
  .stats-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .summary-grid,
  .compare-grid,
  .detail-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 1180px) {
  .content-grid,
  .workspace-grid,
  .detail-grid {
    grid-template-columns: 1fr;
  }

  .strategy-grid,
  .summary-grid,
  .compare-grid {
    grid-template-columns: 1fr;
  }

  .case-panel {
    min-height: auto;
  }
}

@media (max-width: 760px) {
  .eval-shell {
    padding: 16px;
  }

  .eval-hero {
    flex-direction: column;
    align-items: stretch;
  }

  .stats-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .filters {
    grid-template-columns: 1fr;
  }
}
</style>
