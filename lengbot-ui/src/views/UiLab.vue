<script setup>
/**
 * UI Lab —— shadcn 迁移验证页（Phase 0/1/2 验收用）
 *
 * 目的：把 antd 组件与 shadcn 组件并排渲染，直观确认两者共存无冲突：
 * 1. antd 组件样式未被 Tailwind 影响（无 preflight 生效）
 * 2. shadcn 组件的 Tailwind 工具类正常生成并渲染
 * 3. 切换深色模式时两者同步变化
 *
 * 该页面为独立路由 /ui-lab，不影响任何现有业务页面。
 */
import { ref } from 'vue'
import {
  Button,
  Badge,
  Input,
  Textarea,
  Label,
  Separator,
  Skeleton,
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  CardFooter,
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
  DialogClose,
} from '@/components/ui'

const dark = ref(document.documentElement.getAttribute('data-theme') === 'dark')

function toggleDark() {
  dark.value = !dark.value
  document.documentElement.setAttribute('data-theme', dark.value ? 'dark' : 'light')
}

const dialogOpen = ref(false)
const inputValue = ref('')
const promptValue = ref('你是一个严谨的代码审查助手。')

/* 模拟真实后台的智能体列表数据 */
const agents = [
  { name: '代码审查助手', desc: '自动 Review PR 并给出改进建议', status: 'running', model: 'GPT-4o' },
  { name: '日报生成器', desc: '汇总每日提交记录生成研发日报', status: 'stopped', model: 'Claude 3.5' },
  { name: '异常告警分析', desc: '解析线上错误日志并定位根因', status: 'error', model: 'GPT-4o-mini' },
]

const statusMap = {
  running: { variant: 'success', text: '运行中' },
  stopped: { variant: 'secondary', text: '已停止' },
  error: { variant: 'destructive', text: '异常' },
}
</script>

<template>
  <div class="ui-lab">
    <header class="lab-header">
      <h2>UI Lab · antd 与 shadcn 共存验证</h2>
      <a-button @click="toggleDark">切换{{ dark ? '浅色' : '深色' }}</a-button>
    </header>

    <section class="lab-section">
      <h3 class="lab-section-title">1. antd 组件（应保持原有样式）</h3>
      <a-space wrap>
        <a-button type="primary">Primary</a-button>
        <a-button>Default</a-button>
        <a-button type="dashed">Dashed</a-button>
        <a-button danger>Danger</a-button>
        <a-input placeholder="antd 输入框" style="width: 200px" />
        <a-tag color="blue">Tag</a-tag>
      </a-space>
    </section>

    <section class="lab-section">
      <h3 class="lab-section-title">2. Button（6 变体 × 4 尺寸）</h3>
      <div class="flex flex-wrap items-center gap-2">
        <Button>Default</Button>
        <Button variant="secondary">Secondary</Button>
        <Button variant="outline">Outline</Button>
        <Button variant="ghost">Ghost</Button>
        <Button variant="destructive">Destructive</Button>
        <Button variant="link">Link</Button>
      </div>
      <div class="mt-3 flex items-center gap-2">
        <Button size="sm">Small</Button>
        <Button size="default">Default</Button>
        <Button size="lg">Large</Button>
        <Button size="icon" aria-label="添加">+</Button>
      </div>
    </section>

    <section class="lab-section">
      <h3 class="lab-section-title">3. Badge（含 success / warning 语义色）</h3>
      <div class="flex flex-wrap items-center gap-2">
        <Badge>Default</Badge>
        <Badge variant="secondary">Secondary</Badge>
        <Badge variant="outline">Outline</Badge>
        <Badge variant="success">运行中</Badge>
        <Badge variant="warning">待审核</Badge>
        <Badge variant="destructive">异常</Badge>
      </div>
    </section>

    <section class="lab-section">
      <h3 class="lab-section-title">4. 表单组件（Input / Textarea / Label）</h3>
      <div class="flex max-w-md flex-col gap-4">
        <div class="flex flex-col gap-2">
          <Label for="lab-name">智能体名称</Label>
          <Input id="lab-name" v-model="inputValue" placeholder="例如：代码审查助手" />
        </div>
        <div class="flex flex-col gap-2">
          <Label for="lab-prompt">系统提示词</Label>
          <Textarea id="lab-prompt" v-model="promptValue" :rows="3" />
        </div>
        <p class="text-xs text-muted-foreground">当前输入：{{ inputValue || '(空)' }}</p>
      </div>
    </section>

    <section class="lab-section">
      <h3 class="lab-section-title">5. Card（真实场景：智能体列表）</h3>
      <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <Card v-for="agent in agents" :key="agent.name">
          <CardHeader>
            <div class="flex items-start justify-between gap-2">
              <CardTitle>{{ agent.name }}</CardTitle>
              <Badge :variant="statusMap[agent.status].variant">
                {{ statusMap[agent.status].text }}
              </Badge>
            </div>
            <CardDescription>{{ agent.desc }}</CardDescription>
          </CardHeader>
          <CardContent>
            <div class="flex items-center gap-2 text-xs text-muted-foreground">
              <span>模型</span>
              <Separator orientation="vertical" class="h-3" />
              <span class="font-medium text-foreground">{{ agent.model }}</span>
            </div>
          </CardContent>
          <CardFooter class="gap-2">
            <Button size="sm" variant="outline">配置</Button>
            <Button size="sm" variant="ghost">日志</Button>
          </CardFooter>
        </Card>
      </div>
    </section>

    <section class="lab-section">
      <h3 class="lab-section-title">6. Skeleton（loading 态）</h3>
      <Card class="max-w-sm">
        <CardHeader>
          <Skeleton class="h-4 w-32" />
          <Skeleton class="h-3 w-full" />
        </CardHeader>
        <CardContent>
          <Skeleton class="h-3 w-24" />
        </CardContent>
      </Card>
    </section>

    <section class="lab-section">
      <h3 class="lab-section-title">7. Dialog（reka-ui，含焦点陷阱 / ESC 关闭）</h3>
      <Button @click="dialogOpen = true">打开对话框</Button>
      <Dialog v-model:open="dialogOpen">
        <DialogContent>
          <DialogHeader>
            <DialogTitle>删除智能体</DialogTitle>
            <DialogDescription>此操作不可撤销，该智能体的配置与历史会话将被永久删除。</DialogDescription>
          </DialogHeader>
          <div class="text-sm text-foreground">请确认要删除「代码审查助手」。</div>
          <DialogFooter>
            <DialogClose as-child>
              <Button variant="outline">取消</Button>
            </DialogClose>
            <Button variant="destructive" @click="dialogOpen = false">确认删除</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>

    <section class="lab-section">
      <h3 class="lab-section-title">8. Tailwind 工具类渲染检查</h3>
      <div class="flex flex-wrap gap-3">
        <div class="rounded-lg border border-border bg-card px-4 py-3 text-sm text-foreground">
          bg-card / text-foreground / rounded-lg
        </div>
        <div class="rounded-lg bg-primary px-4 py-3 text-sm text-primary-foreground">
          bg-primary / text-primary-foreground
        </div>
        <div class="rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">bg-muted / text-muted-foreground</div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.ui-lab {
  padding: 24px;
  max-width: 1080px;
  margin: 0 auto;
  color: var(--color-ink);
  background: var(--color-canvas);
  min-height: 100vh;
}
.lab-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}
.lab-section {
  margin-bottom: 32px;
  padding: 16px;
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
}
/**
 * ⚠️ 这里【必须】用独立类名而非裸标签选择器 `.lab-section h3`。
 *
 * 原因：Vue 的 scoped 样式会把父组件 scope-id 打到【子组件根元素】上，
 * 因此 `.lab-section h3[data-v-xxx]`（权重 0,2,1）会命中并压过
 * CardTitle 内部的 `.text-base`（权重 0,1,0），导致组件字号被页面样式劫持。
 *
 * 迁移到 shadcn 时，老页面里所有针对裸标签（h1~h6/p/span/button）的
 * scoped 规则都要按同样方式改成类选择器，否则会静默污染组件。
 */
.lab-section-title {
  margin: 0 0 12px;
  font-size: 14px;
  color: var(--color-mute);
}
</style>
