<template>
  <div v-if="detail">
    <a-card :bordered="false">
      <div class="head">
        <div>
          <a-space align="center">
            <span class="stock">{{ detail.vehicle.stockNo }}</span>
            <a-tag :color="statusOf(detail.vehicle.status).color">{{ statusOf(detail.vehicle.status).text }}</a-tag>
          </a-space>
          <h2 class="name">{{ detail.vehicle.brand }} {{ detail.vehicle.model }}</h2>
          <div class="meta">
            <span>收车日期 {{ detail.vehicle.purchaseDate }}</span>
            <span>收车价 ¥{{ money(detail.vehicle.purchasePrice) }}</span>
            <span v-if="detail.vehicle.mileage">{{ detail.vehicle.mileage }} km</span>
            <span v-if="detail.vehicle.plateNo">{{ detail.vehicle.plateNo }}</span>
          </div>
        </div>
        <a-space>
          <a-button v-for="t in nextTargets" :key="t.value" size="small" @click="onChangeStatus(t)">
            {{ t.label }}
          </a-button>
        </a-space>
      </div>
    </a-card>

    <!-- 定价测算 -->
    <a-card :bordered="false" style="margin-top: 12px" title="定价测算">
      <template #extra>
        <span class="tip">参数来自 pricing_rule 表，改表 30 秒内生效</span>
      </template>
      <a-grid v-if="pricing" :cols="4" :col-gap="12" :row-gap="12">
        <a-grid-item>
          <div class="stat"><span class="label">成本合计</span><span class="value">¥{{ money(pricing.totalCost) }}</span></div>
        </a-grid-item>
        <a-grid-item>
          <div class="stat"><span class="label">持有天数</span><span class="value">{{ pricing.holdingDays }} 天</span></div>
        </a-grid-item>
        <a-grid-item>
          <div class="stat"><span class="label">资金成本</span><span class="value">¥{{ money(pricing.capitalCost) }}</span></div>
        </a-grid-item>
        <a-grid-item>
          <div class="stat"><span class="label">保本底价</span><span class="value">¥{{ money(pricing.breakevenPrice) }}</span></div>
        </a-grid-item>
        <a-grid-item>
          <div class="stat"><span class="label">当前挂牌价</span><span class="value">¥{{ money(pricing.listPrice) }}</span></div>
        </a-grid-item>
        <a-grid-item>
          <div class="stat">
            <span class="label">阶梯建议价</span>
            <span class="value">¥{{ money(pricing.suggestedPrice) }}<em class="rate">降 {{ (pricing.ladderRate * 100).toFixed(0) }}%</em></span>
          </div>
        </a-grid-item>
        <a-grid-item>
          <div class="stat">
            <span class="label">预计毛利</span>
            <span class="value" :class="{ danger: pricing.expectedProfit < 0 }">¥{{ money(pricing.expectedProfit) }}</span>
          </div>
        </a-grid-item>
        <a-grid-item>
          <div class="stat">
            <span class="label">滞销等级</span>
            <span class="value">
              <a-tag :color="slowOf(pricing.slowLevel).color">{{ slowOf(pricing.slowLevel).text }}</a-tag>
            </span>
          </div>
        </a-grid-item>
      </a-grid>
    </a-card>

    <a-card :bordered="false" style="margin-top: 12px">
      <a-tabs v-model:active-key="activeTab">
        <!-- 成本明细 -->
        <a-tab-pane key="costs" title="成本明细">
          <a-space style="margin-bottom: 12px">
            <a-button size="small" type="outline" @click="costVisible = true">追加成本</a-button>
          </a-space>
          <a-table row-key="id" :columns="costColumns" :data="costs" :pagination="false" stripe>
            <template #type="{ record }">{{ COST_TYPE[record.costType] || record.costType }}</template>
            <template #amount="{ record }">¥{{ money(record.amount) }}</template>
          </a-table>
        </a-tab-pane>

        <!-- 状态流水 -->
        <a-tab-pane key="logs" title="状态流水">
          <a-timeline>
            <a-timeline-item v-for="log in detail.statusLogs" :key="log.id">
              <span class="log-status">{{ labelOf(log.fromStatus) }} → {{ labelOf(log.toStatus) }}</span>
              <span class="log-time">{{ log.createdAt }}</span>
              <div class="log-remark">{{ log.remark || '—' }}</div>
            </a-timeline-item>
          </a-timeline>
        </a-tab-pane>

        <!-- 证件 -->
        <a-tab-pane key="certs" title="证件">
          <a-space style="margin-bottom: 12px">
            <a-button size="small" type="outline" @click="certVisible = true">新增证件</a-button>
          </a-space>
          <a-table row-key="id" :columns="certColumns" :data="certs" :pagination="false" stripe>
            <template #certType="{ record }">{{ CERT_TYPE[record.certType] || record.certType }}</template>
            <template #expire="{ record }">
              <span :class="{ danger: isSoon(record.expireDate) }">{{ record.expireDate || '长期有效' }}</span>
            </template>
          </a-table>
        </a-tab-pane>

        <!-- 视频任务 -->
        <a-tab-pane key="videos" title="AI 短视频">
          <a-space style="margin-bottom: 12px">
            <a-input v-model="videoText" placeholder="补充卖点，留空则用品牌车型" style="width: 380px" allow-clear />
            <a-button type="primary" :loading="submitting" @click="onSubmitVideo">生成视频</a-button>
          </a-space>
          <a-table row-key="id" :columns="videoColumns" :data="videoTasks" :pagination="false" stripe>
            <template #status="{ record }">
              <a-tag :color="videoOf(record.status).color">{{ videoOf(record.status).text }}</a-tag>
            </template>
            <template #retry="{ record }">{{ record.retryCount }}</template>
            <template #reason="{ record }">{{ record.failReason || '—' }}</template>
          </a-table>
          <div v-if="lastCopy" class="copy-box">
            <div class="copy-title">口播文案</div>
            <div>{{ lastCopy }}</div>
          </div>
          <div v-if="assets.length" class="asset-box">
            <div class="copy-title">产物</div>
            <div v-for="a in assets" :key="a.id" class="asset-row">
              <a-tag>{{ a.assetType }}</a-tag>
              <span class="asset-path">{{ a.url }}</span>
            </div>
          </div>
        </a-tab-pane>
      </a-tabs>
    </a-card>

    <!-- 追加成本 -->
    <a-modal v-model:visible="costVisible" title="追加成本" @before-ok="onAddCost">
      <a-form :model="costForm" layout="vertical">
        <a-form-item label="成本类型">
          <a-select v-model="costForm.costType">
            <a-option v-for="(label, key) in COST_TYPE" :key="key" :value="key">{{ label }}</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="金额（元）">
          <a-input-number v-model="costForm.amount" :min="0.01" :precision="2" style="width: 100%" />
        </a-form-item>
        <a-form-item label="发生日期">
          <a-date-picker v-model="costForm.occurDate" value-format="YYYY-MM-DD" style="width: 100%" />
        </a-form-item>
        <a-form-item label="说明">
          <a-input v-model="costForm.remark" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 新增证件 -->
    <a-modal v-model:visible="certVisible" title="新增证件" @before-ok="onAddCert">
      <a-form :model="certForm" layout="vertical">
        <a-form-item label="证件类型">
          <a-select v-model="certForm.certType">
            <a-option v-for="(label, key) in CERT_TYPE" :key="key" :value="key">{{ label }}</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="证件编号">
          <a-input v-model="certForm.certNo" />
        </a-form-item>
        <a-form-item label="到期日期（留空表示长期有效）">
          <a-date-picker v-model="certForm.expireDate" value-format="YYYY-MM-DD" style="width: 100%" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import {
  addCost, changeStatus, createCertificate, getPricing, getVehicle, getVideoAssets,
  getVideoTask, listCertificates, listCosts, listVideoTasks, submitVideoTask
} from '../api'
import { COST_TYPE, SLOW_LEVEL, VEHICLE_STATUS, VIDEO_STATUS, money } from '../utils/dict'

const route = useRoute()
const vehicleId = Number(route.params.id)

/**
 * 当前标签页。默认读 URL 上的 ?tab=，这样「发给同事一个链接，直接打开视频页」能实现；
 * 不传时回落到成本明细。
 */
const activeTab = ref(typeof route.query.tab === 'string' ? route.query.tab : 'costs')

const CERT_TYPE = { REGISTER: '登记证', DRIVING: '行驶证', INSURANCE: '交强险', INSPECT: '年检' }

/** 状态机的前进方向，和 VehicleStatus 枚举的 ALLOWED 表一一对应 */
const NEXT = {
  PURCHASED: [{ value: 'ON_SALE', label: '挂牌上架' }],
  ON_SALE: [{ value: 'SOLD', label: '成交' }, { value: 'CLEARED', label: '直接清库' }],
  SOLD: [{ value: 'AFTER_SALE', label: '进入售后' }, { value: 'CLEARED', label: '直接清库' }],
  AFTER_SALE: [{ value: 'CLEARED', label: '售后结案清库' }],
  CLEARED: []
}

const detail = ref(null)
const pricing = ref(null)
const costs = ref([])
const certs = ref([])
const videoTasks = ref([])
const assets = ref([])
const lastCopy = ref('')
const videoText = ref('')
const submitting = ref(false)

const costVisible = ref(false)
const certVisible = ref(false)
const costForm = reactive({ costType: 'RECONDITION', amount: undefined, occurDate: undefined, remark: '' })
const certForm = reactive({ certType: 'INSURANCE', certNo: '', expireDate: undefined })

const costColumns = [
  { title: '类型', slotName: 'type', width: 100 },
  { title: '金额', slotName: 'amount', width: 120 },
  { title: '发生日期', dataIndex: 'occurDate', width: 120 },
  { title: '说明', dataIndex: 'remark' }
]
const certColumns = [
  { title: '类型', slotName: 'certType', width: 100 },
  { title: '编号', dataIndex: 'certNo' },
  { title: '到期日', slotName: 'expire', width: 140 }
]
const videoColumns = [
  // 任务号 20 位（VT + 14 位时间 + 3 位随机），宽度给到 210 才不会折行
  { title: '任务号', dataIndex: 'taskNo', width: 210 },
  { title: '状态', slotName: 'status', width: 100 },
  { title: '重试', slotName: 'retry', width: 70 },
  { title: '失败原因', slotName: 'reason' }
]

const nextTargets = computed(() => NEXT[detail.value?.vehicle.status] || [])

function statusOf(s) {
  return VEHICLE_STATUS[s] || { text: s, color: 'gray' }
}
function slowOf(s) {
  return SLOW_LEVEL[s] || { text: s, color: 'gray' }
}
function videoOf(s) {
  return VIDEO_STATUS[s] || { text: s, color: 'gray' }
}
function labelOf(s) {
  return s ? (VEHICLE_STATUS[s]?.text || s) : '开始'
}
function isSoon(date) {
  if (!date) return false
  const days = (new Date(date).getTime() - Date.now()) / 86400000
  return days <= 30
}

async function loadAll() {
  detail.value = await getVehicle(vehicleId)
  pricing.value = await getPricing(vehicleId)
  costs.value = await listCosts(vehicleId)
  certs.value = await listCertificates(vehicleId)
  videoTasks.value = await listVideoTasks(vehicleId)
  const done = videoTasks.value.find((t) => t.status === 'SUCCESS')
  if (done) {
    lastCopy.value = done.marketingCopy
    assets.value = await getVideoAssets(done.id)
  }
}

async function onChangeStatus(target) {
  try {
    await changeStatus(vehicleId, { targetStatus: target.value })
    Message.success('状态已更新')
    await loadAll()
  } catch (e) {
    // 拦截器已提示
  }
}

async function onAddCost() {
  try {
    await addCost(vehicleId, costForm)
    Message.success('已记录')
    costVisible.value = false
    costs.value = await listCosts(vehicleId)
    pricing.value = await getPricing(vehicleId)
  } catch (e) {
    // 忽略
  }
  return false
}

async function onAddCert() {
  try {
    await createCertificate(vehicleId, certForm)
    Message.success('已新增')
    certVisible.value = false
    certs.value = await listCertificates(vehicleId)
  } catch (e) {
    // 忽略
  }
  return false
}

// ---------- 视频任务轮询 ----------
let pollTimer = null

async function onSubmitVideo() {
  submitting.value = true
  try {
    const taskId = await submitVideoTask({ vehicleId, sourceText: videoText.value || undefined })
    Message.success('任务已提交')
    videoTasks.value = await listVideoTasks(vehicleId)
    pollTask(taskId)
  } catch (e) {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

/**
 * 轮询任务状态。
 * 为什么在前端轮询而不是后端 WebSocket：
 *   任务最长几十秒，轮询够用；WebSocket 要维护长连接和断线重连，对这个场景是过度设计。
 */
function pollTask(taskId) {
  clearInterval(pollTimer)
  pollTimer = setInterval(async () => {
    const task = await getVideoTask(taskId)
    videoTasks.value = await listVideoTasks(vehicleId)
    if (task.status === 'SUCCESS') {
      clearInterval(pollTimer)
      lastCopy.value = task.marketingCopy
      assets.value = await getVideoAssets(taskId)
      Message.success('视频生成完成')
    } else if (task.status === 'FAILED') {
      clearInterval(pollTimer)
      Message.error('视频生成失败：' + (task.failReason || ''))
    }
  }, 3000)
}

onMounted(loadAll)
onUnmounted(() => clearInterval(pollTimer))
</script>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}
.stock {
  font-family: ui-monospace, Menlo, monospace;
  font-size: 13px;
  color: #4e5969;
}
.name {
  margin: 6px 0 8px;
  font-size: 20px;
  font-weight: 500;
}
.meta {
  display: flex;
  gap: 18px;
  font-size: 12px;
  color: #86909c;
}
.tip {
  font-size: 12px;
  color: #86909c;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  background: #f7f8fa;
  border-radius: 6px;
}
.stat .label {
  font-size: 12px;
  color: #86909c;
}
.stat .value {
  font-size: 16px;
  font-weight: 500;
}
.stat .rate {
  margin-left: 6px;
  font-size: 12px;
  font-style: normal;
  color: #f53f3f;
}
.danger {
  color: #f53f3f;
}
.log-status {
  font-size: 13px;
  font-weight: 500;
}
.log-time {
  margin-left: 10px;
  font-size: 12px;
  color: #86909c;
}
.log-remark {
  margin-top: 2px;
  font-size: 12px;
  color: #4e5969;
}
.copy-box,
.asset-box {
  margin-top: 14px;
  padding: 12px;
  background: #f7f8fa;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.7;
}
.copy-title {
  margin-bottom: 6px;
  font-size: 12px;
  color: #86909c;
}
.asset-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 4px;
}
.asset-path {
  font-family: ui-monospace, Menlo, monospace;
  font-size: 12px;
  color: #4e5969;
  word-break: break-all;
}
</style>
