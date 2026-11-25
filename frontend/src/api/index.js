import http from './http'
import { Message } from '@arco-design/web-vue'

/**
 * 接口封装。
 *
 * 全部集中在这一个文件里，而不是各页面自己拼 URL。
 * 好处：后端改路径时只改这一处；参数名写错在联调前就能发现。
 */

/**
 * 车辆写操作统一返回 WriteResult：ADMIN 直接执行、员工落申请单。
 * 提示只在 api 层弹一次，页面只判断 res.executed 决定要不要继续跳详情页。
 */
const wrapWrite = (promise) => promise.then((res) => {
  if (res && res.executed === false) {
    Message.info(res.message || '已提交申请，等待管理员审批')
  }
  return res
})

// ---------- 登录 ----------
export const login = (data) => http.post('/auth/login', data)
export const logout = () => http.post('/auth/logout')
export const getMe = () => http.get('/auth/me')

// ---------- 车辆 ----------
export const pageVehicles = (params) => http.get('/vehicles', { params })
export const getVehicle = (id) => http.get(`/vehicles/${id}`)
export const createVehicle = (data) => wrapWrite(http.post('/vehicles', data))
export const updateVehicle = (id, data) => wrapWrite(http.put(`/vehicles/${id}`, data))
export const changeStatus = (id, data) => wrapWrite(http.post(`/vehicles/${id}/status`, data))
export const addCost = (id, data) => wrapWrite(http.post(`/vehicles/${id}/costs`, data))
export const listCosts = (id) => http.get(`/vehicles/${id}/costs`)
export const getPricing = (id) => http.get(`/vehicles/${id}/pricing`)
export const changePrice = (id, data) => wrapWrite(http.post(`/vehicles/${id}/price-change`, data))

// ---------- 证件 ----------
export const listCertificates = (vehicleId) => http.get(`/vehicles/${vehicleId}/certificates`)
export const createCertificate = (vehicleId, data) =>
  wrapWrite(http.post(`/vehicles/${vehicleId}/certificates`, data))
export const listExpiringCertificates = (params) => http.get('/certificates/expiring', { params })

// ---------- 预警 ----------
export const listAlerts = () => http.get('/alerts')
export const countAlerts = () => http.get('/alerts/count')
export const handleAlert = (id, status) => http.post(`/alerts/${id}/handle`, null, { params: { status } })
export const inspectAlerts = () => http.post('/alerts/inspect')

// ---------- 成交订单与售后 ----------
export const listCustomers = (params) => http.get('/customers', { params })
export const listSaleOrders = (vehicleId) => http.get(`/vehicles/${vehicleId}/sale-orders`)
export const createSaleOrder = (vehicleId, data) =>
  wrapWrite(http.post(`/vehicles/${vehicleId}/sale-orders`, data))
export const listAfterSales = (vehicleId) => http.get(`/vehicles/${vehicleId}/after-sales`)
export const createAfterSale = (vehicleId, data) =>
  wrapWrite(http.post(`/vehicles/${vehicleId}/after-sales`, data))
export const closeAfterSale = (id) => wrapWrite(http.post(`/after-sales/${id}/close`))

// ---------- 员工 ----------
// GET 列表只要登录（车辆页要拿它做姓名映射和对接人下拉），写操作由后端校验 ADMIN
export const listEmployees = (params) => http.get('/employees', { params })
export const createEmployee = (data) => http.post('/employees', data)
export const updateEmployee = (id, data) => http.put(`/employees/${id}`, data)
export const setEmployeeStatus = (id, status) =>
  http.post(`/employees/${id}/status`, null, { params: { status } })
export const resetEmployeePassword = (id, newPassword) =>
  http.post(`/employees/${id}/password`, { newPassword })
export const getPendingHandoverCount = (id) => http.get(`/employees/${id}/pending-handover-count`)
export const leaveEmployee = (id, successorId, reason) =>
  http.post(`/employees/${id}/leave`, { successorId, reason })
export const listVehicleHandovers = (vehicleId) => http.get(`/vehicles/${vehicleId}/handovers`)

// ---------- 车辆审批 ----------
// 注意 confirmAiCard 不走 wrapWrite：AI 收车页自己按 executed 弹不同提示
export const listApprovals = (scope, status) =>
  http.get('/approvals', { params: { scope, status } })
export const getApprovalSpec = () => http.get('/approvals/spec')
export const approveApproval = (id, remark) => http.post(`/approvals/${id}/approve`, { remark })
export const rejectApproval = (id, remark) => http.post(`/approvals/${id}/reject`, { remark })
export const resubmitApproval = (id, payload) => http.post(`/approvals/${id}/resubmit`, { payload })
export const cancelApproval = (id) => http.post(`/approvals/${id}/cancel`)

// ---------- AI 收车 ----------
// 发消息要等大模型返回，可能超过 http 实例默认的 30 秒；
// 后端 SiliconFlowClient 的读超时是 60 秒，这里留到 90 秒，别让前端先断。
export const listAiConversations = () => http.get('/ai/conversations')
export const createAiConversation = () => http.post('/ai/conversations')
export const getAiConversation = (id) => http.get(`/ai/conversations/${id}`)
// 执行轨迹：这个会话每次工具调用的工具名、参数、查询语句、结果条数与耗时
export const listAiTraces = (id) => http.get(`/ai/conversations/${id}/traces`)
export const sendAiMessage = (id, content) =>
  http.post(`/ai/conversations/${id}/messages`, { content }, { timeout: 90000 })

/**
 * 流式发送，走 fetch 而不是 axios。
 *
 * 为什么不能用 axios：浏览器端 axios 走 XHR，拿不到可读的 ReadableStream，
 * 只能等整个响应结束，等于没做流式。fetch 才能 reader.read() 逐块拿。
 *
 * 事件由后端 SseEmitter 决定：
 *   event: delta  data: {"text":"增量"}
 *   event: done   data: { conversationId, reply, card }
 *   event: error  data: {"message":"..."}
 */
export async function sendAiMessageStream(id, content, handlers = {}) {
  const resp = await fetch(`/api/ai/conversations/${id}/messages/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      satoken: localStorage.getItem('satoken') || ''
    },
    body: JSON.stringify({ content })
  })
  if (!resp.ok || !resp.body) {
    throw new Error(`流式请求失败（HTTP ${resp.status}）`)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    // stream: true 让多字节中文被切断时不会乱码，剩下的字节留在 decoder 里
    buffer += decoder.decode(value, { stream: true })
    // 必须先把 CRLF 归一成 LF 再找空行：SSE 规范允许 \r\n\r\n 分隔，
    // 某些代理也会改写换行。不归一的话 indexOf('\n\n') 永远匹配不到边界，
    // 所有增量会一直堆在缓冲区，直到流结束才当一整块解析，等于没有流式。
    // 跨 chunk 被切断的 \r\n 也没问题：\r 留在 buffer 里，下次拼接后照样被替换。
    buffer = buffer.replace(/\r\n/g, '\n')
    let cut
    while ((cut = buffer.indexOf('\n\n')) >= 0) {
      dispatchSse(buffer.slice(0, cut), handlers)
      buffer = buffer.slice(cut + 2)
    }
  }
  if (buffer.trim()) dispatchSse(buffer, handlers)
}

function dispatchSse(raw, handlers) {
  const chunk = raw.replace(/\r/g, '')
  let event = 'message'
  let data = ''
  for (const line of chunk.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data += line.slice(5).trim()
  }
  if (!data) return
  let payload
  try {
    payload = JSON.parse(data)
  } catch {
    return
  }
  if (event === 'delta') handlers.onDelta?.(payload.text || '')
  else if (event === 'done') handlers.onDone?.(payload)
  else if (event === 'error') handlers.onError?.(new Error(payload.message || '服务端处理失败'))
}

export const patchAiCard = (id, data) => http.patch(`/ai/conversations/${id}/card`, data)
export const confirmAiCard = (id) => http.post(`/ai/conversations/${id}/card/confirm`)
export const discardAiCard = (id) => http.post(`/ai/conversations/${id}/card/discard`)
