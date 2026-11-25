<template>
  <div class="uc-page">
    <header class="uc-page-head">
      <div>
        <h1 class="uc-h1">库存预警</h1>
        <p class="uc-sub">滞销、证件到期、毛利过低由巡检自动生成；处理完记得点「已处理」关单</p>
      </div>
      <div class="uc-head-actions">
        <a-button @click="load" :loading="loading">
          <template #icon><NavIcon name="refresh" :size="16" /></template>
          刷新
        </a-button>
        <a-button type="primary" :loading="inspecting" @click="onInspect">
          <template #icon><NavIcon name="fire" :size="16" /></template>
          立即巡检
        </a-button>
      </div>
    </header>

    <section class="summary">
      <div v-for="s in summary" :key="s.key" class="sum-card" :class="`lv-${s.key.toLowerCase()}`">
        <span class="sum-bar" />
        <div class="sum-body">
          <span class="sum-label">{{ s.label }}</span>
          <b class="sum-value num">{{ s.count }}<em>条</em></b>
        </div>
        <span class="sum-icon"><NavIcon :name="s.icon" :size="18" /></span>
      </div>
    </section>

    <a-card :bordered="false" class="list-card">
      <div class="list-head">
        <div>
          <h3>待处理明细</h3>
          <p>按严重程度排序，红色优先处理</p>
        </div>
        <span class="count-pill">共 {{ rows.length }} 条</span>
      </div>

      <a-spin v-if="loading" style="width: 100%" />

      <div v-else-if="!rows.length" class="uc-empty big">
        <div class="mark"><NavIcon name="bell" :size="24" /></div>
        <div class="empty-title">没有待处理预警</div>
        <div class="empty-sub">库存状态良好。可以点「立即巡检」再跑一次规则。</div>
      </div>

      <div v-else class="alert-list">
        <article v-for="record in rows" :key="record.id" class="alert-item" :class="`lv-${(record.alertLevel || 'INFO').toLowerCase()}`">
          <span class="alert-bar" />
          <div class="alert-icon">
            <NavIcon :name="iconOf(record.alertType)" :size="17" />
          </div>
          <div class="alert-main">
            <div class="alert-top">
              <span class="alert-type">{{ ALERT_TYPE[record.alertType] || record.alertType }}</span>
              <span class="pill" :class="levelOf(record.alertLevel).cls">{{ levelOf(record.alertLevel).text }}</span>
            </div>
            <p class="alert-content">{{ record.content }}</p>
            <button type="button" class="vehicle-link num" @click="goDetail(record.vehicleId)">
              {{ record.stockNo }}
              <i v-if="record.brand">{{ record.brand }} {{ record.model }}</i>
              <NavIcon name="arrow" :size="12" />
            </button>
          </div>
          <div class="alert-actions">
            <a-button size="small" type="primary" @click="onHandle(record.id, 'RESOLVED')">已处理</a-button>
            <a-button size="small" @click="onHandle(record.id, 'IGNORED')">忽略</a-button>
          </div>
        </article>
      </div>
    </a-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import NavIcon from '../components/NavIcon.vue'
import { handleAlert, inspectAlerts, listAlerts } from '../api'
import { ALERT_LEVEL, ALERT_TYPE } from '../utils/dict'

const router = useRouter()
const rows = ref([])
const loading = ref(false)
const inspecting = ref(false)

const LEVEL_META = {
  DANGER: { text: '严重', cls: 'pill-red', label: '严重预警', icon: 'fire' },
  WARN: { text: '警告', cls: 'pill-amber', label: '警告', icon: 'bell' },
  INFO: { text: '提示', cls: 'pill-gray', label: '一般提示', icon: 'bulb' }
}

function levelOf(level) {
  return LEVEL_META[level] || { text: level || '提示', cls: 'pill-gray' }
}

function iconOf(type) {
  if (type === 'CERT_EXPIRE') return 'approval'
  if (type === 'LOW_MARGIN') return 'coin'
  if (type === 'SLOW_MOVING') return 'clock'
  return 'bell'
}

const summary = computed(() =>
  ['DANGER', 'WARN', 'INFO'].map((key) => ({
    key,
    ...LEVEL_META[key],
    count: rows.value.filter((r) => (r.alertLevel || 'INFO') === key).length
  }))
)

function goDetail(id) {
  if (id) router.push({ name: 'vehicle-detail', params: { id } })
}

async function load() {
  loading.value = true
  try {
    rows.value = await listAlerts()
  } finally {
    loading.value = false
  }
}

async function onInspect() {
  inspecting.value = true
  try {
    const created = await inspectAlerts()
    Message.success(created > 0 ? `新增 ${created} 条预警` : '没有新的预警')
    await load()
  } finally {
    inspecting.value = false
  }
}

async function onHandle(id, status) {
  try {
    await handleAlert(id, status)
    Message.success(status === 'RESOLVED' ? '已标记处理' : '已忽略')
    await load()
  } catch (e) {
    // 提示已由拦截器弹出
  }
}

onMounted(load)
</script>

<style scoped>
.summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}
.sum-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px 16px 20px;
  background: #fff;
  border: 1px solid var(--uc-border);
  border-radius: var(--uc-r-lg);
  box-shadow: var(--uc-shadow-1);
  overflow: hidden;
}
.sum-bar {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: #b9c0cf;
}
.lv-danger .sum-bar { background: #c8493b; }
.lv-warn .sum-bar { background: #d99a2b; }
.lv-info .sum-bar { background: #8b93a5; }

.sum-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
}
.sum-label {
  font-size: 12px;
  color: var(--uc-text-3);
}
.sum-value {
  font-size: 25px;
  font-weight: 650;
  letter-spacing: -0.02em;
  line-height: 1.1;
}
.sum-value em {
  margin-left: 3px;
  font-style: normal;
  font-size: 11px;
  font-weight: 500;
  color: var(--uc-text-3);
}
.sum-icon {
  width: 38px;
  height: 38px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f6f9;
  color: #8b93a5;
}
.lv-danger .sum-icon { background: var(--uc-danger-soft); color: var(--uc-danger); }
.lv-warn .sum-icon { background: var(--uc-warning-soft); color: var(--uc-warning); }

.list-card :deep(.arco-card-body) {
  padding: 20px 22px 22px;
}
.list-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.list-head h3 {
  margin: 0;
  font-size: 14.5px;
  font-weight: 600;
  color: var(--uc-text);
}
.list-head p {
  margin: 4px 0 0;
  font-size: 11.5px;
  color: var(--uc-text-3);
}
.count-pill {
  padding: 3px 10px;
  border-radius: 99px;
  background: #f1f3f7;
  color: var(--uc-text-2);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.alert-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.alert-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px 14px 20px;
  border: 1px solid var(--uc-border-soft);
  border-radius: var(--uc-r-md);
  background: var(--uc-surface-2);
  transition: all 0.16s ease;
}
.alert-item:hover {
  background: #fff;
  box-shadow: var(--uc-shadow-1);
}
.alert-bar {
  position: absolute;
  left: 0;
  top: 12px;
  bottom: 12px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: #b9c0cf;
}
.lv-danger .alert-bar { background: #c8493b; }
.lv-warn .alert-bar { background: #d99a2b; }

.alert-icon {
  width: 38px;
  height: 38px;
  flex: none;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f1f3f7;
  color: #7c8598;
}
.lv-danger .alert-icon { background: var(--uc-danger-soft); color: var(--uc-danger); }
.lv-warn .alert-icon { background: var(--uc-warning-soft); color: var(--uc-warning); }

.alert-main {
  flex: 1;
  min-width: 0;
}
.alert-top {
  display: flex;
  align-items: center;
  gap: 8px;
}
.alert-type {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--uc-text);
}
.alert-content {
  margin: 5px 0 6px;
  font-size: 12px;
  line-height: 1.65;
  color: var(--uc-text-2);
}
.vehicle-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--uc-primary);
  font-size: 11.5px;
  font-family: inherit;
  cursor: pointer;
}
.vehicle-link i {
  font-style: normal;
  color: var(--uc-text-3);
}
.vehicle-link:hover {
  text-decoration: underline;
}
.alert-actions {
  display: flex;
  gap: 8px;
  flex: none;
}

.uc-empty.big {
  padding: 56px 16px;
}
.empty-title {
  margin-top: 4px;
  font-size: 14px;
  font-weight: 500;
  color: var(--uc-text-2);
}
.empty-sub {
  margin-top: 5px;
  font-size: 11.5px;
  color: var(--uc-text-3);
}

@media (max-width: 1100px) {
  .summary {
    grid-template-columns: minmax(0, 1fr);
  }
  .alert-item {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .alert-actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
