import request from '../utils/request'

export function listUserMemories(params = {}) {
  return request.get('/user/memories', { params })
}

export function createUserMemory(data) {
  return request.post('/user/memories', data)
}

export function updateUserMemory(id, data) {
  return request.put(`/user/memories/${id}`, data)
}

export function updateUserMemoryStatus(id, status) {
  return request.put(`/user/memories/${id}/status`, { status })
}

export function deleteUserMemory(id) {
  return request.delete(`/user/memories/${id}`)
}
