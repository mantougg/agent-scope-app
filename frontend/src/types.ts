export interface UiMsg {
  id: string
  role: 'user' | 'assistant'
  text: string
}

export interface Todo {
  id: string
  type: string
  targetName: string
  status: string
  payload?: unknown
  errorMessage?: string
}

export interface JsonPatchOp {
  op: 'add' | 'replace' | 'remove'
  path: string
  value?: unknown
}

export interface PendingConfirm {
  toolCallId: string
  todos: Todo[]
}

export type HitlDecision = 'USER_CONFIRMED' | 'USER_REJECTED'
