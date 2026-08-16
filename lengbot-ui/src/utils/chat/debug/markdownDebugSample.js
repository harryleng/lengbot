/** Debug Lab Markdown 模块完整测试样例 */
export const MARKDOWN_DEBUG_SAMPLE = `# Markdown 测试文档

本文档专为测试 Markdown 渲染效果而设计，旨在全面展示 Markdown 的各种语法及其渲染表现。

---

## 1. 文本样式

普通文本

**加粗文本**

*斜体文本*

***加粗 + 斜体文本***

~~删除线文本~~

\`行内代码\`

---

## 2. 引用

> 这是一段引用内容。
>> 引用可以有多行。
> 一级引用
>> > 二级引用
> >> > > 三级引用

---

## 3. 列表

### 无序列表

- 苹果
- 香蕉
- 橘子
  - 砂糖橘
  - 沃柑
- 西瓜

### 有序列表

1. 第一步
2. 第二步
3. 第三步
   1. 子步骤一
   2. 子步骤二

### 任务列表

- [x] 标题测试
- [x] 文本样式测试
- [x] 表格测试
- [ ] 图片测试
- [ ] Mermaid 测试

---

## 4. 链接与图片

[官网](https://bigrandall.io)

![示例图片](/uploads/Randall/9eTJSW8t9hfrw_Oq.png)

---

## 5. 表格

| 姓名 | 角色 | 状态 | 分数 |
|---|---|---:|---:|
| Randall | 工程师 | 活跃 | 95 |
| 张三 | 后端开发 | 正常 | 88 |
| 李四 | 前端开发 | 待评估 | 76 |

---

## 6. 代码块

### Bash

\`\`\`bash
echo "Hello Markdown"
mkdir markdown-test
cd markdown-test
\`\`\`

### JavaScript

\`\`\`javascript
function hello(name) {
  return \`Hello, \${name}\`;
}

console.log(hello("Randall"));
\`\`\`

### Python

\`\`\`python
def hello(name: str) -> str:
    return f"Hello, {name}"

print(hello("Randall"))
\`\`\`

### JSON

\`\`\`json
{
  "name": "markdown-test",
  "version": "1.0.0",
  "enabled": true,
  "items": ["title", "table", "code", "quote"]
}
\`\`\`

### YAML

\`\`\`yaml
app:
  name: markdown-test
  env: production
  debug: false

server:
  host: 0.0.0.0
  port: 3000
\`\`\`

---

## 7. 数学公式

行内公式：$E = mc^2$

块级公式：

$$a^2 + b^2 = c^2$$

---

## 8. Mermaid 流程图

\`\`\`mermaid
flowchart TD
    A[开始] --> B{是否通过测试}
    B -- 是 --> C[发布]
    B -- 否 --> D[修复问题]
    D --> B
\`\`\`

---

## 9. 折叠内容

<details>
<summary>点击展开详情</summary>

这里是折叠后的内容。

- 可以写列表
- 可以写说明
- 可以放代码块

\`\`\`bash
echo "这是折叠内容里的代码"
\`\`\`

</details>

---

## 10. HTML 混合测试

<div style="padding: 12px; border: 1px solid #ddd; border-radius: 8px;">
  <strong>这是 HTML 块</strong>
  <p>部分 Markdown 渲染器支持直接写 HTML。</p>
</div>

---

## 11. 分割线

---

***

___

---

## 12. 总结

如果你能正常看到：

- 标题层级
- 加粗、斜体、删除线
- 表格
- 代码高亮
- 图片
- 数学公式
- Mermaid 图
- 折叠内容

说明这个 Markdown 渲染器支持比较完整。

我来试一下 md 渲染
`
