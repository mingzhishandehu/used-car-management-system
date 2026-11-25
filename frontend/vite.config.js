import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 配置。
 *
 * proxy 的作用：浏览器里发 /api/xxx，Vite 开发服务器转给 http://localhost:8080。
 * 这样前端代码里永远写相对路径 /api，不用关心后端端口，
 * 也不会因为浏览器跨域（后端 8080、前端 5174 属于不同源）被拦。
 *
 * host 显式设成 IPv6 通配 '::'：Node 在 Windows 上默认开 dual-stack，
 * 同一个监听会同时接受 ::1（localhost）和 IPv4 映射地址（127.0.0.1），
 * 两个地址都能打开，不用再记「只能敲 localhost」。
 * 代价是会监听所有网卡，所以只适合本机开发学习用；别在公共网络或办公网里这么开，
 * 那种环境把这一行去掉（回到只绑 ::1）或改成 '127.0.0.1'。
 *
 * 排查连通性时 curl 要带 --noproxy '*'，否则请求会被本机 HTTP 代理截走，
 * 拿到代理返回的 502 假错误，看起来像服务没启动。
 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
    host: '::',
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
