<template>
  <div class="debug-preview-shell">
    <div class="debug-preview-shell-label">渲染预览（与 Chat 页一致）</div>
    <div class="debug-preview-shell-body">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.debug-preview-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  border: 1px solid var(--gray-100);
  border-radius: 8px;
  background: var(--color-canvas-soft);
  overflow: hidden;
}

.debug-preview-shell-label {
  flex-shrink: 0;
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-700);
  border-bottom: 1px solid var(--gray-100);
  background: var(--color-canvas);
}

.debug-preview-shell-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 12px 0 20px;
  background: var(--color-canvas);
}
</style>
