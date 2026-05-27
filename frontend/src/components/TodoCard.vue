<script setup lang="ts">
import type { Todo } from '../types'

defineProps<{
  todo: Todo
  active?: boolean
}>()

defineEmits<{
  click: []
}>()
</script>

<template>
  <li :class="['todo-card', todo.status.toLowerCase(), { active }]"
      @click="$emit('click')"
      tabindex="0"
      @keydown.enter.prevent="$emit('click')"
      @keydown.space.prevent="$emit('click')">
    <div class="todo-strip"></div>
    <div class="todo-body">
      <div class="todo-line">
        <span class="todo-type-badge">{{ todo.type.replace('CREATE_', '') }}</span>
        <span class="todo-name" :title="todo.targetName">{{ todo.targetName }}</span>
      </div>
      <div class="todo-line todo-line-sub">
        <span class="todo-status-pill">
          <span class="status-dot"></span>
          <span class="status-text">{{ todo.status }}</span>
        </span>
        <span class="todo-id" :title="todo.id">{{ todo.id }}</span>
      </div>
    </div>
  </li>
</template>

<style scoped>
.todo-card {
  display: flex;
  background: #fff;
  border: 1px solid rgba(0, 0, 0, 0.05);
  border-radius: 10px;
  overflow: hidden;
  cursor: pointer;
  outline: none;
  text-align: left;
  list-style: none;
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.04);
  animation: todoCardIn 0.28s cubic-bezier(0.16, 1, 0.3, 1);
}

.todo-card:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 18px rgba(99, 102, 241, 0.12);
  border-color: rgba(99, 102, 241, 0.18);
}

.todo-card:focus-visible {
  border-color: rgba(99, 102, 241, 0.5);
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.18);
}

.todo-card.active {
  border-color: rgba(99, 102, 241, 0.5);
  box-shadow:
    0 0 0 3px rgba(99, 102, 241, 0.15),
    0 8px 20px rgba(99, 102, 241, 0.2);
  transform: translateY(-1px);
}

@keyframes todoCardIn {
  from { opacity: 0; transform: translateY(6px); }
  to   { opacity: 1; transform: translateY(0); }
}

.todo-strip {
  flex: 0 0 3px;
  background: #cbd5e1;
}

.todo-card.pending .todo-strip { background: linear-gradient(180deg, #cbd5e1, #94a3b8); }
.todo-card.running .todo-strip { background: linear-gradient(180deg, #60a5fa, #3b82f6); }
.todo-card.success .todo-strip { background: linear-gradient(180deg, #34d399, #10b981); }
.todo-card.failed  .todo-strip { background: linear-gradient(180deg, #f87171, #ef4444); }

.todo-body {
  flex: 1;
  padding: 7px 10px 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  text-align: left;
}

.todo-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.todo-type-badge {
  flex: 0 0 auto;
  font-size: 9.5px;
  font-weight: 700;
  letter-spacing: 0.5px;
  padding: 1px 7px;
  border-radius: 4px;
  background: rgba(99, 102, 241, 0.1);
  color: #4f46e5;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  line-height: 1.6;
}

.todo-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: #111827;
  line-height: 1.35;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: left;
}

.todo-line-sub { font-size: 10.5px; }

.todo-id {
  flex: 1;
  min-width: 0;
  color: #9ca3af;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: left;
}

.todo-status-pill {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 9.5px;
  font-weight: 700;
  padding: 1px 8px;
  border-radius: 999px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  letter-spacing: 0.4px;
  line-height: 1.6;
}

.status-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  flex: 0 0 5px;
}

.todo-card.pending .todo-status-pill { background: rgba(148, 163, 184, 0.16); color: #64748b; }
.todo-card.running .todo-status-pill { background: rgba(59, 130, 246, 0.14); color: #2563eb; }
.todo-card.success .todo-status-pill { background: rgba(16, 185, 129, 0.14); color: #059669; }
.todo-card.failed  .todo-status-pill { background: rgba(239, 68, 68, 0.14); color: #dc2626; }

.todo-card.running .status-dot { animation: dotPulse 1.2s ease-in-out infinite; }

@keyframes dotPulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50%      { opacity: 0.5; transform: scale(1.5); }
}

@media (prefers-color-scheme: dark) {
  .todo-card {
    background: rgba(31, 41, 55, 0.65);
    border-color: rgba(255, 255, 255, 0.06);
  }
  .todo-name { color: #e5e7eb; }
}
</style>
