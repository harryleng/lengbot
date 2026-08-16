import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { sseFetch, streamFetch } from '@/utils/sseFetch'

/**
 * 构造一个伪 ReadableStream，按 chunks 顺序推送字符串
 */
function makeReadableStream(chunks) {
  const encoder = new TextEncoder()
  return new ReadableStream({
    start(controller) {
      for (const c of chunks) controller.enqueue(encoder.encode(c))
      controller.close()
    },
  })
}

/**
 * 构造一个带 ReadableStream body 的伪 fetch Response
 */
function mockFetchResponse(chunks, init = {}) {
  const response = {
    ok: true,
    status: 200,
    body: makeReadableStream(chunks),
    ...init,
  }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
  return vi.mocked(globalThis.fetch)
}

describe('streamFetch', () => {
  beforeEach(() => {
    vi.stubEnv('node', 'test')
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('streams newline-delimited chunks via onLines callback', async () => {
    const onLines = vi.fn()
    mockFetchResponse(['data: hello\n', 'data: world\n'])
    await streamFetch('/api/x', { method: 'GET', onLines })
    expect(onLines).toHaveBeenCalledWith('data: hello')
    expect(onLines).toHaveBeenCalledWith('data: world')
  })

  it('buffers partial lines until newline arrives', async () => {
    const onLines = vi.fn()
    // 第一块没有 \n：应被缓冲，不触发回调
    mockFetchResponse(['data: partial', ' continued\n'])
    await streamFetch('/api/x', { onLines })
    expect(onLines).toHaveBeenCalledWith('data: partial continued')
  })

  it('flushes remaining buffer at stream end', async () => {
    const onLines = vi.fn()
    // 流末尾没有 \n：余下 buffer 也应作为最后一行回调
    mockFetchResponse(['data: no-newline-at-end'])
    await streamFetch('/api/x', { onLines })
    expect(onLines).toHaveBeenCalledWith('data: no-newline-at-end')
  })

  it('handles consecutive newlines as single chunk boundary', async () => {
    const onLines = vi.fn()
    mockFetchResponse(['data: ok\n\n'])
    await streamFetch('/api/x', { onLines })
    // streamFetch 按 \n 切段回调；多段空行不应触发空回调
    const calls = onLines.mock.calls.map((c) => c[0])
    // 至少回调一次（含 data: ok），不应包含纯空白
    expect(calls.some((c) => c.includes('data: ok'))).toBe(true)
    expect(calls.every((c) => c.trim() !== '')).toBe(true)
  })

  it('sends Authorization header when token provided', async () => {
    const fetchMock = mockFetchResponse(['x\n'])
    await streamFetch('/api/x', { token: 'Bearer abc', onLines: () => {} })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/x',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer abc' }),
      })
    )
  })

  it('sends JSON body with Content-Type when body provided', async () => {
    const fetchMock = mockFetchResponse(['x\n'])
    await streamFetch('/api/x', { method: 'POST', body: { foo: 1 }, onLines: () => {} })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/x',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ foo: 1 }),
      })
    )
  })

  it('throws on non-2xx response with status in message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500, json: async () => ({}) }))
    await expect(streamFetch('/api/x')).rejects.toThrow('流式请求失败: 500')
  })

  it('uses backend error message when body is JSON {code, message}', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 400,
        json: async () => ({ code: 400, message: '参数非法' }),
      })
    )
    await expect(streamFetch('/api/x')).rejects.toThrow('参数非法')
  })

  it('aborts silently when AbortError is thrown by fetch', async () => {
    const abortErr = new Error('aborted')
    abortErr.name = 'AbortError'
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(abortErr))
    // 不应抛出
    await expect(streamFetch('/api/x')).resolves.toBeUndefined()
  })
})

describe('sseFetch', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('parses standard SSE event:/data: into { event, data }', async () => {
    const onEvent = vi.fn()
    mockFetchResponse(['event: count\ndata: {"n":1}\n\n', 'event: count\ndata: {"n":2}\n\n'])
    sseFetch('/api/x', { onEvent })
    // sseFetch 异步执行，等一拍
    await new Promise((r) => setTimeout(r, 50))
    expect(onEvent).toHaveBeenCalledWith({ event: 'count', data: '{"n":1}' })
    expect(onEvent).toHaveBeenCalledWith({ event: 'count', data: '{"n":2}' })
  })

  it('calls onDone when stream ends', async () => {
    const onDone = vi.fn()
    mockFetchResponse(['data: x\n\n'])
    sseFetch('/api/x', { onEvent: () => {}, onDone })
    await new Promise((r) => setTimeout(r, 50))
    expect(onDone).toHaveBeenCalled()
  })

  it('close() aborts the underlying stream', async () => {
    const abortSpy = vi.spyOn(AbortController.prototype, 'abort')
    mockFetchResponse(['data: x\n\n'])
    const handle = sseFetch('/api/x', { onEvent: () => {} })
    handle.close()
    expect(abortSpy).toHaveBeenCalled()
    abortSpy.mockRestore()
  })
})
