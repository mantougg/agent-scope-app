<script setup lang="ts">
import type { HitlDecision, UiMsg } from '../types'
defineProps<{ card: UiMsg }>()
defineEmits<{ decide: [decision: HitlDecision] }>()
</script>

<template>
  <div class="bubble hitl-card" :class="{ decided: !!card.hitlDecision }">
    <header class="hitl-head">
      <span class="hitl-icon">✓</span>
      <span class="hitl-title">确认下发到前端？</span>
      <span class="hitl-count">{{ card.hitlTodos?.length ?? 0 }} 项</span>
    </header>

    <ul class="hitl-list">
      <li v-for="t in card.hitlTodos" :key="t.id">
        <span class="hitl-type">{{ t.type.replace('CREATE_', '') }}</span>
        <span class="hitl-name">{{ t.targetName }}</span>
        <span class="hitl-id">{{ t.id }}</span>
      </li>
    </ul>

    <div v-if="!card.hitlDecision" class="hitl-actions">
      <button class="btn-secondary" type="button"
              @click="$emit('decide', 'USER_REJECTED')">取消</button>
      <button class="btn-primary" type="button"
              @click="$emit('decide', 'USER_CONFIRMED')">
        <span>确认下发</span>
      </button>
    </div>
    <div v-else class="hitl-decided">
      {{ card.hitlDecision === 'USER_CONFIRMED'
         ? '✓ 已确认下发'
         : '✕ 已取消（清空待办）' }}
    </div>
  </div>
</template>

<style scoped>
.hitl-card {
  background: linear-gradient(180deg, rgba(99, 102, 241, 0.04), rgba(255, 255, 255, 0.92));
  border: 1px solid rgba(99, 102, 241, 0.18);
  border-radius: 14px;
  padding: 14px 16px;
  box-shadow: 0 8px 24px rgba(99, 102, 241, 0.10);
  max-width: 480px;
  font-size: 14px;
  color: #111827;
}

.hitl-card.decided { opacity: 0.7; }

.hitl-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.hitl-icon {
  width: 24px;
  height: 24px;
  border-radius: 8px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6 60%, #ec4899);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
  box-shadow: 0 4px 10px rgba(139, 92, 246, 0.35);
}

.hitl-title {
  font-weight: 700;
  font-size: 14px;
  color: #111827;
}

.hitl-count {
  margin-left: auto;
  font-size: 11px;
  color: #4f46e5;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  background: rgba(99, 102, 241, 0.10);
  border: 1px solid rgba(99, 102, 241, 0.20);
  padding: 2px 8px;
  border-radius: 999px;
}

.hitl-list {
  list-style: none;
  margin: 0 0 12px;
  padding: 4px;
  max-height: 220px;
  overflow-y: auto;
  background: #f9fafb;
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 10px;
}

.hitl-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 10px;
  font-size: 13px;
  border-radius: 6px;
}

.hitl-list li:hover { background: rgba(99, 102, 241, 0.06); }

.hitl-type {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 10px;
  font-weight: 700;
  background: rgba(99, 102, 241, 0.10);
  color: #4f46e5;
  padding: 2px 8px;
  border-radius: 5px;
}

.hitl-name {
  flex: 1;
  color: #111827;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hitl-id {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  color: #9ca3af;
}

.hitl-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.hitl-actions button {
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  padding: 7px 14px;
  border-radius: 10px;
  border: none;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.2s ease, background 0.18s ease;
}

.btn-primary {
  background: linear-gradient(135deg, #6366f1, #8b5cf6 60%, #ec4899);
  color: #fff;
  box-shadow: 0 4px 12px rgba(139, 92, 246, 0.35);
}

.btn-primary:hover { transform: translateY(-1px); box-shadow: 0 8px 18px rgba(139, 92, 246, 0.45); }

.btn-secondary {
  background: rgba(15, 23, 42, 0.05);
  color: #4b5563;
}

.btn-secondary:hover { background: rgba(15, 23, 42, 0.09); color: #111827; }

.hitl-decided {
  font-size: 13px;
  color: #4f46e5;
  font-weight: 600;
  padding: 6px 2px;
  text-align: right;
}

@media (prefers-color-scheme: dark) {
  .hitl-card {
    background: linear-gradient(180deg, rgba(99, 102, 241, 0.10), rgba(31, 41, 55, 0.92));
    color: #e5e7eb;
    border-color: rgba(99, 102, 241, 0.30);
  }
  .hitl-title { color: #f3f4f6; }
  .hitl-list {
    background: rgba(0, 0, 0, 0.25);
    border-color: rgba(255, 255, 255, 0.08);
  }
  .hitl-name { color: #e5e7eb; }
  .btn-secondary { background: rgba(255, 255, 255, 0.08); color: #cbd5e1; }
  .btn-secondary:hover { background: rgba(255, 255, 255, 0.14); color: #fff; }
}
</style>
