<template>
  <div class="uc-page dash">
    <!-- 页头 -->
    <header class="dash-head">
      <div>
        <div class="hello">{{ todayText }}</div>
        <h1 class="uc-h1">{{ greeting }}，{{ userName || '你好' }}</h1>
        <p class="uc-sub">在库 {{ stockCount }} 台 · 在售 {{ statusTotals.ON_SALE }} 台 · 待处理预警 {{ alertCount }} 条</p>
      </div>
      <div class="uc-head-actions">
        <a-button @click="go('/ai-intake')">
          <template #icon><NavIcon name="robot" :size="17" /></template>
          AI 收车
        </a-button>
        <a-button type="primary" @click="go('/vehicles')">
          <template #icon><NavIcon name="plus" :size="17" /></template>
          收车入库
        </a-button>
      </div>
    </header>

    <!-- 关键指标 -->
    <section class="kpi-grid">
      <article v-for="k in kpis" :key="k.key" class="kpi" :class="`tone-${k.tone}`">
        <div class="kpi-icon"><NavIcon :name="k.icon" :size="19" /></div>
        <div class="kpi-body">
          <span class="kpi-label">{{ k.label }}</span>
          <b class="kpi-value num">{{ k.value }}<em>{{ k.unit }}</em></b>
          <span class="kpi-foot">{{ k.foot }}</span>
        </div>
      </article>
    </section>

    <section class="grid-2">
      <!-- 库存结构 -->
      <a-card :bordered="false" class="panel">
        <div class="panel-head">
          <div>
            <h3>库存结构</h3>
            <p>按车辆状态拆分，实时反映门店的库存结构</p>
          </div>
          <span class="panel-tag">实时</span>
        </div>

        <div class="donut-wrap">
          <div class="donut">
            <svg viewBox="0 0 168 168" role="img" aria-label="库存状态分布">
              <circle cx="84" cy="84" r="64" fill="none" stroke="#f0f2f7" stroke-width="17" />
              <circle
                v-for="seg in segments"
                :key="seg.key"
                cx="84"
                cy="84"
                r="64"
                fill="none"
                :stroke="seg.color"
                stroke-width="17"
                :stroke-dasharray="`${seg.len} ${circumference - seg.len}`"
                :stroke-dashoffset="-seg.offset"
                transform="rotate(-90 84 84)"
                class="seg"
              />
            </svg>
            <div class="donut-center">
              <b class="num">{{ totalVehicles }}</b>
              <span>全部车辆</span>
            </div>
          </div>

          <ul class="legend">
            <li v-for="s in legend" :key="s.key">
              <span class="dot" :style="{ background: s.color }" />
              <span class="legend-name">{{ s.text }}</span>
              <b class="num">{{ s.count }}</b>
              <em class="num">{{ s.percent }}%</em>
            </li>
          </ul>
        </div>
      </a-card>

      <!-- 快捷入口 -->
      <a-card :bordered="false" class="panel">
        <div class="panel-head">
          <div>
            <h3>快捷入口</h3>
            <p>日常使用频率最高的四个动作</p>
          </div>
        </div>
        <div class="quick-grid">
          <button v-for="q in quicks" :key="q.path" type="button" class="quick" @click="go(q.path)">
            <span class="quick-icon"><NavIcon :name="q.icon" :size="20" /></span>
            <span class="quick-title">{{ q.title }}</span>
            <span class="quick-desc">{{ q.desc }}</span>
            <NavIcon name="arrow" :size="15" class="quick-arrow" />
          </button>
        </div>
      </a-card>
    </section>

    <section class="grid-2 list-row">
      <!-- 最近入库 -->
      <a-card :bordered="false" class="panel">
        <div class="panel-head">
          <div>
            <h3>最近入库</h3>
            <p>按收车时间倒序，点任意一行进入车辆详情</p>
          </div>
          <a-link @click="go('/vehicles')">全部车辆 <NavIcon name="arrow" :size="13" /></a-link>
        </div>

        <div v-if="!recent.length" class="uc-empty">
          <div class="mark"><NavIcon name="car" :size="22" /></div>
          还没有入库记录，先从「收车入库」开始
        </div>
        <ul v-else class="recent">
          <li v-for="v in recent" :key="v.id" @click="openVehicle(v.id)">
            <span class="car-avatar">{{ (v.brand || '?').slice(0, 1) }}</span>
            <div class="recent-main">
              <b>{{ v.brand }} {{ v.model }}</b>
              <span class="num">{{ v.stockNo }}<i v-if="v.plateNo"> · {{ v.plateNo }}</i></span>
            </div>
            <span class="money recent-price"><span class="cny">¥</span>{{ money(v.listPrice || v.purchasePrice) }}</span>
            <span class="pill" :class="statusOf(v.status).cls">{{ statusOf(v.status).text }}</span>
          </li>
        </ul>
      </a-card>

      <!-- 预警 -->
      <a-card :bordered="false" class="panel">
        <div class="panel-head">
          <div>
            <h3>待处理预警</h3>
            <p>滞销、证件到期、毛利过低都会在这里排队</p>
          </div>
          <a-link @click="go('/alerts')">去处理 <NavIcon name="arrow" :size="13" /></a-link>
        </div>

        <div v-if="!alerts.length" class="uc-empty">
          <div class="mark"><NavIcon name="bell" :size="22" /></div>
          没有待处理预警，库存状态良好
        </div>
        <ul v-else class="alert-list">
          <li v-for="a in alerts.slice(0, 5)" :key="a.id" :class="`lv-${(a.alertLevel || 'INFO').toLowerCase()}`">
            <span class="alert-bar" />
            <div class="alert-main">
              <div class="alert-top">
                <span class="alert-type">{{ ALERT_TYPE[a.alertType] || a.alertType }}</span>
                <span class="alert-vehicle num">{{ a.stockNo }}</span>
              </div>
              <p>{{ a.content }}</p>
            </div>
            <a-link @click="openVehicle(a.vehicleId)">查看</a-link>
          </li>
        </ul>
      </a-card>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import NavIcon from '../components/NavIcon.vue'
import { countAlerts, getMe, listAlerts, listApprovals, pageVehicles } from '../api'
import { ALERT_TYPE, VEHICLE_STATUS, money } from '../utils/dict'

/**
 * 工作台。
 *
 * 数据全部来自既有接口，没有为这个页面新增后端：
 *   - 各状态数量：并发查 5 次 pageVehicles(size=1)，只取 total；
 *   - 最近入库：pageVehicles(page=1,size=6)；
 *   - 预警：countAlerts + listAlerts；
 *   - 待审批：只有管理员调用 listApprovals('pending')。
 * 任何一块失败都不影响其它块，用 allSettled 而不是 Promise.all。
 */
const router = useRouter()

const userName = ref('')
const isAdmin = ref(false)
const alertCount = ref(0)
const alerts = ref([])
const recent = ref([])
const approvalCount = ref(0)
const myPending = ref(0)

const statusTotals = reactive({ PURCHASED: 0, ON_SALE: 0, SOLD: 0, AFTER_SALE: 0, CLEARED: 0 })
const STATUS_META = [
  { key: 'PURCHASED', text: '已收车', color: '#3457d5' },
  { key: 'ON_SALE', text: '在售', color: '#1f8a5b' },
  { key: 'SOLD', text: '已成交', color: '#6b4ec9' },
  { key: 'AFTER_SALE', text: '售后', color: '#b7791f' },
  { key: 'CLEARED', text: '已清库', color: '#9aa3b5' }
]

const totalVehicles = computed(() => STATUS_META.reduce((sum, s) => sum + (statusTotals[s.key] || 0), 0))
const stockCount = computed(() => totalVehicles.value - (statusTotals.CLEARED || 0))

const circumference = 2 * Math.PI * 64

const segments = computed(() => {
  const total = totalVehicles.value
  if (!total) return []
  let offset = 0
  return STATUS_META.map((s) => {
    const len = ((statusTotals[s.key] || 0) / total) * circumference
    const seg = { key: s.key, color: s.color, len: Math.max(0, len - 1.5), offset }
    offset += len
    return seg
  }).filter((s) => s.len > 0)
})

const legend = computed(() =>
  STATUS_META.map((s) => ({
    ...s,
    count: statusTotals[s.key] || 0,
    percent: totalVehicles.value ? Math.round(((statusTotals[s.key] || 0) / totalVehicles.value) * 100) : 0
  }))
)

const kpis = computed(() => [
  {
    key: 'stock',
    label: '在库车辆',
    value: stockCount.value,
    unit: ' 台',
    foot: `已清库 ${statusTotals.CLEARED} 台不计入`,
    icon: 'box',
    tone: 'blue'
  },
  {
    key: 'sale',
    label: '在售中',
    value: statusTotals.ON_SALE,
    unit: ' 台',
    foot: statusTotals.PURCHASED ? `另有 ${statusTotals.PURCHASED} 台待上架` : '全部已收车车辆都已上架',
    icon: 'car',
    tone: 'green'
  },
  {
    key: 'alert',
    label: '待处理预警',
    value: alertCount.value,
    unit: ' 条',
    foot: alertCount.value ? '滞销与证件风险优先处理' : '当前没有需要处理的预警',
    icon: 'bell',
    tone: 'amber'
  },
  {
    key: 'approval',
    label: isAdmin.value ? '待我审批' : '我的待审申请',
    value: isAdmin.value ? approvalCount.value : myPending.value,
    unit: ' 单',
    foot: isAdmin.value ? '员工提交的车辆操作申请' : '管理员审批后才会生效',
    icon: 'approval',
    tone: 'violet'
  }
])

const quicks = [
  { path: '/vehicles', icon: 'plus', title: '收车入库', desc: '手动登记一台新车' },
  { path: '/ai-intake', icon: 'robot', title: 'AI 收车', desc: '一句话完成信息抽取' },
  { path: '/approvals', icon: 'approval', title: '审批中心', desc: '处理员工提交的申请' },
  { path: '/alerts', icon: 'fire', title: '库存预警', desc: '巡检滞销与证件风险' }
]

const todayText = computed(() => {
  const d = new Date()
  const week = ['日', '一', '二', '三', '四', '五', '六']
  return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日 · 星期${week[d.getDay()]}`
})

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 11) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

function statusOf(status) {
  const found = VEHICLE_STATUS[status]
  const cls = { arcoblue: 'pill-blue', green: 'pill-green', purple: 'pill-violet', orange: 'pill-amber', gray: 'pill-gray' }
  return { text: found?.text || status, cls: cls[found?.color] || 'pill-gray' }
}

function go(path) {
  router.push(path)
}

function openVehicle(id) {
  if (id) router.push({ name: 'vehicle-detail', params: { id } })
}

async function load() {
  // 角色必须先拿到：审批接口的 scope 取决于 ADMIN 还是员工，
  // 和统计请求混在一个 Promise.all 里会拿到旧值，管理员会按「我的申请」算待办数。
  try {
    const me = await getMe()
    userName.value = me.realName || me.username || ''
    isAdmin.value = me.role === 'ADMIN'
  } catch (e) {
    // 401 已由拦截器处理
  }

  const statusKeys = STATUS_META.map((s) => s.key)
  const results = await Promise.allSettled([
    ...statusKeys.map((status) => pageVehicles({ page: 1, size: 1, status })),
    pageVehicles({ page: 1, size: 6 }),
    countAlerts(),
    listAlerts(),
    listApprovals(isAdmin.value ? 'pending' : 'mine')
  ])

  statusKeys.forEach((key, i) => {
    const r = results[i]
    statusTotals[key] = r.status === 'fulfilled' ? r.value?.total || 0 : 0
  })

  const recentRes = results[statusKeys.length]
  recent.value = recentRes.status === 'fulfilled' ? recentRes.value?.records || [] : []

  const countRes = results[statusKeys.length + 1]
  alertCount.value = countRes.status === 'fulfilled' ? countRes.value || 0 : 0

  const listRes = results[statusKeys.length + 2]
  alerts.value = listRes.status === 'fulfilled' ? listRes.value || [] : []

  const apprRes = results[statusKeys.length + 3]
  const apprRows = apprRes.status === 'fulfilled' && Array.isArray(apprRes.value) ? apprRes.value : []
  if (isAdmin.value) {
    approvalCount.value = apprRows.length
  } else {
    myPending.value = apprRows.filter((r) => r.status === 'PENDING').length
  }
}

onMounted(load)
</script>

<style scoped>
.dash-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
  padding: 4px 2px 2px;
}
.hello {
  font-size: 12px;
  font-weight: 500;
  color: var(--uc-primary);
  letter-spacing: 0.02em;
  margin-bottom: 8px;
}
.dash-head .uc-h1 {
  font-size: 25px;
}

/* KPI */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}
.kpi {
  position: relative;
  display: flex;
  gap: 14px;
  padding: 18px;
  border-radius: var(--uc-r-lg);
  background: #fff;
  border: 1px solid var(--uc-border);
  box-shadow: var(--uc-shadow-1);
  overflow: hidden;
}
.kpi::after {
  content: '';
  position: absolute;
  right: -30px;
  top: -40px;
  width: 110px;
  height: 110px;
  border-radius: 50%;
  opacity: 0.5;
  background: radial-gradient(circle, var(--tone-soft) 0%, transparent 70%);
}
.kpi-icon {
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--tone);
  background: var(--tone-soft);
}
.kpi-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.kpi-label {
  font-size: 12px;
  color: var(--uc-text-3);
}
.kpi-value {
  margin-top: 5px;
  font-size: 27px;
  font-weight: 650;
  line-height: 1.1;
  letter-spacing: -0.02em;
  color: var(--uc-text);
}
.kpi-value em {
  font-style: normal;
  font-size: 12px;
  font-weight: 500;
  color: var(--uc-text-3);
  margin-left: 2px;
}
.kpi-foot {
  margin-top: 6px;
  font-size: 11px;
  color: var(--uc-text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tone-blue {
  --tone: #3457d5;
  --tone-soft: #eef2fe;
}
.tone-green {
  --tone: #1f8a5b;
  --tone-soft: #e9f7f0;
}
.tone-amber {
  --tone: #b7791f;
  --tone-soft: #fdf5e6;
}
.tone-violet {
  --tone: #6b4ec9;
  --tone-soft: #f1edfd;
}

/* 通用面板 */
.grid-2 {
  display: grid;
  grid-template-columns: minmax(0, 1.18fr) minmax(0, 0.82fr);
  gap: 14px;
}
.panel :deep(.arco-card-body) {
  padding: 20px 22px 22px;
}
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
}
.panel-head h3 {
  margin: 0;
  font-size: 14.5px;
  font-weight: 600;
  color: var(--uc-text);
  letter-spacing: -0.01em;
}
.panel-head p {
  margin: 4px 0 0;
  font-size: 11.5px;
  color: var(--uc-text-3);
}
.panel-tag {
  padding: 3px 9px;
  border-radius: 99px;
  background: var(--uc-success-soft);
  color: var(--uc-success);
  font-size: 10.5px;
  font-weight: 500;
}

/* 环形图 */
.donut-wrap {
  display: flex;
  align-items: center;
  gap: 30px;
}
.donut {
  position: relative;
  width: 168px;
  height: 168px;
  flex: none;
}
.seg {
  transition: stroke-dasharray 0.6s ease;
}
.donut-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
}
.donut-center b {
  font-size: 30px;
  font-weight: 650;
  letter-spacing: -0.03em;
  line-height: 1;
}
.donut-center span {
  font-size: 11px;
  color: var(--uc-text-3);
}
.legend {
  flex: 1;
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.legend li {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 12.5px;
}
.legend .dot {
  width: 8px;
  height: 8px;
  border-radius: 3px;
  flex: none;
}
.legend-name {
  flex: 1;
  color: var(--uc-text-2);
}
.legend b {
  font-weight: 600;
  color: var(--uc-text);
}
.legend em {
  width: 38px;
  text-align: right;
  font-style: normal;
  font-size: 11.5px;
  color: var(--uc-text-3);
}

/* 快捷入口 */
.quick-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.quick {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  padding: 14px 14px 13px;
  border: 1px solid var(--uc-border);
  border-radius: var(--uc-r-md);
  background: #fff;
  text-align: left;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.18s ease;
}
.quick:hover {
  border-color: #c9d3f2;
  background: #fbfcff;
  transform: translateY(-2px);
  box-shadow: var(--uc-shadow-2);
}
.quick-icon {
  width: 34px;
  height: 34px;
  margin-bottom: 6px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--uc-primary-soft);
  color: var(--uc-primary);
}
.quick-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--uc-text);
}
.quick-desc {
  font-size: 11px;
  color: var(--uc-text-3);
}
.quick-arrow {
  position: absolute;
  right: 12px;
  top: 15px;
  color: #cbd1de;
  transition: all 0.18s ease;
}
.quick:hover .quick-arrow {
  color: var(--uc-primary);
  transform: translateX(2px);
}

/* 最近入库 */
.recent {
  margin: 0;
  padding: 0;
  list-style: none;
}
.recent li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 8px;
  margin: 0 -8px;
  border-radius: 10px;
  cursor: pointer;
  transition: background-color 0.16s ease;
}
.recent li + li {
  border-top: 1px solid var(--uc-border-soft);
}
.recent li:hover {
  background: #f8f9fc;
}
.car-avatar {
  width: 34px;
  height: 34px;
  flex: none;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(150deg, #eef2fe, #dfe6fd);
  color: var(--uc-primary-deep);
  font-size: 13px;
  font-weight: 600;
}
.recent-main {
  flex: 1;
  min-width: 0;
}
.recent-main b {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--uc-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.recent-main span {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--uc-text-3);
}
.recent-main i {
  font-style: normal;
}
.recent-price {
  font-size: 13px;
  color: var(--uc-text-2);
}

/* 预警 */
.alert-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.alert-list li {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px 10px 14px;
  border-radius: var(--uc-r-md);
  background: var(--uc-surface-2);
  border: 1px solid var(--uc-border-soft);
  overflow: hidden;
}
.alert-bar {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: #9aa3b5;
}
.lv-warn .alert-bar {
  background: #d99a2b;
}
.lv-danger .alert-bar {
  background: #c8493b;
}
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
  font-size: 11px;
  font-weight: 500;
  color: var(--uc-text-2);
}
.alert-vehicle {
  font-size: 10.5px;
  color: var(--uc-text-3);
}
.alert-main p {
  margin: 3px 0 0;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--uc-text-3);
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

@media (max-width: 1280px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .grid-2 {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 720px) {
  .kpi-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .donut-wrap {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
