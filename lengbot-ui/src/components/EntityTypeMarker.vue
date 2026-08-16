<template>
  <component :is="icon" class="entity-type-marker" />
</template>

<script setup>
import { computed } from 'vue'
import {
  BookOutlined,
  FileTextOutlined,
  AppstoreOutlined,
  DatabaseOutlined,
  AuditOutlined,
} from '@ant-design/icons-vue'

const props = defineProps({ type: { type: String, required: true } })

const icon = computed(
  () =>
    ({
      knowledge: BookOutlined,
      prompt: FileTextOutlined,
      template: AppstoreOutlined,
      dataset: DatabaseOutlined,
      evaluator: AuditOutlined,
    })[props.type] || FileTextOutlined
)
</script>

<style scoped>
.entity-type-marker {
  display: inline-block;
  margin-right: 6px;
  font-size: 18px;
  color: var(--color-mute);
  vertical-align: -0.14em;
}
</style>
