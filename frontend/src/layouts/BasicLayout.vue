<template>
  <div class="shell" :class="{ 'is-collapsed': collapsed }">
    <!-- 侧栏：深色墨底，靠一条铜金细线和大块留白做质感，不用渐变堆花 -->
    <aside class="sider">
      <div class="brand">
        <div class="brand-mark">UC</div>
        <div v-show="!collapsed" class="brand-text">
          <strong>优驰云</strong>
          <span>USED CAR RETAIL OS</span>
        </div>
      </div>

      <nav class="nav">
        <template v-for="group in groups" :key="group.label">
          <div v-if="!collapsed" class="nav-label">{{ group.label }}</div>
          <div v-else class="nav-divider" />

          <a-tooltip
            v-for="item in group.items"
            :key="item.path"
            :content="item.label"
            position="right"
            :disabled="!collapsed"
          >
            <button
              class="nav-item"
              :class="{ active: activeKey === item.path }"
              type="button"
              @click="go(item.path)"
            >
              <NavIcon :name="item.icon" :size="19" />
              <span v-show="!collapsed" class="nav-text">{{ item.label }}</span>
              <span v-if="badgeOf(item) > 0" class="nav-badge" :class="{ dot: collapsed }">
                {{ collapsed ? '' : badgeOf(item) > 99 ? '99+' : badgeOf(item) }}
              </span>
            </button>
          </a-tooltip>
        </template>
      </nav>

      <div class="sider-foot">
        <div v-show="!collapsed" class="tenant">
          <span class="live-dot" />
          <div class="tenant-text">
            <b>杭州旗舰店</b>
            <em>企业版 v3.4.2</em>
          </div>
        </div>
        <button class="collapse-btn" type="button" @click="toggleCollapse">
          <icon-menu-unfold v-if="collapsed" />
          <icon-menu-fold v-else />
        </button>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="crumb">
          <span class="crumb-root">优驰云</span>
          <span class="crumb-sep">/</span>
          <span v-if="parentTitle" class="crumb-parent">{{ parentTitle }}</span>
          <span v-if="parentTitle" class="crumb-sep">/</span>
          <span class="crumb-current">{{ pageTitle }}</span>
        </div>

        <div class="topbar-right">
          <a-tooltip content="库存预警">
            <button class="icon-btn" type="button" @click="go('/alerts')">
              <NavIcon name="bell" :size="19" />
              <span v-if="alertCount > 0" class="dot-badge">{{ alertCount > 99 ? '99+' : alertCount }}</span>
            </button>
          </a-tooltip>

          <a-divider direction="vertical" :margin="6" />

          <a-dropdown trigger="click" position="br">
            <button class="user-chip" type="button">
              <span class="uc-avatar sm">{{ initial }}</span>
              <span class="user-meta">
                <b>{{ userName || '未登录' }}</b>
                <em>{{ roleText }}</em>
              </span>
              <icon-down class="caret" />
            </button>
            <template #content>
              <a-doption @click="onLogout">
                <template #icon><NavIcon name="logout" :size="16" /></template>
                退出登录
              </a-doption>
            </template>
          </a-dropdown>
        </div>
      </header>

      <main class="page">
        <router-view v-slot="{ Component }">
          <transition name="uc" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NavIcon from '../components/NavIcon.vue'
import { countAlerts, getMe, listApprovals, logout } from '../api'

/**
 * 应用外壳。
 *
 * 侧栏没有用 a-layout-sider + a-menu：默认主题是浅底，和深色品牌区拼在一起
 * 会出现「logo 白字写在白底上」看不见的问题；自绘导航还能做分组标题和折叠态气泡。
 * 折叠状态存 localStorage，刷新后不跳回去。
 */
const route = useRoute()
const router = useRouter()

const collapsed = ref(localStorage.getItem('uc-sider-collapsed') === '1')
const alertCount = ref(0)
const approvalCount = ref(0)
const userName = ref('')
const userRole = ref('')

const isAdmin = computed(() => userRole.value === 'ADMIN')
const roleText = computed(() => ({ ADMIN: '管理员', SALES: '销售', FINANCE: '财务' }[userRole.value] || '员工'))
const initial = computed(() => (userName.value || 'U').slice(0, 1))

const groups = computed(() => [
  {
    label: '总览',
    items: [{ path: '/dashboard', label: '工作台', icon: 'dashboard' }]
  },
  {
    label: '业务',
    items: [
      { path: '/vehicles', label: '车辆台账', icon: 'car' },
      { path: '/ai-intake', label: 'AI 收车', icon: 'robot' },
      { path: '/approvals', label: '审批中心', icon: 'approval', badge: 'approval' },
      { path: '/alerts', label: '库存预警', icon: 'bell', badge: 'alert' }
    ]
  },
  {
    label: '组织',
    items: [{ path: '/employees', label: '员工管理', icon: 'users', adminOnly: true }]
  }
].map((g) => ({ ...g, items: g.items.filter((i) => !i.adminOnly || isAdmin.value) })))

const activeKey = computed(() => {
  const p = route.path
  if (p.startsWith('/vehicles')) return '/vehicles'
  for (const key of ['/alerts', '/employees', '/approvals', '/ai-intake', '/dashboard']) {
    if (p.startsWith(key)) return key
  }
  return '/dashboard'
})

const pageTitle = computed(() => route.meta.title || '工作台')
const parentTitle = computed(() => route.meta.parent || '')

function badgeOf(item) {
  if (item.badge === 'alert') return alertCount.value
  if (item.badge === 'approval' && isAdmin.value) return approvalCount.value
  return 0
}

function go(path) {
  if (route.path !== path) router.push(path)
}

function toggleCollapse() {
  collapsed.value = !collapsed.value
  localStorage.setItem('uc-sider-collapsed', collapsed.value ? '1' : '0')
}

async function refreshBadges() {
  try {
    alertCount.value = await countAlerts()
  } catch (e) {
    // 角标失败不该打断页面
  }
  if (!isAdmin.value) return
  try {
    const rows = await listApprovals('pending')
    approvalCount.value = Array.isArray(rows) ? rows.length : 0
  } catch (e) {
    // 非管理员调用会被后端拒绝，这里静默
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

let timer = null

onMounted(async () => {
  try {
    const me = await getMe()
    userName.value = me.realName || me.username
    userRole.value = me.role || ''
  } catch (e) {
    // 401 已由拦截器统一处理
  }
  await refreshBadges()
  timer = setInterval(refreshBadges, 60000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--uc-bg);
}

/* ---------------- 侧栏 ---------------- */
.sider {
  position: relative;
  z-index: 2;
  width: 236px;
  flex: none;
  display: flex;
  flex-direction: column;
  background: radial-gradient(120% 60% at 0% 0%, #17233c 0%, transparent 60%), linear-gradient(180deg, #0d1524 0%, #090f1b 100%);
  transition: width 0.22s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
}
.is-collapsed .sider {
  width: 78px;
}
.sider::after {
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  width: 1px;
  height: 100%;
  background: linear-gradient(180deg, rgba(223, 171, 92, 0.5), rgba(255, 255, 255, 0.06) 30%, rgba(255, 255, 255, 0.02));
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 68px;
  padding: 0 18px;
  flex: none;
}
.is-collapsed .brand {
  padding: 0;
  justify-content: center;
}
.brand-mark {
  width: 38px;
  height: 38px;
  flex: none;
  border-radius: 11px;
  background: linear-gradient(150deg, #e6b871 0%, #c08c3e 55%, #9a6c2a 100%);
  color: #1a1206;
  font-size: 13px;
  font-weight: 800;
  letter-spacing: 0.02em;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 8px 18px -10px rgba(223, 171, 92, 0.9);
}
.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
  min-width: 0;
}
.brand-text strong {
  color: #f4f6fa;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.04em;
}
.brand-text span {
  margin-top: 3px;
  font-size: 9px;
  color: rgba(223, 171, 92, 0.7);
  letter-spacing: 0.16em;
}

.nav {
  flex: 1;
  padding: 6px 12px;
  overflow-y: auto;
  overflow-x: hidden;
}
.is-collapsed .nav {
  padding: 6px 10px;
}
.nav-label {
  padding: 16px 10px 8px;
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.14em;
  color: rgba(255, 255, 255, 0.3);
}
.nav-divider {
  height: 1px;
  margin: 10px 8px;
  background: rgba(255, 255, 255, 0.06);
}
.nav-iitem {
  display: block;
}

.nav-item {
  position: relative;
  width: 100%;
  height: 42px;
  margin-bottom: 2px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  gap: 11px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: rgba(226, 232, 244, 0.62);
  font-size: 13.5px;
  font-family: inherit;
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease;
  white-space: nowrap;
}
.is-collapsed .nav-item {
  justify-content: center;
  padding: 0;
}
.nav-item:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #eef2f9;
}
.nav-item.active {
  background: rgba(223, 171, 92, 0.12);
  color: #f7f9fc;
  font-weight: 500;
}
.nav-item.active::before {
  content: '';
  position: absolute;
  left: -12px;
  top: 11px;
  width: 3px;
  height: 20px;
  border-radius: 0 3px 3px 0;
  background: linear-gradient(180deg, #e9c084, #c08c3e);
}
.is-collapsed .nav-item.active::before {
  left: -10px;
}
.nav-item.active :deep(.nav-icon) {
  color: #dfab5c;
}
.nav-text {
  flex: 1;
  text-align: left;
}
.nav-badge {
  min-width: 20px;
  height: 19px;
  padding: 0 6px;
  border-radius: 99px;
  background: #c8493b;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  line-height: 19px;
  text-align: center;
}
.nav-badge.dot {
  position: absolute;
  top: 9px;
  right: 12px;
  min-width: 7px;
  width: 7px;
  height: 7px;
  padding: 0;
  box-shadow: 0 0 0 2px #0d1524;
}

.sider-foot {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 16px 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}
.is-collapsed .sider-foot {
  justify-content: center;
  padding: 12px 0 16px;
}
.tenant {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}
.tenant-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
  min-width: 0;
}
.tenant-text b {
  font-size: 11.5px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.72);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tenant-text em {
  margin-top: 2px;
  font-style: normal;
  font-size: 9.5px;
  color: rgba(255, 255, 255, 0.36);
  letter-spacing: 0.02em;
}
.live-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #48b784;
  box-shadow: 0 0 0 3px rgba(72, 183, 132, 0.16);
}
.collapse-btn {
  width: 30px;
  height: 30px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.05);
  color: rgba(255, 255, 255, 0.5);
  cursor: pointer;
  font-size: 15px;
  transition: all 0.16s ease;
}
.collapse-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

/* ---------------- 主区 ---------------- */
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.topbar {
  height: 64px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 max(26px, calc((100% - 1560px) / 2));
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: saturate(180%) blur(10px);
  border-bottom: 1px solid var(--uc-border);
}
.crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--uc-text-3);
}
.crumb-current {
  color: var(--uc-text);
  font-weight: 600;
  font-size: 13px;
}
.crumb-sep {
  color: #cfd4de;
}
.crumb-root {
  letter-spacing: 0.02em;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.icon-btn {
  position: relative;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  color: var(--uc-text-2);
  cursor: pointer;
  transition: all 0.16s ease;
}
.icon-btn:hover {
  background: #f4f6fa;
  border-color: var(--uc-border);
  color: var(--uc-text);
}
.dot-badge {
  position: absolute;
  top: 3px;
  right: 2px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 99px;
  background: #c8493b;
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
  text-align: center;
  box-shadow: 0 0 0 2px #fff;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 4px 8px 4px 4px;
  border: 1px solid transparent;
  border-radius: 11px;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.16s ease;
}
.user-chip:hover {
  background: #f5f7fb;
  border-color: var(--uc-border);
}
.user-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.25;
}
.user-meta b {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--uc-text);
}
.user-meta em {
  font-style: normal;
  font-size: 10.5px;
  color: var(--uc-text-3);
}
.caret {
  color: var(--uc-text-3);
  font-size: 12px;
}

.page {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  /* 宽屏下把内容收在 1560px 内并居中：1920 上如果铺满，卡片会被拉得很空 */
  padding: 22px max(26px, calc((100% - 1560px) / 2)) 44px;
  scroll-behavior: smooth;
}

@media (max-width: 900px) {
  .sider {
    width: 78px;
  }
  .brand-text,
  .nav-text,
  .nav-label,
  .tenant {
    display: none !important;
  }
  .brand {
    justify-content: center;
    padding: 0;
  }
  .nav-item {
    justify-content: center;
    padding: 0;
  }
  .page {
    padding: 16px 14px 36px;
  }
  .topbar {
    padding: 0 14px;
  }
  .user-meta {
    display: none;
  }
}
</style>
