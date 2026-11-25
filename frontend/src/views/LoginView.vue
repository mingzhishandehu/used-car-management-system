<template>
  <div class="login-page">
    <!-- 左侧：品牌与产品预览，不放任何运行环境、表数量这类内部信息 -->
    <section class="pane">
      <div class="grid-bg" />
      <div class="glow" />

      <header class="pane-head">
        <div class="brand-mark">UC</div>
        <div class="brand-text">
          <strong>优驰云</strong>
          <span>USED CAR RETAIL OS</span>
        </div>
      </header>

      <div class="pane-body">
        <div class="eyebrow">二手车门店经营管理系统</div>
        <h1>收车有据，定价有数<br />库存有底</h1>
        <p class="lead">
          从一句话收车、成本核算，到挂牌定价、成交与售后，
          把门店每天的车辆经营装进同一套流程。
        </p>

        <!-- 对外展示只放门店实力与服务承诺，不出现任何真实库存、价格、单量 -->
        <div class="promo">
          <div class="promo-head">
            <div>
              <div class="promo-title">杭州旗舰店</div>
              <div class="promo-sub">现车销售 · 收购置换 · 售后质保</div>
            </div>
            <span class="promo-place">浙江 · 杭州</span>
          </div>

          <div class="promo-stats">
            <div class="promo-stat">
              <b class="num">20<span>+</span></b>
              <em>在售车型</em>
            </div>
            <div class="promo-stat">
              <b class="num">1,800<span>+</span></b>
              <em>服务车主</em>
            </div>
            <div class="promo-stat">
              <b class="num">259</b>
              <em>项检测标准</em>
            </div>
          </div>

          <div class="promo-foot">
            <span>1 年整车质保</span>
            <span>3 年动力总成质保</span>
            <span>7 天无理由退换</span>
          </div>
        </div>
      </div>

      <footer class="pane-foot">
        <span><NavIcon name="approval" :size="14" />数据按门店隔离</span>
        <span><NavIcon name="clock" :size="14" />关键操作全程留痕</span>
        <span><NavIcon name="bulb" :size="14" />7×12 小时支持</span>
      </footer>
    </section>

    <!-- 右侧：登录 -->
    <section class="form-pane">
      <div class="form-wrap">
        <div class="form-card">
          <div class="form-head">
            <h2>登录优驰云</h2>
            <p>使用门店账号进入经营管理系统</p>
          </div>

          <a-form :model="form" layout="vertical" @submit="onSubmit">
            <a-form-item field="username" label="账号">
              <a-input v-model="form.username" placeholder="手机号 / 登录名" size="large" allow-clear>
                <template #prefix><icon-user /></template>
              </a-input>
            </a-form-item>

            <a-form-item field="password" label="密码">
              <a-input-password v-model="form.password" placeholder="请输入密码" size="large" allow-clear>
                <template #prefix><icon-lock /></template>
              </a-input-password>
            </a-form-item>

            <div class="form-row">
              <a-checkbox v-model="remember">记住账号</a-checkbox>
              <button class="text-link" type="button" @click="helpVisible = true">忘记密码？</button>
            </div>

            <a-button class="submit" type="primary" size="large" long :loading="loading" html-type="submit">
              登录
            </a-button>
          </a-form>

          <p class="legal">
            登录即代表你已阅读并同意
            <button class="text-link inline" type="button" @click="openLegal('terms')">《服务协议》</button>
            与
            <button class="text-link inline" type="button" @click="openLegal('privacy')">《隐私政策》</button>
          </p>

          <div class="form-foot">
            还没有账号？请联系门店管理员开通
          </div>
        </div>

        <div class="page-foot">
          <span>© 2026 优驰科技</span>
          <span class="sep">·</span>
          <span>客服 400-881-6688</span>
        </div>
      </div>
    </section>

    <!-- 忘记密码 -->
    <a-modal v-model:visible="helpVisible" title="忘记密码" :width="440" :footer="false">
      <div class="modal-copy">
        <p>为了保证账号安全，密码由门店管理员统一管理，不通过线上自助找回。</p>
        <p>请联系门店管理员在「员工管理 → 重置密码」中为你设置新密码；也可以拨打客服电话 <b>400-881-6688</b>（周一至周六 9:00–18:00）。</p>
      </div>
    </a-modal>

    <!-- 协议 -->
    <a-modal
      v-model:visible="legalVisible"
      :title="legalType === 'terms' ? '服务协议' : '隐私政策'"
      :width="540"
      :footer="false"
    >
      <div class="modal-copy legal-copy">
        <p v-for="p in legalParagraphs" :key="p">{{ p }}</p>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import NavIcon from '../components/NavIcon.vue'
import { login } from '../api'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const remember = ref(true)
const helpVisible = ref(false)
const legalVisible = ref(false)
const legalType = ref('terms')

const REMEMBER_KEY = 'uc-login-username'

const form = reactive({
  username: '',
  password: ''
})

// 只存账号，不存密码：密码留在浏览器的密码管理器里，比写 localStorage 安全
onMounted(() => {
  const saved = localStorage.getItem(REMEMBER_KEY)
  if (saved) {
    form.username = saved
    remember.value = true
  }
})

const legalParagraphs = computed(() => {
  if (legalType.value === 'terms') {
    return [
      '本系统为二手车门店提供车辆台账、成本与定价测算、库存预警以及审批协作服务。',
      '账号由门店管理员统一创建与停用。使用期间请妥善保管登录凭证，不得转借他人；账号下的操作均视为本人行为。',
      '系统内的车辆、客户与交易数据归门店所有，平台仅在提供服务所必需的范围内进行处理。'
    ]
  }
  return [
    '我们仅收集提供服务所必需的信息：账号信息、车辆与交易记录、操作日志。',
    '数据按门店隔离存储，不同门店之间互不可见；平台不会将数据用于与服务无关的用途。',
    '如需查询、更正或删除数据，请联系门店管理员，或拨打客服电话 400-881-6688。'
  ]
})

function openLegal(type) {
  legalType.value = type
  legalVisible.value = true
}

async function onSubmit() {
  if (!form.username || !form.password) {
    Message.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    const info = await login({ username: form.username, password: form.password })
    // tokenName 是后端返回的请求头名（默认 satoken），存值不存名字，拦截器里写死同名
    localStorage.setItem('satoken', info.tokenValue)
    if (remember.value) localStorage.setItem(REMEMBER_KEY, form.username)
    else localStorage.removeItem(REMEMBER_KEY)
    Message.success('登录成功')
    // 带上 redirect，登录后回到原本想访问的页面
    const redirect = route.query.redirect
    router.push(typeof redirect === 'string' ? redirect : { name: 'dashboard' })
  } catch (e) {
    // 错误提示已由 axios 拦截器统一弹出，这里不重复提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  display: flex;
  height: 100vh;
  min-height: 640px;
  background: #f4f5f8;
}

/* ---------------- 左侧 ---------------- */
.pane {
  position: relative;
  width: 54%;
  min-width: 540px;
  padding: 40px 56px 32px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  color: #eef2f9;
  background:
    radial-gradient(90% 70% at 10% 0%, #1b2841 0%, transparent 55%),
    linear-gradient(160deg, #0d1524 0%, #0a101c 60%, #080d17 100%);
}
.grid-bg {
  position: absolute;
  inset: 0;
  background-image: linear-gradient(rgba(255, 255, 255, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.045) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(75% 60% at 40% 35%, #000 0%, transparent 78%);
  pointer-events: none;
}
.glow {
  position: absolute;
  width: 520px;
  height: 520px;
  right: -180px;
  top: -140px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(223, 171, 92, 0.16) 0%, transparent 62%);
  pointer-events: none;
}

.pane-head {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
}
.brand-mark {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: linear-gradient(150deg, #e6b871 0%, #c08c3e 55%, #9a6c2a 100%);
  color: #1a1206;
  font-size: 13px;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 10px 24px -12px rgba(223, 171, 92, 0.9);
}
.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}
.brand-text strong {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.05em;
}
.brand-text span {
  margin-top: 3px;
  font-size: 9px;
  letter-spacing: 0.18em;
  color: rgba(223, 171, 92, 0.72);
}

.pane-body {
  position: relative;
  margin-top: auto;
  margin-bottom: auto;
  padding-top: 20px;
  max-width: 660px;
}
.eyebrow {
  display: inline-flex;
  align-items: center;
  height: 26px;
  padding: 0 11px;
  border-radius: 99px;
  border: 1px solid rgba(223, 171, 92, 0.32);
  background: rgba(223, 171, 92, 0.08);
  color: #e2b673;
  font-size: 11px;
  letter-spacing: 0.06em;
}
.pane h1 {
  margin: 18px 0 14px;
  font-size: 38px;
  line-height: 1.3;
  font-weight: 600;
  letter-spacing: -0.02em;
}
.lead {
  margin: 0;
  max-width: 480px;
  font-size: 13.5px;
  line-height: 1.85;
  color: rgba(226, 232, 244, 0.56);
}

/* 门店实力卡：对外展示，只放宣传口径 */
.promo {
  width: 100%;
  max-width: 660px;
  margin-top: 26px;
  padding: 22px 24px 20px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 24px 60px -28px rgba(0, 0, 0, 0.65), 0 0 0 1px rgba(255, 255, 255, 0.12);
  color: var(--uc-text);
}
.promo-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.promo-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--uc-text);
  letter-spacing: 0.01em;
}
.promo-sub {
  margin-top: 4px;
  font-size: 11px;
  color: var(--uc-text-3);
}
.promo-place {
  flex: none;
  padding: 3px 9px;
  border-radius: 99px;
  background: #f5f7fb;
  color: var(--uc-text-3);
  font-size: 10.5px;
  white-space: nowrap;
}
.promo-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin: 20px 0 18px;
}
.promo-stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-left: 14px;
  border-left: 2px solid #eef1f7;
}
.promo-stat:first-child {
  border-left-color: var(--uc-accent);
}
.promo-stat b {
  font-size: 30px;
  font-weight: 650;
  letter-spacing: -0.03em;
  line-height: 1.05;
}
.promo-stat b span {
  margin-left: 1px;
  font-size: 15px;
  font-weight: 600;
}
.promo-stat em {
  font-style: normal;
  font-size: 11px;
  color: var(--uc-text-3);
}
.promo-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 14px;
  border-top: 1px solid #f1f3f7;
}
.promo-foot span {
  display: inline-flex;
  align-items: center;
  height: 26px;
  padding: 0 10px;
  border-radius: 7px;
  background: var(--uc-accent-soft);
  color: #8a6a1c;
  font-size: 11px;
  white-space: nowrap;
}

.pane-foot {
  position: relative;
  display: flex;
  align-items: center;
  gap: 26px;
  padding-top: 18px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
}
.pane-foot span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: rgba(226, 232, 244, 0.44);
}
.pane-foot :deep(svg) {
  color: rgba(223, 171, 92, 0.6);
}

/* ---------------- 右侧 ---------------- */
.form-pane {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  background:
    radial-gradient(60% 50% at 80% 10%, #eef2fe 0%, transparent 60%),
    #f4f5f8;
}
.form-wrap {
  width: 100%;
  max-width: 400px;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.form-card {
  width: 100%;
  padding: 38px 36px 30px;
  border-radius: 20px;
  background: #fff;
  border: 1px solid var(--uc-border);
  box-shadow: 0 2px 6px rgba(16, 25, 43, 0.04), 0 34px 60px -40px rgba(16, 25, 43, 0.35);
  animation: uc-fade-up 0.45s ease both;
}
.form-head h2 {
  margin: 0;
  font-size: 23px;
  font-weight: 650;
  letter-spacing: -0.02em;
  color: var(--uc-text);
}
.form-head p {
  margin: 7px 0 26px;
  font-size: 12.5px;
  color: var(--uc-text-3);
}
.form-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: -2px 0 18px;
}
.text-link {
  padding: 0;
  border: none;
  background: transparent;
  color: var(--uc-primary);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
}
.text-link:hover {
  text-decoration: underline;
}
.text-link.inline {
  font-size: 11px;
}
.submit {
  height: 44px;
  font-size: 14px;
  border-radius: 11px;
}
.legal {
  margin: 16px 0 0;
  font-size: 10.5px;
  line-height: 1.7;
  color: var(--uc-text-3);
  text-align: center;
}
.form-foot {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--uc-border-soft);
  font-size: 11.5px;
  color: var(--uc-text-3);
  text-align: center;
}
.page-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 26px;
  font-size: 11px;
  color: var(--uc-text-3);
}
.page-foot .sep {
  color: #cfd4de;
}

.modal-copy p {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--uc-text-2);
  text-align: left;
}
.modal-copy p:last-child {
  margin-bottom: 0;
}
.legal-copy p {
  color: var(--uc-text-2);
}

@media (max-width: 1200px) {
  .pane {
    width: 50%;
    min-width: 0;
    padding: 36px 40px 28px;
  }
  .pane h1 {
    font-size: 32px;
  }
  .promo {
    max-width: 100%;
  }
}
@media (max-width: 900px) {
  .pane {
    display: none;
  }
  .form-pane {
    padding: 24px;
  }
}
@media (max-height: 780px) and (min-width: 901px) {
  .pane {
    padding-top: 32px;
    padding-bottom: 22px;
  }
  .pane h1 {
    font-size: 32px;
    margin: 14px 0 10px;
  }
  .lead {
    font-size: 12.5px;
  }
  .promo {
    margin-top: 18px;
    padding: 14px 18px 12px;
  }
  .promo-stats {
    margin: 12px 0 10px;
  }
  .promo-stat b {
    font-size: 21px;
  }
  .pane-foot {
    padding-top: 14px;
  }
}
</style>
