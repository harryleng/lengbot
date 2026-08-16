import { describe, it, expect } from 'vitest'
import { safeJsonParse } from '@/utils/request'

describe('safeJsonParse', () => {
  it('returns null for invalid JSON', () => {
    expect(safeJsonParse('not-json')).toBeNull()
    expect(safeJsonParse('{invalid')).toBeNull()
  })

  it('parses normal JSON unchanged', () => {
    expect(safeJsonParse('{"name":"lengbot","age":3}')).toEqual({ name: 'lengbot', age: 3 })
  })

  it('passes through non-string input', () => {
    const obj = { foo: 1 }
    expect(safeJsonParse(obj)).toBe(obj)
    expect(safeJsonParse(null)).toBeNull()
    expect(safeJsonParse(42)).toBe(42)
  })

  it('converts Long (>= 16 digits) numeric ids to string to prevent precision loss', () => {
    // 雪花算法 ID 通常为 18-19 位，超过 JS Number.MAX_SAFE_INTEGER (2^53 = 16 位)
    const json = '{"id":2056961707612393473,"name":"foo"}'
    const parsed = safeJsonParse(json)
    expect(parsed.id).toBe('2056961707612393473')
    expect(parsed.name).toBe('foo')
  })

  it('keeps short numbers as numbers', () => {
    const json = '{"id":42,"count":1234567}'
    const parsed = safeJsonParse(json)
    expect(parsed.id).toBe(42)
    expect(parsed.count).toBe(1234567)
  })

  it('handles negative Long IDs', () => {
    const json = '{"id":-2056961707612393473}'
    const parsed = safeJsonParse(json)
    expect(parsed.id).toBe('-2056961707612393473')
  })

  it('does not touch numbers embedded in string values', () => {
    const json = '{"content":"ID is 2056961707612393473 here"}'
    const parsed = safeJsonParse(json)
    expect(parsed.content).toBe('ID is 2056961707612393473 here')
  })

  it('handles nested objects with Long IDs', () => {
    const json = '{"user":{"id":2056961707612393473},"agent":{"id":2056961707612393474}}'
    const parsed = safeJsonParse(json)
    expect(parsed.user.id).toBe('2056961707612393473')
    expect(parsed.agent.id).toBe('2056961707612393474')
  })

  it('handles arrays of Long IDs', () => {
    const json = '{"ids":[2056961707612393473,2056961707612393474,2056961707612393475]}'
    const parsed = safeJsonParse(json)
    expect(parsed.ids).toEqual(['2056961707612393473', '2056961707612393474', '2056961707612393475'])
  })
})
