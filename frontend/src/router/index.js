import { createRouter, createWebHistory } from 'vue-router'

/**
 * 路由。
 *
 * 全局前置守卫做两件事：
 *   1. 没登录就跳登录页，并带上 redirect，登录后回到原来想去的地方；
 *   2. 已登录还去登录页，直接送回工作台，避免重复登录。
 * 注意这只是前端体验层的拦截，真正的权限校验在后端 Sa-Token，前端拦截能被绕过。
 *
 * meta.title 只给顶栏面包屑用，不参与权限判断。
 */
const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('../layouts/BasicLayout.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/DashboardView.vue'), meta: { title: '工作台' } },
      { path: 'vehicles', name: 'vehicles', component: () => import('../views/VehicleListView.vue'), meta: { title: '车辆台账' } },
      {
        path: 'vehicles/:id',
        name: 'vehicle-detail',
        component: () => import('../views/VehicleDetailView.vue'),
        meta: { title: '车辆详情', parent: '车辆台账' }
      },
      { path: 'ai-intake', name: 'ai-intake', component: () => import('../views/AiIntakeView.vue'), meta: { title: 'AI 收车' } },
      { path: 'employees', name: 'employees', component: () => import('../views/EmployeeView.vue'), meta: { title: '员工管理' } },
      { path: 'approvals', name: 'approvals', component: () => import('../views/ApprovalView.vue'), meta: { title: '审批中心' } },
      { path: 'alerts', name: 'alerts', component: () => import('../views/AlertView.vue'), meta: { title: '库存预警' } }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const token = localStorage.getItem('satoken')
  if (!to.meta.public && !token) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && token) {
    return { name: 'dashboard' }
  }
  return true
})

// 浏览器标签页标题跟随当前页面，开多个标签时能分清哪个是哪个
router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 优驰云` : '优驰云 · 二手车经营管理系统'
})

export default router
