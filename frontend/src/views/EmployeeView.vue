<template>
  <div class="uc-page">
    <header class="uc-page-head">
      <div>
        <h1 class="uc-h1">员工管理</h1>
        <p class="uc-sub">账号、角色与车辆归属都在这里维护；离职只停用账号，历史记录一律保留</p>
      </div>
      <div class="uc-head-actions">
        <a-button v-if="isAdmin" type="primary" @click="openCreate">
          <template #icon><icon-plus /></template>
          新增员工
        </a-button>
      </div>
    </header>

    <a-alert v-if="!isAdmin" type="warning">
      员工管理仅管理员可操作，当前账号只能查看员工列表。
    </a-alert>

    <div class="status-bar">
      <button
        v-for="c in employmentChips"
        :key="c.key || 'all'"
        type="button"
        class="status-chip"
        :class="{ active: employmentFilter === c.key }"
        @click="onEmployment(c.key)"
      >
        <span class="chip-dot" :style="{ background: c.color }" />
        {{ c.text }}
      </button>
      <div class="bar-spacer" />
      <a-input
        v-model="keyword"
        class="search"
        placeholder="搜索登录名 / 姓名 / 手机号"
        allow-clear
        @press-enter="reload"
      >
        <template #prefix><NavIcon name="search" :size="15" /></template>
      </a-input>
      <a-button type="primary" @click="reload">查询</a-button>
      <a-button @click="onReset">重置</a-button>
    </div>

    <a-card :bordered="false" class="table-card">
      <a-table
        row-key="id"
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="false"
        :bordered="false"
      >
        <template #employee="{ record }">
          <div class="staff-cell">
            <span class="uc-avatar" :style="avatarStyle(record)">{{ (record.realName || record.username || '?').slice(0, 1) }}</span>
            <div class="staff-main">
              <div class="staff-name">
                {{ record.realName || record.username }}
                <span v-if="record.id === meId" class="me-tag">我</span>
              </div>
              <div class="staff-sub">@{{ record.username }}</div>
            </div>
          </div>
        </template>

        <template #phone="{ record }">
          <span class="num">{{ record.phone || '—' }}</span>
        </template>

        <template #role="{ record }">
          <span class="pill" :class="roleCls(record.role)">{{ roleText(record.role) }}</span>
        </template>

        <template #status="{ record }">
          <span class="state" :class="record.status === 1 ? 'on' : 'off'">
            <i />{{ record.status === 1 ? '启用' : '停用' }}
          </span>
        </template>

        <template #employment="{ record }">
          <span class="state" :class="record.employmentStatus === 'LEFT' ? 'off' : 'on'">
            <i />{{ record.employmentStatus === 'LEFT' ? '离职' : '在任' }}
          </span>
        </template>

        <template #createdAt="{ record }">
          <span class="num time">{{ fmt(record.createdAt) }}</span>
        </template>

        <template #actions="{ record }">
          <template v-if="isAdmin && record.employmentStatus !== 'LEFT'">
            <a-space :size="10">
              <a-link @click="openEdit(record)">编辑</a-link>
              <a-link @click="openPassword(record)">重置密码</a-link>
              <a-dropdown trigger="click" position="br">
                <a-link>
                  更多
                  <icon-down style="font-size: 10px; margin-left: 2px" />
                </a-link>
                <template #content>
                  <a-doption @click="onToggleStatus(record)">
                    {{ record.status === 1 ? '停用账号' : '启用账号' }}
                  </a-doption>
                  <a-doption class="danger-option" @click="openLeave(record)">办理离职</a-doption>
                </template>
              </a-dropdown>
            </a-space>
          </template>
          <span v-else class="readonly">
            {{ record.employmentStatus === 'LEFT' ? '已离职 · 记录保留' : '只读' }}
          </span>
        </template>

        <template #empty>
          <div class="uc-empty">
            <div class="mark"><NavIcon name="users" :size="22" /></div>
            <div>没有符合条件的员工</div>
          </div>
        </template>
      </a-table>
    </a-card>

    <!-- 新增 / 编辑员工 -->
    <a-modal
      v-model:visible="editVisible"
      :title="editing ? '编辑员工' : '新增员工'"
      :width="500"
      :ok-text="editing ? '保存修改' : '创建员工'"
      @before-ok="onSubmit"
      @cancel="editVisible = false"
    >
      <a-form :model="form" layout="vertical">
        <a-form-item label="登录名" required>
          <a-input v-model="form.username" :disabled="!!editing" placeholder="例如 sales01" />
          <div v-if="editing" class="field-hint">登录名创建后不可修改</div>
        </a-form-item>
        <a-form-item v-if="!editing" label="初始密码" required>
          <a-input-password v-model="form.password" placeholder="6~32 位" />
        </a-form-item>
        <a-form-item label="真实姓名" required>
          <a-input v-model="form.realName" placeholder="例如 张伟" />
        </a-form-item>
        <a-form-item label="手机号">
          <a-input v-model="form.phone" placeholder="可留空" />
        </a-form-item>
        <a-form-item label="角色" required>
          <a-select v-model="form.role">
            <a-option value="ADMIN">管理员</a-option>
            <a-option value="SALES">销售</a-option>
            <a-option value="FINANCE">财务</a-option>
          </a-select>
          <div class="field-hint">管理员可以管理员工账号与门店设置；销售与财务使用业务功能</div>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 重置密码 -->
    <a-modal v-model:visible="passwordVisible" title="重置密码" :width="420" ok-text="确认重置" @before-ok="onResetPassword">
      <a-form :model="passwordForm" layout="vertical">
        <a-form-item :label="`为 ${passwordTargetName} 设置新密码`" required>
          <a-input-password v-model="passwordForm.newPassword" placeholder="6~32 位" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 办理离职与未完成车辆交接 -->
    <a-modal v-model:visible="leaveVisible" title="办理离职与车辆交接" :width="540" ok-text="确认办理离职" @before-ok="onLeave">
      <a-alert type="warning" style="margin-bottom: 14px">
        该员工名下还有 <b>{{ pendingCount }}</b> 辆未完成车辆（未清库），将全部转给接手人。
        原对接人、录入人、状态流水、申请单、聊天记录全部保留，只新增流转记录。
      </a-alert>
      <a-form :model="leaveForm" layout="vertical">
        <a-form-item label="接手员工" required>
          <a-select v-model="leaveForm.successorId" placeholder="选择在职且启用的员工" allow-clear>
            <a-option v-for="e in candidates" :key="e.id" :value="e.id">
              {{ e.realName || e.username }}（{{ roleText(e.role) }}）
            </a-option>
          </a-select>
          <div class="field-hint">离职员工本人不会出现在列表里</div>
        </a-form-item>
        <a-form-item label="流转原因">
          <a-input v-model="leaveForm.reason" :placeholder="`例如：${leaveTargetName} 离职交接`" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import NavIcon from '../components/NavIcon.vue'
import {
  getMe, listEmployees, createEmployee, updateEmployee,
  setEmployeeStatus, resetEmployeePassword, getPendingHandoverCount, leaveEmployee
} from '../api'

/**
 * 员工管理页。
 * 前端的 isAdmin 只用来隐藏按钮、改善体验，真正的权限校验在后端
 * EmployeeService.requireAdmin() —— 前端判断能被绕过，不能当安全边界。
 */
const isAdmin = ref(false)
const meId = ref(null)
const loading = ref(false)
const rows = ref([])
const keyword = ref('')
const employmentFilter = ref(undefined)
const editVisible = ref(false)
const passwordVisible = ref(false)
const editing = ref(null)
const passwordTargetId = ref(null)
const passwordTargetName = ref('')

const leaveVisible = ref(false)
const leaveTargetId = ref(null)
const leaveTargetName = ref('')
const pendingCount = ref(0)
const candidates = ref([])
const leaveForm = reactive({ successorId: undefined, reason: '' })

const form = reactive({ username: '', password: '', realName: '', phone: '', role: 'SALES' })
const passwordForm = reactive({ newPassword: '' })

const employmentChips = [
  { key: undefined, text: '全部员工', color: '#3457d5' },
  { key: 'ACTIVE', text: '在任', color: '#1f8a5b' },
  { key: 'LEFT', text: '离职', color: '#9aa3b5' }
]

const columns = [
  { title: '员工', slotName: 'employee', width: 220 },
  { title: '手机号', slotName: 'phone', width: 150 },
  { title: '角色', slotName: 'role', width: 110 },
  { title: '账号状态', slotName: 'status', width: 110 },
  { title: '在职状态', slotName: 'employment', width: 110 },
  { title: '创建时间', slotName: 'createdAt', width: 150 },
  { title: '操作', slotName: 'actions', width: 240 }
]

const ROLE_TEXT = { ADMIN: '管理员', SALES: '销售', FINANCE: '财务' }
const ROLE_CLS = { ADMIN: 'pill-blue', SALES: 'pill-gray', FINANCE: 'pill-amber' }

function roleText(role) {
  return ROLE_TEXT[role] || role
}
function roleCls(role) {
  return ROLE_CLS[role] || 'pill-gray'
}
function fmt(s) {
  return s ? String(s).replace('T', ' ').slice(0, 16) : '—'
}

/** 头像色相跟随员工 id，同一个人在车辆页和员工页颜色一致 */
function avatarStyle(record) {
  const hue = (Number(record.id) * 47) % 360
  return {
    background: `linear-gradient(150deg, hsl(${hue} 62% 58%), hsl(${hue} 52% 44%))`
  }
}

async function load() {
  loading.value = true
  try {
    rows.value = await listEmployees({
      keyword: keyword.value || undefined,
      employmentStatus: employmentFilter.value || undefined
    })
  } finally {
    loading.value = false
  }
}
function reload() {
  load()
}
function onEmployment(key) {
  employmentFilter.value = key
  load()
}
function onReset() {
  keyword.value = ''
  employmentFilter.value = undefined
  load()
}

async function openLeave(row) {
  leaveTargetId.value = row.id
  leaveTargetName.value = row.realName || row.username
  leaveForm.successorId = undefined
  leaveForm.reason = ''
  pendingCount.value = 0
  candidates.value = []
  try {
    pendingCount.value = await getPendingHandoverCount(row.id)
    const active = await listEmployees({ employmentStatus: 'ACTIVE' })
    // 离职员工本人、以及被停用的账号都不能当接手人（后端也会再校验一次）
    candidates.value = active.filter((e) => e.id !== row.id && e.status === 1)
  } catch (e) {
    // 提示已由拦截器弹出
  }
  leaveVisible.value = true
}

async function onLeave() {
  if (!leaveForm.successorId) {
    Message.warning('必须选择接手员工')
    return false
  }
  try {
    const r = await leaveEmployee(leaveTargetId.value, leaveForm.successorId, leaveForm.reason)
    Message.success(`已办理离职，${r.vehicleCount} 辆未完成车辆已流转给 ${r.successorName}`)
    leaveVisible.value = false
    await load()
  } catch (e) {
    // 提示已由拦截器弹出
  }
  return false
}

function openCreate() {
  editing.value = null
  Object.assign(form, { username: '', password: '', realName: '', phone: '', role: 'SALES' })
  editVisible.value = true
}

function openEdit(row) {
  editing.value = row.id
  Object.assign(form, {
    username: row.username, password: '', realName: row.realName,
    phone: row.phone || '', role: row.role
  })
  editVisible.value = true
}

async function onSubmit() {
  if (!form.realName.trim() || !form.role) {
    Message.warning('姓名和角色必填')
    return false
  }
  if (!editing.value && (!form.username.trim() || !form.password)) {
    Message.warning('登录名和初始密码必填')
    return false
  }
  try {
    if (editing.value) {
      await updateEmployee(editing.value, {
        realName: form.realName.trim(),
        phone: form.phone.trim(),
        role: form.role
      })
      Message.success('已保存')
    } else {
      await createEmployee({
        username: form.username.trim(),
        password: form.password,
        realName: form.realName.trim(),
        phone: form.phone.trim(),
        role: form.role
      })
      Message.success('员工已创建')
    }
    editVisible.value = false
    await load()
  } catch (e) {
    // 提示已由拦截器弹出
  }
  // 关闭时机由上面控制，不让 a-modal 自己关
  return false
}

async function onToggleStatus(row) {
  try {
    await setEmployeeStatus(row.id, row.status === 1 ? 0 : 1)
    Message.success(row.status === 1 ? '已停用' : '已启用')
    await load()
  } catch (e) {
    // 提示已由拦截器弹出
  }
}

function openPassword(row) {
  passwordTargetId.value = row.id
  passwordTargetName.value = row.realName || row.username
  passwordForm.newPassword = ''
  passwordVisible.value = true
}

async function onResetPassword() {
  if (!passwordForm.newPassword) {
    Message.warning('新密码不能为空')
    return false
  }
  try {
    await resetEmployeePassword(passwordTargetId.value, passwordForm.newPassword)
    Message.success('密码已重置')
    passwordVisible.value = false
  } catch (e) {
    // 提示已由拦截器弹出
  }
  return false
}

onMounted(async () => {
  try {
    const me = await getMe()
    isAdmin.value = me.role === 'ADMIN'
    meId.value = me.id ?? null
  } catch (e) {
    // 401 已由拦截器处理
  }
  load()
})
</script>

<style scoped>
.status-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.bar-spacer {
  flex: 1;
}
.search {
  width: 270px;
}
.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 34px;
  padding: 0 14px;
  border: 1px solid var(--uc-border);
  border-radius: 99px;
  background: #fff;
  color: var(--uc-text-2);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.16s ease;
}
.status-chip:hover {
  border-color: #c8d1ea;
  color: var(--uc-text);
}
.status-chip.active {
  background: var(--uc-ink);
  border-color: var(--uc-ink);
  color: #fff;
  box-shadow: 0 10px 22px -14px rgba(11, 18, 32, 0.9);
}
.chip-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex: none;
}
.status-chip.active .chip-dot {
  box-shadow: 0 0 0 3px rgba(255, 255, 255, 0.14);
}

.table-card :deep(.arco-card-body) {
  padding: 8px 20px 16px;
}
.staff-cell {
  display: flex;
  align-items: center;
  gap: 11px;
}
.staff-main {
  min-width: 0;
}
.staff-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--uc-text);
}
.me-tag {
  padding: 0 5px;
  border-radius: 4px;
  background: var(--uc-primary-soft);
  color: var(--uc-primary);
  font-size: 9.5px;
  line-height: 15px;
  font-weight: 500;
}
.staff-sub {
  margin-top: 2px;
  font-size: 11px;
  color: var(--uc-text-3);
}
.state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--uc-text-2);
}
.state i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.state.on i {
  background: #1f8a5b;
  box-shadow: 0 0 0 3px rgba(31, 138, 91, 0.12);
}
.state.off i {
  background: #b9c0cf;
  box-shadow: 0 0 0 3px rgba(185, 192, 207, 0.16);
}
.time {
  font-size: 12px;
  color: var(--uc-text-2);
}
.readonly {
  font-size: 12px;
  color: var(--uc-text-3);
}
.field-hint {
  margin-top: 4px;
  font-size: 11px;
  color: var(--uc-text-3);
  line-height: 1.5;
}
</style>
