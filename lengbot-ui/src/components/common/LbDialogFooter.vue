<template>
  <div class="lb-dialog-footer">
    <div class="lb-dialog-footer__left"><slot name="left" /></div>
    <div class="lb-dialog-footer__right">
      <slot name="right" />
      <button v-if="!hideCancel" type="button" class="lb-btn" @click="emit('cancel')">{{ cancelText }}</button>
      <button
        v-if="!hideConfirm"
        type="button"
        class="lb-btn lb-btn--primary"
        :disabled="confirmDisabled || loading"
        @click="emit('confirm')"
      >
        <slot name="confirmContent">{{ loading ? loadingText : confirmText }}</slot>
      </button>
    </div>
  </div>
</template>

<script setup>
/**
 * 弹窗底部 Cancel/Confirm 组件
 * 统一项目中 18+ 处 .dialog-footer + .btn-cancel + .btn-primary-sm 的重复写法，
 * 默认按钮圆角统一为 pill 风格（与 .lb-btn 系统一致），避免圆角忽大忽小。
 */
const props = defineProps({
  cancelText: { type: String, default: '取消' },
  confirmText: { type: String, default: '确定' },
  loadingText: { type: String, default: '提交中...' },
  hideCancel: { type: Boolean, default: false },
  hideConfirm: { type: Boolean, default: false },
  confirmDisabled: { type: Boolean, default: false },
  loading: { type: Boolean, default: false },
})

const emit = defineEmits(['cancel', 'confirm'])
</script>
