<template>
  <component :is="renderer" :skill="skill" :meta="meta" />
</template>

<script setup>
import { computed } from 'vue'
import { getSkillItemRenderer, getSkillMeta } from './skillRegistry.js'

const props = defineProps({
  skill: { type: Object, required: true },
})

const meta = computed(() => getSkillMeta(props.skill))
const renderer = computed(() => getSkillItemRenderer(props.skill))
</script>
