/**
 * Debug Lab Fixture 导出 / 导入（纯前端文件）
 */

/**
 * 下载 JSON fixture
 * @param {object} payload
 * @param {string} [filename]
 */
export function downloadDebugFixture(payload, filename = 'chat-debug-fixture.json') {
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

/**
 * 从 File 读取 fixture
 * @param {File} file
 * @returns {Promise<object>}
 */
export function readDebugFixtureFile(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      try {
        resolve(JSON.parse(String(reader.result)))
      } catch (e) {
        reject(new Error('Fixture JSON 解析失败'))
      }
    }
    reader.onerror = () => reject(new Error('文件读取失败'))
    reader.readAsText(file)
  })
}

/** 构建标准 fixture 结构 */
export function buildDebugFixture({ message, uiState, label = 'debug-fixture' }) {
  return {
    version: 1,
    label,
    exportedAt: new Date().toISOString(),
    message,
    uiState: uiState || {},
  }
}
