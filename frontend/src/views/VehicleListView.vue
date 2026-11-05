<template>
  <div>
    <a-card :bordered="false">
      <a-space wrap>
        <a-input v-model="query.keyword" placeholder="搜索品牌 / 车型 / 库存编号" allow-clear style="width: 260px" @press-enter="reload" />
        <a-select v-model="query.status" placeholder="全部状态" allow-clear style="width: 140px">
          <a-option v-for="(v, k) in VEHICLE_STATUS" :key="k" :value="k">{{ v.text }}</a-option>
        </a-select>
        <a-button type="primary" @click="reload">查询</a-button>
        <a-button @click="onReset">重置</a-button>
        <a-button type="outline" status="success" @click="openCreate">
          <template #icon><icon-plus /></template>
          收车入库
        </a-button>
      </a-space>

      <a-table
        row-key="id"
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :bordered="false"
        stripe
        style="margin-top: 16px"
        @page-change="onPageChange"
        @page-size-change="onPageSizeChange"
      >
        <template #brandModel="{ record }">{{ record.brand }} {{ record.model }}</template>
        <template #status="{ record }">
          <a-tag :color="statusOf(record.status).color">{{ statusOf(record.status).text }}</a-tag>
        </template>
        <template #purchasePrice="{ record }">{{ money(record.purchasePrice) }}</template>
        <template #listPrice="{ record }">{{ money(record.listPrice) }}</template>
        <template #holding="{ record }">{{ holdingDays(record.purchaseDate) }} 天</template>
        <template #actions="{ record }">
          <a-link @click="goDetail(record.id)">详情</a-link>
        </template>
      </a-table>
    </a-card>

    <!-- 收车入库 -->
    <a-modal v-model:visible="createVisible" title="收车入库" :width="620" @before-ok="onCreate">
      <a-form :model="form" layout="vertical">
        <a-grid :cols="2" :col-gap="16">
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
            <a-form-item label="收车价（元）" required>
              <a-input-number v-model="form.purchasePrice" :min="0" :precision="2" style="width: 100%" />
            </a-form-item>
          </a-grid-item>
          <a-grid-item>
            <a-form-item label="收车日期" required>
              <a-date-picker v-model="form.purchaseDate" value-format="YYYY-MM-DD" style="width: 100%" />
            </a-form-item>
          </a-grid-item>
          <a-grid-item>
            <a-form-item label="挂牌价（元）">
              <a-input-number v-model="form.listPrice" :min="0" :precision="2" style="width: 100%" />
            </a-form-item>
          </a-grid-item>
          <a-grid-item>
            <a-form-item label="首次上牌日期">
              <a-date-picker v-model="form.firstRegDate" value-format="YYYY-MM-DD" style="width: 100%" />
            </a-form-item>
          </a-grid-item>
          <a-grid-item>
            <a-form-item label="表显里程（km）">
              <a-input-number v-model="form.mileage" :min="0" style="width: 100%" />
            </a-form-item>
          </a-grid-item>
          <a-grid-item>
            <a-form-item label="车牌号">
              <a-input v-model="form.plateNo" placeholder="豫A12345" />
            </a-form-item>
          </a-grid-item>
        </a-grid>
        <a-form-item label="备注">
          <a-textarea v-model="form.remark" :rows="2" placeholder="车况、来源等" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { createVehicle, pageVehicles } from '../api'
import { VEHICLE_STATUS, money } from '../utils/dict'

const router = useRouter()

const loading = ref(false)
const rows = ref([])
const pagination = reactive({ current: 1, pageSize: 10, total: 0, showTotal: true, showPageSize: true })
const query = reactive({ keyword: '', status: undefined })

const columns = [
  { title: '库存编号', dataIndex: 'stockNo', width: 150 },
  { title: '品牌车型', slotName: 'brandModel', width: 240 },
  { title: '收车价', slotName: 'purchasePrice', width: 110 },
  { title: '挂牌价', slotName: 'listPrice', width: 110 },
  { title: '持有', slotName: 'holding', width: 90 },
  { title: '状态', slotName: 'status', width: 100 },
  { title: '操作', slotName: 'actions', width: 80 }
]

function statusOf(status) {
  return VEHICLE_STATUS[status] || { text: status, color: 'gray' }
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

function reload() {
  pagination.current = 1
  load()
}

function onReset() {
  query.keyword = ''
  query.status = undefined
  reload()
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

// ---------- 收车入库 ----------
const createVisible = ref(false)
const form = reactive({})

function openCreate() {
  Object.assign(form, {
    brand: '', model: '', purchasePrice: undefined, purchaseDate: undefined,
    listPrice: undefined, firstRegDate: undefined, mileage: undefined, plateNo: '', remark: ''
  })
  createVisible.value = true
}

async function onCreate() {
  if (!form.brand || !form.model || !form.purchasePrice || !form.purchaseDate) {
    Message.warning('品牌、车型、收车价、收车日期必填')
    return false
  }
  try {
    const id = await createVehicle(form)
    Message.success('收车入库成功')
    createVisible.value = false
    await load()
    router.push({ name: 'vehicle-detail', params: { id } })
  } catch (e) {
    // 提示已由拦截器弹出
  }
  // 返回 false 让 a-modal 不自己关闭，关闭时机由上面控制
  return false
}

onMounted(load)
</script>
