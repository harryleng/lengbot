<template>
  <div class="message assistant">
    <div class="message-body">
      <div class="message-content status-content">
        <div v-if="statusBadges.length" class="status-loading">
          <span class="status-spinner"></span>
          <div class="status-badges">
            <span v-for="badge in statusBadges" :key="badge.key" class="status-badge" :class="`is-${badge.status}`">
              <span class="status-badge-dot" />
              {{ badge.label }}
            </span>
          </div>
        </div>
        <span v-else class="status-shimmer">{{ statusText || '正在思考...' }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  statusText: { type: String, default: '' },
  statusBadges: { type: Array, default: () => [] },
})
</script>

<style scoped>
.message {
  padding: 12px 32px;
  max-width: 800px;
  margin: 0 auto;
  width: 100%;
}
.message-body {
  min-width: 0;
  width: 100%;
}
.message-content {
  font-size: 15px;
  line-height: 1.7;
  color: var(--color-ink);
  word-break: break-word;
}
.status-content {
  display: flex;
  align-items: center;
}
.status-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: var(--color-canvas-soft-2);
  border-radius: 12px;
  animation: fadeIn 0.3s ease;
  flex-wrap: wrap;
}
@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
.status-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--color-hairline);
  border-top-color: var(--color-link);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  flex-shrink: 0;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
/* 无子智能体徽章时：不包裹气泡，纯文字 + 流光呼吸动画 */
.status-shimmer {
  font-size: 15px;
  font-weight: 500;
  background: linear-gradient(
    90deg,
    var(--color-mute) 0%,
    var(--color-mute) 35%,
    var(--color-ink) 50%,
    var(--color-mute) 65%,
    var(--color-mute) 100%
  );
  background-size: 200% 100%;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  animation: shimmerText 1.8s linear infinite;
}
@keyframes shimmerText {
  from {
    background-position: 200% 0;
  }
  to {
    background-position: -200% 0;
  }
}
.status-badges {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--color-canvas-soft);
  color: var(--color-body);
}
.status-badge.is-running,
.status-badge.is-pending {
  color: var(--color-warning-deep);
  background: color-mix(in srgb, var(--color-warning) 14%, transparent);
}
.status-badge.is-completed {
  color: #15803d;
  background: rgba(34, 197, 94, 0.16);
}
.status-badge.is-failed {
  color: var(--color-error-deep);
  background: color-mix(in srgb, var(--color-error) 12%, transparent);
}
.status-badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}
.status-badge.is-running .status-badge-dot {
  animation: pulseDot 1.2s ease-in-out infinite;
}
@keyframes pulseDot {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.45;
    transform: scale(0.85);
  }
}
</style>
