// Vite + monaco-editor: 全局只配一次 worker 工厂。
// 不在这里 import monaco-editor 主包，避免冷启动时连带 5MB 全量加载——
// JsonViewer 真正用到时再按需 import。
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'
import JsonWorker from 'monaco-editor/esm/vs/language/json/json.worker?worker'

let initialized = false

export function initMonaco() {
  if (initialized) return
  initialized = true
  ;(self as unknown as { MonacoEnvironment: unknown }).MonacoEnvironment = {
    getWorker(_workerId: string, label: string) {
      if (label === 'json') return new JsonWorker()
      return new EditorWorker()
    },
  }
}
