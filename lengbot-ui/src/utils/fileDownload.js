/**
 * 触发浏览器下载（不打开新标签页预览）
 * @param {string} url 下载地址（建议带 Content-Disposition: attachment）
 * @param {string} [filename] 建议文件名
 */
export function triggerBrowserDownload(url, filename = '') {
  if (!url) return
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.rel = 'noopener noreferrer'
  if (filename) {
    anchor.download = filename
  }
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
}

/**
 * 从 Blob 触发下载
 * @param {Blob} blob
 * @param {string} filename
 */
export function downloadBlob(blob, filename) {
  const objectUrl = URL.createObjectURL(blob)
  try {
    triggerBrowserDownload(objectUrl, filename)
  } finally {
    setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
  }
}
