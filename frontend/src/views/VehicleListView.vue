<template>
  <div class="uc-page">
    <header class="uc-page-head">
      <div>
        <h1 class="uc-h1">车辆台账</h1>
        <p class="uc-sub">收车、挂牌、成交、售后到清库，一台车从进店到出库的全过程都留在这张表里</p>
      </div>
      <div class="uc-head-actions">
        <a-button @click="goAiIntake">
          <template #icon><NavIcon name="robot" :size="17" /></template>
          AI 收车
        </a-button>
        <a-button type="primary" @click="openCreate">
          <template #icon><NavIcon name="plus" :size="17" /></template>
          收车入库
        </a-button>
      </div>
    </header>

    <!-- 状态筛选：用整块胶囊切换，比下拉框更快，也顺手把各状态数量摊开给老板看 -->
    <div class="status-bar">
      <button
        v-for="chip in statusChips"
        :key="chip.key"
        type="button"
        class="status-chip"
        :class="{ active: query.status === chip.key }"
        @click="onStatusChip(chip.key)"
      >
        <span class="chip-dot" :style="{ background: chip.color }" />
        {{ chip.text }}
        <b class="num">{{ chip.count }}</b>
      </button>
    </div>

    <a-card :bordered="false" class="table-card">
      <div class="toolbar">
        <a-input
          v-model="query.keyword"
          class="search"
          placeholder="搜索品牌 / 车型 / 库存编号"
          allow-clear
          @press-enter="reload"
        >
          <template #prefix><NavIcon name="search" :size="16" /></template>
        </a-input>
        <a-button type="primary" @click="reload">查询</a-button>
        <a-button @click="onReset">重置</a-button>
        <span class="toolbar-hint">
          共 <b class="num">{{ pagination.total }}</b> 台
        </span>
        <a-button class="refresh" type="text" @click="refreshAll" :loading="loading">
          <template #icon><NavIcon name="refresh" :size="16" /></template>
        </a-button>
      </div>

      <a-table
        row-key="id"
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :bordered="false"
        :row-class="() => 'clickable-row'"
        @row-click="(record) => goDetail(record.id)"
        @page-change="onPageChange"
        @page-size-change="onPageSizeChange"
      >
        <template #vehicle="{ record }">
          <div class="car-cell">
            <span class="car-avatar" :style="avatarStyle(record)">{{ (record.brand || '?').slice(0, 1) }}</span>
            <div class="car-main">
              <div class="car-name">{{ record.brand }} {{ record.model }}</div>
              <div class="car-sub">
                <span class="num">{{ record.stockNo }}</span>
                <i v-if="record.plateNo">· {{ record.plateNo }}</i>
                <i v-if="record.mileage">· {{ mileageText(record.mileage) }}</i>
              </div>
            </div>
          </div>
        </template>

        <template #status="{ record }">
          <span class="pill" :class="statusOf(record.status).cls">{{ statusOf(record.status).text }}</span>
        </template>

        <template #purchasePrice="{ record }">
          <span class="money"><span class="cny">¥</span>{{ money(record.purchasePrice) }}</span>
        </template>

        <template #listPrice="{ record }">
          <span v-if="record.listPrice" class="money"><span class="cny">¥</span>{{ money(record.listPrice) }}</span>
          <span v-else class="muted">未挂牌</span>
        </template>

        <template #holding="{ record }">
          <span class="hold"><NavIcon name="clock" :size="13" />{{ holdingDays(record.purchaseDate) }} 天</span>
        </template>

        <template #people="{ record }">
          <div class="people">
            <span><em>录</em>{{ employeeName(record.createdBy) }}</span>
            <span><em>接</em>{{ employeeName(record.ownerUserId) }}</span>
          </div>
        </template>

        <template #actions="{ record }">
          <a-link class="row-link" @click.stop="goDetail(record.id)">
            详情
            <NavIcon name="arrow" :size="13" />
          </a-link>
        </template>

        <template #empty>
          <div class="uc-empty">
            <div class="mark"><NavIcon name="car" :size="22" /></div>
            <div>当前筛选条件下没有车辆</div>
            <div style="margin-top: 12px">
              <a-button size="small" type="primary" @click="openCreate">收车入库</a-button>
            </div>
          </div>
        </template>
      </a-table>
    </a-card>

    <!-- 收车入库 -->
    <a-modal
      v-model:visible="createVisible"
      title="收车入库"
      :width="700"
      ok-text="确认收车"
      unmount-on-close
      @before-ok="onCreate"
    >
      <a-form :model="form" layout="horizontal">
        <p class="modal-lead">带 <i>*</i> 为必填项，其余可以后补。</p>

      <div class="form-section">
        <div class="form-section-title">车辆信息</div>
        <a-grid :cols="2" :col-gap="18">
          <a-grid-item>
            <a-form-item label="品牌" required>
              <a-input v-model="form.brand" placeholder="例如 大众" />
            </a-form-item>
          </a-grid-item>
          <a-grid-item>
            <a-form-item label="车型" required>
              <a-input v-model="form.model" placeholder="例如 朗逸 2021款 1.5L 自动舒适版" />
            </a-form-item>
          </a-grid-item>
          <a-grid-item>
            <a-form-item label="车牌号">
              <a-input v-model="form.plateNo" placeholder="豫A12345" />
            </a-form-item>
          </a-grid-item>
          <a-grid-item>
            <a-form-item label="表显里程（km）">
              <a-input-number v-model="form.mileage" :min="0" placeholder="可选" style="width: 100%" />
            </a-form-item>
          </a-grid-item>
        </a-grid>
      </div>

      <div class="form-section">
        <div class="form-section-title">交易信息</div>
        <a-grid :cols="2" :col-gap="18">
          <a-grid-item>
            <a-form-item label="收车价（元）" required>
              <a-input-number v-model="form.purchasePrice" :min="0" :precision="2" placeholder="成本价" style="width: 100%" />
            </a-form-item>
          </a-grid-item>
          <a-grid-item>
            <a-form-item label="收车日期" required>
              <a-date-picker v-model="form.purchaseDate" value-format="YYYY-MM-DD" style="width: 100%" />
            </a-form-item>
          </a-grid-item>
          <a-grid-item>
            <a-form-item label="挂牌价（元）">
              <a-input-number v-model="form.listPrice" :min="0" :precision="2" placeholder="可留空，上架时再定" style="width: 100%" />
            </a-form-item>
          </a-grid-item>
          <a-grid-item>
            <a-form-item label="首次上牌日期">
              <a-date-picker v-model="form.firstRegDate" value-format="YYYY-MM-DD" style="width: 100%" />
            </a-form-item>
          </a-grid-item>
        </a-grid>
      </div>

      <div class="form-section">
        <div class="form-section-title">归属与备注</div>
        <a-grid :cols="2" :col-gap="18">
          <a-grid-item>
            <a-form-item label="对接员工">
              <a-select v-model="form.ownerUserId" allow-clear placeholder="留空则记为当前登录人">
                <a-option v-for="e in employees" :key="e.id" :value="e.id">
                  {{ e.realName || e.username }}（{{ roleText(e.role) }}）
                </a-option>
              </a-select>
            </a-form-item>
          </a-grid-item>
          <a-grid-item>
            <a-form-item label="备注">
              <a-input v-model="form.remark" placeholder="车况、来源等" />
            </a-form-item>
          </a-grid-item>
        </a-grid>
      </div>

      <div class="uc-note">
        <NavIcon name="approval" :size="15" />
        <span>管理员提交后直接入库并生成库存编号；员工提交后进入审批，管理员通过后正式入库。</span>
      </div>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import NavIcon from '../components/NavIcon.vue'
import { createVehicle, pageVehicles, listEmployees } from '../api'
import { VEHICLE_STATUS, money } from '../utils/dict'

const router = useRouter()

const loading = ref(false)
const rows = ref([])
// 门店员工数量很少，一次拉全量在前端把 id 映射成姓名，
// 不用给车辆分页查询加 join，也不用为每行再发一次请求
const employees = ref([])
const pagination = reactive({ current: 1, pageSize: 10, total: 0, showTotal: true, showPageSize: true, showJumper: false })
const query = reactive({ keyword: '', status: undefined })

const STATUS_CHIPS = [
  { key: undefined, text: '全部', color: '#3457d5' },
  { key: 'PURCHASED', text: '已收车', color: '#3457d5' },
  { key: 'ON_SALE', text: '在售', color: '#1f8a5b' },
  { key: 'SOLD', text: '已成交', color: '#6b4ec9' },
  { key: 'AFTER_SALE', text: '售后', color: '#b7791f' },
  { key: 'CLEARED', text: '已清库', color: '#9aa3b5' }
]
const statusCounts = reactive({ all: 0, PURCHASED: 0, ON_SALE: 0, SOLD: 0, AFTER_SALE: 0, CLEARED: 0 })

const statusChips = STATUS_CHIPS.map((chip) => ({
  ...chip,
  get count() {
    return chip.key ? statusCounts[chip.key] : statusCounts.all
  }
}))

const columns = [
  { title: '车辆', slotName: 'vehicle', width: 330 },
  { title: '状态', slotName: 'status', width: 108 },
  { title: '收车价', slotName: 'purchasePrice', width: 132, align: 'right' },
  { title: '挂牌价', slotName: 'listPrice', width: 132, align: 'right' },
  { title: '持有', slotName: 'holding', width: 100 },
  { title: '录入 / 对接', slotName: 'people', width: 150 },
  { title: '', slotName: 'actions', width: 76, align: 'right' }
]

const PILL_CLASS = {
  arcoblue: 'pill-blue',
  green: 'pill-green',
  purple: 'pill-violet',
  orange: 'pill-amber',
  gray: 'pill-gray'
}

function statusOf(status) {
  const found = VEHICLE_STATUS[status]
  return { text: found?.text || status, cls: PILL_CLASS[found?.color] || 'pill-gray' }
}

const ROLE_TEXT = { ADMIN: '管理员', SALES: '销售', FINANCE: '财务' }
function roleText(role) {
  return ROLE_TEXT[role] || role
}

function employeeName(id) {
  // created_by 为 NULL 的是 V8 升级前录入的历史车辆，当时还没记录归属
  if (!id) return '—'
  const e = employees.value.find((x) => x.id === id)
  return e ? (e.realName || e.username) : '未知员工'
}

/** 品牌首字固定用同一套色相，保证「大众」永远是同一个颜色，扫列表时能靠颜色定位 */
function avatarStyle(record) {
  const text = record.brand || '车'
  let hash = 0
  for (let i = 0; i < text.length; i++) hash = (hash * 31 + text.charCodeAt(i)) % 360
  return {
    background: `linear-gradient(150deg, hsl(${hash} 70% 96%), hsl(${hash} 62% 90%))`,
    color: `hsl(${hash} 42% 38%)`
  }
}

function mileageText(value) {
  const n = Number(value) || 0
  return n >= 10000 ? `${(n / 10000).toFixed(1)} 万公里` : `${n} 公里`
}

function holdingDays(purchaseDate) {
  if (!purchaseDate) return 0
  return Math.max(0, Math.floor((Date.now() - new Date(purchaseDate).getTime()) / 86400000))
}

async function load() {
  loading.value = true
  try {
    const data = await pageVehicles({
      page: pagination.current,
      size: pagination.pageSize,
      keyword: query.keyword || undefined,
      status: query.status || undefined
    })
    rows.value = data.records
    pagination.total = data.total
  } finally {
    loading.value = false
  }
}

/** 各状态数量单独统计：表格分页只给当前页，胶囊上要的是全局数字 */
async function loadCounts() {
  const keys = ['PURCHASED', 'ON_SALE', 'SOLD', 'AFTER_SALE', 'CLEARED']
  const results = await Promise.allSettled([
    pageVehicles({ page: 1, size: 1 }),
    ...keys.map((status) => pageVehicles({ page: 1, size: 1, status }))
  ])
  statusCounts.all = results[0].status === 'fulfilled' ? results[0].value?.total || 0 : 0
  keys.forEach((key, i) => {
    const r = results[i + 1]
    statusCounts[key] = r.status === 'fulfilled' ? r.value?.total || 0 : 0
  })
}

function reload() {
  pagination.current = 1
  load()
}

function refreshAll() {
  load()
  loadCounts()
}

function onStatusChip(key) {
  query.status = key
  reload()
}

function onReset() {
  query.keyword = ''
  query.status = undefined
  pagination.current = 1
  pagination.pageSize = 10
  refreshAll()
}

function onPageChange(page) {
  pagination.current = page
  load()
}

function onPageSizeChange(size) {
  pagination.pageSize = size
  pagination.current = 1
  load()
}

function goDetail(id) {
  router.push({ name: 'vehicle-detail', params: { id } })
}

function goAiIntake() {
  router.push({ name: 'ai-intake' })
}

// ---------- 收车入库 ----------
const createVisible = ref(false)
const form = reactive({})

function openCreate() {
  Object.assign(form, {
    brand: '', model: '', purchasePrice: undefined, purchaseDate: undefined,
    listPrice: undefined, firstRegDate: undefined, mileage: undefined, plateNo: '', remark: '',
    ownerUserId: undefined
  })
  createVisible.value = true
}

async function onCreate() {
  if (!form.brand || !form.model || !form.purchasePrice || !form.purchaseDate) {
    Message.warning('品牌、车型、收车价、收车日期必填')
    return false
  }
  try {
    const res = await createVehicle(form)
    if (!res || !res.executed) {
      // 员工：只生成了申请单，api 层已统一提示；没有车辆 id，不能跳详情页
      createVisible.value = false
      refreshAll()
      return false
    }
    Message.success('收车入库成功')
    createVisible.value = false
    router.push({ name: 'vehicle-detail', params: { id: res.data } })
  } catch (e) {
    // 提示已由拦截器弹出
  }
  // 返回 false 让 a-modal 不自己关闭，关闭时机由上面控制
  return false
}

onMounted(async () => {
  load()
  loadCounts()
  try {
    employees.value = await listEmployees()
  } catch (e) {
    // 员工列表拉失败不该挡住车辆列表；姓名列会退化成显示账号 id
  }
})
</script>

<style scoped>
/* 状态胶囊条 */
.status-bar {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 34px;
  padding: 0 14px;
  border: 1px solid var(--uc-border);
  border-radius: 99px;
  background: #fff;
  color: var(--uc-text-2);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.16s ease;
}
.status-chip:hover {
  border-color: #c8d1ea;
  color: var(--uc-text);
}
.status-chip.active {
  background: var(--uc-ink);
  border-color: var(--uc-ink);
  color: #fff;
  box-shadow: 0 10px 22px -14px rgba(11, 18, 32, 0.9);
}
.status-chip b {
  font-weight: 600;
  font-size: 12px;
  color: var(--uc-text-3);
  font-variant-numeric: tabular-nums;
}
.status-chip.active b {
  color: rgba(255, 255, 255, 0.6);
}
.chip-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex: none;
}
.status-chip.active .chip-dot {
  box-shadow: 0 0 0 3px rgba(255, 255, 255, 0.14);
}

.table-card :deep(.arco-card-body) {
  padding: 18px 20px 14px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}
.toolbar .search {
  width: 290px;
}
.toolbar-hint {
  margin-left: auto;
  font-size: 12px;
  color: var(--uc-text-3);
}
.toolbar-hint b {
  color: var(--uc-text);
  font-weight: 600;
}
.refresh {
  color: var(--uc-text-3);
}

/* 表格单元 */
.car-cell {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
}
.car-avatar {
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 600;
}
.car-main {
  min-width: 0;
}
.car-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--uc-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.car-sub {
  margin-top: 3px;
  font-size: 11px;
  color: var(--uc-text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.car-sub i {
  font-style: normal;
  margin-left: 5px;
}
.hold {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--uc-text-2);
  font-variant-numeric: tabular-nums;
}
.hold :deep(svg) {
  color: var(--uc-text-3);
}
.people {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 11.5px;
  color: var(--uc-text-2);
}
.people em {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  margin-right: 6px;
  border-radius: 5px;
  background: #f1f3f7;
  color: var(--uc-text-3);
  font-style: normal;
  font-size: 10px;
}
.row-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 12.5px;
}
:deep(.clickable-row) {
  cursor: pointer;
}

/* 弹窗表单 */
.modal-lead {
  margin: 0 0 18px;
  font-size: 12px;
  color: var(--uc-text-3);
}
.modal-lead i {
  font-style: normal;
  color: var(--uc-danger);
}
.form-section {
  padding: 14px 16px 0;
  margin-bottom: 14px;
  border: 1px solid var(--uc-border-soft);
  border-radius: var(--uc-r-md);
  background: var(--uc-surface-2);
}
.form-section-title {
  margin-bottom: 12px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--uc-text-3);
}
.form-section :deep(.arco-form-item) {
  margin-bottom: 14px;
}
/* 横向表单的标签列必须定宽：不定宽时「品牌」和「首次上牌日期」按各自字数撑开，
   同一列输入框左边缘会参差。104px 是按最长标签「表显里程（km）」量出来的。 */
.form-section :deep(.arco-form-item-label-col) {
  flex: 0 0 104px;
  width: 104px;
}
.form-section :deep(.arco-form-item-label) {
  white-space: nowrap;
}
.form-section :deep(.arco-form-item-wrapper-col) {
  flex: 1;
  min-width: 0;
}
</style>
