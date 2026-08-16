import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { message, notification } from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
// Tailwind v4（迁移 Phase 0）：必须放在 antd reset 之后，避免冲突
import './styles/tailwind.css'
import './styles/variables.css'
import './styles/scrollbar.css'
import './styles/code-block-scrollbar.css'
import './styles/admin-page.css'
import './styles/provider-card.css'
import './styles/ui-utils.css'
import './styles/modal-scroll.css'
import './styles/lb-components.css'

import App from './App.vue'
import router from './router'
import { captureException, installGlobalErrorHandlers } from './utils/errorReport'
import { installModalScrollObserver } from './utils/modalScroll'

// 限制全局最多显示3条消息提示，防止堆叠
message.config({ maxCount: 3, duration: 2 })
// 通知右上角，最多堆叠3条，默认4秒消失
notification.config({ placement: 'topRight', duration: 4, maxCount: 3, top: '64px' })

const app = createApp(App)

// 全局 Vue 错误兜底：组件未捕获异常由这里捕获，避免整页白屏
app.config.errorHandler = (err, instance, info) => {
  captureException(err, {
    source: 'vue.errorHandler',
    info,
    componentTag: instance?.$options?.__name || instance?.$options?.name,
    route: router.currentRoute.value?.fullPath,
  })
  // 用户可见的轻提示（不打断流程，详细信息走 captureException 落盘）
  if (import.meta.env.PROD) {
    message.error('页面发生异常，请重试或刷新')
  }
}

// 全局兜底：捕获 Vue 之外的异常（setTimeout / Promise reject / 资源加载失败）
installGlobalErrorHandlers()

app.use(createPinia())
app.use(router)
// Ant Design Vue 组件通过 unplugin-vue-components 自动按需引入
app.mount('#app')

// 弹窗滚动条动态监听：装在 mount 之后，确保初始 modal body 已就绪
installModalScrollObserver()
