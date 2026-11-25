/**
 * 枚举与标签映射。
 *
 * 前端不做业务判断，只做"把英文枚举翻译成人话"。
 * 状态的中文含义以后端为准，这里只是展示用的字典，改错不影响业务。
 */

export const VEHICLE_STATUS = {
  PURCHASED: { text: '已收车', color: 'arcoblue' },
  ON_SALE: { text: '在售', color: 'green' },
  SOLD: { text: '已成交', color: 'purple' },
  AFTER_SALE: { text: '售后', color: 'orange' },
  CLEARED: { text: '已清库', color: 'gray' }
}

export const SLOW_LEVEL = {
  NORMAL: { text: '正常', color: 'green' },
  ATTENTION: { text: '滞销关注', color: 'orange' },
  WARN: { text: '滞销', color: 'orangered' },
  DANGER: { text: '严重滞销', color: 'red' }
}

export const ALERT_TYPE = {
  SLOW_MOVING: '滞销',
  CERT_EXPIRE: '证件到期',
  LOW_MARGIN: '毛利过低'
}

export const ALERT_LEVEL = {
  INFO: { text: '提示', color: 'gray' },
  WARN: { text: '警告', color: 'orange' },
  DANGER: { text: '严重', color: 'red' }
}

export const COST_TYPE = {
  PURCHASE: '收车款',
  RECONDITION: '整备',
  TRANSPORT: '运输',
  TRANSFER: '过户',
  OTHER: '其他'
}

/** 金额格式化：12345.6 → 12,345.60 */
export function money(value) {
  if (value === null || value === undefined || value === '') return '-'
  return Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
