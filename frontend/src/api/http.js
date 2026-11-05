import axios from 'axios'
import { Message } from '@arco-design/web-vue'
import router from '../router'

/**
 * axios 实例。
 *
 * 关键点一：token 放在请求头 satoken 里，名字必须和后端 sa-token.token-name 一致，
 *          写错成 Authorization 的话，后端读不到，所有接口都是 401。
 * 关键点二：后端统一用 HTTP 200 + body.code 表达成败，所以真正的判断在响应拦截器里，
 *          不能只看 axios 的 status。
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// 请求拦截：自动带上 token
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('satoken')
  if (token) {
    config.headers['satoken'] = token
  }
  return config
})

// 响应拦截：统一处理 code 与 401
http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && body.code === 0) {
      return body.data
    }
    // 401 单独处理：清登录态并跳登录页，否则用户会看到满屏报错却不知道要重新登录
    if (body && body.code === 401) {
      localStorage.removeItem('satoken')
      localStorage.removeItem('userInfo')
      if (router.currentRoute.value.name !== 'login') {
        router.push({ name: 'login' })
      }
    }
    Message.error(body?.message || '请求失败')
    return Promise.reject(new Error(body?.message || '请求失败'))
  },
  (error) => {
    Message.error(error.message || '网络异常')
    return Promise.reject(error)
  }
)

export default http
