/**
 * Skill Chat 渲染注册表（对齐 toolRegistry）
 * <p>内置 Skill slug 与后端 BuiltInSkillDefinitions 保持一致</p>
 */
import {
  ThunderboltOutlined,
  GlobalOutlined,
  SearchOutlined,
  CalculatorOutlined,
  PictureOutlined,
  TableOutlined,
  FileTextOutlined,
} from '@ant-design/icons-vue'
import { resolveIcon } from '@/utils/iconRegistry'
import { defineAsyncComponent } from 'vue'

/** skill_active SSE 事件类型 */
export const SKILL_ACTIVE_EVENT_TYPE = 'skill_active'

/** 内置 Skill slug 清单（与 BuiltInSkillDefinitions.java 同步） */
export const BUILTIN_SKILL_SLUGS = [
  'deep-research',
  'knowledge-grounded-qa',
  'calculator-precise',
  'image-create',
  'db-introspect',
]

const DefaultSkillItem = defineAsyncComponent(() => import('./items/DefaultSkillItem.vue'))

/**
 * Skill 列表项渲染组件（按 slug 可覆盖；未注册则用 DefaultSkillItem + SKILL_META）
 * 新增定制：在此映射 slug → 组件即可，无需改 Chat.vue
 */
export const SKILL_ITEM_RENDERERS = {
  // 示例：'deep-research': defineAsyncComponent(() => import('./items/DeepResearchSkillItem.vue')),
}

/**
 * Skill 元数据（展示名、描述、图标、主题色等）
 */
export const SKILL_META = {
  'deep-research': {
    slug: 'deep-research',
    displayName: '深度研究',
    description: '多轮联网检索与结构化整理，适合调研报告与行业分析。',
    hint: '将拆分检索子问题并标注信息出处',
    icon: GlobalOutlined,
    tag: '调研',
    accent: '#2563eb',
    bg: 'var(--blue-50)',
    border: 'var(--blue-200)',
    builtin: true,
  },
  'knowledge-grounded-qa': {
    slug: 'knowledge-grounded-qa',
    displayName: '知识库严谨问答',
    description: '基于绑定知识库回答，避免凭空发挥。',
    hint: '未命中时会明确告知并保守回答',
    icon: SearchOutlined,
    tag: '知识库',
    accent: '#059669',
    bg: 'var(--green-50)',
    border: 'var(--green-200)',
    builtin: true,
  },
  'calculator-precise': {
    slug: 'calculator-precise',
    displayName: '精确数值计算',
    description: '数值运算强制调用计算器，避免口算偏差。',
    hint: '多步运算将分步调用 calculator',
    icon: CalculatorOutlined,
    tag: '计算',
    accent: '#d97706',
    bg: 'var(--color-warn-bg)',
    border: 'var(--color-warning-soft)',
    builtin: true,
  },
  'image-create': {
    slug: 'image-create',
    displayName: '图片创作',
    description: '生成插画、海报、示意图等视觉产物。',
    hint: '意图将转为英文提示词后生成图像',
    icon: PictureOutlined,
    tag: '图像',
    accent: '#9333ea',
    bg: 'var(--color-purple-bg)',
    border: 'var(--color-purple-border)',
    builtin: true,
  },
  'db-introspect': {
    slug: 'db-introspect',
    displayName: '数据库探查',
    description: '安全查询 PostgreSQL 元信息与样本数据。',
    hint: '仅允许 SELECT，默认 LIMIT 20',
    icon: TableOutlined,
    tag: '数据库',
    accent: '#0891b2',
    bg: '#ecfeff',
    border: '#a5f3fc',
    builtin: true,
  },
}

const DEFAULT_SKILL_META = {
  slug: '',
  displayName: 'Skill',
  description: '',
  hint: '',
  icon: ThunderboltOutlined,
  tag: '',
  accent: '#db2777',
  bg: 'var(--color-purple-bg)',
  border: '#f9a8d4',
  builtin: false,
}

/** 从 skill 对象解析 slug（兼容 name / slug 字段） */
export function resolveSkillSlug(skill) {
  if (!skill) return ''
  const raw = skill.slug || skill.name || ''
  return String(raw).trim().toLowerCase()
}

/** 从后端 icon 字符串（Ant Design 图标名）解析图标组件，无法解析返回 null */
function resolveIconComponent(iconName) {
  return resolveIcon(iconName)
}

/** 获取 Skill 元数据（未知 slug 回退默认） */
export function getSkillMeta(skillOrSlug) {
  const slug = typeof skillOrSlug === 'string' ? skillOrSlug.trim().toLowerCase() : resolveSkillSlug(skillOrSlug)
  // 后端配置的图标（Ant Design 图标名）优先于内置注册表图标
  const backendIcon = typeof skillOrSlug === 'object' && skillOrSlug ? resolveIconComponent(skillOrSlug.icon) : null
  if (slug && SKILL_META[slug]) {
    return backendIcon ? { ...SKILL_META[slug], icon: backendIcon } : SKILL_META[slug]
  }
  if (typeof skillOrSlug === 'object' && skillOrSlug) {
    return {
      ...DEFAULT_SKILL_META,
      slug,
      displayName: skillOrSlug.displayName || skillOrSlug.name || slug || 'Skill',
      description: skillOrSlug.description || '',
      hint: skillOrSlug.hint || '',
      icon: backendIcon || DEFAULT_SKILL_META.icon,
      builtin: !!skillOrSlug.builtin,
    }
  }
  return { ...DEFAULT_SKILL_META, slug }
}

export function getSkillIcon(skillOrSlug) {
  return getSkillMeta(skillOrSlug).icon || FileTextOutlined
}

export function getSkillDisplayName(skillOrSlug) {
  if (typeof skillOrSlug === 'object' && skillOrSlug) {
    return skillOrSlug.displayName || skillOrSlug.name || resolveSkillSlug(skillOrSlug) || 'Skill'
  }
  return getSkillMeta(skillOrSlug).displayName
}

/** 列表项渲染组件 */
export function getSkillItemRenderer(skillOrSlug) {
  const slug = typeof skillOrSlug === 'string' ? skillOrSlug.trim().toLowerCase() : resolveSkillSlug(skillOrSlug)
  if (slug && SKILL_ITEM_RENDERERS[slug]) {
    return SKILL_ITEM_RENDERERS[slug]
  }
  return DefaultSkillItem
}

export function hasSkillItemRenderer(slug) {
  if (!slug) return false
  return Object.prototype.hasOwnProperty.call(SKILL_ITEM_RENDERERS, String(slug).trim().toLowerCase())
}

export function isBuiltinSkillSlug(slug) {
  return BUILTIN_SKILL_SLUGS.includes(
    String(slug || '')
      .trim()
      .toLowerCase()
  )
}

export function formatSkillActiveTitle(skills) {
  const count = Array.isArray(skills) ? skills.length : 0
  return count > 0 ? `已启用 ${count} 个 Skill` : 'Skill 准备中'
}

export function formatSkillActiveStatus(skills) {
  return formatSkillActiveTitle(skills)
}
