<template>
  <div class="dh-root">
    <!-- 控制条：静音 / 停止（右上角浮层） -->
    <div class="dh-controls">
      <button
        class="dh-ctrl-btn"
        :class="{ 'dh-ctrl-muted': muted }"
        :title="muted ? '取消静音' : '静音'"
        @click="$emit('toggle-mute')"
      >
        <SoundOutlined />
      </button>
      <button class="dh-ctrl-btn" title="停止播报" @click="$emit('stop')">
        <StopOutlined />
      </button>
    </div>

    <!-- 状态徽标 -->
    <div
      v-if="state === 'thinking' || state === 'speaking'"
      class="dh-badge"
      :class="'dh-badge--' + state"
    >
      {{ state === 'thinking' ? '思考中…' : '播报中' }}
    </div>

    <!-- 底部 TTS 引擎切换条：运行时切换后端引擎（edge-tts / mock），无需刷新 -->
    <div v-if="engineOptions.length" class="dh-engine-bar">
      <span class="dh-engine-label">TTS 引擎</span>
      <a-select
        :value="engineValue"
        class="dh-engine-select"
        size="small"
        :options="engineOptions.map((p) => ({ label: p, value: p }))"
        @change="(v) => $emit('update:engine', v)"
      />
    </div>

    <!-- 真实引擎（未来）：直接播放口型视频 -->
    <video v-if="videoUrl" class="dh-video" :src="videoUrl" autoplay loop playsinline></video>

    <!-- 占位版：形象图 + 前端口型动画 -->
    <div v-else class="dh-stage" :class="{ 'is-speaking': speaking, 'is-thinking': state === 'thinking' }">
      <div class="dh-figure">
        <img v-if="portraitUrl" class="dh-portrait" :src="portraitUrl" alt="数字人形象" />
        <div v-else class="dh-portrait dh-portrait--empty">形象</div>

        <div class="dh-mouth" :style="mouthStyle">
          <svg viewBox="0 0 100 60" preserveAspectRatio="none">
            <ellipse cx="50" cy="30" rx="42" ry="22" />
          </svg>
        </div>
      </div>
      <div v-if="agentName" class="dh-name">{{ agentName }}</div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { SoundOutlined, StopOutlined } from '@ant-design/icons-vue'

const props = defineProps({
  portraitUrl: { type: String, default: '' },
  speaking: { type: Boolean, default: false },
  // 嘴部定位（相对形象图的百分比）：{ x, y, w, h }
  mouthZone: { type: Object, default: null },
  // 真实引擎生成的口型视频地址（占位版无需）
  videoUrl: { type: String, default: '' },
  agentName: { type: String, default: '' },
  // 面板状态：idle | thinking | speaking
  state: { type: String, default: 'idle' },
  // 是否已静音
  muted: { type: Boolean, default: false },
  // 当前生效的 TTS 引擎（Provider 名称），由父组件传入
  engineValue: { type: String, default: '' },
  // 全部可选 TTS 引擎名称列表（如 ['edge-tts','mock']）
  engineOptions: { type: Array, default: () => [] },
})

defineEmits(['toggle-mute', 'stop', 'update:engine'])

const DEFAULT_MOUTH = { x: 38, y: 60, w: 24, h: 14 }

const mouthStyle = computed(() => {
  const z = props.mouthZone || DEFAULT_MOUTH
  return {
    left: `${z.x}%`,
    top: `${z.y}%`,
    width: `${z.w}%`,
    height: `${z.h}%`,
  }
})
</script>

<style scoped>
.dh-root {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: radial-gradient(circle at 50% 35%, #1f2a44 0%, #0d1322 70%);
  border-radius: 12px;
  overflow: hidden;
}

.dh-video {
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: #000;
}

.dh-stage {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.dh-figure {
  position: relative;
  width: 78%;
  max-width: 320px;
  aspect-ratio: 3 / 4;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.45);
  animation: dh-breathe 4.5s ease-in-out infinite;
  background: #11182b;
}

/* 思考态：轻微呼吸光晕，提示“正在理解” */
.dh-figure.is-thinking {
  animation: dh-breathe 4.5s ease-in-out infinite, dh-think-glow 1.8s ease-in-out infinite;
}

.dh-portrait {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  user-select: none;
  -webkit-user-drag: none;
}

.dh-portrait--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #6b7891;
  font-size: 14px;
  background: #1a2238;
}

.dh-mouth {
  position: absolute;
  pointer-events: none;
}

.dh-mouth svg {
  width: 100%;
  height: 100%;
  display: block;
  fill: #5b1320;
  transform-origin: center;
  transform: scaleY(0.16);
  transition: transform 0.06s linear;
}

.dh-stage.is-speaking .dh-mouth svg {
  animation: dh-mouth-flap 0.2s infinite;
}

.dh-name {
  margin-top: 12px;
  color: #cdd6ea;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

/* ===== 控制条 ===== */
.dh-controls {
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 5;
  display: flex;
  gap: 6px;
}

.dh-ctrl-btn {
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 8px;
  background: rgba(13, 19, 34, 0.6);
  color: #cdd6ea;
  font-size: 15px;
  cursor: pointer;
  backdrop-filter: blur(4px);
  transition: background 0.15s, color 0.15s;
}

.dh-ctrl-btn:hover {
  background: rgba(40, 56, 92, 0.8);
  color: #fff;
}

.dh-ctrl-muted {
  color: #6b7891;
  opacity: 0.7;
}

/* ===== 状态徽标 ===== */
.dh-badge {
  position: absolute;
  top: 12px;
  left: 12px;
  z-index: 5;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.5px;
  color: #fff;
  backdrop-filter: blur(4px);
}

.dh-badge--thinking {
  background: rgba(56, 92, 160, 0.55);
  border: 1px solid rgba(120, 160, 230, 0.5);
}

.dh-badge--speaking {
  background: rgba(30, 150, 110, 0.55);
  border: 1px solid rgba(80, 210, 160, 0.5);
}

/* ===== 底部 TTS 引擎切换条 ===== */
.dh-engine-bar {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 6;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: linear-gradient(to top, rgba(8, 12, 22, 0.92), rgba(8, 12, 22, 0));
  backdrop-filter: blur(2px);
}

.dh-engine-label {
  color: #cdd6ea;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.dh-engine-select {
  width: 140px;
}

@keyframes dh-breathe {
  0%,
  100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.015);
  }
}

@keyframes dh-think-glow {
  0%,
  100% {
    box-shadow: 0 12px 40px rgba(0, 0, 0, 0.45), 0 0 0 0 rgba(120, 160, 230, 0);
  }
  50% {
    box-shadow: 0 12px 40px rgba(0, 0, 0, 0.45), 0 0 22px 2px rgba(120, 160, 230, 0.45);
  }
}

@keyframes dh-mouth-flap {
  0%,
  100% {
    transform: scaleY(0.16);
  }
  50% {
    transform: scaleY(1);
  }
}
</style>
