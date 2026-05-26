# docs/screenshots/

本目录存放各 Day 课程的截图 / GIF 录屏，不在 git 历史里跟踪二进制文件的差异，
但 png/gif 本体直接 commit（仓库不大，方便文档跳转引用）。

## 当前文件清单

| 文件 | 来自课程 | 录制要点 |
|------|---------|---------|
| `day6-curl-trace.png`  | [Day06 Phase 3 §6.2 / 验收项](<../lessons/Day06_AG-UI 协议集成（基础）.md>) | `grep '^data:' logs/agui-trace.log \| sed 's/^data: //' \| jq -r '.type' \| sort \| uniq -c` 的输出截图（确认 17 事件类型齐） |
| `day6-end-to-end.gif`  | [Day06 Phase 5 §8.3](<../lessons/Day06_AG-UI 协议集成（基础）.md>) | 30 秒前后端联调录屏，含"输入 → 流式打字机 → TOOL_CALL_* 日志"完整路径，Windows 用 `Win+G` 录后转 GIF |

## 录制 / 转 GIF 工具速查

- **Windows**：`Win+G` 调出 Game Bar 录 MP4 → 用 [ScreenToGif](https://www.screentogif.com/) 或 ffmpeg 转 GIF
- **macOS**：`Cmd+Shift+5` 录 MOV → `ffmpeg -i in.mov -r 12 -s 800x out.gif`
- **Linux**：`peek` 或 `byzanz-record`

## 提交规约

- 文件名按 `day<N>-<场景>.png/gif`，全小写、kebab-case
- 单文件 ≤ 2MB，超了就裁短 / 降帧
- 不在课程文档里写 `<img src="...">`，统一用 `![描述](../screenshots/dayN-xxx.png)` 相对引用
