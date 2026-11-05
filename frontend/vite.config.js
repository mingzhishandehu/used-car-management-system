import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 配置。
 *
 * proxy 的作用：浏览器里发 /api/xxx，Vite 开发服务器转给 http://localhost:8080。
 * 这样前端代码里永远写相对路径 /api，不用关心后端端口，
 * 也不会因为浏览器跨域（后端 8080、前端 5174 属于不同源）被拦。
 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
