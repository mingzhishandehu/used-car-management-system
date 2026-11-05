<template>
  <div>
    <a-card :bordered="false" title="库存预警">
      <template #extra>
        <a-space>
          <a-button size="small" @click="load">刷新</a-button>
          <a-button size="small" type="primary" :loading="inspecting" @click="onInspect">
            立即巡检
          </a-button>
        </a-space>
      </template>

      <a-empty v-if="!loading && rows.length === 0" description="没有待处理预警" />

      <a-table v-else row-key="id" :columns="columns" :data="rows" :loading="loading" :pagination="false" stripe>
        <template #level="{ record }">
          <a-tag :color="levelOf(record.alertLevel).color">{{ levelOf(record.alertLevel).text }}</a-tag>
        </template>
        <template #type="{ record }">{{ ALERT_TYPE[record.alertType] || record.alertType }}</template>
        <template #vehicle="{ record }">
          <a-link @click="goDetail(record.vehicleId)">{{ record.stockNo }}</a-link>
          <span class="model">{{ record.brand }} {{ record.model }}</span>
        </template>
        <template #actions="{ record }">
          <a-space>
            <a-link @click="onHandle(record.id, 'RESOLVED')">已处理</a-link>
            <a-link status="warning" @click="onHandle(record.id, 'IGNORED')">忽略</a-link>
          </a-space>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { handleAlert, inspectAlerts, listAlerts } from '../api'
import { ALERT_LEVEL, ALERT_TYPE } from '../utils/dict'

const router = useRouter()
const rows = ref([])
const loading = ref(false)
const inspecting = ref(false)

const columns = [
  { title: '级别', slotName: 'level', width: 90 },
  { title: '类型', slotName: 'type', width: 110 },
  { title: '车辆', slotName: 'vehicle', width: 340, ellipsis: true, tooltip: true },
  { title: '内容', dataIndex: 'content' },
  { title: '操作', slotName: 'actions', width: 130 }
]

function levelOf(level) {
  return ALERT_LEVEL[level] || { text: level, color: 'gray' }
}

function goDetail(id) {
  router.push({ name: 'vehicle-detail', params: { id } })
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
  await handleAlert(id, status)
  Message.success('已处理')
  await load()
}

onMounted(load)
</script>

<style scoped>
.model {
  margin-left: 8px;
  font-size: 12px;
  color: #86909c;
}
</style>
