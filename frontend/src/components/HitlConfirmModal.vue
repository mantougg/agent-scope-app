<script setup lang="ts">
import type { HitlDecision, PendingConfirm } from '../types'

defineProps<{
  pending: PendingConfirm | null
}>()

defineEmits<{
  decide: [decision: HitlDecision]
}>()
</script>

<template>
  <div v-if="pending" class="modal-backdrop" @click.self="$emit('decide', 'USER_REJECTED')">
    <div class="modal" role="dialog" aria-modal="true">
      <header class="modal-header">
        <div class="modal-icon">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M9 11l3 3L22 4"/>
            <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/>
          </svg>
        </div>
        <div class="modal-title-block">
          <h3>确认下发到前端？</h3>
          <p class="modal-subtitle">共 <strong>{{ pending.todos.length }}</strong> 项需求待执行</p>
        </div>
      </header>

      <ul class="preview">
        <li v-for="t in pending.todos" :key="t.id" class="preview-item">
          <span class="preview-type">{{ t.type.replace('CREATE_', '') }}</span>
          <span class="preview-name">{{ t.targetName }}</span>
          <span class="preview-id">{{ t.id }}</span>
        </li>
      </ul>

      <div class="actions">
        <button @click="$emit('decide', 'USER_REJECTED')" class="btn-secondary" type="button">取消</button>
        <button @click="$emit('decide', 'USER_CONFIRMED')" class="btn-primary" type="button">
          <span>确认下发</span>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
            <path d="M5 12h14M13 5l7 7-7 7"/>
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
  animation: backdropIn 0.22s ease;
}

@keyframes backdropIn {
  from { opacity: 0; }
  to   { opacity: 1; }
}

.modal {
  background: #fff;
  border-radius: 18px;
  padding: 24px 26px 22px;
  min-width: 420px;
  max-width: 560px;
  width: 100%;
  box-shadow:
    0 24px 64px rgba(15, 23, 42, 0.25),
    0 0 0 1px rgba(99, 102, 241, 0.08);
  animation: modalIn 0.3s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes modalIn {
  from { opacity: 0; transform: translateY(12px) scale(0.96); }
  to   { opacity: 1; transform: translateY(0) scale(1); }
}

.modal-header {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  margin-bottom: 18px;
}

.modal-icon {
  flex: 0 0 42px;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6 55%, #ec4899);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 6px 18px rgba(139, 92, 246, 0.45);
}

.modal-title-block { flex: 1; min-width: 0; }

.modal-title-block h3 {
  margin: 0 0 4px;
  font-size: 17px;
  font-weight: 700;
  color: #111827;
  letter-spacing: -0.2px;
  line-height: 1.3;
}

.modal-subtitle {
  margin: 0;
  font-size: 13px;
  color: #6b7280;
  line-height: 1.5;
}

.modal-subtitle strong {
  color: #4f46e5;
  font-weight: 700;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.preview {
  list-style: none;
  margin: 0 0 20px;
  padding: 4px;
  max-height: 280px;
  overflow-y: auto;
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 12px;
  background: #f9fafb;
}

.preview::-webkit-scrollbar { width: 6px; }
.preview::-webkit-scrollbar-thumb { background: rgba(99, 102, 241, 0.22); border-radius: 4px; }

.preview-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  font-size: 13px;
  border-radius: 8px;
  transition: background 0.15s ease;
}

.preview-item:hover { background: rgba(99, 102, 241, 0.06); }

.preview-type {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.6px;
  padding: 2px 8px;
  border-radius: 5px;
  background: rgba(99, 102, 241, 0.1);
  color: #4f46e5;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  flex: 0 0 auto;
}

.preview-name {
  flex: 1;
  color: #111827;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-id {
  font-size: 11px;
  color: #9ca3af;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  flex: 0 0 auto;
}

.actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  margin-top: 4px;
}

.btn-primary,
.btn-secondary {
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  padding: 9px 18px;
  border-radius: 11px;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.2s ease, background 0.18s ease, color 0.18s ease;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  border: none;
  line-height: 1;
}

.btn-primary {
  background: linear-gradient(135deg, #6366f1, #8b5cf6 60%, #ec4899);
  color: #fff;
  box-shadow: 0 6px 16px rgba(139, 92, 246, 0.38);
}

.btn-primary:hover {
  transform: translateY(-1px);
  box-shadow: 0 10px 22px rgba(139, 92, 246, 0.5);
}

.btn-primary:active { transform: translateY(0); }

.btn-secondary {
  background: rgba(15, 23, 42, 0.05);
  color: #4b5563;
}

.btn-secondary:hover {
  background: rgba(15, 23, 42, 0.09);
  color: #111827;
}

.btn-secondary:active { background: rgba(15, 23, 42, 0.12); }

@media (prefers-color-scheme: dark) {
  .modal {
    background: #1f2937;
    box-shadow:
      0 24px 64px rgba(0, 0, 0, 0.55),
      0 0 0 1px rgba(99, 102, 241, 0.18);
  }
  .modal-title-block h3 { color: #f3f4f6; }
  .modal-subtitle { color: #cbd5e1; }
  .preview {
    background: rgba(0, 0, 0, 0.25);
    border-color: rgba(255, 255, 255, 0.08);
  }
  .preview-name { color: #e5e7eb; }
  .btn-secondary { background: rgba(255, 255, 255, 0.08); color: #cbd5e1; }
  .btn-secondary:hover { background: rgba(255, 255, 255, 0.14); color: #fff; }
}

@media (max-width: 640px) {
  .modal { min-width: 0; }
}
</style>
