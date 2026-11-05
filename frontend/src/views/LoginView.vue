<template>
  <div class="login-page">
    <a-card class="login-card" :bordered="false">
      <h1 class="title">二手车业务管理</h1>
      <p class="subtitle">车辆全流程 · 定价与滞销 · AI 短视频</p>

      <a-form :model="form" layout="vertical" @submit="onSubmit">
        <a-form-item field="username" label="账号">
          <a-input v-model="form.username" placeholder="请输入账号" allow-clear>
            <template #prefix><icon-user /></template>
          </a-input>
        </a-form-item>

        <a-form-item field="password" label="密码">
          <a-input-password v-model="form.password" placeholder="请输入密码" allow-clear>
            <template #prefix><icon-lock /></template>
          </a-input-password>
        </a-form-item>

        <a-button type="primary" long :loading="loading" html-type="submit">登录</a-button>
      </a-form>

      <p class="hint">初始账号 admin / admin123，首次登录后请修改</p>
    </a-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { login } from '../api'

const router = useRouter()
const route = useRoute()
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

async function onSubmit() {
  if (!form.username || !form.password) {
    Message.warning('账号和密码都要填')
    return
  }
  loading.value = true
  try {
    const info = await login({ username: form.username, password: form.password })
    // tokenName 是后端返回的请求头名（默认 satoken），存值不存名字，拦截器里写死同名
    localStorage.setItem('satoken', info.tokenValue)
    Message.success('登录成功')
    // 带上 redirect，登录后回到原本想访问的页面
    const redirect = route.query.redirect
    router.push(typeof redirect === 'string' ? redirect : { name: 'vehicles' })
  } catch (e) {
    // 错误提示已由 axios 拦截器统一弹出，这里不重复提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f2f3f5;
}
.login-card {
  width: 360px;
  padding: 8px 12px 0;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
  border-radius: 8px;
}
.title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 500;
}
.subtitle {
  margin: 0 0 22px;
  font-size: 12px;
  color: #86909c;
}
.hint {
  margin-top: 14px;
  font-size: 12px;
  color: #86909c;
  text-align: center;
}
</style>
