# Day 0 · 环境准备

> 上级文档：[../learning.md](../learning.md)
> 配套笔记：[../agents/_links.md](../agents/_links.md)

## 0. 一句话目标

**进 Day 1 之前**，你的开发机能做到：

1. `java -version` 报 17.x
2. `mvn -v` 报 3.9+
3. `node -v` 报 v20.x
4. `docker --version` 能跑（Day 7 才真用，但提前装好不耽误事）
5. Git Bash 里 `curl --version` 和 `jq --version` 都能输出（Windows 用户重点）
6. 环境变量 `ARK_API_KEY` 在新开终端里 `echo` 得到

> 📌 本课程主跑在 **Windows 11**，但同样适用 macOS / Linux。每节给出 Windows 主路径 + 一句话其它平台等价命令。

## 1. 一次性自检脚本

先把这一段拷贝到 Git Bash（Windows）或 zsh/bash（mac/Linux）跑一遍，**看哪些项报错就跳到对应章节**：

```bash
echo "=== Day 0 环境自检 ==="
echo -n "JDK 17+      : "; java -version 2>&1 | head -1
echo -n "Maven 3.9+   : "; mvn -v 2>&1 | head -1
echo -n "Node 20+     : "; node -v
echo -n "npm          : "; npm -v
echo -n "Docker       : "; docker --version 2>/dev/null || echo '未装（Day 7 才用）'
echo -n "curl         : "; curl --version 2>&1 | head -1
echo -n "jq           : "; jq --version 2>/dev/null || echo '未装（Day 6 起需要）'
echo -n "git          : "; git --version
echo -n "ARK_API_KEY  : "; [ -n "$ARK_API_KEY" ] && echo "已设置（${#ARK_API_KEY} 字符）" || echo '未设置'
echo ""
echo "全部项都有输出 + 无 '未设置' 后即可进 Day 1"
```

期望输出（数字版本可以更高，但不能更低）：

```
JDK 17+      : openjdk version "17.0.10" 2024-01-16
Maven 3.9+   : Apache Maven 3.9.6
Node 20+     : v20.11.1
npm          : 10.2.4
Docker       : Docker version 26.1.4
curl         : curl 8.4.0
jq           : jq-1.7.1
git          : git version 2.45.1
ARK_API_KEY  : 已设置（68 字符）
```

---

## 2. JDK 17

**为什么是 17**：`pom.xml` 写死 `maven.compiler.release=17`，不能降。AS-Java 1.0.12 用到 `record` / `sealed` / `switch pattern`，<17 编译不过。18+ 可以但不推荐（团队基线 17）。

### 2.1 Windows

```powershell
# 用 scoop（推荐）
scoop install temurin17-jdk

# 或用 choco
choco install temurin17 -y

# 验证
java -version
```

如果你机器上有多个 JDK（公司常态），需要把 17 设成默认：

1. 控制面板 → 系统 → 高级系统设置 → 环境变量
2. **系统变量** 新建 `JAVA_HOME` = `C:\Users\你\scoop\apps\temurin17-jdk\current`（按实际路径）
3. **系统变量** `Path` 把 `%JAVA_HOME%\bin` **置顶**（覆盖 Path 里其他老 JDK）
4. **新开**一个终端，`where java` 应该首行就是 17 的路径

### 2.2 macOS

```bash
brew install --cask temurin@17
# JAVA_HOME 加进 ~/.zshrc：
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 17)' >> ~/.zshrc
source ~/.zshrc
```

### 2.3 Linux

```bash
# Ubuntu/Debian
sudo apt install temurin-17-jdk

# CentOS/RHEL
sudo dnf install temurin-17-jdk
```

### 2.4 IDE 对齐

IntelliJ：**File → Project Structure → Project**，把 SDK 选为 17、Language level 选 `17 - Sealed types, ...`。否则 IDE 跑跟命令行跑会版本不一致。

---

## 3. Maven 3.9+

**为什么 3.9+**：`spring-boot-maven-plugin` 3.2+ 要 Maven 3.6.3+，但 3.9 是当前稳定线，依赖解析速度比 3.6 快一截。

### 3.1 安装

```powershell
# Windows
scoop install maven

# macOS
brew install maven

# Linux
sudo apt install maven
```

验证：`mvn -v` 应输出 3.9.x 起。

### 3.2 配镜像源（中国大陆必做）

不配的话拉 `spring-boot-dependencies` 能等 10 分钟+。

`~/.m2/settings.xml`（Windows 是 `C:\Users\你\.m2\settings.xml`，不存在就新建）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors>
    <mirror>
      <id>aliyun-maven</id>
      <name>Aliyun Maven Mirror</name>
      <url>https://maven.aliyun.com/repository/public</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
</settings>
```

> 📌 **只镜像 `central`**，不要写 `*`——AS-Java 的 `io.agentscope` artifact 在 Sonatype，写 `*` 会让阿里云拦截再 404 回来。当前写法只代理 maven central，其他仓库照常走源站。

验证：第一次 `mvn -U dependency:resolve` 下载日志里出现 `aliyun.com` 即生效。

### 3.3 增大下载超时（可选）

如果你网络抖动严重，加进 `settings.xml` 的 `<settings>` 顶层：

```xml
<servers>
  <server>
    <id>central</id>
    <configuration>
      <httpConfiguration>
        <all><connectionTimeout>60000</connectionTimeout></all>
      </httpConfiguration>
    </configuration>
  </server>
</servers>
```

---

## 4. Node 20 + npm

**何时用**：Day 6 起的 `frontend/` Vue3 工程。Day 1-5 用不到 Node。

**为什么 20**：`@ag-ui/client` 是 ESM-only，要求 Node ≥ 18；20 是当前 LTS，**别**用 21/22 这种 odd-numbered（非 LTS）。

### 4.1 Windows（强烈建议走 nvm-windows）

```powershell
scoop install nvm
nvm install 20
nvm use 20
node -v   # 应该输出 v20.x
```

### 4.2 macOS / Linux

```bash
# 安装 nvm
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
source ~/.bashrc  # 或 ~/.zshrc
nvm install 20
nvm use 20
```

### 4.3 npm 镜像（中国大陆必做）

```bash
npm config set registry https://registry.npmmirror.com
npm config get registry   # 验证
```

如果某天要安装 npm 公网才有的包，临时切回：

```bash
npm install xxx --registry=https://registry.npmjs.org
```

---

## 5. Docker Desktop（Day 7 才用，建议现在装）

Day 7 Phase 3b 跑 Jaeger 本地 all-in-one 用 Docker。安装慢、占空间大，**前置装好不耽误 Day 1-6**。

### 5.1 Windows

下载 Docker Desktop：https://www.docker.com/products/docker-desktop/

装完先在系统托盘启动一次，确认状态栏里小鲸鱼是绿的。

> ⚠️ Windows Home 版需要打开 **WSL 2**（控制面板 → 启用或关闭 Windows 功能 → 适用于 Linux 的 Windows 子系统）；Windows Pro 用 Hyper-V 也行。

### 5.2 macOS

```bash
brew install --cask docker
# 或下安装包：https://www.docker.com/products/docker-desktop/
```

### 5.3 Linux

```bash
# 用 systemd 跑 docker 守护进程
sudo apt install docker.io docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker $USER   # 把当前用户加进 docker 组，免 sudo
# 重新登录终端生效
```

### 5.4 验证

```bash
docker run --rm hello-world
```

应该看到 "Hello from Docker!" 字样。

> 📌 不想装 Docker？Day 7 也可以下 Jaeger 的 native 二进制（GitHub Releases），跑 `./jaeger-all-in-one` 同样效果。但用 Docker 整洁、卸载干净。

---

## 6. Git Bash + jq（Windows 学员重点）

**为什么 Day 6 起需要 Git Bash**：Day 6/7 的命令都按 Unix shell 写（`curl -N` / `grep ^data:` / `jq` / pipe），cmd 跟 PowerShell 跑不动。**直接全程用 Git Bash 是最省事的**。

### 6.1 Git for Windows（自带 Git Bash + curl）

下载：https://git-scm.com/download/win

装时一路默认，但记得**勾上**"Git from the command line and also from 3rd-party software"。

装完桌面右键能看到 "Git Bash Here"——这就是 Day 6/7 的主终端。

### 6.2 jq（JSON 命令行处理器）

Day 6 §6.2 用 jq 数 AG-UI 事件分布；Day 7 §6.5 用 jq 按 traceId 过日志。**不装的话这两节命令跑不动**。

```powershell
# Windows
scoop install jq
# 或
choco install jq -y

# macOS
brew install jq

# Linux
sudo apt install jq
```

验证：`jq --version` 应该输出 `jq-1.6` 或更高。

### 6.3 Windows Terminal（强烈推荐）

默认 Git Bash 窗口字体丑、中文偶尔乱码。**Microsoft Store 装 Windows Terminal**，里面把 Git Bash 加为一个 profile，体验飞跃。

Profile 的命令行字段：`C:\Program Files\Git\bin\bash.exe -i -l`

---

## 7. IDE：IntelliJ IDEA（社区版即可）

**为什么不用 VS Code**：本课 Java 重，IDEA 对 `record` / `@Tool` 注解 / Maven 项目结构理解最深；前端 Vue3 部分 VS Code 也很合适，**双 IDE 并行**最舒服。

### 7.1 安装

下载社区版：https://www.jetbrains.com/idea/download/

### 7.2 必装插件

- **Maven Helper**（依赖冲突排查神器，Day 1/4/6 都会用）
- **Lombok**（即使我们不用 Lombok，AS-Java 传递依赖里可能有）
- **AgentScope** 官方插件（如果有；没有也不影响）

### 7.3 项目导入

`File → Open → 选项目根目录 pom.xml`，**等 Maven indexing 跑完**（右下角进度条），再开始写代码。否则 IDE 报红比 LLM 还多。

### 7.4 关键设置

- **File → Settings → Build → Build Tools → Maven**：勾上 "Always update snapshots"
- **File → Settings → Editor → File Encodings**：所有字段都设 **UTF-8**，否则 Windows 中文乱码

---

## 8. 火山方舟 API Key（默认 LLM provider）

本课默认走火山方舟（豆包），OpenAI 兼容协议。换 DashScope/OpenAI/DeepSeek 改 `application.conf` 的 `baseUrl` 即可，但 API Key 必须有。

### 8.1 申请

1. 注册 https://www.volcengine.com/product/ark
2. 控制台 → API Key 管理 → 创建一个
3. 同一个控制台开通 `doubao-1-5-pro-32k-250115`（或最新 doubao-pro 模型）的访问权

> 💸 **额度**：方舟新用户有免费额度（具体数额看官网），跑完 7 天课程完全够。重度调试可能撞上限——遇到 429 / quota exceeded 把模型换 doubao-lite。

### 8.2 设环境变量

**Windows**（PowerShell，永久级）：

```powershell
[System.Environment]::SetEnvironmentVariable('ARK_API_KEY', 'sk-xxxxx', 'User')
```

设完**关闭所有终端重开**才生效（包括 IDEA、Git Bash）。

**macOS / Linux**（写进 `~/.zshrc` 或 `~/.bashrc`）：

```bash
echo 'export ARK_API_KEY=sk-xxxxx' >> ~/.zshrc
source ~/.zshrc
```

### 8.3 验证

```bash
# 新开终端
echo $ARK_API_KEY   # 应该输出你的 key
```

> 🔒 **千万不要**：把 key 写进 `application.conf` 提交进 git；写 `application-local.conf` 留本地 OK，那个文件已 gitignored。

---

## 9. 验收清单

跑完上面 §1 的自检脚本，**每项都不报错** + 跑下面这段端到端测试：

```bash
# 1. 拉项目（如果还没 clone）
git clone https://github.com/your-org/agent-scope-app.git
cd agent-scope-app

# 2. 编译
mvn -q compile

# 3. 跑离线测试（不需要 API Key）
mvn -q test

# 期望：BUILD SUCCESS，至少 30+ 个测试通过
```

如果都过，**今天可以开始 Day 1 了**。

---

## 10. 故障排查表

| 现象 | 原因 / 解法 |
|------|-----------|
| `java -version` 报 1.8 / 11 | 装了但 PATH 里被旧版本压着；走 §2.1 的 JAVA_HOME 置顶 |
| IntelliJ 编译报 "release 17 not supported" | IDE 配的还是老 JDK；§2.4 改 Project SDK |
| `mvn compile` 卡在 "Downloading from central" | 没配阿里云镜像；§3.2 配上重跑 |
| `mvn dependency:tree` 报 401 / 403 | settings.xml 里 `<mirrorOf>` 写成了 `*`，把 Sonatype 也代理了；改成 `central` |
| `mvn test` 中文乱码 | `pom.xml` surefire 已加 `-Dfile.encoding=UTF-8`；如果还乱，IDEA 设置改 UTF-8、Windows cmd 跑 `chcp 65001`，或者直接在 Git Bash 跑 |
| `npm install` 拉某个包卡住 | npm 镜像没配；§4.3 配上重跑 |
| `nvm use 20` 报 "version not found" | 先 `nvm install 20` 再 use |
| Git Bash 里 `jq` 报 command not found | §6.2 装 jq；Windows Terminal 重启一次 |
| Docker 启动慢 / 报 WSL 错误 | Windows Home 走 §5.1 的 WSL 2；Pro 用 Hyper-V |
| `echo $ARK_API_KEY` 空 | 环境变量设了但终端没重开；**关掉所有终端 + IDEA 重开** |
| 跑 `mvn -Dgroups=live test` 报 "API key 未配置" | live 测试要真 key；先 `echo $ARK_API_KEY` 确认非空 |
| IDEA 跑 main 没 ARK_API_KEY | IDEA 启动时间早于环境变量设置；重启 IDEA |

---

## 11. 写在 Day 1 之前

Day 0 不是水时间——**环境是 7 天课程的地基**。我见过太多人在 Day 1 卡在"Maven 拉不到包 / IDEA 报红 / 中文乱码"上耗一整天，然后觉得"AS-Java 好难"。

把上面这些**提前一次性踩平**，Day 1 起你只需要关心 Agent 本身。

Day 1 走起：[Day01_项目骨架 + AS-Java Hello World.md](<Day01_项目骨架 + AS-Java Hello World.md>)
