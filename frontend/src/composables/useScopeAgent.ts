import { onBeforeUnmount, onMounted, ref } from 'vue'
import { HttpAgent } from '@ag-ui/client'
import type { HitlDecision, JsonPatchOp, Todo, UiMsg } from '../types'

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
      } else if (op.op === 'remove') {
        const m = op.path.match(/^\/todos\/id=([^/]+)$/)
        if (m) {
          const idx = arr.findIndex(t => t.id === m[1])
          if (idx >= 0) arr.splice(idx, 1)
          continue
        }
        console.warn('[STATE_DELTA] unhandled remove path', op.path)
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
        ;(t as Record<string, unknown>)[field] = op.value
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
        if (lastSubmitToolCallId.value) {
          console.log('[HITL] pushing inline card for', lastSubmitToolCallId.value)
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

  async function resumeRun(decision: HitlDecision, toolCallId: string) {
    const card = messages.value.find(
        m => m.kind === 'hitl-card' && m.toolCallId === toolCallId)
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
  }) {
    if (state.messages) messages.value = [...state.messages]
    if (state.todos) todos.value = [...state.todos]
  }

  onMounted(connectSse)
  onBeforeUnmount(disconnectSse)

  return {
    threadId,
    messages,
    todos,
    running,
    streamingId,
    send,
    resumeRun,
    loadMockState,
    isMock: !!opts.mock,
  }
}
