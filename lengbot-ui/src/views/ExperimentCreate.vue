<template>
  <div class="detail-page">
    <LbDetailHeader
      title="创建实验"
      :breadcrumb="[{ label: '实验', onClick: () => router.push('/app/eval') }]"
      @back="router.push('/app/eval')"
    />

    <div class="detail-page__body">
      <div class="create-card">
        <ExperimentCreateForm :label-span="4" @success="onCreateSuccess">
          <template #cancel>
            <button class="btn-cancel" @click="router.push('/app/eval')">取消</button>
          </template>
        </ExperimentCreateForm>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { ExperimentOutlined } from '@ant-design/icons-vue'
import ExperimentCreateForm from '../components/eval/ExperimentCreateForm.vue'
import LbDetailHeader from '../components/common/LbDetailHeader.vue'

const router = useRouter()

function onCreateSuccess() {
  router.push('/app/eval?tab=experiments')
}
</script>

<style scoped>
.page-header {
  margin-bottom: 24px;
}
.btn-back {
  background: none;
  border: none;
  color: var(--color-mute);
  cursor: pointer;
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 8px;
}
.btn-back:hover {
  color: var(--color-link);
}
.page-title {
  font-size: 24px;
  font-weight: 600;
  color: var(--color-ink);
  margin: 0 0 4px;
}
.page-desc {
  font-size: 14px;
  color: var(--color-mute);
  margin: 0;
}
.create-card {
  background: var(--color-canvas);
  border: 1px solid var(--color-hairline);
  border-radius: 12px;
  padding: 32px;
  max-width: 800px;
}
.btn-cancel {
  padding: 10px 20px;
  background: transparent;
  border: 1px solid var(--color-hairline);
  border-radius: 100px;
  cursor: pointer;
  font-size: 14px;
}
.btn-cancel:hover {
  border-color: var(--color-link);
  color: var(--color-link);
}
</style>
