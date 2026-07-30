---
name: yuque-writer
agent: BROWSER
description: 在语雀上创建/编辑文档、填写内容。当需要在语雀写文档、编辑文章、发布内容到语雀时触发。
priority: 5
---

# 语雀文档编辑

## 你必须使用以下工具
- browser_navigate / browser_new_page：打开/切换到语雀页面
- browser_snapshot：获取页面结构，查找可操作的元素
- browser_click：点击按钮、链接、编辑器区域
- browser_fill：填写普通 INPUT/TEXTAREA（如标题、描述）
- browser_type_text：逐字输入文本到已聚焦的编辑器
- browser_evaluate_script：JS 直接注入内容到富文本编辑器（长篇内容首选）
- browser_wait_for：等待页面加载或操作完成
- browser_press_key：键盘快捷键

---

## 核心规则（必须严格遵守）

### 1. 语雀页面结构认知
语雀文档编辑页面的典型布局（从上到下）：
- 顶部工具栏（加粗、斜体、插入等按钮）
- **标题区域**：一个普通的 INPUT 元素，placeholder 通常是"请输入标题"或"无标题"
- **正文编辑器**：标题下方的大片空白区域，这是一个 contenteditable 富文本编辑器

### 2. 填写标题
- 用 browser_snapshot 找到标题 INPUT 的 uid（通常是名字中包含"标题"或"title"的 textbox）
- 用 browser_fill 填写标题

### 3. 填写正文——最重要
正文编辑器是 contenteditable 富文本编辑器，不是 INPUT/TEXTAREA。

**❌ 禁止操作：**
- 禁止点击"会议记录"、"模板"、"知识库"、"导入"、"目录"——这些都不是编辑器！
- 禁止点击工具栏上的图标按钮（B/I/U/插入图片等）
- 禁止反复尝试不同的 uid 去 fill 正文——browser_fill 对 contenteditable 无效

**✅ 正确操作（方案A——JS注入，推荐，适合任何长度）：**
```
步骤1: browser_evaluate_script 查找编辑器
  const el = document.querySelector('[contenteditable="true"]');
  if (el) { return JSON.stringify({found: true, tag: el.tagName, className: el.className}); }
  return JSON.stringify({found: false});

步骤2: 确认找到后，browser_click 点击编辑器区域（用步骤1返回的 selector）
  或者在步骤1中直接获取编辑器的 aria 相关 uid

步骤3: browser_evaluate_script 写入内容
  注意：内容中的换行用 <br> 或 <p> 标签，引号和反斜杠要转义

  const el = document.querySelector('[contenteditable="true"]');
  if (!el) return 'EDITOR_NOT_FOUND';
  el.focus();
  el.innerHTML = '第一段内容<br><br>第二段内容';
  el.dispatchEvent(new Event('input', {bubbles: true}));
  el.dispatchEvent(new Event('change', {bubbles: true}));
  return 'CONTENT_INSERTED_OK';

步骤4: browser_snapshot 确认内容已显示在编辑器中
```

**✅ 正确操作（方案B——逐字输入，仅适合<500字短内容）：**
```
步骤1: browser_click 点击编辑器区域对应的 uid
步骤2: browser_type_text 输入文本
```

### 4. 发布/保存文档
- 填写完标题和正文后，查找并点击"发布"或"保存"按钮
- 用 browser_wait_for 等待"发布成功"或类似提示出现
- 用 browser_snapshot 确认最终结果

### 5. 创建新文档
- 如果用户需要创建新文档，先在语雀首页/知识库页面点击"新建"或"+"按钮
- 选择"文档"类型（不是"会议记录"、"表格"、"画板"等其他类型！）
- 等待文档编辑页加载后，再按上述流程填写标题和正文

---

## 常见错误速查

| 错误行为 | 为什么是错的 | 正确做法 |
|---------|------------|---------|
| 点"会议记录"按钮 | 那是模板按钮，不是正文编辑器 | 编辑器在页面中央大片的空白区域 |
| 反复用 browser_fill 试不同的 uid | contenteditable 不接受 fill | 用 browser_evaluate_script 注入 |
| 对 3000 字内容用 browser_type_text | 逐字输入太慢，会超时 | 用方案A（evaluate_script） |
| 填完标题就停 | 任务没完成 | 必须继续填写正文并发布 |
| 不先确认编辑器就写内容 | 可能写到错误位置 | 先用 evaluate_script 确认找到了 contenteditable 元素 |
