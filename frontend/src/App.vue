<script setup lang="ts">
import {onMounted, onBeforeUnmount, ref, nextTick, watch} from 'vue'
import {HttpAgent} from '@ag-ui/client'

const threadId = 'thread-' + Date.now()
const agent = new HttpAgent({
  url: 'http://localhost:8888/agui/run',
  threadId,
})

interface UiMsg {
  id: string;
  role: 'user' | 'assistant';
  text: string
}

interface Todo {
  id: string;
  type: string;
  targetName: string;
  status: string;
  payload?: unknown;
  errorMessage?: string;
}

const messages = ref<UiMsg[]>([])
const input = ref('')
const running = ref(false)
const logEl = ref<HTMLDivElement | null>(null)
const taEl = ref<HTMLTextAreaElement | null>(null)
const todos = ref<Todo[]>([])

// 当前正在流式的 assistant 消息（用 messageId 索引）
const streamingId = ref<string | null>(null)

function autoResize() {
  const el = taEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

function useSuggestion(text: string) {
  input.value = text
  nextTick(() => {
    autoResize()
    taEl.value?.focus()
  })
}

interface JsonPatchOp {
  op: 'add' | 'replace' | 'remove'
  path: string         // 形如 "/todos/-" 或 "/todos/id=todo-3/status"
  value?: unknown
}

function mergeTodos(incoming: Todo[]) {
  // 按 id 合并：已存在的不动，新来的追加；不会覆盖前面的 STATE_DELTA
  const existing = new Map(todos.value.map(t => [t.id, t]))
  for (const t of incoming) {
    if (!existing.has(t.id)) existing.set(t.id, t)
  }
  todos.value = [...existing.values()]
}

function applyOps(ops: JsonPatchOp[]) {
  const arr = [...todos.value]
  for (const op of ops) {
    if (op.path === '/todos/-' && op.op === 'add') {
      const item = op.value as Todo
      // 幂等：已存在同 id 则跳过
      if (!arr.find(t => t.id === item.id)) {
        arr.push(item)
      } else {
        console.debug('[STATE_DELTA] skip duplicate add id=', item.id)
      }
    } else if (op.path === '/todos' && op.op === 'replace') {
      arr.length = 0
      ;(op.value as Todo[]).forEach(t => arr.push(t))
    } else {
      const m = op.path.match(/^\/todos\/id=([^/]+)\/(\w+)$/)
      if (!m) {
        console.warn('[STATE_DELTA] unknown path', op.path);
        continue
      }
      const [, id, field] = m
      const t = arr.find(x => x.id === id)
      if (!t) {
        console.warn('[STATE_DELTA] todo not found id=', id);
        continue
      }
      ;(t as Record<string, unknown>)[field] =
          op.op === 'remove' ? undefined : op.value
    }
  }
  todos.value = arr
}

watch(
    () => messages.value.map(m => m.text).join('|'),
    () => {
      nextTick(() => {
        if (logEl.value) logEl.value.scrollTop = logEl.value.scrollHeight
      })
    }
)

interface PendingConfirm {
  toolCallId: string
  todos: Todo[]    // 拍一份快照展示给用户
}

const pendingConfirm = ref<PendingConfirm | null>(null)
const lastSubmitToolCallId = ref<string | null>(null)   // 用 ToolCallStart 记 toolName→id

agent.subscribe({
  onTextMessageStartEvent: ({event}) => {
    streamingId.value = event.messageId
    messages.value.push({id: event.messageId, role: 'assistant', text: ''})
  },
  onTextMessageContentEvent: ({event}) => {
    const msg = messages.value.find(m => m.id === event.messageId)
    if (msg) msg.text += event.delta
  },
  onTextMessageEndEvent: () => {
    streamingId.value = null
  },
  onToolCallStartEvent: ({event}) => {
    console.log('[ToolCall START]', event.toolCallName, event.toolCallId)
    if (event.toolCallName === 'submit_to_frontend') {
      lastSubmitToolCallId.value = event.toolCallId
    }
  },
  onToolCallEndEvent: (params) => {
    console.log('[ToolCall END]', params.event.toolCallId,
        'toolCallName' in params ? params.toolCallName : '?')
  },
  onToolCallResultEvent: ({event}) => {
    console.log('[ToolCall RESULT] toolCallId=', event.toolCallId, 'content[0:40]=',
        typeof event.content === 'string' ? event.content.substring(0, 40) : event.content)
    // 同一个 toolCallId 收到了 RESULT 说明工具走完了（confirmed=true 那次会走到这里）；
    // 把"挂起候选"清掉，避免续跑结束后误触弹窗。挂起态在 1.0.12 里不发 RESULT，
    // 所以只剩"真挂起"的 id 留到 RUN_FINISHED。
    if (event.toolCallId === lastSubmitToolCallId.value) {
      lastSubmitToolCallId.value = null
    }
  },
  onRunFinishedEvent: () => {
    running.value = false
    // submit_to_frontend 的 ToolSuspend 不会产生 TOOL_CALL_RESULT，
    // 用 TOOL_CALL_START 记下的 toolCallId + RUN_FINISHED 作为弹窗触发点
    if (lastSubmitToolCallId.value && !pendingConfirm.value) {
      console.log('[HITL] triggering confirm dialog for', lastSubmitToolCallId.value)
      pendingConfirm.value = {
        toolCallId: lastSubmitToolCallId.value,
        todos: [...todos.value],
      }
      lastSubmitToolCallId.value = null
    }
  },
  onRunErrorEvent: ({event}) => {
    running.value = false
    console.error('[RUN_ERROR]', event)
    messages.value.push({
      id: 'err-' + Date.now(), role: 'assistant',
      text: '[ERROR] ' + (event.message || '未知错误')
    })
  },
})

async function send() {
  const text = input.value.trim()
  if (!text || running.value) return
  const userMsg: UiMsg = {id: 'u-' + Date.now(), role: 'user', text}
  messages.value.push(userMsg)
  agent.addMessage({id: userMsg.id, role: 'user', content: text})
  input.value = ''
  nextTick(() => autoResize())
  running.value = true
  try {
    await agent.runAgent({runId: 'run-' + Date.now()})
  } catch (e) {
    running.value = false
    console.error(e)
  }
}

async function resumeRun(decision: 'USER_CONFIRMED' | 'USER_REJECTED') {
  const pc = pendingConfirm.value
  if (!pc) return
  pendingConfirm.value = null
  running.value = true
  try {
    console.log('[HITL] resumeRun', decision, 'toolCallId=', pc.toolCallId,
        'messages.length=', agent.messages.length)
    // 在聊天框里保留一条 UI 提示，方便用户回看自己点了什么
    const uiMsg: UiMsg = {
      id: 'tr-ui-' + Date.now(), role: 'user',
      text: decision === 'USER_CONFIRMED' ? '[已确认下发]' : '[已取消]'
    }
    messages.value.push(uiMsg)
    // 协议正确的续跑姿势：push 一条 role:'tool' 的 tool result，
    // toolCallId 对齐挂起的 submit_to_frontend，content 直接是 decision 字符串。
    // 这样 ReActAgent 才会把"suspended 的 toolCallId"接上继续 acting 循环。
    // 用 .messages.push 而不是 agent.addMessage：@ag-ui/client 1.x 的 addMessage
    // 对 role:'tool' 常拒收（Day 7 §5.4 注脚）。
    agent.messages.push({
      id: 'tr-' + Date.now(),
      role: 'tool',
      toolCallId: pc.toolCallId,
      content: decision,
    } as any)
    console.log('[HITL] pushed tool result, toolCallId=', pc.toolCallId, 'content=', decision)
    const result = await agent.runAgent({ runId: 'run-' + Date.now() })
    console.log('[HITL] runAgent returned', result)
  } catch (e) {
    console.error('[HITL] resumeRun error', e)
  } finally {
    running.value = false
    console.log('[HITL] resumeRun done, running=', running.value)
  }
}

let es: EventSource | null = null
onMounted(() => {
  const url = `http://localhost:8888/agui/state-stream/${encodeURIComponent(threadId)}`
  es = new EventSource(url)
  es.addEventListener('STATE_SNAPSHOT', (ev) => {
    const e = JSON.parse((ev as MessageEvent).data)
    const incoming = (e.snapshot?.todos ?? []) as Todo[]
    mergeTodos(incoming)  // 合并而非替换，避免覆盖先到的 STATE_DELTA
    console.log('[STATE_SNAPSHOT] size=', todos.value.length)
  })
  es.addEventListener('STATE_DELTA', (ev) => {
    const e = JSON.parse((ev as MessageEvent).data)
    applyOps(e.delta as JsonPatchOp[])
    console.log('[STATE_DELTA] ops=', e.delta.length)
  })
  es.onerror = (err) => console.warn('[StateStream] error', err)
})
onBeforeUnmount(() => es?.close())
</script>

<template>
  <div class="layout">
    <div class="chat-pane">
      <div class="app">
        <header class="app-header">
          <div class="brand">
            <div class="logo">S</div>
            <div class="title-block">
              <h1>Scope</h1>
              <span class="subtitle">Day 6 · AG-UI 联调</span>
            </div>
          </div>
          <span class="thread" :title="threadId">{{ threadId }}</span>
        </header>

        <div class="log" ref="logEl">
          <div v-if="messages.length === 0" class="empty">
            <div class="empty-icon">✨</div>
            <div class="empty-title">开始一段需求对话</div>
            <div class="empty-hint">告诉 Scope 你想要构建什么，我会帮你拆解模块与数据模型</div>
            <div class="suggestions">
              <button type="button" class="suggestion" @click="useSuggestion('做一个简单的员工档案管理')">
                👥 员工档案管理
              </button>
              <button type="button" class="suggestion" @click="useSuggestion('做一个图书借阅系统，支持续借和逾期提醒')">
                📚 图书借阅系统
              </button>
              <button type="button" class="suggestion" @click="useSuggestion('做一个项目任务看板，支持拖拽和评论')">
                📋 项目任务看板
              </button>
            </div>
          </div>

          <div v-for="m in messages" :key="m.id" :class="['row', m.role]">
            <div class="avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
            <div class="bubble-wrap">
              <div class="role-label">{{ m.role === 'user' ? 'You' : 'Agent' }}</div>
              <div class="bubble">
                <div class="text">{{ m.text }}<span v-if="streamingId === m.id" class="cursor">▍</span></div>
              </div>
            </div>
          </div>
        </div>

        <form @submit.prevent="send" class="composer-wrap">
          <div class="composer">
        <textarea v-model="input"
                  placeholder="例如：做一个简单的员工档案管理（Enter 发送 / Shift+Enter 换行）"
                  :disabled="running"
                  rows="1"
                  ref="taEl"
                  @input="autoResize"
                  @keydown.enter.exact.prevent="send"/>
            <button :disabled="running || !input.trim()" class="send-btn" :title="running ? '思考中' : '发送'">
              <span v-if="running" class="dot-flashing"></span>
              <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                   stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M22 2L11 13"/>
                <path d="M22 2l-7 20-4-9-9-4 20-7z"/>
              </svg>
            </button>
          </div>
          <div class="hint-bar">
            <span><kbd>Enter</kbd> 发送 · <kbd>Shift</kbd>+<kbd>Enter</kbd> 换行</span>
            <span class="char-count" :class="{ over: input.length > 500 }">{{ input.length }} 字</span>
          </div>
        </form>
      </div>
    </div>

    <aside class="todos">
      <h2>Todo Board ({{ todos.length }})</h2>
      <ul>
        <li v-for="t in todos" :key="t.id" :class="['todo', t.status.toLowerCase()]">
          <span class="id">{{ t.id }}</span>
          <span class="type">{{ t.type }}</span>
          <span class="name">{{ t.targetName }}</span>
          <span class="status">{{ t.status }}</span>
        </li>
      </ul>
    </aside>

    <div v-if="pendingConfirm" class="modal-backdrop">
      <div class="modal">
        <h3>确认下发 {{ pendingConfirm.todos.length }} 项？</h3>
        <ul class="preview">
          <li v-for="t in pendingConfirm.todos" :key="t.id">
            {{ t.id }} · {{ t.type }} · {{ t.targetName }}
          </li>
        </ul>
        <div class="actions">
          <button @click="resumeRun('USER_CONFIRMED')" class="primary">确认下发</button>
          <button @click="resumeRun('USER_REJECTED')">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.app {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
  "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
  max-width: 1080px;
  width: 100%;
  margin: 0 auto;
  height: 100vh;
  display: flex;
  flex-direction: column;
  padding: 18px 28px 22px;
  box-sizing: border-box;
}

.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  margin-bottom: 8px;
  background: rgba(255, 255, 255, 0.6);
  backdrop-filter: saturate(180%) blur(14px);
  -webkit-backdrop-filter: saturate(180%) blur(14px);
  border: 1px solid rgba(255, 255, 255, 0.65);
  border-radius: 16px;
  box-shadow: 0 8px 24px rgba(99, 102, 241, 0.08);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6 55%, #ec4899);
  color: #fff;
  font-weight: 700;
  font-size: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 6px 18px rgba(139, 92, 246, 0.45);
  position: relative;
}

.logo::after {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.4), transparent 50%);
  pointer-events: none;
}

.title-block h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #111827;
  line-height: 1.2;
  letter-spacing: -0.2px;
}

.subtitle {
  font-size: 12px;
  color: #6b7280;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.subtitle::before {
  content: "";
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #10b981;
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.18);
  animation: pulse 2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% {
    box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.18);
  }
  50% {
    box-shadow: 0 0 0 6px rgba(16, 185, 129, 0.05);
  }
}

.thread {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  color: #6366f1;
  background: rgba(99, 102, 241, 0.08);
  border: 1px solid rgba(99, 102, 241, 0.18);
  padding: 5px 12px;
  border-radius: 999px;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.log {
  flex: 1;
  overflow-y: auto;
  padding: 24px 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 22px;
  scroll-behavior: smooth;
}

.log::-webkit-scrollbar {
  width: 8px;
}

.log::-webkit-scrollbar-thumb {
  background: rgba(99, 102, 241, 0.18);
  border-radius: 4px;
}

.log::-webkit-scrollbar-thumb:hover {
  background: rgba(99, 102, 241, 0.32);
}

.empty {
  margin: auto;
  text-align: center;
  color: #9ca3af;
  padding: 24px;
}

.empty-icon {
  font-size: 40px;
  margin-bottom: 10px;
  filter: drop-shadow(0 4px 12px rgba(139, 92, 246, 0.3));
}

.empty-title {
  font-size: 16px;
  font-weight: 600;
  color: #374151;
  margin-bottom: 6px;
}

.empty-hint {
  font-size: 13px;
  max-width: 360px;
  margin: 0 auto 18px;
  line-height: 1.6;
}

.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.suggestion {
  font: inherit;
  font-size: 13px;
  color: #4f46e5;
  background: rgba(99, 102, 241, 0.08);
  border: 1px solid rgba(99, 102, 241, 0.18);
  border-radius: 999px;
  padding: 6px 14px;
  cursor: pointer;
  transition: all 0.18s ease;
}

.suggestion:hover {
  background: rgba(99, 102, 241, 0.16);
  border-color: rgba(99, 102, 241, 0.35);
  transform: translateY(-1px);
}

.row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  animation: fadeIn 0.28s ease;
}

.row.user {
  flex-direction: row-reverse;
}

.avatar {
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  color: #fff;
  background: #94a3b8;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
}

.row.user .avatar {
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  box-shadow: 0 4px 14px rgba(37, 99, 235, 0.35);
}

.row.assistant .avatar {
  background: linear-gradient(135deg, #6366f1, #8b5cf6 60%, #ec4899);
  box-shadow: 0 4px 14px rgba(139, 92, 246, 0.4);
}

.bubble-wrap {
  display: flex;
  flex-direction: column;
  max-width: 78%;
  min-width: 0;
}

.row.user .bubble-wrap {
  align-items: flex-end;
}

.row.assistant .bubble-wrap {
  align-items: flex-start;
}

.bubble {
  padding: 12px 16px;
  border-radius: 16px;
  font-size: 14.5px;
  line-height: 1.65;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.04);
  word-wrap: break-word;
  word-break: break-word;
  position: relative;
}

.row.user .bubble {
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  color: #fff;
  border-top-right-radius: 4px;
  text-align: right;
  box-shadow: 0 6px 18px rgba(79, 70, 229, 0.28);
}

.row.assistant .bubble {
  background: #fff;
  color: #111827;
  border: 1px solid rgba(0, 0, 0, 0.05);
  border-top-left-radius: 4px;
  text-align: left;
}

.role-label {
  font-size: 11px;
  font-weight: 600;
  color: #6b7280;
  margin: 0 6px 4px;
  letter-spacing: 0.4px;
  text-transform: uppercase;
}

.row.user .role-label {
  color: #4f46e5;
}

.row.assistant .role-label {
  color: #8b5cf6;
}

.text {
  white-space: pre-wrap;
}

.cursor {
  display: inline-block;
  margin-left: 2px;
  animation: blink 1s steps(2) infinite;
  color: #8b5cf6;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.composer-wrap {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
}

.composer {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding: 12px 12px 12px 18px;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: saturate(180%) blur(12px);
  -webkit-backdrop-filter: saturate(180%) blur(12px);
  border: 1px solid rgba(99, 102, 241, 0.15);
  border-radius: 18px;
  box-shadow: 0 10px 30px rgba(99, 102, 241, 0.1);
  transition: border-color 0.2s, box-shadow 0.2s, transform 0.2s;
}

.composer:focus-within {
  border-color: rgba(99, 102, 241, 0.5);
  box-shadow: 0 12px 36px rgba(99, 102, 241, 0.22);
}

textarea {
  flex: 1;
  width: 100%;
  font: inherit;
  font-size: 14.5px;
  line-height: 1.55;
  padding: 8px 4px;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  color: #111827;
  min-height: 24px;
  max-height: 200px;
  overflow-y: auto;
}

textarea::placeholder {
  color: #9ca3af;
}

.send-btn {
  flex: 0 0 auto;
  width: 44px;
  height: 44px;
  padding: 0;
  border: none;
  border-radius: 14px;
  color: #fff;
  background: linear-gradient(135deg, #6366f1, #8b5cf6 60%, #ec4899);
  cursor: pointer;
  transition: transform 0.15s, opacity 0.15s, box-shadow 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 6px 18px rgba(139, 92, 246, 0.35);
}

.send-btn:hover:not(:disabled) {
  transform: translateY(-2px) scale(1.03);
  box-shadow: 0 10px 24px rgba(139, 92, 246, 0.5);
}

.send-btn:active:not(:disabled) {
  transform: translateY(0) scale(0.98);
}

.send-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
  box-shadow: none;
}

.hint-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11px;
  color: #9ca3af;
  padding: 0 6px;
}

.hint-bar kbd {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 10px;
  background: rgba(0, 0, 0, 0.06);
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-bottom-width: 2px;
  border-radius: 4px;
  padding: 1px 5px;
  color: #4b5563;
}

.char-count.over {
  color: #ef4444;
  font-weight: 600;
}

.dot-flashing {
  position: relative;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #fff;
  animation: dotFlashing 1s infinite linear alternate;
  animation-delay: 0.5s;
}

.dot-flashing::before,
.dot-flashing::after {
  content: "";
  display: inline-block;
  position: absolute;
  top: 0;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #fff;
}

.dot-flashing::before {
  left: -10px;
  animation: dotFlashing 1s infinite alternate;
  animation-delay: 0s;
}

.dot-flashing::after {
  left: 10px;
  animation: dotFlashing 1s infinite alternate;
  animation-delay: 1s;
}

@keyframes dotFlashing {
  0% {
    background: #fff;
  }
  50%, 100% {
    background: rgba(255, 255, 255, 0.3);
  }
}

@media (max-width: 640px) {
  .app {
    padding: 12px 14px 14px;
  }

  .bubble-wrap {
    max-width: 85%;
  }
}
</style>

<style>
body {
  margin: 0;
  min-height: 100vh;
  color: #111827;
  background: #f5f7ff;
  background-image: radial-gradient(at 12% 8%, rgba(99, 102, 241, 0.18) 0px, transparent 50%),
  radial-gradient(at 88% 12%, rgba(236, 72, 153, 0.12) 0px, transparent 50%),
  radial-gradient(at 50% 92%, rgba(139, 92, 246, 0.16) 0px, transparent 50%);
  background-attachment: fixed;
}

@media (prefers-color-scheme: dark) {
  body {
    background: #0b1020;
    background-image: radial-gradient(at 12% 8%, rgba(99, 102, 241, 0.25) 0px, transparent 50%),
    radial-gradient(at 88% 12%, rgba(236, 72, 153, 0.18) 0px, transparent 50%),
    radial-gradient(at 50% 92%, rgba(139, 92, 246, 0.22) 0px, transparent 50%);
    color: #e5e7eb;
  }
}

.layout {
  display: grid;
  grid-template-columns: 1fr 360px;
  gap: 16px;
  height: 100vh;
}

.chat-pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.todos {
  border-left: 1px solid rgba(0, 0, 0, .08);
  padding: 18px 16px;
  overflow-y: auto;
}

.todos h2 {
  font-size: 14px;
  margin: 0 0 12px;
  color: #374151;
}

.todo {
  display: grid;
  grid-template-columns: 70px 100px 1fr 80px;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px solid rgba(0, 0, 0, .04);
}

.todo.pending {
  color: #9ca3af;
}

.todo.running {
  color: #2563eb;
  font-weight: 500;
}

.todo.success {
  color: #16a34a;
}

.todo.failed {
  color: #dc2626;
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, .3);
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  min-width: 360px;
  max-width: 600px;
}

.modal .preview {
  max-height: 240px;
  overflow: auto;
  font-size: 13px;
}

.modal .actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 16px;
}

.primary {
  background: #0066cc;
  color: #fff;
  padding: 6px 16px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}
</style>