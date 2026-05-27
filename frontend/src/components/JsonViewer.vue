<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type * as Monaco from 'monaco-editor'
import { initMonaco } from '../monacoSetup'

const props = withDefaults(defineProps<{
  value: string
  language?: string
}>(), {
  language: 'json',
})

const container = ref<HTMLDivElement | null>(null)
let editor: Monaco.editor.IStandaloneCodeEditor | null = null
let themeDefined = false

onMounted(async () => {
  if (!container.value) return
  initMonaco()
  // 按需加载 monaco 主包：Vite 会自动 code-split，首次进入页面时不会下载这 ~3MB
  const monaco = await import('monaco-editor')
  if (!container.value) return  // 卸载得比 await 还快

  if (!themeDefined) {
    monaco.editor.defineTheme('scope-dark', {
      base: 'vs-dark',
      inherit: true,
      rules: [
        { token: 'string.key.json', foreground: '#a5b4fc' },
        { token: 'string.value.json', foreground: '#86efac' },
        { token: 'number', foreground: '#fbbf24' },
        { token: 'keyword.json', foreground: '#f0abfc' },
      ],
      colors: {
        'editor.background': '#0f172a',
        'editor.foreground': '#e2e8f0',
        'editor.lineHighlightBackground': '#1e293b80',
        'editorGutter.background': '#0f172a',
        'editorLineNumber.foreground': '#475569',
        'editorLineNumber.activeForeground': '#a5b4fc',
        'editorIndentGuide.background1': '#1e293b',
        'editorIndentGuide.activeBackground1': '#334155',
      },
    })
    themeDefined = true
  }

  editor = monaco.editor.create(container.value, {
    value: props.value,
    language: props.language,
    theme: 'scope-dark',
    readOnly: true,
    minimap: { enabled: false },
    automaticLayout: true,
    scrollBeyondLastLine: false,
    fontSize: 12.5,
    lineHeight: 20,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace',
    // —— 左侧 gutter 尽量塞紧：去 glyph 列、去行号、去装饰列宽，仅保留折叠（嵌套 JSON 很有用）
    lineNumbers: 'off',
    glyphMargin: false,
    lineDecorationsWidth: 0,
    lineNumbersMinChars: 0,
    folding: true,
    foldingHighlight: false,
    showFoldingControls: 'always',
    wordWrap: 'on',
    renderLineHighlight: 'none',
    contextmenu: false,
    smoothScrolling: true,
    padding: { top: 10, bottom: 10 },
    scrollbar: {
      verticalScrollbarSize: 8,
      horizontalScrollbarSize: 8,
      alwaysConsumeMouseWheel: false,
    },
    overviewRulerLanes: 0,
    overviewRulerBorder: false,
    hideCursorInOverviewRuler: true,
    guides: { indentation: true, highlightActiveIndentation: false },
  })
})

watch(() => props.value, (val) => {
  if (editor && editor.getValue() !== val) {
    editor.setValue(val)
  }
})

onBeforeUnmount(() => {
  editor?.dispose()
  editor = null
})
</script>

<template>
  <div ref="container" class="json-viewer-monaco"/>
</template>

<style scoped>
.json-viewer-monaco {
  width: 100%;
  height: 100%;
  border-radius: 12px;
  overflow: hidden;
  background: #0f172a;
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.06),
    0 6px 18px rgba(15, 23, 42, 0.25);
}

.json-viewer-monaco :deep(.monaco-editor),
.json-viewer-monaco :deep(.monaco-editor .overflow-guard) {
  border-radius: 12px;
}

.json-viewer-monaco :deep(.monaco-editor.no-user-select .view-lines) {
  width: 20px !important;
}
</style>
