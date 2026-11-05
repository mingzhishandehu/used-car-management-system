import http from './http'

/**
 * 接口封装。
 *
 * 全部集中在这一个文件里，而不是各页面自己拼 URL。
 * 好处：后端改路径时只改这一处；参数名写错在联调前就能发现。
 */

// ---------- 登录 ----------
export const login = (data) => http.post('/auth/login', data)
export const logout = () => http.post('/auth/logout')
export const getMe = () => http.get('/auth/me')

// ---------- 车辆 ----------
export const pageVehicles = (params) => http.get('/vehicles', { params })
export const getVehicle = (id) => http.get(`/vehicles/${id}`)
export const createVehicle = (data) => http.post('/vehicles', data)
export const updateVehicle = (id, data) => http.put(`/vehicles/${id}`, data)
export const changeStatus = (id, data) => http.post(`/vehicles/${id}/status`, data)
export const addCost = (id, data) => http.post(`/vehicles/${id}/costs`, data)
export const listCosts = (id) => http.get(`/vehicles/${id}/costs`)
export const getPricing = (id) => http.get(`/vehicles/${id}/pricing`)

// ---------- 证件 ----------
export const listCertificates = (vehicleId) => http.get(`/vehicles/${vehicleId}/certificates`)
export const createCertificate = (vehicleId, data) => http.post(`/vehicles/${vehicleId}/certificates`, data)
export const listExpiringCertificates = (params) => http.get('/certificates/expiring', { params })

// ---------- 预警 ----------
export const listAlerts = () => http.get('/alerts')
export const countAlerts = () => http.get('/alerts/count')
export const handleAlert = (id, status) => http.post(`/alerts/${id}/handle`, null, { params: { status } })
export const inspectAlerts = () => http.post('/alerts/inspect')

// ---------- 视频任务 ----------
export const submitVideoTask = (data) => http.post('/videos/tasks', data)
export const getVideoTask = (id) => http.get(`/videos/tasks/${id}`)
export const getVideoAssets = (id) => http.get(`/videos/tasks/${id}/assets`)
export const listVideoTasks = (vehicleId) => http.get('/videos/tasks', { params: { vehicleId } })
