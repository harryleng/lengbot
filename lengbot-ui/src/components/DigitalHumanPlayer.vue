<template>
  <div class="dh-root">
    <!-- 真实引擎（未来）：直接播放口型视频 -->
    <video v-if="videoUrl" class="dh-video" :src="videoUrl" autoplay loop playsinline></video>

    <!-- 占位版：形象图 + 前端口型动画 -->
    <div v-else class="dh-stage" :class="{ 'is-speaking': speaking }">
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

const props = defineProps({
  portraitUrl: { type: String, default: '' },
  speaking: { type: Boolean, default: false },
  // 嘴部定位（相对形象图的百分比）：{ x, y, w, h }
  mouthZone: { type: Object, default: null },
  // 真实引擎生成的口型视频地址（占位版无需）
  videoUrl: { type: String, default: '' },
  agentName: { type: String, default: '' },
})

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

@keyframes dh-breathe {
  0%,
  100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.015);
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
