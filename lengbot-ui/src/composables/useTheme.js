import { ref, computed, watch } from 'vue'
import { theme } from 'ant-design-vue'

const saved = localStorage.getItem('lengbot-theme')
const isDark = ref(saved === 'dark' || (!saved && window.matchMedia('(prefers-color-scheme: dark)').matches))

export function useTheme() {
  const themeConfig = computed(() => ({
    algorithm: theme.darkAlgorithm,
    token: {
      // AgentScope 绿作为品牌主色：antd primary 按钮 / 链接 / 选中高亮统一走绿
      colorPrimary: '#6366F1',
      colorLink: '#22D3EE',
      colorLinkHover: '#0EA5C4',
      borderRadius: 8,
      borderRadiusLG: 12,
      borderRadiusSM: 6,
      fontFamily: 'var(--font-sans)',
      fontSize: 14,
      controlHeight: 32,
      wireframe: false,
      // controlItemBgActive 是 Select / Cascader / DatePicker / Menu 等组件的"选中态背景"全局别名，
      // 默认从 colorPrimaryBg 派生。本项目 colorPrimary=#171717 会导致派生色为暗灰，
      // 下拉项选中态在浅色模式下也会变成深色。显式覆盖为蓝色淡背景，与配色一致。
      controlItemBgActive: 'rgba(34, 211, 238, 0.14)',
      controlItemBgActiveHover: 'rgba(34, 211, 238, 0.22)',
      controlItemBgHover: 'rgba(34, 211, 238, 0.08)',
      ...{
            colorBgContainer: '#0C1024',
            colorBgElevated: '#11162E',
            colorBgLayout: '#070A18',
            colorBorder: 'rgba(34, 211, 238, 0.16)',
            colorText: '#E6EDFF',
            colorTextSecondary: '#9CAAC9',
          }
    },
    components: {
      Button: {
        borderRadius: 100,
        fontWeight: 500,
        primaryShadow: 'none',
        defaultShadow: 'none',
      },
      Modal: {
        borderRadiusLG: 12,
      },
      Tabs: {
        // 浅色模式用近黑高亮，深色模式切到浅色保证对比度
        inkBarColor: '#22D3EE',
        itemSelectedColor: '#22D3EE',
        itemHoverColor: '#A78BFA',
        itemActiveColor: '#E6EDFF',
        titleFontSize: 14,
      },
      Table: {
        headerBg: 'transparent',
        headerSplitColor: 'transparent',
        // 浅色用黑色 2% 半透，深色用白色 4% 半透
        rowHoverBg: isDark.value ? 'rgba(255,255,255,0.04)' : 'rgba(0,0,0,0.02)',
      },
      Card: {
        borderRadiusLG: 12,
      },
      Input: { borderRadius: 6 },
      InputNumber: { borderRadius: 6 },
      // Select 下拉项选中态：由根 token controlItemBgActive 统一覆盖（见上方 token 块）
      // 选中项的勾选图标 colorPrimary 会保持近黑，文字色由 colorText 派生即可。
      Select: { borderRadius: 6 },
      Cascader: { borderRadius: 6 },
      DatePicker: { borderRadius: 6 },
      Pagination: {
        // 浅色激活态用近黑底，深色用中灰底；文字始终为反色
        itemActiveBg: '#6366F1',
        itemActiveColor: '#ffffff',
        itemActiveColorDisabled: 'rgba(255,255,255,0.35)',
      },
      Tooltip: {
        borderRadius: 6,
        fontSize: 12,
      },
      Notification: {
        borderRadiusLG: 8,
      },
      Drawer: {
        borderRadiusLG: 12,
      },
    },
  }))

  function toggleTheme() {
    isDark.value = !isDark.value
  }

  watch(
    isDark,
    (dark) => {
      document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light')
      localStorage.setItem('lengbot-theme', dark ? 'dark' : 'light')
    },
    { immediate: true }
  )

  return { isDark, themeConfig, toggleTheme }
}
