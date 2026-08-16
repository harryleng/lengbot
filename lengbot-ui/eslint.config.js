// ESLint 9 flat config（vue3 + prettier 整合）
import js from '@eslint/js'
import vue from 'eslint-plugin-vue'
import vueParser from 'vue-eslint-parser'
import prettier from '@vue/eslint-config-prettier'
import globals from 'globals'

const { configs: jsConfigs } = js

export default [
  {
    ignores: ['dist/**', 'node_modules/**', '*.config.js', 'public/**'],
  },
  jsConfigs.recommended,
  ...vue.configs['flat/recommended'],
  {
    files: ['**/*.{vue,js,mjs,cjs}'],
    languageOptions: {
      parser: vueParser,
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module',
      },
      globals: {
        ...globals.browser,
        ...globals.node,
        ...globals.es2021,
        defineProps: 'readonly',
        defineEmits: 'readonly',
        defineExpose: 'readonly',
        withDefaults: 'readonly',
        __VUE_OPTIONS_API__: 'readonly',
        __VUE_PROD_DEVTOOLS__: 'readonly',
        import: 'readonly',
        importMeta: 'readonly',
      },
    },
    rules: {
      // Vue 单文件组件允许单词命名（如 index.vue / Chat.vue）
      'vue/multi-word-component-names': 'off',
      // v-html 警告而非禁止：知识图谱 / Markdown 渲染确有需求，已在 sanitize 阶段过滤
      'vue/no-v-html': 'warn',
      // defineProps 运行时校验已足够，不强求 default
      'vue/require-default-prop': 'off',
      // template attribute 不强制排序，避免对存量代码产生大量改动
      'vue/attributes-order': 'off',
      // 允许单词 + 短语组件名混用
      'vue/component-definition-name-casing': 'off',
      // 业务大量使用未使用变量做接口契约占位（如 catch (e) 不访问 e）
      'no-unused-vars': ['warn', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      // 允许 console.warn / console.error 在生产环境保留（错误上报通道）；
      // console.info / console.log 视为 warning（应替换为业务日志或删除）
      'no-console': ['warn', { allow: ['warn', 'error'] }],
      'no-debugger': 'warn',
    },
  },
  prettier,
]
