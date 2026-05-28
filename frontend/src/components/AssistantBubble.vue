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

<style scoped>
.assistant-bubble {
  padding: 12px 16px;
  border-radius: 16px;
  border-top-left-radius: 4px;
  font-size: 14.5px;
  line-height: 1.65;
  background: #fff;
  color: #111827;
  border: 1px solid rgba(0, 0, 0, 0.05);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.04);
  text-align: left;
  word-wrap: break-word;
  word-break: break-word;
}

.assistant-bubble :deep(h1) { font-size: 18px; margin: 8px 0 6px; }
.assistant-bubble :deep(h2) { font-size: 16px; margin: 8px 0 6px; }
.assistant-bubble :deep(h3) { font-size: 14.5px; margin: 6px 0 4px; }
.assistant-bubble :deep(p) { margin: 6px 0; }
.assistant-bubble :deep(ul),
.assistant-bubble :deep(ol) { margin: 6px 0; padding-left: 22px; }
.assistant-bubble :deep(li) { margin: 2px 0; }
.assistant-bubble :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  background: rgba(99, 102, 241, 0.10);
  color: #4f46e5;
  padding: 1px 6px;
  border-radius: 4px;
}
.assistant-bubble :deep(pre) {
  background: #0b1020;
  color: #e5e7eb;
  padding: 12px 14px;
  border-radius: 10px;
  overflow-x: auto;
  margin: 8px 0;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.18);
}
.assistant-bubble :deep(pre code) {
  background: transparent;
  color: inherit;
  padding: 0;
}

.cursor {
  display: inline-block;
  margin-left: 2px;
  animation: blink 1s steps(2) infinite;
  color: #8b5cf6;
}

@keyframes blink { 50% { opacity: 0; } }

@media (prefers-color-scheme: dark) {
  .assistant-bubble {
    background: #1f2937;
    color: #e5e7eb;
    border-color: rgba(255, 255, 255, 0.06);
  }
  .assistant-bubble :deep(code) {
    background: rgba(99, 102, 241, 0.22);
    color: #c7d2fe;
  }
}
</style>
