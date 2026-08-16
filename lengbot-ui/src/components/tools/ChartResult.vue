<template>
  <BaseToolCall :tool-name="event.toolName" display-name="图表生成" :icon="BarChartOutlined" status="success">
    <template #result>
      <div v-if="chartUrl" class="chart-result">
        <img :src="chartUrl" alt="MCP 图表结果" @error="loadFailed = true" />
        <a v-if="!loadFailed" :href="chartUrl" target="_blank" rel="noopener noreferrer">在新窗口打开</a>
        <span v-else class="chart-error">图表图片加载失败，请检查 MCP 返回地址</span>
      </div>
      <pre v-else class="chart-fallback">{{ event.result || '图表 MCP 未返回可预览图片' }}</pre>
    </template>
  </BaseToolCall>
</template>

<script setup>
import { BarChartOutlined } from '@ant-design/icons-vue'
import { computed, ref } from 'vue'
import BaseToolCall from './BaseToolCall.vue'

const props = defineProps({
  event: { type: Object, required: true },
})

const loadFailed = ref(false)

const chartUrl = computed(() => {
  const raw = props.event.result
  if (typeof raw !== 'string' || !raw.trim()) return ''
  try {
    const parsed = JSON.parse(raw)
    if (Array.isArray(parsed)) {
      const image = parsed.find((item) => item?.type === 'image' && item?.url)
      if (image) return image.url
      const text = parsed.find((item) => item?.type === 'text' && /^https?:\/\//i.test(String(item.text || '')))
      if (text) return text.text
    }
  } catch {
    if (/^https?:\/\//i.test(raw.trim())) return raw.trim()
  }
  return ''
})
</script>

<style scoped>
.chart-result {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.chart-result img {
  display: block;
  max-width: 100%;
  max-height: 480px;
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  object-fit: contain;
}
.chart-result a {
  align-self: flex-start;
  color: var(--color-primary);
  font-size: 12px;
}
.chart-error {
  color: var(--color-error);
  font-size: 12px;
}
.chart-fallback {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
