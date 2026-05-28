# 2026-05-28 设计稿 · 提交前 Todo 编辑 + Markdown 流式渲染 + HITL 内联

> 对应需求：[docs/requirements/2026-05-28.md](../../requirements/2026-05-28.md)（R1 / R2 / R3）
> 技术栈基线：见仓库根 [CLAUDE.md](../../../CLAUDE.md) 第 2 节。
> 当前进度：Day 6 + Day 7 §4 §5 已落地（详见 [docs/learning.md](../../learning.md)）。

## 1. 范围与目标

把 R1 / R2 / R3 三条需求拆成可独立验收的交付：

| 交付 | 目标 | 涉及层 |
|---|---|---|
| D1 | 补齐 Todo 编辑工具集，使"对话改 Todo"覆盖应用名 / 模块增删 / 字段增删 / 字段改类型 | 后端 |
| D2 | 接入 `markstream-vue`，assistant 气泡走流式 markdown 渲染 | 前端 |
| D3 | 删 `HitlConfirmModal.vue`，HITL 确认改为 chat 内联卡片；新增 `cancel_submission` 工具支撑"取消 = 清空"语义 | 前后端 |

**范围外**（明确不做）：

- subs 嵌套字段的 update/delete
- LLM 不调 `cancel_submission` 的兜底自动 clear（依赖 prompt 工程约束）
- markstream-vue 的 Monaco / Mermaid / KaTeX 子模块
- `HitlConfirmModal.vue` 留作备份组件 —— 直接删
- OpenTelemetry / Micrometer / Jaeger / HttpDispatcher（Day 7 §6+ 范畴）

## 2. 后端工具集（D1 + D3 后端部分）

### 2.1 新增 `@Tool` 清单

**追加到 `tool/TodoUpdateTools.java`**：

```java
@Tool(name = "update_app",
      description = "修改应用的英文标识 (name) 或中文显示名 (label)。type 分类码不允许改。" +
                    "一次会话里只允许有一条 CREATE_APP 待办，本工具不需要传 id：" +
                    "工具内部找唯一一条 CREATE_APP 待办；若不存在或存在多条，返回 ERROR。")
public String updateApp(@ToolParam(name="newName", description="可选，英文 camelCase；为空表示不改") String newName,
                        @ToolParam(name="newLabel", description="可选，中文显示名；为空表示不改") String newLabel);

@Tool(name = "update_field",
      description = "修改数据模型的某个顶层字段的 dataType 或 comment（中文描述）。" +
                    "通过 modelName + fieldName 定位；subs 嵌套字段本日不支持，" +
                    "遇到请告知用户'当前仅支持顶层字段编辑'。")
public String updateField(@ToolParam(name="modelName") String modelName,
                          @ToolParam(name="fieldName") String fieldName,
                          @ToolParam(name="newDataType",
                              description="可选 long/int/double/string/boolean/date/array；为空不改") String newDataType,
                          @ToolParam(name="newComment", description="可选，字段中文描述；为空不改") String newComment);

@Tool(name = "delete_field",
      description = "删除数据模型的某个顶层字段。subs 嵌套字段本日不支持。")
public String deleteField(@ToolParam(name="modelName") String modelName,
                          @ToolParam(name="fieldName") String fieldName);
```

**新建 `tool/TodoDeleteTools.java`**：

```java
@Tool(name = "delete_module",
      description = "从待办池里删除一个 CREATE_MODULE 待办。仅当 status=PENDING 时允许。")
public String deleteModule(@ToolParam(name="moduleId") String moduleId);

@Tool(name = "delete_model",
      description = "从待办池里删除一个 CREATE_MODEL 待办。仅当 status=PENDING 时允许。")
public String deleteModel(@ToolParam(name="modelName") String modelName);

@Tool(name = "cancel_submission",
      description = "在用户拒绝提交（收到 USER_REJECTED）后立刻调用，清空所有待办。" +
                    "只在 submit_to_frontend 续跑得到 USER_REJECTED 后调用，不要在其他场景使用。")
public String cancelSubmission();
```

### 2.2 `TodoManager` 新增能力

```java
public synchronized boolean remove(String id) {
    TodoItem cur = items.get(id);
    if (cur == null) return false;
    if (cur.status() != TodoStatus.PENDING) {
        throw new IllegalStateException("非 PENDING 不可删: " + id + " status=" + cur.status());
    }
    items.remove(id);
    log.info("[Todo] REMOVE id={}", id);
    listeners.forEach(l -> l.onRemove(id));
    return true;
}
```

`replacePayload` 末尾追加：
```java
listeners.forEach(l -> l.onPayloadReplace(id, newPayload));
```

### 2.3 `TodoChangeListener` 新增钩子

```java
default void onRemove(String id) {}
default void onPayloadReplace(String id, JsonNode newPayload) {}
```

### 2.4 `AguiStateBridge` 实现新钩子

```java
@Override
public void onRemove(String id) {
    emit(buildDelta("remove", "/todos/id=" + id, null));
}

@Override
public void onPayloadReplace(String id, JsonNode newPayload) {
    emit(buildDelta("replace", "/todos/id=" + id + "/payload", newPayload));
}
```

`emit` 仍 `synchronized`，沿用 [[reactor-sink-parallel-quirk]] 的约束。

### 2.5 `SubmitTool` 工具描述微调

仅追加一句到 description 末尾：
> 若续跑时收到 `USER_REJECTED`，**必须随后立即调用 `cancel_submission` 工具**清空所有待办；不要再追问用户是否要清，也不要尝试继续提交。

### 2.6 `AguiAgentConfig` Toolkit 注册

在 `resolveAgent` 构造 Toolkit 时把 `TodoDeleteTools` 与现有 `TodoUpdateTools` / `FrontendCreateTools` / `TodoQueryTools` / `SubmitTool` 一起 `registerTool(...)`。

### 2.7 Schema 校验策略

| 工具 | 校验 |
|---|---|
| `update_app` | 改完 payload 必须过 `APP_VAL`（`/schemas/app-spec.schema.json`）兜底 `name` 的 `pattern: ^[a-zA-Z][a-zA-Z0-9]*$` 与 `label` 的 `minLength: 1` |
| `update_field` / `delete_field` | 改完后 payload 必须过 `MODEL_VAL`（`/schemas/data-model-spec.schema.json`）兜底 |
| `delete_module` / `delete_model` | 不重组 payload，无需 schema |
| `cancel_submission` | 不涉及 payload |

工具失败仍走"返回 `ERROR: ...` 字符串让 LLM 自纠错"风格，与现有 `FrontendCreateTools` / `TodoUpdateTools` 一致。

## 3. 前端 Markdown 流式渲染（D2）

### 3.1 依赖变更

`frontend/package.json` 新增：
```json
"markstream-vue": "^0.0.13"
```

不引入 Monaco / Mermaid / KaTeX 子模块（让 markstream 走最小集成路径）。

### 3.2 `main.ts` 入口

```ts
import 'markstream-vue/index.css'
```

只在入口加一次 CSS import；组件按需 import `MarkdownRender`。

### 3.3 新增 `components/AssistantBubble.vue`

把 assistant 气泡独立出来，专门负责 markdown 流式渲染。理由：(1) `ChatPane.vue` 已 500+ 行 CSS，再塞渲染逻辑可读性差；(2) user 气泡保持纯文本，分开避免误改。

```vue
<script setup lang="ts">
import { MarkdownRender } from 'markstream-vue'
defineProps<{ text: string; streaming: boolean }>()
</script>

<template>
  <div class="bubble assistant-bubble">
    <MarkdownRender :content="text" />
    <span v-if="streaming" class="cursor">▍</span>
  </div>
</template>
```

### 3.4 `ChatPane.vue` v-for 改造

```vue
<div v-for="m in messages" :key="m.id" :class="['row', m.role]">
  <div class="avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
  <div class="bubble-wrap">
    <div class="role-label">{{ m.role === 'user' ? 'You' : 'Agent' }}</div>

    <div v-if="m.role === 'user'" class="bubble">
      <div class="text">{{ m.text }}</div>
    </div>

    <HitlInlineCard v-else-if="m.kind === 'hitl-card'"
                    :card="m"
                    @decide="$emit('decide', $event, m.toolCallId!)" />
    <AssistantBubble v-else
                     :text="m.text"
                     :streaming="streamingId === m.id" />
  </div>
</div>
```

### 3.5 CSS 兼容性

- `.assistant-bubble` 独立 class，不带 `white-space: pre-wrap`（避免与 markdown 段落间距冲突）
- 现有 `.text { white-space: pre-wrap }` 仅作用于 user 气泡
- 补一组限定在 `.assistant-bubble` 作用域的 prose 样式：
  - `h1/h2/h3` margin 收紧到 8/6/4px
  - 段落 `p { margin: 6px 0 }`
  - 行内 `code` 浅紫底高亮
  - 代码块 `pre` 用现有 design 的圆角和阴影

## 4. HITL 内联卡片（D3 前端部分）

### 4.1 `types.ts` 改动

```ts
export interface UiMsg {
  id: string
  role: 'user' | 'assistant'
  text: string
  kind?: 'text' | 'hitl-card'      // 默认 'text'
  toolCallId?: string              // kind='hitl-card' 时必填
  hitlTodos?: Todo[]               // kind='hitl-card' 时携带 Todo 快照
  hitlDecision?: HitlDecision      // 用户点完按钮后填上，用于卡片 disabled 状态
}
```

**删除** `PendingConfirm` 类型。

### 4.2 新建 `components/HitlInlineCard.vue`

把 `HitlConfirmModal` 内 `.modal` 的样式（去掉 `position:fixed` / `.modal-backdrop`）迁移过来套到气泡尺寸（`max-width: 480px`）；按钮 disabled 显示态由 `card.hitlDecision` 控制。

```vue
<script setup lang="ts">
import type { HitlDecision, UiMsg } from '../types'
defineProps<{ card: UiMsg }>()
defineEmits<{ decide: [decision: HitlDecision] }>()
</script>

<template>
  <div class="bubble hitl-card" :class="{ decided: card.hitlDecision }">
    <header class="hitl-head">
      <span class="hitl-icon">✓</span>
      <span class="hitl-title">确认下发到前端？</span>
      <span class="hitl-count">{{ card.hitlTodos?.length ?? 0 }} 项</span>
    </header>

    <ul class="hitl-list">
      <li v-for="t in card.hitlTodos" :key="t.id">
        <span class="hitl-type">{{ t.type.replace('CREATE_', '') }}</span>
        <span class="hitl-name">{{ t.targetName }}</span>
      </li>
    </ul>

    <div v-if="!card.hitlDecision" class="hitl-actions">
      <button class="btn-secondary" @click="$emit('decide', 'USER_REJECTED')">取消</button>
      <button class="btn-primary" @click="$emit('decide', 'USER_CONFIRMED')">确认下发</button>
    </div>
    <div v-else class="hitl-decided">
      {{ card.hitlDecision === 'USER_CONFIRMED' ? '✓ 已确认下发' : '✕ 已取消（清空待办）' }}
    </div>
  </div>
</template>
```

### 4.3 `useScopeAgent.ts` 改动

- **删** `pendingConfirm` ref / `PendingConfirm` 类型 import
- **改** `onRunFinishedEvent`：不再设 `pendingConfirm`，改为 push 一条 `kind='hitl-card'` 的 assistant 消息

```ts
onRunFinishedEvent: () => {
  running.value = false
  if (lastSubmitToolCallId.value) {
    messages.value.push({
      id: 'hitl-' + Date.now(),
      role: 'assistant',
      text: '',
      kind: 'hitl-card',
      toolCallId: lastSubmitToolCallId.value,
      hitlTodos: [...todos.value],
    })
    lastSubmitToolCallId.value = null
  }
},
```

- **改** `resumeRun(decision, toolCallId)` 签名：

```ts
async function resumeRun(decision: HitlDecision, toolCallId: string) {
  const card = messages.value.find(m => m.kind === 'hitl-card' && m.toolCallId === toolCallId)
  if (!card || card.hitlDecision) return
  card.hitlDecision = decision

  if (opts.mock) { console.log('[mock][HITL] decision=', decision); return }
  running.value = true
  try {
    ;(agent as unknown as { messages: unknown[] }).messages.push({
      id: 'tr-' + Date.now(),
      role: 'tool',
      toolCallId,
      content: decision,
    })
    await agent.runAgent({ runId: 'run-' + Date.now() })
  } catch (e) {
    console.error('[HITL] resumeRun error', e)
  } finally {
    running.value = false
  }
}
```

### 4.4 `App.vue` 改动

- **删** `HitlConfirmModal` import 与 `<HitlConfirmModal>` 标签
- 不再从 `useScopeAgent` 解构 `pendingConfirm`；继续解构 `resumeRun` 传给 `<ChatPane>`
- `<ChatPane>` 加 `@decide="resumeRun"` 监听

### 4.5 `ChatPane.vue` emit 透传

```ts
const emit = defineEmits<{
  send: [text: string]
  decide: [decision: HitlDecision, toolCallId: string]
}>()
```

### 4.6 删除清单

| 文件 / 字段 | 动作 |
|---|---|
| `frontend/src/components/HitlConfirmModal.vue` | 整文件删 |
| `frontend/src/types.ts` 内 `PendingConfirm` 类型 | 删 |
| `frontend/src/composables/useScopeAgent.ts` 内 `pendingConfirm` ref | 删 |
| `frontend/src/App.vue` 模板内 `<HitlConfirmModal>` 与 import | 删 |
| `frontend/src/mocks/sampleState.json` 内 `pendingConfirm` 字段（若有） | 改为塞一条 `kind='hitl-card'` 的 mock 消息便于样式调试 |

## 5. 端到端时序

### 5.1 修改 Todo（对话路径）

```
用户 "把员工模型的 phone 字段类型改成 STRING"
  ↓
LLM 调 update_field(modelName=员工, fieldName=phone, newType=STRING)
  ↓
TodoUpdateTools.updateField → deep-copy payload → 改 fields[i].type → MODEL_VAL.validate
  ↓ 校验过
TodoManager.replacePayload(id, newPayload) → log [Todo] PAYLOAD-REPLACE
  ↓
listeners.onPayloadReplace → AguiStateBridge → STATE_DELTA(replace /todos/id=X/payload)
  ↓
前端 applyOps 命中通用分支 (t as Record<string, unknown>)[field] = op.value，看板即时更新
```

### 5.2 删除字段 / 模块（对话路径）

```
用户 "把员工模型的 phone 字段删掉"
  ↓
LLM 调 delete_field(modelName=员工, fieldName=phone)
  ↓
TodoUpdateTools.deleteField → deep-copy payload → 移除 fields[i] → MODEL_VAL.validate（兜底 minItems）
  ↓
TodoManager.replacePayload(id, newPayload) → STATE_DELTA(replace /todos/id=X/payload)
```

```
用户 "删掉第二个模块"
  ↓
LLM 先调 list_todos 拿到 moduleId → 调 delete_module(moduleId)
  ↓
TodoDeleteTools.deleteModule → TodoManager.remove(id) → listener.onRemove → STATE_DELTA(remove /todos/id=X)
```

### 5.3 取消提交（HITL 路径）

```
LLM 调 submit_to_frontend(confirmed=false)
  ↓ ToolSuspendException("AWAITING_USER_CONFIRMATION\n...")
runAgent 流结束
  ↓
前端 onRunFinishedEvent 触发 → push kind='hitl-card' 助手消息
  ↓ 用户点"取消"
前端 push role:'tool' content='USER_REJECTED' + runAgent 续跑
  ↓
LLM 续跑，看到 USER_REJECTED → 按 SubmitTool 描述指引调 cancel_submission()
  ↓
TodoDeleteTools.cancelSubmission → todos.clear() → listener.onClear → STATE_DELTA(replace /todos [])
  ↓
LLM 流式回复 "已取消并清空所有待办，请重新描述需求"（markstream-vue 边到边渲）
```

## 6. 错误处理

| 场景 | 处理 |
|---|---|
| LLM 收到 USER_REJECTED 不调 `cancel_submission` | SubmitTool 描述给重指令；若仍跳过，留 warning 日志，不做兜底（依赖 prompt 工程） |
| `update_field` 找不到 fieldName | 返回 `ERROR: 未找到 model=X 的 field=Y`，LLM 自纠错 |
| `update_field` 改类型后 schema 不过 | 返回 `ERROR: 参数不合规：...`，LLM 看到具体校验错重试 |
| `delete_field` 删到 schema `minItems` 违规 | 校验拒收，返回 ERROR，LLM 可建议改用 `delete_model` |
| `delete_module` / `delete_model` 命中非 PENDING 待办 | TodoManager.remove 抛 IllegalStateException；工具捕获后返回 `ERROR: 状态为 X，不可删` |
| 用户对 subs 嵌套字段下指令 | 工具识别不到顶层 fieldName，返回 `ERROR: 仅支持顶层字段；fieldName=X 可能是嵌套字段，本日不支持` |
| `MarkdownRender` 异常 | 不做 Vue inline try；若真出问题再补 `onErrorCaptured` fallback 到纯文本 |
| 并发 `Sinks.Many.tryEmitNext` | 已由 `AguiStateBridge.emit synchronized` 兜住，见 [[reactor-sink-parallel-quirk]] |

## 7. 测试矩阵

| 层 | 用例 | 落点 |
|---|---|---|
| 后端单测 | `updateApp_changeLabel_ok` / `updateApp_changeName_ok` / `updateApp_badNamePattern_rejected` / `updateApp_noAppTodo_rejected` / `updateApp_multipleAppTodos_rejected` / `updateApp_runningTodo_rejected` | `tool/TodoUpdateToolsTest`（若不存在则新建，否则追加） |
| 后端单测 | `updateField_changeDataType_ok` / `updateField_changeComment_ok` / `updateField_unknownField_returnsError` / `updateField_badDataType_rejected` | 同上 |
| 后端单测 | `deleteField_ok` / `deleteField_lastField_rejected`（若 schema 有 minItems） | 同上 |
| 后端单测 | `deleteModule_happy` / `deleteModel_happy` / `deleteRunning_rejected` | `tool/TodoDeleteToolsTest`（新建） |
| 后端单测 | `cancelSubmission_clearsAll` / `cancelSubmission_emptyOk` | 同上 |
| 后端单测 | `remove_pendingOk` / `remove_runningRejected` / `remove_triggersOnRemove` / `replacePayload_triggersOnPayloadReplace` | `todo/TodoManagerTest`（追加） |
| 后端单测 | `bridge_onRemove_emitsRemoveOp` / `bridge_onPayloadReplace_emitsReplaceOp` | `agui/AguiStateBridgeTest`（若不存在则新建） |
| 前端冒烟 | `?mock=1` 模式下 `sampleState.json` —— hitl-card mock 消息 + assistant markdown 气泡都能正确渲染 | 手动 |
| 端到端 | 起后端 + 前端 → 「做一个员工档案管理 → 把 phone 改成 STRING → 提交 → 取消」 → 看板：先增 3 todo / phone payload type 变 STRING / 出现 hitl-card / 点取消后 todos 清空；chat：assistant 消息走 markdown 渲染 | 手动验收 |

## 8. 文件清单

### 新建

- `src/main/java/space/wlshow/scope/tool/TodoDeleteTools.java`
- `frontend/src/components/AssistantBubble.vue`
- `frontend/src/components/HitlInlineCard.vue`
- `src/test/java/space/wlshow/scope/tool/TodoDeleteToolsTest.java`
- `src/test/java/space/wlshow/scope/agui/AguiStateBridgeTest.java`（若不存在）

### 修改

- `src/main/java/space/wlshow/scope/tool/TodoUpdateTools.java`（追加 3 个工具）
- `src/main/java/space/wlshow/scope/tool/SubmitTool.java`（仅改 description）
- `src/main/java/space/wlshow/scope/todo/TodoManager.java`（新增 `remove` / 触发 `onPayloadReplace`）
- `src/main/java/space/wlshow/scope/todo/TodoChangeListener.java`（新增两个 default 钩子）
- `src/main/java/space/wlshow/scope/agui/AguiStateBridge.java`（实现两个新钩子）
- `src/main/java/space/wlshow/scope/config/AguiAgentConfig.java`（注册 `TodoDeleteTools`）
- `src/test/java/space/wlshow/scope/tool/TodoUpdateToolsTest.java`（追加用例，若不存在则新建）
- `src/test/java/space/wlshow/scope/todo/TodoManagerTest.java`（追加用例）
- `frontend/package.json`（新增 markstream-vue 依赖）
- `frontend/src/main.ts`(import CSS)
- `frontend/src/types.ts`(UiMsg 字段扩展、删 PendingConfirm)
- `frontend/src/components/ChatPane.vue`(v-for 分支 + 透传 decide emit)
- `frontend/src/composables/useScopeAgent.ts`(删 pendingConfirm、改 onRunFinishedEvent / resumeRun)
- `frontend/src/App.vue`(删 HitlConfirmModal 引用)
- `frontend/src/mocks/sampleState.json`(改 mock 数据形态)

### 删除

- `frontend/src/components/HitlConfirmModal.vue`

## 9. 与 CLAUDE.md 第 8 节"硬规矩"的一致性核查

- 包名：所有新文件落在 `space.wlshow.scope.tool.*` / `space.wlshow.scope.agui.*`，符合既有子包划分 ✓
- 不引 OTel / Micrometer / Jaeger ✓
- 不让 SubmitTool 真发 HTTP（保持 dry-run，Day 7 §9 才接 HttpDispatcher）✓
- Schema 校验先行：`update_field` / `delete_field` 必过 `MODEL_VAL` ✓
- 中文 Javadoc OK ✓

## 10. 已知局限

1. **subs 嵌套字段编辑**：明确不支持，工具显式返回 ERROR 提示用户
2. **LLM 不调 `cancel_submission`**：依赖 prompt 工程约束，不做硬兜底
3. **markstream-vue beta 版本（0.0.13）**：风险已知；若渲染异常，回退方案是把 `AssistantBubble.vue` 切回 `{{ text }}` 纯文本
4. **HitlInlineCard 在 chat 历史中长留**：用户多次"取消 → 重新提交"会在 chat 流中累积多张卡片，本次不做折叠 / 归并；下一次需求再处理

## 11. 实施顺序建议

1. 后端 D1：`TodoManager.remove` + `TodoChangeListener` 两个新钩子 + `AguiStateBridge` 实现 → 单测先行
2. 后端 D1：`TodoUpdateTools` 追加 3 个工具 + `TodoDeleteTools` 新文件 → 单测
3. 后端 D3：`SubmitTool` 改 description + `AguiAgentConfig` 注册 `TodoDeleteTools` → 启动冒烟（curl 跑一遍）
4. 前端 D2：装 `markstream-vue` + `AssistantBubble.vue` + `ChatPane.vue` v-for 改造 → `?mock=1` 调样式
5. 前端 D3：`types.ts` + `useScopeAgent.ts` + `App.vue` + `HitlInlineCard.vue` + 删 `HitlConfirmModal.vue` → `?mock=1` 调样式
6. 端到端联调：起后端 + 前端，跑「员工档案管理 → 改字段 → 提交 → 取消」全链路
