<template>
  <pre class="lb-json-viewer">{{ formatted }}</pre>
</template>

<script setup>
/**
 * 只读 JSON 展示
 * 统一项目中 10+ 处各自定义本地 formatJson + mi-pre / tn-json / exec-json 的重复写法。
 * 编辑态 JSON 用 JsonInput.vue，本组件只负责只读渲染。
 */
import { computed } from 'vue'
import { formatJson } from '../../utils/format'

const props = defineProps({
  value: { type: [String, Object, Array, Number, Boolean], default: '' },
  emptyText: { type: String, default: '-' },
})

const formatted = computed(() => {
  if (props.value == null || props.value === '') return props.emptyText
  const out = formatJson(props.value)
  return out === '' ? props.emptyText : out
})
</script>
