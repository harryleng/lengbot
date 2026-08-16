import request from '../utils/request'

export function getUserPreferences() {
  return request.get('/user/preferences')
}

export function updateUserPreferences(data) {
  return request.put('/user/preferences', data)
}
