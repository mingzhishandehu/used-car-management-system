<template>
  <a-layout class="layout">
    <a-layout-sider collapsible :width="200" :collapsed-width="60">
      <div class="logo">二手车</div>
      <a-menu :selected-keys="[activeKey]" @menu-item-click="onMenuClick">
        <a-menu-item key="/vehicles">
          <template #icon><icon-car /></template>
          车辆管理
        </a-menu-item>
        <a-menu-item key="/alerts">
          <template #icon><icon-notification /></template>
          库存预警
          <a-badge v-if="alertCount > 0" :count="alertCount" :offset="[8, 0]" />
        </a-menu-item>
      </a-menu>
    </a-layout-sider>

    <a-layout>
      <a-layout-header class="header">
        <span class="title">二手车业务管理与短视频营销系统</span>
        <a-space>
          <span class="user">{{ userName }}</span>
          <a-button size="small" @click="onLogout">退出</a-button>
        </a-space>
      </a-layout-header>

      <a-layout-content class="content">
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { countAlerts, getMe, logout } from '../api'

const route = useRoute()
const router = useRouter()
const alertCount = ref(0)
const userName = ref('')

// 用路径当选中项，刷新页面后菜单高亮不会丢；用 index 的话刷新会回到第一项
const activeKey = computed(() => (route.path.startsWith('/alerts') ? '/alerts' : '/vehicles'))

function onMenuClick(key) {
  router.push(key)
}

async function refreshAlertCount() {
  try {
    alertCount.value = await countAlerts()
  } catch (e) {
    // 顶栏的角标失败不该打断页面，静默处理
  }
}

async function onLogout() {
  try {
    await logout()
  } finally {
    localStorage.removeItem('satoken')
    localStorage.removeItem('userInfo')
    router.push({ name: 'login' })
  }
}

onMounted(async () => {
  // 刷新页面后从后端恢复用户信息，避免把用户信息存 localStorage 被篡改
  try {
    const me = await getMe()
    userName.value = me.realName || me.username
  } catch (e) {
    // 401 已由拦截器统一处理
  }
  refreshAlertCount()
  // 每 60 秒刷一次角标，够用又不至于频繁请求
  setInterval(refreshAlertCount, 60000)
})
</script>

<style scoped>
.layout {
  height: 100vh;
}
.logo {
  height: 48px;
  line-height: 48px;
  text-align: center;
  color: #fff;
  font-weight: 500;
  letter-spacing: 2px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}
.header {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: #fff;
  border-bottom: 1px solid #e5e6eb;
}
.title {
  font-size: 14px;
  font-weight: 500;
}
.user {
  font-size: 13px;
  color: #4e5969;
}
.content {
  padding: 16px;
  overflow: auto;
  background: #f7f8fa;
}
</style>
