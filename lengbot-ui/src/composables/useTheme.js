import { ref, computed, watch } from 'vue'
import { theme } from 'ant-design-vue'

const saved = localStorage.getItem('lengbot-theme')
const isDark = ref(saved === 'dark' || (!saved && window.matchMedia('(prefers-color-scheme: dark)').matches))

export function useTheme() {
  const themeConfig = computed(() => ({
    // 双主题：浅色奶油用 defaultAlgorithm，深色拿铁用 darkAlgorithm（token 覆写为暖棕）
    algorithm: isDark.value ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: {
      // 品牌主色：焦糖 #C98A5E（浅/深共用暖调）
      colorPrimary: '#C98A5E',
      colorLink: isDark.value ? '#E0A878' : '#B5754A',
      colorLinkHover: '#9A5E38',
      borderRadius: 10,
      borderRadiusLG: 16,
      borderRadiusSM: 8,
      fontFamily: 'var(--font-sans)',
      fontSize: 14,
      controlHeight: 34,
      wireframe: false,
      // 下拉项选中态背景（焦糖淡底）
      controlItemBgActive: isDark.value ? 'rgba(216, 154, 110, 0.20)' : 'rgba(201, 138, 94, 0.14)',
      controlItemBgActiveHover: isDark.value ? 'rgba(216, 154, 110, 0.28)' : 'rgba(201, 138, 94, 0.20)',
      controlItemBgHover: isDark.value ? 'rgba(216, 154, 110, 0.12)' : 'rgba(201, 138, 94, 0.08)',
      ...(isDark.value
        ? {
            // 拿铁棕深色
            colorBgContainer: '#2E281F',
            colorBgElevated: '#362F24',
            colorBgLayout: '#1E1A15',
            colorBorder: 'rgba(230, 210, 180, 0.18)',
            colorText: '#F2E9DA',
            colorTextSecondary: '#B6A488',
          }
        : {
            // 奶油浅色
            colorBgContainer: '#FFFDF7',
            colorBgElevated: '#FFFDF7',
            colorBgLayout: '#F6EFE2',
            colorBorder: 'rgba(201, 138, 94, 0.20)',
            colorText: '#5A4A38',
            colorTextSecondary: '#8A7558',
          })
    },
    components: {
      Button: {
        borderRadius: 999,
        fontWeight: 500,
        primaryShadow: 'none',
        defaultShadow: 'none',
      },
      Modal: { borderRadiusLG: 16 },
      Tabs: {
        inkBarColor: '#C98A5E',
        itemSelectedColor: '#C98A5E',
        itemHoverColor: '#B5754A',
        itemActiveColor: '#5A4A38',
        titleFontSize: 14,
      },
      Table: {
        headerBg: 'transparent',
        headerSplitColor: 'transparent',
        rowHoverBg: isDark.value ? 'rgba(216, 154, 110, 0.10)' : 'rgba(201, 138, 94, 0.08)',
      },
      Card: { borderRadiusLG: 16 },
      Input: { borderRadius: 10 },
      InputNumber: { borderRadius: 10 },
      Select: { borderRadius: 10 },
      Cascader: { borderRadius: 10 },
      DatePicker: { borderRadius: 10 },
      Pagination: {
        itemActiveBg: '#C98A5E',
        itemActiveColor: '#ffffff',
        itemActiveColorDisabled: 'rgba(255,255,255,0.6)',
      },
      Tooltip: { borderRadius: 8, fontSize: 12 },
      Notification: { borderRadiusLG: 12 },
      Drawer: { borderRadiusLG: 16 },
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
