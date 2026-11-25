<template>
  <div class="uc-page">
    <header class="uc-page-head">
      <div>
        <h1 class="uc-h1">审批中心</h1>
        <p class="uc-sub">
          {{ isAdmin ? '员工的车辆操作会先生成申请单，管理员审批后才生效' : '你提交的车辆操作会先到这里，管理员审批后才会执行' }}
        </p>
      </div>
      <div class="uc-head-actions">
        <a-button @click="load">
          <template #icon><NavIcon name="refresh" :size="16" /></template>
          刷新
        </a-button>
      </div>
    </header>

    <div class="scope-bar">
      <button
        v-for="t in scopes"
        :key="t.key"
        type="button"
        class="status-chip"
        :class="{ active: scope === t.key }"
        @click="onScope(t.key)"
      >
        {{ t.text }}
      </button>
      <div class="scope-spacer" />
      <template v-if="scope !== 'pending'">
        <button
          v-for="s in statusFilters"
          :key="s.key || 'all'"
          type="button"
          class="filter-chip"
          :class="{ active: statusFilter === s.key }"
          @click="onStatus(s.key)"
        >
          {{ s.text }}
        </button>
      </template>
    </div>

    <a-card :bordered="false" class="table-card">
      <a-table
        row-key="id"
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="false"
        :bordered="false"
      >
        <template #id="{ record }">
          <span class="order-no num">{{ approvalNo(record) }}</span>
        </template>

        <template #type="{ record }">
          <div class="type-cell">
            <span>{{ record.operationLabel }}</span>
            <a-tag v-if="record.source === 'AI'" size="small" color="arcoblue">AI</a-tag>
          </div>
        </template>

        <template #applicant="{ record }">
          <div class="people">
            <span class="uc-avatar sm soft">{{ (record.applicantName || '?').slice(0, 1) }}</span>
            <span>{{ record.applicantName || '未知申请人' }}</span>
          </div>
        </template>

        <template #target="{ record }">
          <!-- 展示库存编号和车型，而不是数据库主键：主键是内部实现，用户看不懂 -->
          <div v-if="targetVehicle(record)" class="vehicle-cell">
            <a-link class="num" @click="goVehicle(targetId(record))">{{ targetVehicle(record).stockNo }}</a-link>
            <span>{{ targetVehicle(record).brand }} {{ targetVehicle(record).model }}</span>
          </div>
          <div v-else-if="record.payload && record.payload.brand" class="vehicle-cell">
            <span class="pending-name">{{ record.payload.brand }} {{ record.payload.model }}</span>
            <span>待入库</span>
          </div>
          <a-link v-else-if="targetId(record)" @click="goVehicle(targetId(record))">查看车辆</a-link>
          <span v-else class="muted">—</span>
        </template>

        <template #status="{ record }">
          <span class="pill" :class="statusOf(record.status).cls">{{ statusOf(record.status).text }}</span>
          <div v-if="record.approveRemark" class="remark" :title="record.approveRemark">{{ record.approveRemark }}</div>
        </template>

        <template #createdAt="{ record }">
          <span class="num time">{{ fmt(record.createdAt) }}</span>
        </template>

        <template #actions="{ record }">
          <a-space :size="10" wrap>
            <template v-if="isAdmin && record.status === 'PENDING'">
              <a-popconfirm content="批准后立刻执行该操作，确定吗？" @ok="onApprove(record)">
                <a-link v-if="record.operationType !== 'AGENT_BATCH'" status="success">通过</a-link>
              </a-popconfirm>
              <a-link v-if="record.operationType === 'AGENT_BATCH'" @click="openDetail(record)">查看清单并确认</a-link>
              <a-link status="danger" @click="openReject(record)">驳回</a-link>
            </template>
            <template v-if="scope === 'mine' && record.status === 'PENDING'">
              <a-popconfirm content="撤回后这次申请作废，确定吗？" @ok="onCancel(record)">
                <a-link>撤回</a-link>
              </a-popconfirm>
            </template>
            <template v-if="scope === 'mine' && record.operationType !== 'AGENT_BATCH' && ['REJECTED', 'CANCELLED'].includes(record.status)">
              <a-link @click="openResubmit(record)">复制重提</a-link>
            </template>
            <a-link @click="openDetail(record)">详情</a-link>
          </a-space>
        </template>

        <template #empty>
          <div class="uc-empty">
            <div class="mark"><NavIcon name="approval" :size="22" /></div>
            <div>{{ scope === 'pending' ? '没有待审批的申请，先歇会儿' : '没有符合条件的申请单' }}</div>
          </div>
        </template>
      </a-table>
    </a-card>

    <div class="page-foot">
      <NavIcon name="approval" :size="14" />
      <span>审批通过后会立即执行对应操作；如果车辆状态已变化导致无法执行，申请单会自动退回「待审批」，不会产生半完成的数据。</span>
    </div>

    <!-- 驳回原因 -->
    <a-modal v-model:visible="rejectVisible" title="驳回申请" :width="440" ok-text="确认驳回" @before-ok="onReject">
      <a-form layout="vertical">
        <a-form-item label="驳回原因（员工重提时会看到）" required>
          <a-textarea v-model="rejectReason" :rows="3" placeholder="例如：收车价填错了，请核对后重提" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 复制重提：字段规格由后端 /approvals/spec 提供，前端不自己维护清单 -->
    <a-modal v-model:visible="resubmitVisible" title="修改后重新提交" :width="560" ok-text="提交申请" @before-ok="onResubmit">
      <a-alert type="info" style="margin-bottom: 14px">
        只列出可以修改的信息，其余沿用原申请内容。提交后会生成一张新的待审批申请单。
      </a-alert>
      <a-form :model="resubmitForm" layout="vertical">
        <a-grid :cols="2" :col-gap="14">
          <a-grid-item v-for="f in resubmitFields" :key="f.key">
            <a-form-item :label="f.label">
              <a-select v-if="f.type === 'SELECT'" v-model="resubmitForm[f.key]" style="width: 100%">
                <a-option v-for="o in f.options" :key="o.value" :value="o.value">{{ o.label }}</a-option>
              </a-select>
              <a-date-picker
                v-else-if="f.type === 'DATE'"
                v-model="resubmitForm[f.key]"
                value-format="YYYY-MM-DD"
                style="width: 100%"
              />
              <a-input-number
                v-else-if="f.type === 'NUMBER'"
                v-model="resubmitForm[f.key]"
                :min="0"
                style="width: 100%"
              />
              <a-input v-else v-model="resubmitForm[f.key]" allow-clear />
            </a-form-item>
          </a-grid-item>
        </a-grid>
      </a-form>
    </a-modal>

    <!-- 申请内容 -->
    <a-modal v-model:visible="detailVisible" title="申请内容" :width="560" :footer="false">
      <div class="detail-meta">
        <span class="pill pill-plain">{{ detailRow?.operationLabel || '—' }}</span>
        <span class="muted">{{ detailRow?.applicantName }} · {{ fmt(detailRow?.createdAt) }}</span>
        <span class="pill" :class="statusOf(detailRow?.status).cls">{{ statusOf(detailRow?.status).text }}</span>
      </div>
      <a-descriptions :column="1" bordered size="small">
        <a-descriptions-item v-for="(v, k) in detailPayload" :key="k" :label="fieldLabel(k)">
          {{ v === null || v === '' || v === undefined ? '—' : v }}
        </a-descriptions-item>
      </a-descriptions>
      <template v-if="detailRow?.operationType === 'AGENT_BATCH'">
        <a-alert type="warning">请核对以下全部操作。确认后整批执行，任意一项失败则整批回滚。</a-alert>
        <div v-for="(item, index) in detailRow.payload.items" :key="index" style="margin: 12px 0">
          <strong>{{ index + 1 }}. {{ item.operation }} · 目标 {{ item.targetId }}</strong>
          <pre style="white-space: pre-wrap">{{ JSON.stringify(item.payload, null, 2) }}</pre>
          <div v-if="item.vehicleSnapshot" class="uc-note">申请时车辆：{{ item.vehicleSnapshot.stockNo }} · {{ item.vehicleSnapshot.brand }} {{ item.vehicleSnapshot.model }} · 状态 {{ item.vehicleSnapshot.status }} · 挂牌价 {{ item.vehicleSnapshot.listPrice ?? '未设置' }} 元</div>
          <template v-if="['APPROVE', 'REJECT'].includes(item.operation)">
            <div v-if="batchTarget(item)" class="uc-note">原申请：{{ batchTarget(item).operationLabel }} · 车辆 ID {{ batchTarget(item).targetVehicleId || '无' }}<pre style="white-space: pre-wrap">{{ JSON.stringify(batchTarget(item).payload, null, 2) }}</pre></div>
            <a-alert v-else type="error">无法核对原申请，请切换到待审批列表并刷新后再确认。</a-alert>
          </template>
        </div>
        <a-popconfirm v-if="isAdmin && detailRow.status === 'PENDING' && batchReviewable" content="已核对清单，确认执行全部操作？" @ok="onApprove(detailRow)">
          <a-button type="primary" status="warning">管理员确认并执行整批操作</a-button>
        </a-popconfirm>
      </template>
      <div v-if="detailRow?.approveRemark" class="uc-note" style="margin-top: 12px">
        <NavIcon name="approval" :size="15" />
        <span>审批意见：{{ detailRow.approveRemark }}</span>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import NavIcon from '../components/NavIcon.vue'
import {
  getMe, listApprovals, getApprovalSpec, approveApproval, rejectApproval,
  resubmitApproval, cancelApproval, pageVehicles
} from '../api'

/**
 * 审批中心。
 * ADMIN 看待办/全部，员工看自己的申请；真正的权限在后端 ApprovalService，
 * 前端 isAdmin 只决定显示哪些 tab 和按钮。
 */
const router = useRouter()
const isAdmin = ref(false)
const scope = ref('mine')
const statusFilter = ref(undefined)
const rows = ref([])
const loading = ref(false)
const specMap = ref({})

const rejectVisible = ref(false)
const rejectReason = ref('')
const rejectTarget = ref(null)
const resubmitVisible = ref(false)
const resubmitFields = ref([])
const resubmitForm = reactive({})
const resubmitTarget = ref(null)
const detailVisible = ref(false)
const detailPayload = ref({})
const detailType = ref('')
const detailRow = ref(null)
const batchTarget = item => rows.value.find(r => r.id === item.targetId && r.status === 'PENDING')
const batchReviewable = computed(() => (detailRow.value?.payload?.items || []).every(item =>
  !['APPROVE', 'REJECT'].includes(item.operation) || batchTarget(item)))

// 审批表只存车辆 id。前端拉一次车辆列表做映射，把库存编号和车型显示出来，
// 不给车辆分页加 join，也不在每行单独发请求。
const vehicles = ref([])
const vehicleMap = computed(() => {
  const map = {}
  for (const v of vehicles.value) map[v.id] = v
  return map
})

function targetId(record) {
  return record.targetVehicleId || record.resultVehicleId || null
}
function targetVehicle(record) {
  const id = targetId(record)
  return id ? vehicleMap.value[id] || null : null
}
/** 审批单号：SP + 日期 + 三位流水，比直接展示自增主键更像业务单据 */
function approvalNo(record) {
  const day = String(record.createdAt || '').slice(0, 10).replace(/-/g, '')
  return `SP${day}${String(record.id).padStart(3, '0')}`
}

const scopes = computed(() => [
  ...(isAdmin.value ? [{ key: 'pending', text: '待办审批' }] : []),
  { key: 'mine', text: '我的申请' },
  ...(isAdmin.value ? [{ key: 'all', text: '全部申请' }] : [])
])

const statusFilters = [
  { key: undefined, text: '全部状态' },
  { key: 'PENDING', text: '待审批' },
  { key: 'APPROVED', text: '已通过' },
  { key: 'REJECTED', text: '已驳回' },
  { key: 'CANCELLED', text: '已撤回' }
]

const columns = [
  { title: '单号', slotName: 'id', width: 130 },
  { title: '操作类型', slotName: 'type', width: 150 },
  { title: '申请人', slotName: 'applicant', width: 130 },
  { title: '关联车辆', slotName: 'target', width: 210 },
  { title: '状态', slotName: 'status', width: 160 },
  { title: '提交时间', slotName: 'createdAt', width: 150 },
  { title: '操作', slotName: 'actions', width: 210 }
]

const STATUS = {
  PENDING: { text: '待审批', cls: 'pill-amber' },
  APPROVED: { text: '已通过', cls: 'pill-green' },
  REJECTED: { text: '已驳回', cls: 'pill-red' },
  CANCELLED: { text: '已撤回', cls: 'pill-gray' }
}
function statusOf(s) {
  return STATUS[s] || { text: s || '—', cls: 'pill-gray' }
}
function fmt(s) {
  return s ? String(s).replace('T', ' ').slice(0, 16) : '—'
}
function goVehicle(id) {
  router.push({ name: 'vehicle-detail', params: { id } })
}

function fieldLabel(key) {
  const fields = specMap.value[detailType.value] || []
  return fields.find((f) => f.key === key)?.label || key
}

async function load() {
  loading.value = true
  try {
    rows.value = await listApprovals(scope.value, statusFilter.value || undefined)
  } finally {
    loading.value = false
  }
}

async function loadVehicles() {
  try {
    const page = await pageVehicles({ page: 1, size: 200 })
    vehicles.value = page.records || []
  } catch (e) {
    // 映射失败时关联车辆退化成「查看车辆」，不影响审批本身
  }
}

function onScope(key) {
  if (scope.value === key) return
  scope.value = key
  statusFilter.value = undefined
  load()
}

function onStatus(key) {
  statusFilter.value = key
  load()
}

async function onApprove(row) {
  try {
    const vo = await approveApproval(row.id, '')
    Message.success(vo.resultVehicleId ? '已通过，车辆已入库' : '已通过，操作已执行')
    detailVisible.value = false
    await load()
  } catch (e) {
    // 提示已由拦截器弹出；业务规则拒绝时申请单会回滚到 PENDING
  }
}

function openReject(row) {
  rejectTarget.value = row
  rejectReason.value = ''
  rejectVisible.value = true
}

async function onReject() {
  if (!rejectReason.value.trim()) {
    Message.warning('驳回必须填写原因')
    return false
  }
  try {
    await rejectApproval(rejectTarget.value.id, rejectReason.value.trim())
    Message.success('已驳回')
    rejectVisible.value = false
    detailVisible.value = false
    await load()
  } catch (e) {
    // 提示已由拦截器弹出
  }
  return false
}

async function onCancel(row) {
  try {
    await cancelApproval(row.id)
    Message.success('已撤回')
    detailVisible.value = false
    await load()
  } catch (e) {
    // 提示已由拦截器弹出
  }
}

function openResubmit(row) {
  resubmitTarget.value = row
  resubmitFields.value = specMap.value[row.operationType] || []
  Object.keys(resubmitForm).forEach((k) => delete resubmitForm[k])
  for (const f of resubmitFields.value) {
    const v = row.payload?.[f.key]
    resubmitForm[f.key] = v === undefined || v === null ? (f.type === 'NUMBER' ? undefined : '') : v
  }
  resubmitVisible.value = true
}

async function onResubmit() {
  const payload = {}
  for (const [k, v] of Object.entries(resubmitForm)) {
    // 空串会让后端把 LocalDate / BigDecimal 反序列化失败，直接不传，沿用原值
    if (v !== '' && v !== null && v !== undefined) payload[k] = v
  }
  if (Object.keys(payload).length === 0) {
    Message.warning('至少修改一项')
    return false
  }
  try {
    const vo = await resubmitApproval(resubmitTarget.value.id, payload)
    Message.success(`已重新提交，新单号 ${vo.id}`)
    resubmitVisible.value = false
    scope.value = 'mine'
    detailVisible.value = false
    await load()
  } catch (e) {
    // 提示已由拦截器弹出
  }
  return false
}

function openDetail(row) {
  detailRow.value = row
  detailPayload.value = row.payload || {}
  detailType.value = row.operationType
  detailVisible.value = true
}

onMounted(async () => {
  try {
    const me = await getMe()
    isAdmin.value = me.role === 'ADMIN'
    if (isAdmin.value) scope.value = 'pending'
  } catch (e) {
    // 401 已由拦截器处理
  }
  try {
    specMap.value = await getApprovalSpec()
  } catch (e) {
    // 规格拉不到时重提表单会退化为空，不影响审批主流程
  }
  loadVehicles()
  load()
})
</script>

<style scoped>
.scope-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.scope-spacer {
  width: 1px;
  height: 18px;
  margin: 0 6px;
  background: var(--uc-border);
}
.filter-chip {
  height: 28px;
  padding: 0 11px;
  border: 1px solid transparent;
  border-radius: 99px;
  background: transparent;
  color: var(--uc-text-3);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.16s ease;
}
.filter-chip:hover {
  color: var(--uc-text);
  background: #fff;
  border-color: var(--uc-border);
}
.filter-chip.active {
  color: var(--uc-primary);
  background: var(--uc-primary-soft);
  border-color: #dfe6fd;
}

/* 复用车辆页胶囊样式，颜色语义一致 */
.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 34px;
  padding: 0 16px;
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

.table-card :deep(.arco-card-body) {
  padding: 8px 20px 16px;
}
.order-no {
  font-size: 11.5px;
  color: var(--uc-text-2);
}
.vehicle-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.vehicle-cell .arco-link {
  font-size: 11.5px;
}
.vehicle-cell span {
  font-size: 11px;
  color: var(--uc-text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.vehicle-cell .pending-name {
  font-size: 11.5px;
  color: var(--uc-text);
}
.type-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}
.people {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--uc-text);
}
.remark {
  margin-top: 4px;
  max-width: 160px;
  font-size: 11px;
  color: var(--uc-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.time {
  font-size: 12px;
  color: var(--uc-text-2);
}
.detail-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  font-size: 11.5px;
}
.page-foot {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 0 4px;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--uc-text-3);
}
.page-foot :deep(svg) {
  margin-top: 2px;
}
</style>
