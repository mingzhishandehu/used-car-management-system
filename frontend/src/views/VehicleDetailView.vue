<template>
  <div v-if="detail" class="uc-page detail">
    <div class="back-row">
      <a-button type="text" size="small" class="back-btn" @click="goBack">
        <template #icon><icon-left /></template>
        返回车辆台账
      </a-button>
    </div>

    <!-- 车辆抬头 -->
    <a-card :bordered="false" class="hero">
      <div class="hero-body">
        <div class="hero-left">
          <div class="hero-tags">
            <span class="stock num">{{ detail.vehicle.stockNo }}</span>
            <span class="pill" :class="statusOf(detail.vehicle.status).cls">{{ statusOf(detail.vehicle.status).text }}</span>
            <span v-if="pricing && pricing.slowLevel !== 'NORMAL'" class="pill" :class="slowOf(pricing.slowLevel).cls">
              {{ slowOf(pricing.slowLevel).text }}
            </span>
          </div>
          <h1 class="hero-title">{{ detail.vehicle.brand }} {{ detail.vehicle.model }}</h1>
          <div class="meta-chips">
            <span class="meta-chip"><em>收车</em>{{ detail.vehicle.purchaseDate || '—' }}</span>
            <span class="meta-chip"><em>收车价</em>¥{{ money(detail.vehicle.purchasePrice) }}</span>
            <span v-if="detail.vehicle.mileage" class="meta-chip"><em>里程</em>{{ mileageText(detail.vehicle.mileage) }}</span>
            <span v-if="detail.vehicle.plateNo" class="meta-chip"><em>车牌</em>{{ detail.vehicle.plateNo }}</span>
            <span class="meta-chip"><em>录入</em>{{ employeeName(detail.vehicle.createdBy) }}</span>
            <span class="meta-chip"><em>对接</em>{{ employeeName(detail.vehicle.ownerUserId) }}</span>
          </div>
        </div>

        <div class="hero-actions">
          <a-button v-if="canChangePrice" @click="openPriceDialog">调整挂牌价</a-button>
          <a-dropdown v-if="secondaryTargets.length" trigger="click" position="br">
            <a-button>
              更多操作
              <template #icon><icon-down /></template>
            </a-button>
            <template #content>
              <a-doption v-for="t in secondaryTargets" :key="t.value" @click="onChangeStatus(t)">
                {{ t.label }}
              </a-doption>
            </template>
          </a-dropdown>
          <a-popconfirm
            v-if="primaryTarget && primaryTone.confirm"
            :content="primaryTone.confirm"
            position="br"
            @ok="onChangeStatus(primaryTarget)"
          >
            <a-button :type="primaryTone.type" :status="primaryTone.status">{{ primaryTarget.label }}</a-button>
          </a-popconfirm>
          <a-button
            v-else-if="primaryTarget"
            :type="primaryTone.type"
            :status="primaryTone.status"
            @click="onChangeStatus(primaryTarget)"
          >
            {{ primaryTarget.label }}
          </a-button>
          <span v-else-if="detail.vehicle.status === 'CLEARED'" class="cleared-tag">已走完全流程</span>
        </div>
      </div>
    </a-card>

    <!-- 定价测算 -->
    <a-card :bordered="false" class="pricing-card">
      <template #title>
        <div class="card-title-row">
          <span>定价测算</span>
          <span class="rule-badge"><NavIcon name="coin" :size="13" />按门店规则</span>
        </div>
      </template>

      <a-spin v-if="!pricing" style="width: 100%" />
      <template v-else>
        <!-- 价格链路：成本 → 保本 → 挂牌 → 建议，四格等宽，不做绝对定位，天然不会标签重叠 -->
        <div class="price-chain">
          <div v-for="(item, i) in priceChain" :key="item.key" class="chain-cell">
            <div class="chain-top">
              <span class="chain-label">{{ item.label }}</span>
              <span v-if="i === 3 && pricing.ladderRate" class="chain-rate">降 {{ (pricing.ladderRate * 100).toFixed(0) }}%</span>
            </div>
            <b class="chain-value money"><span class="cny">¥</span>{{ money(item.value) }}</b>
            <span class="chain-bar"><i :style="{ width: item.percent + '%', background: item.color }" /></span>
            <span class="chain-hint">{{ item.hint }}</span>
          </div>
        </div>

        <div class="stat-grid">
          <div class="stat-tile">
            <span class="stat-label">持有天数</span>
            <b class="stat-value num">{{ pricing.holdingDays }}<em>天</em></b>
            <span class="stat-hint">从收车日期起算</span>
          </div>
          <div class="stat-tile">
            <span class="stat-label">资金成本</span>
            <b class="stat-value money"><span class="cny">¥</span>{{ money(pricing.capitalCost) }}</b>
            <span class="stat-hint">按年化与持有天数计提</span>
          </div>
          <div class="stat-tile">
            <span class="stat-label">预计毛利</span>
            <b class="stat-value money" :class="{ loss: pricing.expectedProfit < 0 }">
              <span class="cny">¥</span>{{ money(pricing.expectedProfit) }}
            </b>
            <span class="stat-hint">按当前挂牌价估算</span>
          </div>
          <div class="stat-tile">
            <span class="stat-label">滞销等级</span>
            <b class="stat-value">
              <span class="pill" :class="slowOf(pricing.slowLevel).cls">{{ slowOf(pricing.slowLevel).text }}</span>
            </b>
            <span class="stat-hint">按库存天数自动分级</span>
          </div>
        </div>

        <div class="uc-note">
          <NavIcon name="bulb" :size="15" />
          <span>
            保本底价、阶梯降幅与滞销阈值由门店的定价规则自动测算，规则调整后即时生效。
            这里是参考值，实际调价仍通过「调整挂牌价」完成，并留下调价记录。
          </span>
        </div>
      </template>
    </a-card>

    <!-- 明细区 -->
    <a-card :bordered="false" class="tabs-card">
      <a-tabs v-model:active-key="activeTab">
        <a-tab-pane key="costs">
          <template #title>成本明细 <span class="uc-tab-count">{{ costs.length }}</span></template>
          <div class="pane-toolbar">
            <a-button size="small" @click="costVisible = true">
              <template #icon><icon-plus /></template>
              追加成本
            </a-button>
            <span class="pane-hint">整备、运输、过户等费用都会计入总成本，直接影响保本价</span>
          </div>
          <a-table row-key="id" :columns="costColumns" :data="costs" :pagination="false">
            <template #type="{ record }">{{ COST_TYPE[record.costType] || record.costType }}</template>
            <template #amount="{ record }">
              <span class="money"><span class="cny">¥</span>{{ money(record.amount) }}</span>
            </template>
            <template #empty><div class="uc-empty">还没有成本记录</div></template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="logs">
          <template #title>状态流水 <span class="uc-tab-count">{{ detail.statusLogs.length }}</span></template>
          <div class="pane-toolbar">
            <span class="pane-hint">状态只能按业务流程向前推进，撤回会单独记录；每次变更都保留操作人与备注</span>
          </div>
          <a-timeline v-if="detail.statusLogs.length" class="flow-timeline">
            <a-timeline-item
              v-for="log in detail.statusLogs"
              :key="log.id"
              :dot-color="isRollback(log) ? '#d99a2b' : '#3457d5'"
            >
              <div class="log-card">
                <div class="log-head">
                  <span :class="['log-status', { rollback: isRollback(log) }]">
                    {{ labelOf(log.fromStatus) }}
                    <icon-right class="log-arrow" />
                    {{ labelOf(log.toStatus) }}
                  </span>
                  <a-tag v-if="isRollback(log)" size="small" color="orange">撤回</a-tag>
                  <span class="log-time num">{{ formatDateTime(log.createdAt) }}</span>
                </div>
                <div class="log-remark">{{ log.remark || '无备注' }}</div>
                <div class="log-by">操作人 {{ employeeName(log.operatorId) }}</div>
              </div>
            </a-timeline-item>
          </a-timeline>
          <div v-else class="uc-empty">还没有状态流水</div>
        </a-tab-pane>

        <a-tab-pane key="certs">
          <template #title>证件 <span class="uc-tab-count">{{ certs.length }}</span></template>
          <div class="pane-toolbar">
            <a-button size="small" @click="certVisible = true">
              <template #icon><icon-plus /></template>
              新增证件
            </a-button>
            <span class="pane-hint">到期日 30 天内会标红，巡检时自动生成证件到期预警</span>
          </div>
          <a-table row-key="id" :columns="certColumns" :data="certs" :pagination="false">
            <template #certType="{ record }">{{ CERT_TYPE[record.certType] || record.certType }}</template>
            <template #expire="{ record }">
              <span v-if="!record.expireDate" class="muted">长期有效</span>
              <span v-else :class="{ 'text-danger': isSoon(record.expireDate) }">
                {{ record.expireDate }}
                <a-tag v-if="isSoon(record.expireDate)" size="small" color="red" style="margin-left: 6px">临期</a-tag>
              </span>
            </template>
            <template #empty><div class="uc-empty">还没有登记证件</div></template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="orders">
          <template #title>成交订单 <span class="uc-tab-count">{{ orders.length }}</span></template>
          <div class="pane-toolbar">
            <a-button v-if="detail.vehicle.status === 'ON_SALE'" size="small" type="primary" @click="openOrderDialog">
              登记成交
            </a-button>
            <span class="pane-hint">
              只有在售车辆能登记成交；登记后自动生成订单号，并把状态推进到「已成交」
            </span>
          </div>
          <a-table row-key="id" :columns="orderColumns" :data="orders" :pagination="false">
            <template #orderCustomer="{ record }">{{ customerName(record.customerId) }}</template>
            <template #orderPrice="{ record }">
              <span class="money"><span class="cny">¥</span>{{ money(record.dealPrice) }}</span>
            </template>
            <template #payType="{ record }">{{ record.payType === 'LOAN' ? '按揭' : '全款' }}</template>
            <template #empty><div class="uc-empty">还没有成交订单</div></template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="afterSales">
          <template #title>售后 <span class="uc-tab-count">{{ afterSales.length }}</span></template>
          <div class="pane-toolbar">
            <a-button size="small" @click="afterSaleVisible = true">
              <template #icon><icon-plus /></template>
              新增售后单
            </a-button>
            <span class="pane-hint">还有未结案的售后单时，车辆无法清库</span>
          </div>
          <a-table row-key="id" :columns="afterSaleColumns" :data="afterSales" :pagination="false">
            <template #asCost="{ record }">
              <span class="money"><span class="cny">¥</span>{{ money(record.cost) }}</span>
            </template>
            <template #asStatus="{ record }">
              <span class="pill" :class="record.status === 'OPEN' ? 'pill-amber' : 'pill-green'">
                {{ record.status === 'OPEN' ? '处理中' : '已结案' }}
              </span>
            </template>
            <template #asActions="{ record }">
              <a-link v-if="record.status === 'OPEN'" @click="onCloseAfterSale(record)">结案</a-link>
              <span v-else class="muted">—</span>
            </template>
            <template #empty><div class="uc-empty">还没有售后记录</div></template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="handovers">
          <template #title>流转记录 <span class="uc-tab-count">{{ handovers.length }}</span></template>
          <a-alert v-if="!handovers.length" type="info">这辆车没有发生过对接权流转，当前对接人即原始对接人。</a-alert>
          <a-table v-else row-key="id" :columns="handoverColumns" :data="handovers" :pagination="false">
            <template #pair="{ record }">
              {{ record.fromUserName }}
              <icon-right class="pair-arrow" />
              <b>{{ record.toUserName }}</b>
            </template>
            <template #createdAt="{ record }">{{ formatDateTime(record.createdAt) }}</template>
          </a-table>
          <div class="uc-note" style="margin-top: 12px">
            <NavIcon name="approval" :size="15" />
            <span>流转只改对接人；录入人、状态流水、调价记录、审批单和聊天记录都按原样保留。</span>
          </div>
        </a-tab-pane>
      </a-tabs>
    </a-card>

    <!-- 追加成本 -->
    <a-modal v-model:visible="costVisible" title="追加成本" :width="460" ok-text="记入成本" @before-ok="onAddCost">
      <a-form :model="costForm" layout="vertical">
        <a-form-item label="成本类型">
          <a-select v-model="costForm.costType">
            <a-option v-for="(label, key) in COST_TYPE" :key="key" :value="key">{{ label }}</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="金额（元）" required>
          <a-input-number v-model="costForm.amount" :min="0.01" :precision="2" style="width: 100%" />
        </a-form-item>
        <a-form-item label="发生日期">
          <a-date-picker v-model="costForm.occurDate" value-format="YYYY-MM-DD" style="width: 100%" />
        </a-form-item>
        <a-form-item label="说明">
          <a-input v-model="costForm.remark" placeholder="例如 补漆、换轮胎" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 新增证件 -->
    <a-modal v-model:visible="certVisible" title="新增证件" :width="460" ok-text="保存证件" @before-ok="onAddCert">
      <a-form :model="certForm" layout="vertical">
        <a-form-item label="证件类型">
          <a-select v-model="certForm.certType">
            <a-option v-for="(label, key) in CERT_TYPE" :key="key" :value="key">{{ label }}</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="证件编号">
          <a-input v-model="certForm.certNo" placeholder="按证件实际编号填写" />
        </a-form-item>
        <a-form-item label="到期日期（留空表示长期有效）">
          <a-date-picker v-model="certForm.expireDate" value-format="YYYY-MM-DD" style="width: 100%" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 调整挂牌价 -->
    <a-modal v-model:visible="priceVisible" title="调整挂牌价" :width="460" ok-text="保存调价" @before-ok="onChangePrice">
      <a-form :model="priceForm" layout="vertical">
        <a-form-item label="新的挂牌价（元）" required>
          <a-input-number v-model="priceForm.newPrice" :min="0.01" :precision="2" style="width: 100%" />
        </a-form-item>
        <a-form-item label="调价原因">
          <a-input v-model="priceForm.reason" placeholder="例如：市场调整、客户议价" allow-clear />
        </a-form-item>
      </a-form>
      <div class="uc-note">
        <NavIcon name="chart" :size="15" />
        <span>每次调价都会留下记录；首次挂牌价作为阶梯降价的基准，后续调价不改动它。</span>
      </div>
    </a-modal>

    <!-- 成交登记 -->
    <a-modal v-model:visible="orderVisible" title="登记成交" :width="500" ok-text="确认成交" @before-ok="onCreateOrder">
      <a-form :model="orderForm" layout="vertical">
        <a-form-item label="买方客户" required>
          <a-select v-model="orderForm.customerId" placeholder="选择客户" show-search>
            <a-option v-for="c in customers" :key="c.id" :value="c.id">
              {{ c.name }}（{{ c.phone }}）
            </a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="成交价（元）" required>
          <a-input-number v-model="orderForm.dealPrice" :min="0.01" :precision="2" style="width: 100%" />
        </a-form-item>
        <a-form-item label="成交日期" required>
          <a-date-picker v-model="orderForm.dealDate" value-format="YYYY-MM-DD" style="width: 100%" />
        </a-form-item>
        <a-grid :cols="2" :col-gap="14">
          <a-grid-item>
            <a-form-item label="付款方式">
              <a-select v-model="orderForm.payType">
                <a-option value="FULL">全款</a-option>
                <a-option value="LOAN">按揭</a-option>
              </a-select>
            </a-form-item>
          </a-grid-item>
          <a-grid-item>
            <a-form-item label="定金（元）">
              <a-input-number v-model="orderForm.deposit" :min="0" :precision="2" style="width: 100%" />
            </a-form-item>
          </a-grid-item>
        </a-grid>
      </a-form>
    </a-modal>

    <!-- 新增售后单 -->
    <a-modal v-model:visible="afterSaleVisible" title="新增售后单" :width="500" ok-text="创建售后单" @before-ok="onCreateAfterSale">
      <a-form :model="afterSaleForm" layout="vertical">
        <a-form-item label="问题类型" required>
          <a-select v-model="afterSaleForm.issueType">
            <a-option value="REPAIR">维修</a-option>
            <a-option value="REFUND">退款</a-option>
            <a-option value="COMPLAINT">投诉</a-option>
            <a-option value="OTHER">其他</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="问题描述" required>
          <a-input v-model="afterSaleForm.description" placeholder="客户反馈的具体问题" />
        </a-form-item>
        <a-form-item label="售后成本（元，计入总成本）" required>
          <a-input-number v-model="afterSaleForm.cost" :min="0" :precision="2" style="width: 100%" />
        </a-form-item>
      </a-form>
      <div class="uc-note">
        <NavIcon name="coin" :size="15" />
        <span>售后成本会写入成本表并计入总成本，保本价随之抬高；未结案时不允许清库。</span>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import NavIcon from '../components/NavIcon.vue'
import {
  addCost, changePrice, changeStatus, createCertificate, getPricing, getVehicle,
  listCertificates, listCosts, listEmployees, listVehicleHandovers,
  listSaleOrders, createSaleOrder, listAfterSales, createAfterSale, closeAfterSale, listCustomers
} from '../api'
import { COST_TYPE, SLOW_LEVEL, VEHICLE_STATUS, money } from '../utils/dict'

const route = useRoute()
const router = useRouter()
const vehicleId = Number(route.params.id)

// 旧链接指向已移除的标签时，回落到成本明细。
const activeTab = ref(['costs', 'logs', 'certs', 'handovers', 'orders', 'afterSales'].includes(route.query.tab) ? route.query.tab : 'costs')

const CERT_TYPE = { REGISTER: '登记证', DRIVING: '行驶证', INSURANCE: '交强险', INSPECT: '年检' }

/** 状态机的前进方向，和 VehicleStatus 枚举的 ALLOWED 表一一对应 */
const NEXT = {
  PURCHASED: [{ value: 'ON_SALE', label: '挂牌上架' }],
  ON_SALE: [{ value: 'SOLD', label: '成交' }, { value: 'CLEARED', label: '直接清库' }],
  SOLD: [{ value: 'ON_SALE', label: '撤回成交' }, { value: 'AFTER_SALE', label: '进入售后' }, { value: 'CLEARED', label: '直接清库' }],
  AFTER_SALE: [{ value: 'SOLD', label: '撤回售后' }, { value: 'CLEARED', label: '售后结案清库' }],
  CLEARED: []
}

const detail = ref(null)
const pricing = ref(null)
const costs = ref([])
const certs = ref([])
const employees = ref([])
const handovers = ref([])
const orders = ref([])
const afterSales = ref([])
const customers = ref([])

const costVisible = ref(false)
const certVisible = ref(false)
const priceVisible = ref(false)
const orderVisible = ref(false)
const afterSaleVisible = ref(false)
const costForm = reactive({ costType: 'RECONDITION', amount: undefined, occurDate: undefined, remark: '' })
const certForm = reactive({ certType: 'INSURANCE', certNo: '', expireDate: undefined })
const priceForm = reactive({ newPrice: undefined, reason: '' })
const orderForm = reactive({
  customerId: undefined, dealPrice: undefined, dealDate: undefined, payType: 'FULL', deposit: 0
})
const afterSaleForm = reactive({ issueType: 'REPAIR', description: '', cost: 0 })

const costColumns = [
  { title: '类型', slotName: 'type', width: 100 },
  { title: '金额', slotName: 'amount', width: 150, align: 'right' },
  { title: '发生日期', dataIndex: 'occurDate', width: 130 },
  { title: '说明', dataIndex: 'remark' }
]
const certColumns = [
  { title: '类型', slotName: 'certType', width: 110 },
  { title: '编号', dataIndex: 'certNo' },
  { title: '到期日', slotName: 'expire', width: 180 }
]
const handoverColumns = [
  { title: '原对接人 → 新对接人', slotName: 'pair', width: 240 },
  { title: '流转原因', dataIndex: 'reason' },
  { title: '办理人', dataIndex: 'operatorName', width: 110 },
  { title: '流转时间', slotName: 'createdAt', width: 160 }
]
const orderColumns = [
  { title: '订单号', dataIndex: 'orderNo', width: 160 },
  { title: '买方', slotName: 'orderCustomer', width: 130 },
  { title: '成交价', slotName: 'orderPrice', width: 150, align: 'right' },
  { title: '成交日期', dataIndex: 'dealDate', width: 120 },
  { title: '付款', slotName: 'payType', width: 90 },
  { title: '状态', dataIndex: 'status', width: 100 }
]
const afterSaleColumns = [
  { title: '问题类型', dataIndex: 'issueType', width: 100 },
  { title: '问题描述', dataIndex: 'description' },
  { title: '成本', slotName: 'asCost', width: 130, align: 'right' },
  { title: '状态', slotName: 'asStatus', width: 110 },
  { title: '操作', slotName: 'asActions', width: 90 }
]

const nextTargets = computed(() => {
  const status = detail.value?.vehicle.status
  if (status === 'CLEARED') {
    const logs = detail.value?.statusLogs || []
    const last = logs[logs.length - 1]
    const previous = last?.toStatus === 'CLEARED' ? last.fromStatus : null
    const labels = { ON_SALE: '在售', SOLD: '已成交', AFTER_SALE: '售后' }
    return labels[previous] ? [{ value: previous, label: `撤回清库（恢复${labels[previous]}）` }] : []
  }
  return NEXT[status] || []
})
const primaryTarget = computed(() => {
  // 主按钮只放「往前走」的动作：已成交的车优先给清库，撤回类动作永远放「更多操作」里，
  // 否则一辆已成交的车，最显眼的按钮会变成「撤回成交」，点错代价很大。
  const pref = { PURCHASED: 'ON_SALE', ON_SALE: 'SOLD', SOLD: 'CLEARED', AFTER_SALE: 'CLEARED' }[detail.value?.vehicle.status]
  return nextTargets.value.find((t) => t.value === pref) || nextTargets.value[0] || null
})
const secondaryTargets = computed(() => nextTargets.value.filter((t) => t !== primaryTarget.value))

const primaryTone = computed(() => {
  if (!primaryTarget.value) return { type: 'primary', status: undefined, confirm: '' }
  const t = primaryTarget.value
  if (t.value === 'CLEARED') {
    return { type: 'primary', status: 'danger', confirm: '清库后车辆退出库存。如果点错了，可以在「更多操作 → 撤回清库」恢复，确定吗？' }
  }
  if (t.label.includes('撤回')) {
    return { type: 'secondary', status: undefined, confirm: `确定要${t.label}吗？` }
  }
  return { type: 'primary', status: undefined, confirm: '' }
})
const canChangePrice = computed(() => ['PURCHASED', 'ON_SALE'].includes(detail.value?.vehicle.status))

/** 价格链路：四格等宽排布，条形长度按四者最大值归一化，只做相对比较，不承担精确刻度 */
const priceChain = computed(() => {
  const p = pricing.value
  if (!p) return []
  const values = [p.totalCost, p.breakevenPrice, p.listPrice, p.suggestedPrice].map((v) => Number(v) || 0)
  const max = Math.max(...values, 1)
  const items = [
    { key: 'cost', label: '成本合计', value: p.totalCost, color: '#8b93a5', hint: '收车款与所有已登记成本' },
    { key: 'break', label: '保本底价', value: p.breakevenPrice, color: '#b7791f', hint: '含资金成本与规则加成' },
    { key: 'list', label: '当前挂牌价', value: p.listPrice, color: '#3457d5', hint: '当前对外报价' },
    {
      key: 'suggest',
      label: '阶梯建议价',
      value: p.suggestedPrice,
      color: p.suggestedPrice < p.listPrice ? '#c0392b' : '#1f8a5b',
      hint: '按持有天数套阶梯规则'
    }
  ]
  return items.map((i) => ({ ...i, percent: Math.round(((Number(i.value) || 0) / max) * 100) }))
})

const PILL_CLASS = { arcoblue: 'pill-blue', green: 'pill-green', purple: 'pill-violet', orange: 'pill-amber', gray: 'pill-gray' }

function statusOf(s) {
  const found = VEHICLE_STATUS[s]
  return { text: found?.text || s, cls: PILL_CLASS[found?.color] || 'pill-gray' }
}

function slowOf(s) {
  const found = SLOW_LEVEL[s]
  const SLOW_CLASS = { NORMAL: 'pill-green', ATTENTION: 'pill-amber', WARN: 'pill-amber', DANGER: 'pill-red' }
  if (!found) return { text: s || '正常', cls: 'pill-gray' }
  return { text: found.text, cls: SLOW_CLASS[s] || 'pill-gray' }
}

function labelOf(s) {
  return s ? (VEHICLE_STATUS[s]?.text || s) : '开始'
}

function isRollback(log) {
  return (log.fromStatus === 'SOLD' && log.toStatus === 'ON_SALE')
    || (log.fromStatus === 'AFTER_SALE' && log.toStatus === 'SOLD')
    || (log.fromStatus === 'CLEARED' && ['ON_SALE', 'SOLD', 'AFTER_SALE'].includes(log.toStatus))
}

function isSoon(date) {
  if (!date) return false
  const days = (new Date(date).getTime() - Date.now()) / 86400000
  return days <= 30
}

function formatDateTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

function mileageText(value) {
  const n = Number(value) || 0
  return n >= 10000 ? `${(n / 10000).toFixed(1)} 万公里` : `${n} 公里`
}

function employeeName(id) {
  // created_by 为 NULL 的是 V8 升级前录入的历史车辆，当时没有记录归属
  if (!id) return '—'
  const e = employees.value.find((x) => x.id === id)
  return e ? (e.realName || e.username) : '未知员工'
}

function customerName(id) {
  const c = customers.value.find((x) => x.id === id)
  return c ? c.name : `客户 #${id}`
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push({ name: 'vehicles' })
}

async function loadAll() {
  detail.value = await getVehicle(vehicleId)
  pricing.value = await getPricing(vehicleId)
  costs.value = await listCosts(vehicleId)
  certs.value = await listCertificates(vehicleId)
  try {
    employees.value = await listEmployees()
  } catch (e) {
    // 员工列表拉失败不该让整页打不开，姓名会退化成显示账号 id
  }
  try {
    handovers.value = await listVehicleHandovers(vehicleId)
  } catch (e) {
    // 流转记录拉失败不影响主详情
  }
  for (const [setter, loader] of [
    [orders, () => listSaleOrders(vehicleId)],
    [afterSales, () => listAfterSales(vehicleId)]
  ]) {
    try {
      setter.value = await loader()
    } catch (e) {
      // 单块数据拉失败不让整页打不开
    }
  }
}

async function onChangeStatus(target) {
  try {
    const res = await changeStatus(vehicleId, { targetStatus: target.value })
    // 员工提交的是申请，业务还没执行；api 层已弹提示，这里不刷新也不报「已更新」
    if (!res || !res.executed) return
    Message.success('状态已更新')
    await loadAll()
  } catch (e) {
    // 拦截器已提示
  }
}

async function onAddCost() {
  if (!costForm.amount) {
    Message.warning('金额必填')
    return false
  }
  try {
    const res = await addCost(vehicleId, costForm)
    if (!res || !res.executed) {
      costVisible.value = false
      return false
    }
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
    const res = await createCertificate(vehicleId, certForm)
    if (!res || !res.executed) {
      certVisible.value = false
      return false
    }
    Message.success('已新增')
    certVisible.value = false
    certs.value = await listCertificates(vehicleId)
  } catch (e) {
    // 忽略
  }
  return false
}

async function onChangePrice() {
  if (!priceForm.newPrice) {
    Message.warning('新价格必填')
    return false
  }
  try {
    const res = await changePrice(vehicleId, priceForm)
    if (!res || !res.executed) {
      priceVisible.value = false
      return false
    }
    Message.success('挂牌价已更新')
    priceVisible.value = false
    priceForm.newPrice = undefined
    priceForm.reason = ''
    await loadAll()
  } catch (e) {
    // 拦截器已提示
  }
  return false
}

function openPriceDialog() {
  priceForm.newPrice = detail.value?.vehicle.listPrice ?? undefined
  priceForm.reason = ''
  priceVisible.value = true
}

async function openOrderDialog() {
  Object.assign(orderForm, {
    customerId: undefined, dealPrice: detail.value?.vehicle.listPrice ?? undefined,
    dealDate: undefined, payType: 'FULL', deposit: 0
  })
  try {
    const page = await listCustomers({ page: 1, size: 100 })
    customers.value = page.records || []
  } catch (e) {
    // 客户列表失败时下拉为空，提交前会提示必填
  }
  orderVisible.value = true
}

async function onCreateOrder() {
  if (!orderForm.customerId || !orderForm.dealPrice || !orderForm.dealDate) {
    Message.warning('客户、成交价、成交日期必填')
    return false
  }
  try {
    await createSaleOrder(vehicleId, { ...orderForm })
    orderVisible.value = false
    await loadAll()
  } catch (e) {
    // 提示已由拦截器弹出
  }
  return false
}

async function onCreateAfterSale() {
  if (!afterSaleForm.description.trim()) {
    Message.warning('问题描述必填')
    return false
  }
  try {
    await createAfterSale(vehicleId, { ...afterSaleForm })
    afterSaleVisible.value = false
    afterSaleForm.description = ''
    await loadAll()
  } catch (e) {
    // 提示已由拦截器弹出
  }
  return false
}

async function onCloseAfterSale(row) {
  try {
    await closeAfterSale(row.id)
    Message.success('售后单已结案')
    await loadAll()
  } catch (e) {
    // 提示已由拦截器弹出
  }
}

onMounted(loadAll)
</script>

<style scoped>
.back-row {
  margin-bottom: -6px;
}
.back-btn {
  padding-left: 0;
  color: var(--uc-text-3);
}
.back-btn:hover {
  color: var(--uc-primary);
}

/* 抬头 */
.hero :deep(.arco-card-body) {
  padding: 22px 24px;
}
.hero-body {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 22px;
  flex-wrap: wrap;
}
.hero-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.stock {
  padding: 3px 9px;
  border-radius: 7px;
  background: var(--uc-ink);
  color: #e6c288;
  font-size: 11.5px;
  letter-spacing: 0.04em;
}
.hero-title {
  margin: 12px 0 14px;
  font-size: 24px;
  font-weight: 650;
  letter-spacing: -0.02em;
  color: var(--uc-text);
  line-height: 1.3;
}
.meta-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
}
.meta-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--uc-text-2);
  font-variant-numeric: tabular-nums;
}
.meta-chip em {
  font-style: normal;
  font-size: 11px;
  color: var(--uc-text-3);
}
.hero-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-top: 4px;
}
.cleared-tag {
  font-size: 12px;
  color: var(--uc-text-3);
}

/* 定价 */
.card-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.rule-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 99px;
  background: var(--uc-accent-soft);
  color: #a26f22;
  font-size: 10.5px;
  font-weight: 500;
}
.price-chain {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 16px;
}
.chain-cell {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px 15px 13px;
  border-radius: var(--uc-r-md);
  border: 1px solid var(--uc-border-soft);
  background: var(--uc-surface-2);
}
.chain-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}
.chain-label {
  font-size: 11.5px;
  color: var(--uc-text-3);
}
.chain-rate {
  font-size: 10.5px;
  color: var(--uc-danger);
}
.chain-value {
  font-size: 19px;
  font-weight: 650;
  letter-spacing: -0.02em;
}
.chain-bar {
  display: block;
  height: 3px;
  border-radius: 99px;
  background: #e9ebf1;
  overflow: hidden;
}
.chain-bar i {
  display: block;
  height: 100%;
  border-radius: 99px;
  transition: width 0.5s ease;
}
.chain-hint {
  font-size: 10.5px;
  color: var(--uc-text-3);
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 16px;
}
.stat-tile {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 13px 15px;
  border-radius: var(--uc-r-md);
  border: 1px dashed var(--uc-border);
}
.stat-label {
  font-size: 11.5px;
  color: var(--uc-text-3);
}
.stat-value {
  font-size: 17px;
  font-weight: 600;
  color: var(--uc-text);
  line-height: 1.3;
}
.stat-value em {
  margin-left: 2px;
  font-style: normal;
  font-size: 11px;
  font-weight: 500;
  color: var(--uc-text-3);
}
.stat-value.loss {
  color: var(--uc-danger);
}
.stat-hint {
  font-size: 10.5px;
  color: var(--uc-text-3);
}

/* tabs */
.pane-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 2px 0 14px;
  flex-wrap: wrap;
}
.pane-hint {
  font-size: 11.5px;
  color: var(--uc-text-3);
  line-height: 1.6;
}
.flow-timeline {
  padding-top: 6px;
}
/* Arco 默认的连接线几乎和背景同色，时间线看起来像散落的圆点，这里加深一档 */
.flow-timeline :deep(.arco-timeline-item-dot-line) {
  border-left-color: #e2e6ef !important;
  border-left-width: 2px !important;
}
.log-card {
  padding-bottom: 6px;
}
.log-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.log-status {
  font-size: 13px;
  font-weight: 500;
  color: var(--uc-text);
}
.log-status.rollback {
  color: #a96b12;
}
.log-arrow {
  margin: 0 3px;
  font-size: 11px;
  color: var(--uc-text-3);
}
.log-time {
  font-size: 11px;
  color: var(--uc-text-3);
}
.log-remark {
  margin-top: 3px;
  font-size: 12px;
  color: var(--uc-text-2);
}
.log-by {
  margin-top: 2px;
  font-size: 11px;
  color: var(--uc-text-3);
}
.pair-arrow {
  margin: 0 6px;
  font-size: 11px;
  color: var(--uc-text-3);
}
.text-danger {
  color: var(--uc-danger);
}

@media (max-width: 1180px) {
  .price-chain,
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 720px) {
  .price-chain,
  .stat-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
