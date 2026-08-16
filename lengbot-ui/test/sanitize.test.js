import { describe, it, expect } from 'vitest'
import { sanitizeHtml, sanitizeGraphHtml, escapeHtml } from '@/utils/sanitize'

describe('sanitizeHtml', () => {
  it('returns empty string for falsy input', () => {
    expect(sanitizeHtml('')).toBe('')
    expect(sanitizeHtml(null)).toBe('')
    expect(sanitizeHtml(undefined)).toBe('')
  })

  it('strips <script> tags', () => {
    const evil = '<script>alert("xss")</script><p>hello</p>'
    expect(sanitizeHtml(evil)).not.toContain('<script>')
    expect(sanitizeHtml(evil)).toContain('<p>hello</p>')
  })

  it('strips inline event handlers', () => {
    const evil = '<img src="x" onerror="alert(1)">'
    const result = sanitizeHtml(evil)
    expect(result).not.toContain('onerror')
  })

  it('strips javascript: URLs', () => {
    const evil = '<a href="javascript:alert(1)">click</a>'
    const result = sanitizeHtml(evil)
    expect(result).not.toContain('javascript:')
  })

  it('preserves allowed attributes (class/style/target)', () => {
    const html = '<a href="https://example.com" class="link" target="_blank" rel="noopener">x</a>'
    const result = sanitizeHtml(html)
    expect(result).toContain('class="link"')
    expect(result).toContain('target="_blank"')
  })

  it('preserves <input> tag via ADD_TAGS', () => {
    const html = '<input type="checkbox" checked disabled>'
    const result = sanitizeHtml(html)
    expect(result).toContain('<input')
  })
})

describe('sanitizeGraphHtml', () => {
  it('returns empty string for falsy input', () => {
    expect(sanitizeGraphHtml('')).toBe('')
    expect(sanitizeGraphHtml(null)).toBe('')
  })

  it('only allows whitelist tags (div/span/p/strong/em/br/b/i/ul/ol/li)', () => {
    const html = '<div><script>alert(1)</script><p>ok</p><iframe src="x"></iframe></div>'
    const result = sanitizeGraphHtml(html)
    expect(result).not.toContain('<script>')
    expect(result).not.toContain('<iframe')
    expect(result).toContain('<p>ok</p>')
  })

  it('strips inline event handlers even if not in FORBID_ATTR', () => {
    const evil = '<div onmouseenter="alert(1)">hover</div>'
    expect(sanitizeGraphHtml(evil)).not.toContain('onmouseenter')
  })

  it('allows class and inline style attribute', () => {
    const html = '<div class="node-label" style="color: red">text</div>'
    const result = sanitizeGraphHtml(html)
    expect(result).toContain('class="node-label"')
    expect(result).toContain('color: red')
  })

  it('preserves class attribute', () => {
    const html = '<div class="node-label">text</div>'
    expect(sanitizeGraphHtml(html)).toContain('class="node-label"')
  })
})

describe('escapeHtml', () => {
  it('returns empty string for null/undefined', () => {
    expect(escapeHtml(null)).toBe('')
    expect(escapeHtml(undefined)).toBe('')
  })

  it('escapes all 5 special characters', () => {
    expect(escapeHtml('<div class="a" data-x=\'b\'>Tom & Jerry</div>')).toBe(
      '&lt;div class=&quot;a&quot; data-x=&#39;b&#39;&gt;Tom &amp; Jerry&lt;/div&gt;'
    )
  })

  it('escapes string representation of numbers/objects', () => {
    expect(escapeHtml(42)).toBe('42')
    expect(escapeHtml({ toString: () => '<x>' })).toBe('&lt;x&gt;')
  })
})
