<template>
  <span v-if="normalizedTags.length === 0" class="lb-tag-list--empty">{{ emptyText }}</span>
  <div v-else class="lb-tag-list">
    <a-tag v-for="(tag, idx) in normalizedTags" :key="tag + '_' + idx" :color="color" :closable="false">
      {{ tag }}
    </a-tag>
  </div>
</template>

<script setup>
/**
 * 标签列表展示
 * 统一项目中 9+ 处 tags.split(',') + <a-tag color="blue"> 的重复写法。
 * 支持三种输入：字符串（逗号切分）/ 数组 / JSON 字符串（自动 parse）。
 * 与 TagInput（编辑端）配套使用，形成标签的"读/写"双向统一。
 */
import { computed } from 'vue'

const props = defineProps({
  tags: { type: [String, Array], default: '' },
  color: { type: String, default: 'blue' },
  emptyText: { type: String, default: '' },
  max: { type: Number, default: 0 },
})

const normalizedTags = computed(() => {
  const list = normalize(props.tags)
  return props.max > 0 ? list.slice(0, props.max) : list
})

function normalize(raw) {
  if (raw == null || raw === '') return []
  if (Array.isArray(raw)) {
    return raw.map((t) => String(t).trim()).filter((t) => t.length > 0)
  }
  let str = String(raw).trim()
  if (!str) return []
  if (str.startsWith('[') || str.startsWith('{')) {
    try {
      const parsed = JSON.parse(str)
      if (Array.isArray(parsed)) {
        return parsed.map((t) => String(t).trim()).filter((t) => t.length > 0)
      }
    } catch {
      // JSON 解析失败时降级为字符串切分
    }
  }
  return str
    .split(/[,，]/)
    .map((t) => t.trim())
    .filter((t) => t.length > 0)
}
</script>
