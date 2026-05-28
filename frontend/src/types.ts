export interface UiMsg {
  id: string
  role: 'user' | 'assistant'
  text: string
  kind?: 'text' | 'hitl-card'      // 默认 'text'
  toolCallId?: string              // kind='hitl-card' 时必填，用于续跑携带
  hitlTodos?: Todo[]               // kind='hitl-card' 时携带 Todo 快照
  hitlDecision?: HitlDecision      // 用户点完按钮后填上，用于卡片 disabled 状态
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

export type HitlDecision = 'USER_CONFIRMED' | 'USER_REJECTED'
