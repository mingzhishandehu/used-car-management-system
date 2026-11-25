import { createApp } from 'vue'
import ArcoVue from '@arco-design/web-vue'
import ArcoVueIcon from '@arco-design/web-vue/es/icon'
import '@arco-design/web-vue/dist/arco.css'
// 主题层必须在 arco.css 之后引入：同样特异性的变量与样式规则，后写的赢
import './styles/theme.css'
import App from './App.vue'
import router from './router'

/**
 * 入口。
 *
 * ArcoVueIcon 要单独注册，否则 <icon-plus /> 这类图标组件不生效，
 * 页面上会变成一片空白，很容易误判成样式没加载。
 */
const app = createApp(App)
app.use(ArcoVue)
app.use(ArcoVueIcon)
app.use(router)
app.mount('#app')
