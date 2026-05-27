import { onBeforeUnmount, onMounted, ref } from 'vue'
import { HttpAgent } from '@ag-ui/client'
import type { HitlDecision, JsonPatchOp, PendingConfirm, Todo, UiMsg } from '../types'

export interface UseScopeAgentOptions {
  mock?: boolean
  baseUrl?: string
}

export function useScopeAgent(opts: UseScopeAgentOptions = {}) {
  const baseUrl = opts.baseUrl ?? 'http://localhost:8888'
  const threadId = 'thread-' + Date.now()
  const agent = new HttpAgent({
    url: `${baseUrl}/agui/run`,
    threadId,
  })

  const messages = ref<UiMsg[]>([])
  const todos = ref<Todo[]>([])
  const running = ref(false)
  const streamingId = ref<string | null>(null)
  const pendingConfirm = ref<PendingConfirm | null>(null)
  const lastSubmitToolCallId = ref<string | null>(null)

  function mergeTodos(incoming: Todo[]) {
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
          console.warn('[STATE_DELTA] unknown path', op.path)
          continue
        }
        const [, id, field] = m
        const t = arr.find(x => x.id === id)
        if (!t) {
          console.warn('[STATE_DELTA] todo not found id=', id)
          continue
        }
        ;(t as Record<string, unknown>)[field] =
            op.op === 'remove' ? undefined : op.value
      }
    }
    todos.value = arr
  }

  if (!opts.mock) {
    agent.subscribe({
      onTextMessageStartEvent: ({ event }) => {
        streamingId.value = event.messageId
        messages.value.push({ id: event.messageId, role: 'assistant', text: '' })
      },
      onTextMessageContentEvent: ({ event }) => {
        const msg = messages.value.find(m => m.id === event.messageId)
        if (msg) msg.text += event.delta
      },
      onTextMessageEndEvent: () => {
        streamingId.value = null
      },
      onToolCallStartEvent: ({ event }) => {
        console.log('[ToolCall START]', event.toolCallName, event.toolCallId)
        if (event.toolCallName === 'submit_to_frontend') {
          lastSubmitToolCallId.value = event.toolCallId
        }
      },
      onToolCallEndEvent: (params) => {
        console.log('[ToolCall END]', params.event.toolCallId)
      },
      onToolCallResultEvent: ({ event }) => {
        console.log('[ToolCall RESULT] id=', event.toolCallId)
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
      onRunErrorEvent: ({ event }) => {
        running.value = false
        console.error('[RUN_ERROR]', event)
        messages.value.push({
          id: 'err-' + Date.now(),
          role: 'assistant',
          text: '[ERROR] ' + (event.message || '未知错误'),
        })
      },
    })
  }

  async function send(text: string) {
    const trimmed = text.trim()
    if (!trimmed || running.value) return
    const userMsg: UiMsg = { id: 'u-' + Date.now(), role: 'user', text: trimmed }
    messages.value.push(userMsg)
    if (opts.mock) {
      running.value = true
      setTimeout(() => {
        messages.value.push({
          id: 'a-' + Date.now(),
          role: 'assistant',
          text: '(mock) 收到 "' + trimmed + '"，当前是 mock 模式，未真正请求后端。',
        })
        running.value = false
      }, 400)
      return
    }
    agent.addMessage({ id: userMsg.id, role: 'user', content: trimmed })
    running.value = true
    try {
      await agent.runAgent({ runId: 'run-' + Date.now() })
    } catch (e) {
      running.value = false
      console.error(e)
    }
  }

  async function resumeRun(decision: HitlDecision) {
    const pc = pendingConfirm.value
    if (!pc) return
    pendingConfirm.value = null
    messages.value.push({
      id: 'tr-ui-' + Date.now(),
      role: 'user',
      text: decision === 'USER_CONFIRMED' ? '[已确认下发]' : '[已取消]',
    })
    if (opts.mock) {
      console.log('[mock][HITL] decision=', decision)
      return
    }
    running.value = true
    try {
      // role:'tool' 不能走 agent.addMessage（1.x 会拒收），直接 push 到内部 messages
      ;(agent as unknown as { messages: unknown[] }).messages.push({
        id: 'tr-' + Date.now(),
        role: 'tool',
        toolCallId: pc.toolCallId,
        content: decision,
      })
      await agent.runAgent({ runId: 'run-' + Date.now() })
    } catch (e) {
      console.error('[HITL] resumeRun error', e)
    } finally {
      running.value = false
    }
  }

  let es: EventSource | null = null

  function connectSse() {
    if (opts.mock) return
    const url = `${baseUrl}/agui/state-stream/${encodeURIComponent(threadId)}`
    es = new EventSource(url)
    es.addEventListener('STATE_SNAPSHOT', (ev) => {
      const e = JSON.parse((ev as MessageEvent).data)
      const incoming = (e.snapshot?.todos ?? []) as Todo[]
      mergeTodos(incoming)
      console.log('[STATE_SNAPSHOT] size=', todos.value.length)
    })
    es.addEventListener('STATE_DELTA', (ev) => {
      const e = JSON.parse((ev as MessageEvent).data)
      applyOps(e.delta as JsonPatchOp[])
      console.log('[STATE_DELTA] ops=', e.delta.length)
    })
    es.onerror = (err) => console.warn('[StateStream] error', err)
  }

  function disconnectSse() {
    es?.close()
    es = null
  }

  function loadMockState(state: {
    messages?: UiMsg[]
    todos?: Todo[]
    pendingConfirm?: PendingConfirm | null
  }) {
    if (state.messages) messages.value = [...state.messages]
    if (state.todos) todos.value = [...state.todos]
    if (state.pendingConfirm) pendingConfirm.value = state.pendingConfirm
  }

  onMounted(connectSse)
  onBeforeUnmount(disconnectSse)

  return {
    threadId,
    messages,
    todos,
    running,
    streamingId,
    pendingConfirm,
    send,
    resumeRun,
    loadMockState,
    isMock: !!opts.mock,
  }
}
