<template>
  <div class="intake-card" :class="{ locked: isLocked, confirmed: card.status === 'CONFIRMED' }">
    <header class="ic-head">
      <div class="ic-title">
        <span class="ic-icon"><NavIcon name="car" :size="16" /></span>
        <div class="ic-title-text">
          <b>车辆信息卡片</b>
          <span>AI 抽取，可逐项修改</span>
        </div>
      </div>

      <div class="ic-head-right">
        <div class="ic-progress" :title="`必填项已填 ${filledRequired}/${requiredTotal}`">
          <i :class="{ complete: progressPct === 100 }" :style="{ width: progressPct + '%' }" />
        </div>
        <span class="ic-progress-text num">{{ filledRequired }}/{{ requiredTotal }}</span>
        <span class="pill" :class="statusPill.cls">{{ statusPill.text }}</span>
      </div>
    </header>

    <div class="ic-notice" :class="noticeTone">
      <span>{{ noticeText }}</span>
      <a-link v-if="card.status === 'CONFIRMED' && card.vehicleId" @click="goVehicle">查看车辆详情</a-link>
    </div>

    <div v-for="group in groups" :key="group.key" class="field-group">
      <div class="group-head">
        <span class="group-title">{{ group.title }}</span>
        <span class="group-hint">{{ group.hint }}</span>
        <a-button
          v-if="group.key === 'OPTIONAL' && !isLocked && !card.skipOptional && card.missingOptional.length"
          class="skip-btn"
          type="text"
          size="mini"
          @click="onSkip"
        >跳过这些</a-button>
      </div>

      <div class="field-grid">
        <div
          v-for="f in group.fields"
          :key="f.key"
          class="field-item"
          :class="{ missing: isMissing(f.key), optional: f.group === 'OPTIONAL', locked: isLocked }"
        >
          <div class="field-label">
            {{ f.label }}
            <span v-if="f.group === 'REQUIRED'" class="required-star">*</span>
            <span v-if="isMissing(f.key)" class="missing-flag">待补</span>
          </div>
          <a-date-picker
            v-if="f.type === 'DATE'"
            v-model="local[f.key]"
            value-format="YYYY-MM-DD"
            :disabled="isLocked"
            :placeholder="f.hint"
            style="width: 100%"
            @change="schedulePatch"
          />
          <a-input-number
            v-else-if="f.type === 'MONEY'"
            v-model="local[f.key]"
            :min="0"
            :precision="2"
            :disabled="isLocked"
            :placeholder="f.hint"
            style="width: 100%"
            @change="schedulePatch"
          />
          <a-input-number
            v-else-if="f.type === 'INTEGER'"
            v-model="local[f.key]"
            :min="0"
            :precision="0"
            :disabled="isLocked"
            :placeholder="f.hint"
            style="width: 100%"
            @change="schedulePatch"
          />
          <a-input
            v-else
            v-model="local[f.key]"
            :disabled="isLocked"
            :placeholder="f.hint"
            allow-clear
            @change="schedulePatch"
          />
        </div>
      </div>
    </div>

    <footer v-if="!isLocked" class="card-actions">
      <a-popconfirm content="放弃后这张卡片不能再确认，确定吗？" @ok="onDiscard">
        <a-button size="small" status="danger" type="text">放弃卡片</a-button>
      </a-popconfirm>
      <div class="action-right">
        <span v-if="actionTip" class="action-tip">{{ actionTip }}</span>
        <a-button type="primary" size="small" :disabled="!card.readyToConfirm" @click="onConfirm">
          确认录入
        </a-button>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, watch } from 'vue'
import { useRouter } from 'vue-router'
import NavIcon from './NavIcon.vue'

/**
 * 内嵌在聊天消息流里的车辆信息卡片。
 *
 * 只有「当前最新那张卡片」可编辑，历史快照一律只读置灰；
 * 确认后 status 变成 CONFIRMED，整张卡片变灰并锁定，但字段照旧展示，
 * 老板以后翻聊天记录还能看清当时确认的是什么。
 *
 * 所有编辑都先 emit('patch') 落库，确认时再把 full fields 交给父组件先 patch 再 confirm，
 * 这样不存在「卡片上改了、后端草稿还是旧值就已经写库」的竞态。
 */
const props = defineProps({
  card: { type: Object, required: true },
  editable: { type: Boolean, default: false }
})
const emit = defineEmits(['patch', 'skip', 'confirm', 'discard'])
const router = useRouter()

const local = reactive({})
let timer = null

watch(
  () => props.card,
  (c) => {
    Object.keys(local).forEach((k) => delete local[k])
    // 模型返回的金额/里程是字符串（"120000"），a-input-number 不认字符串，会显示成空输入框。
    // 这里只在展示层做一次数值转换，写回后端时仍是数字，不改变业务语义。
    const numericKeys = new Set(
      (c?.spec || []).filter((f) => f.type === 'MONEY' || f.type === 'INTEGER').map((f) => f.key)
    )
    const next = {}
    for (const [key, value] of Object.entries(c?.fields || {})) {
      const asNumber = numericKeys.has(key) && typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))
      next[key] = asNumber ? Number(value) : value
    }
    Object.assign(local, next)
  },
  { immediate: true }
)

const isLocked = computed(() => !props.editable || props.card.status !== 'DRAFT')

const groups = computed(() => {
  const spec = props.card.spec || []
  const missingReq = (props.card.missingRequired || []).length
  const missingOpt = (props.card.missingOptional || []).length
  return [
    {
      key: 'REQUIRED',
      title: '必填信息',
      hint: missingReq ? `还缺 ${missingReq} 项` : '已全部填写',
      fields: spec.filter((f) => f.group === 'REQUIRED')
    },
    {
      key: 'OPTIONAL',
      title: '建议补充',
      hint: props.card.skipOptional ? '本次已跳过' : missingOpt ? `还有 ${missingOpt} 项可补` : '已补全',
      fields: spec.filter((f) => f.group === 'OPTIONAL')
    },
    { key: 'EXTRA', title: '其他信息', hint: '可留空', fields: spec.filter((f) => f.group === 'EXTRA') }
  ].filter((g) => g.fields.length)
})

const requiredTotal = computed(() => (props.card.spec || []).filter((f) => f.group === 'REQUIRED').length)
const filledRequired = computed(() => Math.max(0, requiredTotal.value - (props.card.missingRequired || []).length))
const progressPct = computed(() => (requiredTotal.value ? Math.round((filledRequired.value / requiredTotal.value) * 100) : 0))

// 按钮被禁用时要说清楚缺的是什么：缺必填和「只缺建议项」是两种状态，提示不能混着写
const actionTip = computed(() => {
  if (props.card.status !== 'DRAFT' || !props.editable) return ''
  if ((props.card.missingRequired || []).length) return '补齐必填项后才能确认'
  if ((props.card.missingOptional || []).length && !props.card.skipOptional) return '补建议项，或点上方「跳过这些」'
  return ''
})

const statusPill = computed(() => {
  const map = {
    CONFIRMED: { text: '已录入', cls: 'pill-green' },
    DISCARDED: { text: '已放弃', cls: 'pill-gray' },
    PENDING: { text: '待审批', cls: 'pill-amber' }
  }
  if (map[props.card.status]) return map[props.card.status]
  return props.editable ? { text: '待确认', cls: 'pill-blue' } : { text: '历史记录', cls: 'pill-gray' }
})

const noticeTone = computed(() => {
  if (props.card.status === 'CONFIRMED') return 'tone-gray'
  if (props.card.status === 'DISCARDED') return 'tone-gray'
  if (props.card.status === 'PENDING') return 'tone-amber'
  if ((props.card.missingRequired || []).length) return 'tone-orange'
  if ((props.card.missingOptional || []).length && !props.card.skipOptional) return 'tone-amber'
  return 'tone-green'
})

const noticeText = computed(() => {
  const c = props.card
  if (c.status === 'CONFIRMED') {
    return `已录入系统，库存编号 ${c.stockNo || '-'}。卡片已锁定，不能再修改。`
  }
  if (c.status === 'DISCARDED') return '这张卡片已放弃，继续发消息可以重新录入。'
  if (c.status === 'PENDING') return '已提交申请，等待管理员审批。卡片已锁定，可在「审批中心」查看进度。'
  if (!props.editable) return '这是历史记录，仅供查看，不能修改。'
  if ((c.missingRequired || []).length) return `还缺 ${c.missingRequired.length} 项必填：${labels(c.missingRequired)}`
  if ((c.missingOptional || []).length && !c.skipOptional) {
    return `必填齐了。还有 ${c.missingOptional.length} 项建议信息没填，可以补，也可以点「跳过这些」。`
  }
  return '信息齐了。核对无误后点「确认录入」，才会写进系统。'
})

function labels(keys) {
  const spec = props.card.spec || []
  return keys.map((k) => spec.find((f) => f.key === k)?.label || k).join('、')
}

function isMissing(key) {
  return (props.card.missingRequired || []).includes(key) || (props.card.missingOptional || []).includes(key)
}

function schedulePatch() {
  if (isLocked.value) return
  clearTimeout(timer)
  // 防抖：金额、文本逐字输入时不要每敲一下就打一次后端
  timer = setTimeout(() => emit('patch', { ...local }), 400)
}

function flushTimer() {
  clearTimeout(timer)
  timer = null
}

function onConfirm() {
  flushTimer()
  emit('confirm', { ...local })
}

function onSkip() {
  flushTimer()
  emit('skip', { ...local })
}

function onDiscard() {
  flushTimer()
  emit('discard')
}

function goVehicle() {
  router.push({ name: 'vehicle-detail', params: { id: props.card.vehicleId } })
}

onBeforeUnmount(flushTimer)
</script>

<style scoped>
.intake-card {
  width: 100%;
  max-width: 640px;
  margin-top: 10px;
  background: #fff;
  border: 1px solid var(--uc-border);
  border-radius: var(--uc-r-lg);
  box-shadow: var(--uc-shadow-1);
  overflow: hidden;
}
.intake-card.locked {
  background: #fcfcfd;
  border-color: #e9ecf2;
  box-shadow: none;
}
.intake-card.confirmed {
  background: #fafbfc;
}

.ic-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--uc-border-soft);
}
.ic-title {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.ic-icon {
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--uc-primary-soft);
  color: var(--uc-primary);
}
.intake-card.locked .ic-icon {
  background: #f1f3f7;
  color: var(--uc-text-3);
}
.ic-title-text {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
  min-width: 0;
}
.ic-title-text b {
  font-size: 13px;
  font-weight: 600;
  color: var(--uc-text);
}
.ic-title-text span {
  font-size: 10.5px;
  color: var(--uc-text-3);
}
.ic-head-right {
  display: flex;
  align-items: center;
  gap: 9px;
  flex: none;
}
.ic-progress {
  width: 62px;
  height: 4px;
  border-radius: 99px;
  background: #eceff5;
  overflow: hidden;
}
.ic-progress i {
  display: block;
  height: 100%;
  border-radius: 99px;
  background: linear-gradient(90deg, #5c78e8, #3457d5);
  transition: width 0.3s ease;
}
.ic-progress i.complete {
  background: #1f8a5b;
}
.ic-progress-text {
  font-size: 11px;
  color: var(--uc-text-3);
}

.ic-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 16px;
  font-size: 11.5px;
  line-height: 1.6;
}
.tone-orange {
  background: #fdf3e7;
  color: #9a6111;
  border-bottom: 1px solid #f7e5cd;
}
.tone-amber {
  background: #fdf8ec;
  color: #8a6a1c;
  border-bottom: 1px solid #f5ecd6;
}
.tone-green {
  background: #eef8f2;
  color: #1a6e48;
  border-bottom: 1px solid #ddf0e5;
}
.tone-gray {
  background: #f5f6f9;
  color: var(--uc-text-3);
  border-bottom: 1px solid var(--uc-border-soft);
}

.field-group {
  padding: 4px 16px 10px;
}
.group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 0 8px;
}
.group-title {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--uc-text-2);
  letter-spacing: 0.02em;
}
.group-hint {
  font-size: 10.5px;
  color: var(--uc-text-3);
}
.skip-btn {
  margin-left: auto;
  font-size: 11px;
}
.field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 10px;
}
.field-item {
  min-width: 0;
  padding: 7px 9px 9px;
  border: 1px solid transparent;
  border-radius: 9px;
}
.field-item.missing {
  background: #fdf7ea;
  border-color: #f6e6c4;
}
.field-item.missing.optional {
  background: #fdfaf1;
  border-color: #f3ebd2;
}
.field-item.locked {
  background: #f7f8fa;
}
.field-item.missing.locked {
  background: #f7f8fa;
  border-color: transparent;
}
.field-label {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 5px;
  font-size: 11.5px;
  color: var(--uc-text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.required-star {
  color: var(--uc-danger);
}
.missing-flag {
  margin-left: auto;
  padding: 0 5px;
  border-radius: 4px;
  background: #f8e2bc;
  color: #8a5a0c;
  font-size: 9.5px;
  line-height: 15px;
}

.card-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 11px 16px;
  border-top: 1px solid var(--uc-border-soft);
  background: #fbfcfe;
}
.action-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.action-tip {
  font-size: 10.5px;
  color: var(--uc-text-3);
}
</style>
