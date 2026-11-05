import { createRouter, createWebHistory } from 'vue-router'

/**
 * 路由。
 *
 * 全局前置守卫做两件事：
 *   1. 没登录就跳登录页，并带上 redirect，登录后回到原来想去的地方；
 *   2. 已登录还去登录页，直接送回列表页，避免重复登录。
 * 注意这只是前端体验层的拦截，真正的权限校验在后端 Sa-Token，前端拦截能被绕过。
 */
const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('../layouts/BasicLayout.vue'),
    children: [
      { path: '', redirect: '/vehicles' },
      { path: 'vehicles', name: 'vehicles', component: () => import('../views/VehicleListView.vue') },
      { path: 'vehicles/:id', name: 'vehicle-detail', component: () => import('../views/VehicleDetailView.vue') },
      { path: 'alerts', name: 'alerts', component: () => import('../views/AlertView.vue') }
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
    return { name: 'vehicles' }
  }
  return true
})

export default router
