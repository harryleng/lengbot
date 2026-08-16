<template>
  <div v-if="blockEvents.length > 0" class="capability-panel">
    <component
      v-for="entry in blockEvents"
      :key="entry.key"
      :is="entry.renderer"
      :event="entry.event"
      :calls="entry.calls"
      :events="events"
      :all-events="allEvents || events"
      :event-index="entry.index"
      :is-done="entry.isDone ?? isDone"
      :stream-finished="streamFinished"
      :default-expanded="defaultExpanded"
      @height-change="onHeightChange"
    />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { getCapabilityBlockRenderer } from './capabilityRegistry.js'
import { SUBAGENT_CALL_EVENT_TYPE } from './subagentRegistry.js'
import { getSubagentBlockKey, isSubagentBlockDone } from './subagentEventUtils.js'

const props = defineProps({
  events: { type: Array, default: () => [] },
  allEvents: { type: Array, default: null },
  isDone: { type: Boolean, default: true },
  /** 主消息流式已结束（或历史消息），用于控制「查看返回 JSON」等仅完成后展示的 UI */
  streamFinished: { type: Boolean, default: true },
  defaultExpanded: { type: Boolean, default: true },
})

const emit = defineEmits(['heightChange'])

const blockEvents = computed(() => {
  const scopedEvents = props.events || []
  const allEvents = props.allEvents || scopedEvents
  const result = []
  const subagentGroups = new Map()

  for (let index = 0; index < scopedEvents.length; index++) {
    const event = scopedEvents[index]
    const renderer = getCapabilityBlockRenderer(event?.type)
    if (!renderer) continue

    if (event.type === SUBAGENT_CALL_EVENT_TYPE) {
      const blockKey = getSubagentBlockKey(event)
      if (!subagentGroups.has(blockKey)) {
        const group = {
          key: `subagent-block-${blockKey}`,
          renderer,
          event,
          calls: [event],
          index,
          isDone: null,
        }
        subagentGroups.set(blockKey, group)
        result.push(group)
      } else {
        const group = subagentGroups.get(blockKey)
        group.calls.push(event)
        group.event = event
      }
      continue
    }

    result.push({
      event,
      index,
      renderer,
      calls: null,
      key: `${event.type}-${event.contentOffset ?? 'top'}-${index}`,
      isDone: null,
    })
  }

  for (const group of subagentGroups.values()) {
    group.isDone = isSubagentBlockDone(allEvents, group.calls, !props.streamFinished)
  }

  return result
})

function onHeightChange(evt) {
  emit('heightChange', evt)
}
</script>

<style scoped>
.capability-panel {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}
</style>
