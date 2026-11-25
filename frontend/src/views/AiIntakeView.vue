<template>
  <div class="intake-page">
    <!-- 会话列表 -->
    <aside class="side">
      <div class="side-head">
        <div>
          <div class="side-title">收车会话</div>
          <div class="side-sub">{{ conversations.length }} 个会话</div>
        </div>
        <a-button type="primary" size="mini" @click="newConversation">
          <template #icon><icon-plus /></template>
          新会话
        </a-button>
      </div>

      <div class="side-search">
        <a-input v-model="convKeyword" size="small" placeholder="搜索会话" allow-clear>
          <template #prefix><NavIcon name="search" :size="14" /></template>
        </a-input>
      </div>

      <div class="conv-list">
        <button
          v-for="c in filteredConversations"
          :key="c.id"
          type="button"
          class="conv-item"
          :class="{ active: c.id === activeId }"
          @click="selectConv(c.id)"
        >
          <div class="conv-title">{{ c.title }}</div>
          <div class="conv-meta">
            <span class="status-dot" :class="dotClass(c.cardStatus)" />
            {{ statusText(c.cardStatus) }}
            <i>·</i>
            {{ fmt(c.updatedAt) }}
          </div>
        </button>
        <div v-if="!filteredConversations.length" class="conv-empty">
          {{ convKeyword ? '没有匹配的会话' : '还没有会话，点「新会话」开始' }}
        </div>
      </div>

      <div class="side-foot">
        <NavIcon name="approval" :size="14" />
        会话记录云端保存，换设备登录也能继续查看
      </div>
    </aside>

    <!-- 聊天区 -->
    <section class="chat">
      <header class="chat-head">
        <div class="chat-head-left">
          <div class="chat-title">{{ currentTitle }}</div>
          <div class="chat-sub">AI 负责整理信息，点「确认录入」才会保存</div>
        </div>
        <div class="chat-head-right">
          <span v-if="cardPill" class="pill" :class="cardPill.cls">{{ cardPill.text }}</span>
          <a-tooltip :content="traceOpen ? '隐藏执行轨迹' : '查看执行轨迹'">
            <a-button size="mini" type="text" class="refresh-btn" @click="traceOpen = !traceOpen">
              <template #icon><NavIcon name="spark" :size="15" /></template>
            </a-button>
          </a-tooltip>
          <a-tooltip content="刷新会话">
            <a-button size="mini" type="text" class="refresh-btn" @click="loadDetail">
              <template #icon><NavIcon name="refresh" :size="15" /></template>
            </a-button>
          </a-tooltip>
        </div>
      </header>

      <div ref="scrollRef" class="msg-scroll">
        <div v-if="!messages.length" class="empty-chat">
          <div class="empty-mark"><NavIcon name="spark" :size="26" /></div>
          <div class="empty-title">说一句话，开始收车</div>
          <div class="empty-tip">AI 会从原话里抽出品牌、车型、价格和日期，整理成一张可修改的卡片</div>
          <div class="sample-grid">
            <button v-for="s in samples" :key="s" type="button" class="sample" @click="useSample(s)">
              <NavIcon name="arrow" :size="14" class="sample-arrow" />
              <span>{{ s }}</span>
            </button>
          </div>
        </div>

        <div v-for="msg in messages" :key="msg.id" class="msg-row" :class="msg.role === 'USER' ? 'mine' : 'ai'">
          <div class="avatar">{{ msg.role === 'USER' ? '我' : 'AI' }}</div>
          <div class="msg-body">
            <!-- 流式占位气泡：还没收到第一段增量时显示三点，收到后直接渲染文本 -->
            <div v-if="msg.streaming && !msg.content" class="bubble typing"><i /><i /><i /></div>
            <div v-else class="bubble">{{ msg.content }}</div>
            <IntakeCard
              v-if="msg.card"
              :card="isCurrent(msg) ? detail.card : msg.card"
              :editable="isCurrent(msg) && detail.card.status === 'DRAFT'"
              @patch="onPatch"
              @skip="onSkip"
              @confirm="onConfirm"
              @discard="onDiscard"
            />
            <div class="msg-time">{{ fmt(msg.createdAt) }}</div>
          </div>
        </div>
      </div>

      <footer class="composer">
        <div class="composer-box">
          <a-textarea
            v-model="input"
            :rows="2"
            :disabled="sending"
            placeholder="用一句话描述刚收的车；也可以直接说「收车价改成 7 万」"
            @keydown="onKeydown"
          />
          <div class="composer-bar">
            <div class="composer-hints">
              <span class="kbd">Ctrl</span>
              <span class="plus">+</span>
              <span class="kbd">Enter</span>
              <span>发送</span>
            </div>
            <a-button type="primary" :loading="sending" :disabled="!input.trim() || !activeId" @click="send">
              发送
            </a-button>
          </div>
        </div>
      </footer>
    </section>

    <!--
      执行轨迹：AI 每回答一次问题，背后调了哪些工具、跑了几步、各花多少毫秒。
      默认只显示一行摘要，点开才是参数、查询语句和结果。
    -->
    <aside v-if="traceOpen" class="trace">
      <div class="trace-head">
        <div>
          <div class="side-title">执行轨迹</div>
          <div class="side-sub">{{ traces.length ? `${traces.length} 次工具调用` : '暂无调用' }}</div>
        </div>
        <a-button size="mini" type="text" class="refresh-btn" @click="loadTraces">
          <template #icon><NavIcon name="refresh" :size="14" /></template>
        </a-button>
      </div>

      <div class="trace-list">
        <div v-if="!traceGroups.length" class="trace-empty">
          还没有查过门店数据。<br />问一句「库里还有几台车」，这里会留下每一步的记录。
        </div>

        <div v-for="(group, gi) in traceGroups" :key="gi" class="trace-group">
          <div class="trace-q" :title="group.question">
            <span class="trace-q-text">{{ group.question }}</span>
            <span class="trace-q-meta">{{ group.steps.length }} 步 · {{ group.totalMs }} ms</span>
          </div>

          <div
            v-for="step in group.steps"
            :key="step.id"
            class="trace-step"
            :class="{ fail: !step.ok }"
          >
            <div class="trace-step-head" @click="toggleStep(step.id)">
              <span class="trace-round">{{ step.roundNo }}</span>
              <span class="trace-tool">{{ toolLabel(step.tool) }}</span>
              <!-- 失败时没有行数，显示「- 条」会让人以为查出 0 条，直接不显示 -->
              <span v-if="step.rowCount !== null && step.rowCount !== undefined" class="trace-stat">
                {{ step.rowCount }} 条
              </span>
              <span class="trace-ms">{{ step.elapsedMs ?? 0 }}ms</span>
              <span class="trace-flag" :class="step.ok ? 'ok' : 'bad'">{{ step.ok ? '成功' : '失败' }}</span>
            </div>

            <div v-if="openStepIds.has(step.id)" class="trace-detail">
              <div v-if="step.args" class="trace-kv">
                <i>提出的条件</i>
                <code>{{ step.args }}</code>
              </div>
              <div v-if="step.sql" class="trace-kv">
                <i>实际执行的查询</i>
                <code>{{ step.sql }}</code>
              </div>
              <div v-if="step.error" class="trace-kv">
                <i>失败原因</i>
                <code class="err">{{ step.error }}</code>
              </div>
              <div v-if="step.result" class="trace-kv">
                <i>查到的结果</i>
                <code>{{ step.result }}</code>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="side-foot">
        <NavIcon name="spark" :size="14" />
        每次回答背后的查询都留在这里
      </div>
    </aside>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import IntakeCard from '../components/IntakeCard.vue'
import NavIcon from '../components/NavIcon.vue'
import {
  listAiConversations,
  createAiConversation,
  getAiConversation,
  listAiTraces,
  sendAiMessage,
  sendAiMessageStream,
  patchAiCard,
  confirmAiCard,
  discardAiCard
} from '../api'

/**
 * AI 收车聊天页。
 *
 * 卡片是消息流里的一条内容，不是浮层弹窗：每次 AI 回复都带一份卡片快照，
 * 只有最后一张用 detail.card（当前实时草稿）渲染成可编辑态，
 * 其余全是只读历史。确认后 detail.card.status 变 CONFIRMED，最后一张就地变灰。
 */
const conversations = ref([])
const activeId = ref(null)
const detail = ref(null)
const input = ref('')
const sending = ref(false)
// 打字指示只在「发起请求的那个会话」里显示，切到别的会话不该看到它在转
const sendingConvId = ref(null)
const scrollRef = ref(null)
const convKeyword = ref('')

// 执行轨迹不是聊天的必要数据：拉失败就空着，不能让整个页面报错
const traces = ref([])
const traceOpen = ref(true)
// 哪几步是展开的。用 Set 存 id，比给每条记录加一个 open 布尔字段干净
const openStepIds = ref(new Set())

const samples = [
  '今天收了台大众朗逸 2021款，6万8',
  '收了辆宝马3系，收车价 15 万，昨天',
  '丰田凯美瑞，跑了 6.2 万公里'
]

/** 工具名转成人话。后端给的是 run_readonly_sql 这种标识，直接显示给车商看不懂。 */
const TOOL_LABEL = {
  count_vehicles: '数库存台数',
  vehicle_status_summary: '按状态统计',
  find_vehicle: '按条件找车',
  count_pending_approvals: '数待审批',
  count_open_after_sales: '数未结案售后',
  run_readonly_sql: '组合条件查询',
  // 模型这一轮没给出可用的查询意图，后端会落一条失败轨迹
  route_failed: '识别失败'
}

function toolLabel(name) {
  return TOOL_LABEL[name] || name
}

const messages = computed(() => detail.value?.messages || [])
const currentTitle = computed(() => detail.value?.conversation?.title || '收车会话')

const filteredConversations = computed(() => {
  const key = convKeyword.value.trim()
  if (!key) return conversations.value
  return conversations.value.filter((c) => String(c.title || '').includes(key))
})

/**
 * 把轨迹按「一次提问」分组。
 *
 * 后端 roundNo 是一次提问内的序号，从 1 递增。所以序号比上一步小，
 * 就说明这是新的一次提问，而不是同一批里的下一步。
 * 不能靠问题原文判断：同一句话问两遍会被错误合并。
 */
const traceGroups = computed(() => {
  const groups = []
  for (const t of traces.value) {
    const last = groups[groups.length - 1]
    const lastStep = last && last.steps[last.steps.length - 1]
    if (lastStep && (t.roundNo || 1) > (lastStep.roundNo || 1)) {
      last.steps.push(t)
      last.totalMs += t.elapsedMs || 0
    } else {
      groups.push({
        question: t.question || '（未记录问题）',
        steps: [t],
        totalMs: t.elapsedMs || 0
      })
    }
  }
  // 最新的提问排最上面，跟聊天记录的阅读方向一致
  return groups.reverse()
})

const cardPill = computed(() => {
  const s = detail.value?.card?.status
  const map = {
    DRAFT: { text: '待确认', cls: 'pill-blue' },
    PENDING: { text: '待审批', cls: 'pill-amber' },
    CONFIRMED: { text: '已录入', cls: 'pill-green' },
    DISCARDED: { text: '已放弃', cls: 'pill-gray' }
  }
  return map[s] || null
})

const lastCardMsgId = computed(() => {
  const list = messages.value
  for (let i = list.length - 1; i >= 0; i--) {
    if (list[i].card) return list[i].id
  }
  return null
})

function isCurrent(msg) {
  return msg.id === lastCardMsgId.value
}

function fmt(s) {
  return s ? String(s).replace('T', ' ').slice(0, 16) : ''
}

/** 本地时间，给乐观插入的临时消息用；后端返回后会被真实时间替换 */
function nowText() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function statusText(s) {
  return { NONE: '未开始', DRAFT: '待确认', PENDING: '待审批', CONFIRMED: '已录入', DISCARDED: '已放弃' }[s] || s || '未开始'
}

function dotClass(s) {
  return { NONE: 'dot-gray', DRAFT: 'dot-blue', PENDING: 'dot-orange', CONFIRMED: 'dot-green', DISCARDED: 'dot-gray' }[s] || 'dot-gray'
}

function scrollToLatest() {
  nextTick(() => {
    const el = scrollRef.value
    if (!el) return
    const rows = el.querySelectorAll('.msg-row')
    const last = rows[rows.length - 1]
    // 最后一条如果是一张很高的信息卡片，直接贴底会把卡片头和「还缺几项」的提示推到屏幕外。
    // 这种时候对齐到这条消息的顶部，按阅读顺序从提示开始看；短消息仍然贴底。
    if (last && last.offsetHeight > el.clientHeight * 0.7) {
      const box = el.getBoundingClientRect()
      const lastBox = last.getBoundingClientRect()
      el.scrollTop += lastBox.top - box.top - 14
      return
    }
    el.scrollTop = el.scrollHeight
  })
}

async function loadConversations() {
  conversations.value = await listAiConversations()
}

async function loadDetail() {
  if (!activeId.value) return
  detail.value = await getAiConversation(activeId.value)
  scrollToLatest()
  // 轨迹不阻塞聊天渲染：先让消息上屏，轨迹回来再补
  loadTraces()
}

async function loadTraces() {
  if (!activeId.value) return
  try {
    traces.value = await listAiTraces(activeId.value)
  } catch (e) {
    // 轨迹是辅助信息，接口挂了不影响聊天：清空就行，不弹错误提示盖住聊天
    traces.value = []
  }
}

function toggleStep(id) {
  // 换一个新 Set 而不是原地 add：Set 原地修改不会触发 Vue 的响应
  const next = new Set(openStepIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  openStepIds.value = next
}

async function selectConv(id) {
  if (id === activeId.value) return
  activeId.value = id
  input.value = ''
  // 先清掉上个会话的轨迹，否则切换瞬间会看到别人的执行记录
  traces.value = []
  openStepIds.value = new Set()
  await loadDetail()
}

async function newConversation() {
  const c = await createAiConversation()
  await loadConversations()
  activeId.value = c.id
  input.value = ''
  await loadDetail()
}

function useSample(text) {
  input.value = text
}

async function send() {
  const text = input.value.trim()
  if (!text || sending.value || !activeId.value) return

  const convId = activeId.value
  const userId = `temp-u-${Date.now()}`
  const aiId = `temp-a-${Date.now()}`

  // 乐观更新：先把用户这句话和一个空的 AI 气泡塞进当前消息流，
  // 网络往返期间就能看到自己发的内容，AI 侧直接显示「输入中」。
  // 服务端返回后用 loadDetail() 整体替换成真实数据，临时消息自然消失，不会重复。
  detail.value.messages.push({
    id: userId,
    role: 'USER',
    content: text,
    card: null,
    createdAt: nowText()
  })
  const placeholder = reactive({
    id: aiId,
    role: 'ASSISTANT',
    content: '',
    card: null,
    createdAt: nowText(),
    streaming: true
  })
  detail.value.messages.push(placeholder)

  sending.value = true
  sendingConvId.value = convId
  input.value = ''
  scrollToLatest()

  let gotDelta = false
  try {
    await sendAiMessageStream(convId, text, {
      onDelta: (t) => {
        gotDelta = true
        placeholder.content += t
        scrollToLatest()
      },
      onDone: (res) => {
        // 以后端认定值为准：数字校验可能把流式内容换成兜底文案，
        // 前端如果只认增量，就会把模型编的数字留在屏幕上。
        if (res.reply) placeholder.content = res.reply
      },
      onError: (err) => {
        throw err
      }
    })
  } catch (e) {
    // 流式链路不可用（代理没开 SSE、连接被断）：退回非流式接口，功能不能丢。
    // 已经吐过增量就不再重试，否则同一句话会出现两遍答案。
    if (!gotDelta) {
      try {
        await sendAiMessage(convId, text)
      } catch (err) {
        // 后端事务已回滚，把两条临时消息都撤掉，原话塞回输入框
        detail.value.messages = detail.value.messages.filter((m) => m.id !== userId && m.id !== aiId)
        input.value = text
      }
    }
  } finally {
    sending.value = false
    sendingConvId.value = null
    // 发送期间切到了别的会话，就不要把旧会话的结果刷到当前会话上
    if (activeId.value === convId) await loadDetail()
    await loadConversations()
  }
}

function onKeydown(e) {
  if (e.ctrlKey && e.key === 'Enter') {
    e.preventDefault()
    send()
  }
}

async function onPatch(fields) {
  if (!activeId.value || !detail.value) return
  try {
    const card = await patchAiCard(activeId.value, { fields })
    // 只同步状态字段，不整体替换 card：整体替换会触发子组件的 watch，
    // 把用户还在输入的 local 值覆盖掉，表现为打字被打断。
    Object.assign(detail.value.card, {
      missingRequired: card.missingRequired,
      missingOptional: card.missingOptional,
      skipOptional: card.skipOptional,
      readyToConfirm: card.readyToConfirm
    })
  } catch (e) {
    // 提示已由拦截器弹出
  }
}

async function onSkip(fields) {
  if (!activeId.value) return
  try {
    await patchAiCard(activeId.value, { fields, skipOptional: true })
    await loadDetail()
  } catch (e) {
    // 提示已由拦截器弹出
  }
}

async function onConfirm(fields) {
  if (!activeId.value) return
  try {
    // 先把卡片上改过但还没失焦保存的值落库，再确认。
    // 顺序反了的话，写库用的会是上一版草稿，老板刚改的收车价会丢。
    await patchAiCard(activeId.value, { fields })
    const res = await confirmAiCard(activeId.value)
    // ADMIN 直接入库；员工只生成申请单，卡片变 PENDING，不能跳车辆详情页
    if (res.executed) {
      Message.success(`已录入，库存编号 ${res.stockNo}`)
    } else {
      Message.success(`已提交申请（单号 ${res.approvalId}），等待管理员审批`)
    }
    await loadDetail()
    await loadConversations()
  } catch (e) {
    // 提示已由拦截器弹出
  }
}

async function onDiscard() {
  if (!activeId.value) return
  try {
    await discardAiCard(activeId.value)
    await loadDetail()
  } catch (e) {
    // 提示已由拦截器弹出
  }
}

onMounted(async () => {
  await loadConversations()
  if (conversations.value.length) {
    activeId.value = conversations.value[0].id
  } else {
    const c = await createAiConversation()
    conversations.value = [c]
    activeId.value = c.id
  }
  await loadDetail()
})
</script>

<style scoped>
.intake-page {
  display: flex;
  gap: 14px;
  height: calc(100vh - 130px);
  min-height: 520px;
}

/* 会话栏 */
.side {
  width: 252px;
  flex: none;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--uc-border);
  border-radius: var(--uc-r-lg);
  box-shadow: var(--uc-shadow-1);
  overflow: hidden;
}
.side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 14px 10px;
}
.side-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--uc-text);
}
.side-sub {
  margin-top: 2px;
  font-size: 10.5px;
  color: var(--uc-text-3);
}
.side-search {
  padding: 0 12px 10px;
}
.conv-list {
  flex: 1;
  overflow-y: auto;
  padding: 2px 8px 8px;
}
.conv-item {
  display: block;
  width: 100%;
  padding: 10px 11px;
  margin-bottom: 3px;
  border-radius: 10px;
  border: 1px solid transparent;
  background: transparent;
  text-align: left;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.16s ease;
}
.conv-item:hover {
  background: #f7f8fb;
}
.conv-item.active {
  background: var(--uc-primary-soft);
  border-color: #dfe6fd;
}
.conv-title {
  font-size: 12.5px;
  color: var(--uc-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.conv-item.active .conv-title {
  color: var(--uc-primary-deep);
  font-weight: 500;
}
.conv-meta {
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 10.5px;
  color: var(--uc-text-3);
}
.conv-meta i {
  font-style: normal;
  color: #cfd4de;
}
.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
  flex: none;
}
.dot-blue { background: #3457d5; }
.dot-green { background: #1f8a5b; }
.dot-orange { background: #d99a2b; }
.dot-gray { background: #c3c9d6; }
.conv-empty {
  padding: 26px 10px;
  text-align: center;
  font-size: 11.5px;
  color: var(--uc-text-3);
  line-height: 1.7;
}
.side-foot {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 10px 14px;
  border-top: 1px solid var(--uc-border-soft);
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--uc-text-3);
}
.side-foot :deep(svg) {
  margin-top: 2px;
}

/* 聊天区 */
.chat {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--uc-border);
  border-radius: var(--uc-r-lg);
  box-shadow: var(--uc-shadow-1);
  overflow: hidden;
}
.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 18px;
  border-bottom: 1px solid var(--uc-border-soft);
}
.chat-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--uc-text);
}
.chat-sub {
  margin-top: 2px;
  font-size: 10.5px;
  color: var(--uc-text-3);
}
.chat-head-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.refresh-btn {
  color: var(--uc-text-3);
}

.msg-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 20px 22px;
  background:
    linear-gradient(180deg, #fbfcfe 0%, #f7f9fc 100%);
}

.empty-chat {
  max-width: 520px;
  margin: 8vh auto 0;
  text-align: center;
}
.empty-mark {
  width: 56px;
  height: 56px;
  margin: 0 auto 16px;
  border-radius: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(150deg, #e6b871, #c08c3e);
  color: #23180a;
  box-shadow: 0 16px 30px -18px rgba(192, 140, 62, 0.9);
}
.empty-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--uc-text);
}
.empty-tip {
  margin-top: 7px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--uc-text-3);
}
.sample-grid {
  margin-top: 22px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sample {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 11px 14px;
  border: 1px solid var(--uc-border);
  border-radius: 11px;
  background: #fff;
  color: var(--uc-text-2);
  font-size: 12.5px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: all 0.16s ease;
}
.sample:hover {
  border-color: #c9d3f2;
  color: var(--uc-primary);
  transform: translateX(2px);
  box-shadow: var(--uc-shadow-1);
}
.sample-arrow {
  color: #c3c9d6;
  flex: none;
}
.sample:hover .sample-arrow {
  color: var(--uc-primary);
}

.msg-row {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
  animation: uc-fade-up 0.24s ease both;
}
.msg-row.mine {
  flex-direction: row-reverse;
}
.avatar {
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10.5px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(150deg, #e6b871, #c08c3e);
  color: #241a0b;
}
.msg-row.mine .avatar {
  background: linear-gradient(150deg, #33405c, #16203a);
  color: #e8edf7;
}
.msg-body {
  max-width: min(720px, 86%);
  min-width: 0;
}
.bubble {
  padding: 10px 14px;
  border-radius: 12px;
  border-top-left-radius: 4px;
  background: #fff;
  border: 1px solid var(--uc-border);
  font-size: 13px;
  line-height: 1.75;
  color: var(--uc-text);
  white-space: pre-wrap;
  word-break: break-word;
  box-shadow: 0 1px 2px rgba(16, 25, 43, 0.03);
}
.msg-row.mine .bubble {
  background: linear-gradient(150deg, #3d61dd, #2c4cc4);
  border-color: transparent;
  border-top-left-radius: 12px;
  border-top-right-radius: 4px;
  color: #fff;
  box-shadow: 0 8px 20px -14px rgba(44, 76, 196, 0.9);
}
.msg-time {
  margin-top: 5px;
  font-size: 10.5px;
  color: var(--uc-text-3);
}
.msg-row.mine .msg-time {
  text-align: right;
}

/* 输入中 */
.bubble.typing {
  display: flex;
  align-items: center;
  gap: 5px;
  height: 38px;
  padding: 0 16px;
}
.bubble.typing i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #b9c0cf;
  animation: typing 1.2s infinite ease-in-out;
}
.bubble.typing i:nth-child(2) {
  animation-delay: 0.15s;
}
.bubble.typing i:nth-child(3) {
  animation-delay: 0.3s;
}
@keyframes typing {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.45; }
  30% { transform: translateY(-4px); opacity: 1; }
}

/* 输入区 */
.composer {
  padding: 12px 16px 14px;
  border-top: 1px solid var(--uc-border-soft);
  background: #fff;
}
.composer-box {
  padding: 8px 10px 8px 12px;
  border: 1px solid var(--uc-border);
  border-radius: 13px;
  background: #fff;
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}
.composer-box:focus-within {
  border-color: #b9c5ec;
  box-shadow: 0 0 0 3px rgba(52, 87, 213, 0.1);
}
.composer-box :deep(.arco-textarea-wrapper) {
  border: none;
  box-shadow: none;
  padding: 2px 4px;
}
.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
  padding-left: 4px;
}
.composer-hints {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 10.5px;
  color: var(--uc-text-3);
}
.composer-hints .kbd {
  padding: 1px 5px;
  border: 1px solid #e2e6ee;
  border-bottom-width: 2px;
  border-radius: 4px;
  background: #fafbfd;
  font-size: 10px;
  color: var(--uc-text-2);
}
.composer-hints .plus {
  color: #c3c9d6;
}
/* ==================== 执行轨迹 ==================== */
.trace {
  width: 302px;
  flex: none;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--uc-border);
  border-radius: var(--uc-r-lg);
  box-shadow: var(--uc-shadow-1);
  overflow: hidden;
}
.trace-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 14px 10px;
  border-bottom: 1px solid var(--uc-border-soft);
}
.trace-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
/* 自定义滚动条要连按钮一起隐藏，否则两端会露出上下箭头 */
.trace-list::-webkit-scrollbar {
  width: 6px;
}
.trace-list::-webkit-scrollbar-button {
  display: none;
}
.trace-list::-webkit-scrollbar-thumb {
  background: var(--uc-border);
  border-radius: 3px;
}
.trace-empty {
  padding: 26px 14px;
  font-size: 11.5px;
  line-height: 1.8;
  color: var(--uc-text-3);
  text-align: center;
}

.trace-group {
  border: 1px solid var(--uc-border-soft);
  border-radius: var(--uc-r-sm);
  overflow: hidden;
  background: #fff;
}
.trace-q {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  background: var(--uc-surface-2);
  border-bottom: 1px solid var(--uc-border-soft);
}
.trace-q-text {
  flex: 1;
  min-width: 0;
  font-size: 11.5px;
  color: var(--uc-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.trace-q-meta {
  flex: none;
  font-size: 10.5px;
  color: var(--uc-text-3);
}

.trace-step + .trace-step {
  border-top: 1px dashed var(--uc-border-soft);
}
.trace-step-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 10px;
  cursor: pointer;
  transition: background 0.15s;
}
.trace-step-head:hover {
  background: var(--uc-primary-soft);
}
.trace-round {
  flex: none;
  width: 16px;
  height: 16px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--uc-primary-soft);
  color: var(--uc-primary);
  font-size: 10px;
}
.trace-tool {
  flex: 1;
  min-width: 0;
  font-size: 11.5px;
  color: var(--uc-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.trace-stat,
.trace-ms {
  flex: none;
  font-size: 10.5px;
  color: var(--uc-text-3);
}
.trace-flag {
  flex: none;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 10px;
}
.trace-flag.ok {
  color: var(--uc-success);
  background: var(--uc-success-soft);
}
.trace-flag.bad {
  color: var(--uc-danger);
  background: var(--uc-danger-soft);
}

.trace-detail {
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 2px 10px 10px;
}
.trace-kv {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.trace-kv i {
  font-style: normal;
  font-size: 10px;
  color: var(--uc-text-3);
}
.trace-kv code {
  padding: 6px 7px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--uc-text-2);
  background: var(--uc-surface-2);
  border: 1px solid var(--uc-border-soft);
  border-radius: 6px;
  /* 不能用 break-all：英文单词会被从中间劈开，SQL 读起来全是断词。
     break-word 只在单词放不下时才断行。 */
  word-break: normal;
  overflow-wrap: break-word;
  white-space: pre-wrap;
  max-height: 132px;
  overflow-y: auto;
}
.trace-kv code.err {
  color: var(--uc-danger);
  border-color: var(--uc-danger-soft);
}
</style>
