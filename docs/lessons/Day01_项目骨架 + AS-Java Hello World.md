# Day 1 · 项目骨架 + AS-Java Hello World

> 上级文档：[../learning.md](../learning.md)
> 配套笔记：[../agents/01-overview.md](../agents/01-overview.md) · [../agents/02-core-concepts.md](../agents/02-core-concepts.md) · [../agents/05-model-providers.md](../agents/05-model-providers.md)

## 0. 一句话目标

**今天结束时**，你能在自己机器上跑 `mvn exec:java`，敲一句中文，看到 LLM 回复，并在 `logs/scope.log` 里看到完整调用痕迹。

## 1. 学习目标

- ✅ 熟悉 AS-Java 的 Maven 依赖与最小工程组织
- ✅ 跑通 `ReActAgent.call(...).block()` 同步调用
- ✅ 跑通 `ReActAgent.stream(...)` 流式调用
- ✅ 接入 logback 日志、Typesafe Config 配置
- ✅ 形成可被后续 6 天复用的目录骨架

## 2. 时间盒（建议 8 学时）

| 阶段 | 时长 | 主题 | 验收 |
|------|------|------|------|
| Phase 0 | 30 min | 环境就绪 | `java -version` / `mvn -v` / 测 API key |
| Phase 1 | 60 min | Maven 骨架 | `mvn compile` 通过 |
| Phase 2 | 60 min | 基础设施（日志 + 配置） | 跑 `ConfigCheck` 能输出日志和读到配置 |
| Phase 3 | 90 min | Hello World（同步） | 命令行能问 LLM、收到回答 |
| Phase 4 | 60 min | 流式 + REPL 循环 | 增量看到模型吐字、可连续多轮 |
| Phase 5 | 60 min | 拓展练习（任选 1-2） | 见 §10 |
| Phase 6 | 30 min | 收尾：git commit + 总结 | 第一次提交 |

---

## 3. Phase 0 · 环境就绪（30 min）

### 3.1 软件检查

```bash
java -version   # 必须 17+
mvn -v          # 建议 3.8+
git --version   # 任意现代版本
```

不满足就先装：
- **JDK**：推荐 [Eclipse Temurin 17](https://adoptium.net/) 或 17 LTS
- **Maven**：[官网下载](https://maven.apache.org/download.cgi) 解压后把 `bin` 加入 PATH

### 3.2 IDE

- IntelliJ IDEA Community（推荐）
- 或 VS Code + Extension Pack for Java

### 3.3 API Key

本课程默认走 **火山引擎方舟（Volcengine Ark，豆包系列）** —— OpenAI 兼容协议，国内速度快，新账号有免费体验额度。

1. 去 https://console.volcengine.com/ark 开通"方舟"服务并完成实名
2. 在「API Key 管理」生成一个 key（形如 `apikey-xxxxxxxx`）
3. 在「在线推理 → 创建接入点」给你想用的模型建一个**接入点**（Endpoint），拿到 `ep-2024xxxxxxxx-xxxxx` 这种 ID；或者直接用模型 ID 比如 `doubao-1-5-pro-32k-250115`
4. 设置环境变量：

   **Windows（PowerShell，永久）**
   ```powershell
   [System.Environment]::SetEnvironmentVariable('ARK_API_KEY','apikey-xxxxxxxx','User')
   # 关闭并重开终端，新进程才能读到
   ```

   **Windows（cmd / Git Bash 临时）**
   ```bash
   export ARK_API_KEY=apikey-xxxxxxxx
   ```

   **macOS / Linux**
   ```bash
   echo 'export ARK_API_KEY=apikey-xxxxxxxx' >> ~/.zshrc   # 或 ~/.bashrc
   source ~/.zshrc
   ```

> ⚠️ Windows 用户级（User scope）的环境变量**只对设置之后新开的进程生效**。如果设完后 `echo $ARK_API_KEY` 还是空的，关掉当前终端再开一个。

> 🔁 **想用别家模型？** 把 §6 的 `OpenAIChatModel`（含 `baseUrl`）换成 `DashScopeChatModel` / `AnthropicChatModel` 即可，参数对照见 [../agents/05-model-providers.md](../agents/05-model-providers.md)；也见本文 §8.1 拓展 A。

### 3.4 API Key 自检（强烈建议）

跳过这一步 = 后面 Phase 3 卡住时不知道是配置错还是代码错。先用 curl 试：

```bash
curl -X POST https://ark.cn-beijing.volces.com/api/v3/chat/completions \
  -H "Authorization: Bearer $ARK_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "doubao-1-5-pro-32k-250115",
    "messages": [{"role": "user", "content": "ping"}]
  }'
```

期望返回 200 + 包含 `choices` 字段的 JSON。常见错误：
- `401` → key 错或者格式不对
- `404` → 模型 ID / 接入点 ID 错；接入点 ID 是 `ep-xxx`，模型 ID 是 `doubao-x-x-xx-xxxxxx`
- 网络问题 → 检查代理

> 如果你在控制台建了**接入点**，把 `"model"` 换成 `"ep-xxxxxxx"` 也能跑——Ark 同时接受模型 ID 和接入点 ID。

### ✅ Phase 0 验收

- [ ] `java -version` 输出 17+
- [ ] `mvn -v` 正常
- [ ] `echo $ARK_API_KEY`（Win 用 `echo %ARK_API_KEY%`）能打印出 `apikey-...`
- [ ] curl 测试返回正常 JSON

---

## 4. Phase 1 · Maven 骨架（60 min）

### 4.1 新建工程

工作目录已经是 `D:\AI\Agents\agent-scope-app`，直接在里面建即可（不要外面再套一层）。

### 4.2 目录结构

```
agent-scope-app/
├── pom.xml
├── .gitignore
├── README.md
├── docs/                                  ← 已经存在（learning.md / agents/ / lessons/）
├── logs/                                  ← 运行后自动生成
└── src/
    ├── main/
    │   ├── java/space/wlshow/scope/
    │   │   ├── ScopeApp.java              ← Phase 3 写
    │   │   ├── ConfigCheck.java           ← Phase 2 写（验完可删）
    │   │   ├── agent/
    │   │   │   └── AgentFactory.java      ← Phase 3 写
    │   │   └── config/
    │   │       └── AppConfig.java         ← Phase 2 写
    │   └── resources/
    │       ├── application.conf           ← Phase 2 写
    │       └── logback.xml                ← Phase 2 写
    └── test/
        └── java/space/wlshow/scope/
            └── HelloTest.java             ← Phase 1 写（占位）
```

> 本文统一用 `space.wlshow.scope` 作示例包名（与本仓库实际一致）。你要换成自己的，**所有出现 `space.wlshow.scope` 的地方都得同步换**——代码 package、`logback.xml` 的 `<logger name="...">`、`pom.xml` 里 `<exec.mainClass>`、命令行 `-Dexec.mainClass=...`。

### 4.3 `pom.xml`

整文件直接拷贝：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>space.wlshow.scope</groupId>
  <artifactId>agent-scope-app</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>jar</packaging>
  <name>agent-scope-app</name>

  <properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <agentscope.version>1.0.12</agentscope.version>
    <!-- 默认 main class；命令行用 -Dexec.mainClass=... 可临时覆盖 -->
    <exec.mainClass>space.wlshow.scope.ScopeApp</exec.mainClass>
  </properties>

  <dependencies>
    <!-- AS-Java 核心 -->
    <dependency>
      <groupId>io.agentscope</groupId>
      <artifactId>agentscope</artifactId>
      <version>${agentscope.version}</version>
    </dependency>

    <!-- 日志 -->
    <dependency>
      <groupId>ch.qos.logback</groupId>
      <artifactId>logback-classic</artifactId>
      <version>1.5.6</version>
    </dependency>

    <!-- 配置 -->
    <dependency>
      <groupId>com.typesafe</groupId>
      <artifactId>config</artifactId>
      <version>1.4.3</version>
    </dependency>

    <!-- 测试 -->
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.10.2</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
      </plugin>
      <!-- 让 mvn exec:java 直接跑 main -->
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.2.0</version>
        <configuration>
          <!-- 注意：用 ${} 占位符，不要写字面值，否则 -D 覆盖不了，见附录 B-1 -->
          <mainClass>${exec.mainClass}</mainClass>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

> ⚠️ **`<mainClass>` 必须用 `${exec.mainClass}` 占位符，不要写字面值**。如果写死成 `<mainClass>space.wlshow.scope.ScopeApp</mainClass>`，那命令行的 `-Dexec.mainClass=xxx` 会被 pom 静默吞掉——Maven 插件参数解析里 pom 字面值 > user property。后果就是你以为在跑 ConfigCheck，实际跑的是 ScopeApp。详见附录 B-1。

### 4.4 `.gitignore`

```gitignore
# Build
target/
*.class

# IDE
.idea/
*.iml
.vscode/
.project
.classpath
.settings/

# OS
.DS_Store
Thumbs.db

# Logs
logs/
*.log
*.log.gz

# Secrets / local
.env
*.local
src/main/resources/application-local.conf
```

### 4.5 占位测试

`src/test/java/space/wlshow/scope/HelloTest.java`：

```java
package space.wlshow.scope;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelloTest {
    @Test
    void sanity() {
        assertTrue(1 + 1 == 2);
    }
}
```

### 4.6 验证依赖能拉下来

```bash
mvn -U dependency:resolve
mvn test
```

第一次会下很多包。`BUILD SUCCESS` 即可。

### ✅ Phase 1 验收

- [ ] `pom.xml`、`.gitignore` 就位
- [ ] `mvn -U dependency:resolve` 无错
- [ ] `mvn test` 输出 `Tests run: 1, Failures: 0`
- [ ] IDE 能正常识别项目（IntelliJ 选 "Open" 然后选 pom.xml）

### 🚨 常见错误

| 现象 | 原因 | 解决 |
|------|------|------|
| 找不到 `io.agentscope:agentscope` | 国内仓库慢或没代理 | 在 `~/.m2/settings.xml` 加阿里云镜像（见附录 A） |
| `Source option 17 is no longer supported` | JDK < 17 | 升级 JDK |
| Maven 卡 30 秒不动 | 仓库走代理超时 | 加 `-DsocketTimeout=120000`，或换镜像 |

---

## 5. Phase 2 · 日志与配置（60 min）

### 5.1 `src/main/resources/logback.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level %logger{24} - %msg%n</pattern>
      <charset>UTF-8</charset>
    </encoder>
  </appender>

  <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/scope.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>logs/scope.%d{yyyy-MM-dd}.log.gz</fileNamePattern>
      <maxHistory>14</maxHistory>
    </rollingPolicy>
    <encoder>
      <pattern>%d{ISO8601} %-5level [%thread] %logger - %msg%n</pattern>
      <charset>UTF-8</charset>
    </encoder>
  </appender>

  <!-- 业务包打开 DEBUG -->
  <logger name="space.wlshow.scope" level="DEBUG"/>

  <!-- 框架日志降噪 -->
  <logger name="io.agentscope" level="INFO"/>
  <logger name="reactor" level="WARN"/>
  <logger name="okhttp3" level="WARN"/>
  <logger name="io.netty" level="WARN"/>

  <root level="INFO">
    <appender-ref ref="STDOUT"/>
    <appender-ref ref="FILE"/>
  </root>

</configuration>
```

> ⚠️ Windows 中文用户**强烈建议保留** `<charset>UTF-8</charset>` 两处。logback 默认 ConsoleAppender 走 JVM 默认编码（Windows 中文版是 GBK），把 UTF-8 字节写到 GBK 终端就成了 `��������`。详见附录 B-2。

### 5.2 `src/main/resources/application.conf`

[HOCON](https://github.com/lightbend/config/blob/main/HOCON.md) 格式，比 properties 更友好：

```hocon
model {
  provider = "volcengine-ark"
  # 模型 ID（如 doubao-1-5-pro-32k-250115）或接入点 ID（如 ep-2024xxxxxxxx-xxxxx）
  name     = "doubao-1-5-pro-32k-250115"
  baseUrl  = "https://ark.cn-beijing.volces.com/api/v3"
  # ${?VAR}: 环境变量存在则覆盖；不存在则该 key 不会被设置（hasPath 返回 false）
  apiKey   = ${?ARK_API_KEY}
}

agent {
  name      = "RequirementAnalyst"
  maxIters  = 15
  timeout   = 120s
  sysPrompt = """
    你是需求分析助手，Day 1 阶段仅用于联通性验证。
    回答尽量简洁，不超过 3 句话。
    """
}
```

### 5.3 `AppConfig.java`

`src/main/java/space/wlshow/scope/config/AppConfig.java`：

```java
package space.wlshow.scope.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.time.Duration;

public final class AppConfig {

    private static final Config CFG = ConfigFactory.load();

    public static String modelProvider() {
        return CFG.getString("model.provider");
    }

    public static String modelName() {
        return CFG.getString("model.name");
    }

    public static String modelBaseUrl() {
        return CFG.getString("model.baseUrl");
    }

    public static String modelApiKey() {
        if (!CFG.hasPath("model.apiKey") || CFG.getString("model.apiKey").isBlank()) {
            throw new IllegalStateException(
                "API key 未配置。请设置环境变量 ARK_API_KEY 后重启进程。");
        }
        return CFG.getString("model.apiKey");
    }

    public static String agentName()  { return CFG.getString("agent.name"); }
    public static int    maxIters()   { return CFG.getInt("agent.maxIters"); }
    public static Duration timeout()  { return CFG.getDuration("agent.timeout"); }
    public static String sysPrompt()  { return CFG.getString("agent.sysPrompt").trim(); }

    private AppConfig() {}
}
```

### 5.4 自检 main

临时写一个最小 main 验证配置 + 日志（验完可以删，下一阶段会被 `ScopeApp` 取代）：

`src/main/java/space/wlshow/scope/ConfigCheck.java`：

```java
package space.wlshow.scope;

import space.wlshow.scope.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigCheck {
    private static final Logger log = LoggerFactory.getLogger(ConfigCheck.class);

    public static void main(String[] args) {
        log.info("provider = {}", AppConfig.modelProvider());
        log.info("model    = {}", AppConfig.modelName());
        log.info("baseUrl  = {}", AppConfig.modelBaseUrl());
        log.info("apiKey   = {}…(masked)", AppConfig.modelApiKey().substring(0, 6));
        log.info("agent    = {}", AppConfig.agentName());
        log.info("maxIters = {}", AppConfig.maxIters());
        log.info("timeout  = {}", AppConfig.timeout());
        log.debug("sysPrompt = {}", AppConfig.sysPrompt());
    }
}
```

跑（推荐**临时**注入 `ARK_API_KEY`，key 不落到 git 跟踪的文件里）：

```bash
# Linux / macOS / Git Bash
ARK_API_KEY='apikey-xxxxxxxx' mvn -q compile exec:java -Dexec.mainClass=space.wlshow.scope.ConfigCheck

# Windows cmd
set ARK_API_KEY=apikey-xxxxxxxx && mvn -q compile exec:java -Dexec.mainClass=space.wlshow.scope.ConfigCheck

# Windows PowerShell
$env:ARK_API_KEY='apikey-xxxxxxxx'; mvn -q compile exec:java "-Dexec.mainClass=space.wlshow.scope.ConfigCheck"
```

期望看到（**中文不该乱码**；如果乱码先查 `logback.xml` 是否带 `<charset>UTF-8</charset>`）：

```
10:32:11.123 INFO  s.w.scope.ConfigCheck - provider = volcengine-ark
10:32:11.130 INFO  s.w.scope.ConfigCheck - model    = doubao-1-5-pro-32k-250115
10:32:11.131 INFO  s.w.scope.ConfigCheck - baseUrl  = https://ark.cn-beijing.volces.com/api/v3
10:32:11.131 INFO  s.w.scope.ConfigCheck - apiKey   = apikey…(masked)
10:32:11.132 INFO  s.w.scope.ConfigCheck - agent    = RequirementAnalyst
10:32:11.132 INFO  s.w.scope.ConfigCheck - maxIters = 15
10:32:11.133 INFO  s.w.scope.ConfigCheck - timeout  = PT2M
10:32:11.134 DEBUG s.w.scope.ConfigCheck - sysPrompt = 你是需求分析助手，Day 1 阶段仅用于联通性验证。
    回答尽量简洁，不超过 3 句话。
```

并且 `logs/scope.log` 里有同样内容。

### ✅ Phase 2 验收

- [ ] `logs/scope.log` 文件出现，**中文不乱码**
- [ ] `ConfigCheck` 能打印出 7 行配置（apiKey 被脱敏）
- [ ] 故意 unset 环境变量后跑，能看到清晰的 `IllegalStateException` 提示

### 5.5 本地配置覆盖（可选，但推荐）

把**会变的东西**（你自己的模型名、接入点 ID、自定义 baseUrl）从 `application.conf` 里抽出来，放到一个 gitignored 的 `application-local.conf` 中。这样：

- `application.conf`（**进 git**）= 团队公共默认值
- `application-local.conf`（**gitignored**）= 你机器本地覆盖

#### 5.5.1 让 `AppConfig` 支持叠加加载

把 `AppConfig.java` 顶部的 `private static final Config CFG = ConfigFactory.load();` 换成：

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// ...

private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

private static final String LOCAL_RESOURCE = "application-local.conf";
private static final String BASE_RESOURCE  = "application.conf";

private static final Config CFG = loadLayered();

/**
 * 优先级：系统属性(-D) > application-local.conf（如存在）> application.conf > reference.conf
 * 本地文件不存在时 parseResources 返回空 Config，自动降级到普通 load() 行为。
 */
private static Config loadLayered() {
    Config local = ConfigFactory.parseResources(LOCAL_RESOURCE);
    Config base  = ConfigFactory.parseResources(BASE_RESOURCE);

    if (local.isEmpty()) {
        log.info("config source: {} (no local override)", BASE_RESOURCE);
    } else {
        log.info("config source: {} overlaid on {}", LOCAL_RESOURCE, BASE_RESOURCE);
    }

    return ConfigFactory.systemProperties()
            .withFallback(local)
            .withFallback(base)
            .resolve();
}
```

#### 5.5.2 创建 `src/main/resources/application-local.conf`

只写你想覆盖的字段即可，其余从 `application.conf` 继承：

```hocon
# 本地配置覆盖（gitignored）
# AppConfig 会用本文件叠加到 application.conf 上面
model {
  name    = "kimi-k2.6"
  baseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3"
}
```

#### 5.5.3 加到 `.gitignore`

```gitignore
# Local config override
src/main/resources/application-local.conf
```

> 已经有 `application-local.properties` / `application-local.yml` 那两行（Spring Boot 习惯）的话，添一行 `application-local.conf` 即可。

#### 5.5.4 验证

```bash
ARK_API_KEY='apikey-xxxxxxxx' mvn -q compile exec:java -Dexec.mainClass=space.wlshow.scope.ConfigCheck
```

期望第一行：
```
INFO  s.w.s.config.AppConfig - config source: application-local.conf overlaid on application.conf
```

且 `model` / `baseUrl` 显示的是 local 文件里的值，而 `provider` / `agent.*` 仍然是 `application.conf` 里的默认。

把 `application-local.conf` 临时改名（如 `application-local.conf.off`）再跑，应该看到：
```
INFO  s.w.s.config.AppConfig - config source: application.conf (no local override)
```
所有字段回退到 `application.conf` 默认值。

> 💡 同样的模式也可以扩展出 `application-prod.conf`、`application-test.conf` 等——只要 `loadLayered()` 按需多叠几层就行。Day 7 部署/测试章节会再用到这套。

---

## 6. Phase 3 · Hello World 同步调用（90 min）

### 6.1 AgentFactory

`src/main/java/space/wlshow/scope/agent/AgentFactory.java`：

```java
package space.wlshow.scope.agent;

import space.wlshow.scope.config.AppConfig;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.OpenAIChatModel;

public final class AgentFactory {

    public static ReActAgent buildAnalyst() {
        // 火山引擎 Ark 是 OpenAI 兼容协议，用 OpenAIChatModel + baseUrl 即可
        OpenAIChatModel model = OpenAIChatModel.builder()
            .apiKey(AppConfig.modelApiKey())
            .modelName(AppConfig.modelName())
            .baseUrl(AppConfig.modelBaseUrl())
            .build();

        return ReActAgent.builder()
            .name(AppConfig.agentName())
            .sysPrompt(AppConfig.sysPrompt())
            .model(model)
            .maxIters(AppConfig.maxIters())
            .build();
    }

    private AgentFactory() {}
}
```

> 📚 各参数语义见 [../agents/03-react-agent.md § 构造参数全表](../agents/03-react-agent.md)。
> 📚 OpenAI 兼容厂商对照（DeepSeek / 月之暗面 / 智谱…）见 [../agents/05-model-providers.md](../agents/05-model-providers.md) 和本文 §8.1。

### 6.2 ScopeApp（同步 + 单轮）

`src/main/java/space/wlshow/scope/ScopeApp.java`：

```java
package space.wlshow.scope;

import space.wlshow.scope.agent.AgentFactory;
import space.wlshow.scope.config.AppConfig;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScopeApp {

    private static final Logger log = LoggerFactory.getLogger(ScopeApp.class);

    public static void main(String[] args) {
        log.info("Booting Scope App ...");

        ReActAgent agent = AgentFactory.buildAnalyst();

        String userInput = "用一句话告诉我现在是 Day 1。";
        log.info("[USER] {}", userInput);

        Msg request = Msg.builder().textContent(userInput).build();

        Msg response = agent.call(request)
            .timeout(AppConfig.timeout())
            .block();

        String reply = response.getTextContent();
        System.out.println("\n>>> " + reply + "\n");
        log.info("[BOT] reason={} text={}",
            response.getGenerateReason(),
            reply);
    }
}
```

### 6.3 跑起来

```bash
# Linux / macOS / Git Bash
ARK_API_KEY='apikey-xxxxxxxx' mvn -q compile exec:java

# Windows 临时注入方式见 Phase 2 §5.4
```

> `mvn exec:java` 不带 `-Dexec.mainClass=...` 时会走 pom 里 `<exec.mainClass>` 的默认值，即 `ScopeApp`。

期望：
- 终端打印 `>>> ...` 一句 LLM 回复
- `logs/scope.log` 里有 `[USER]` 和 `[BOT]` 两行

### 6.4 拆解：刚才到底发生了什么

照着这张图理解 [../agents/02-core-concepts.md § ReAct 循环](../agents/02-core-concepts.md)：

1. `Msg.builder().textContent(...)` → 构造一个 `role=USER` 的消息，里面是一个 `TextBlock`
2. `agent.call(req)` → 返回 `Mono<Msg>`（**还没执行**）
3. `.timeout(...)` → 限时
4. `.block()` → 真正订阅 + 阻塞等结果
5. Agent 内部：
   - 把 sysPrompt + 历史 + 你的消息丢给 `OpenAIChatFormatter`
   - POST 到 `https://ark.cn-beijing.volces.com/api/v3/chat/completions`
   - 拿回响应 → 包成 `Msg`（`role=ASSISTANT`）
   - 自动写入 `InMemoryMemory`（虽然 main 退出后就丢了）
6. `getGenerateReason()` 此时应该是 `MODEL_STOP`（没用工具，直接结束）

### ✅ Phase 3 验收

- [ ] 终端能看到 LLM 中文回复
- [ ] `getGenerateReason()` == `MODEL_STOP`
- [ ] 日志里至少有 `[USER]` `[BOT]` 各一条

### 🚨 常见错误

| 现象 | 原因 | 解决 |
|------|------|------|
| `TimeoutException` | 网络慢 / API 慢 | 把 `agent.timeout` 改成 `300s` |
| `401 Unauthorized` | API key 错或没设环境变量 | 重新生成 key，确认 `echo $ARK_API_KEY` 有值 |
| `404 model not found` | 模型 ID / 接入点 ID 错 | 控制台「在线推理」核对；接入点 ID 是 `ep-xxx`，模型 ID 是 `doubao-x-x-xx-xxxxxx` |
| `403` / `429` | 未开通该模型 / 配额用完 | 控制台「模型广场」开通，或换 `doubao-1-5-lite` 之类便宜的 |
| `block()` 抛 `IllegalStateException: block()/blockFirst()/blockLast() are blocking` | 在 Reactor 线程内调 `block()` | Day 1 不会触发，main 线程 OK |
| 中文乱码 | logback 缺 `<charset>UTF-8</charset>`，或终端 code page 是 GBK | 见附录 B-2 |
| `IllegalStateException: API key 未配置` | 当前 shell 没有 `ARK_API_KEY` | 在命令前临时注入或重开终端 |

---

## 7. Phase 4 · 流式 + REPL 多轮（60 min）

### 7.1 改造为 REPL

升级 `ScopeApp.java`：

```java
package space.wlshow.scope;

import space.wlshow.scope.agent.AgentFactory;
import space.wlshow.scope.config.AppConfig;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;

public class ScopeApp {

    private static final Logger log = LoggerFactory.getLogger(ScopeApp.class);

    public static void main(String[] args) {
        log.info("Booting Scope App (REPL mode) ...");

        ReActAgent agent = AgentFactory.buildAnalyst();
        Scanner sc = new Scanner(System.in, "UTF-8");

        System.out.println("Scope REPL — 输入 'exit' 退出，'/stream' 切换流式。\n");

        boolean stream = false;
        while (true) {
            System.out.print(stream ? "you (stream) > " : "you > ");
            String line = sc.nextLine();
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;
            if ("exit".equalsIgnoreCase(line)) break;
            if ("/stream".equals(line)) { stream = !stream; continue; }

            log.info("[USER] {}", line);
            Msg req = Msg.builder().textContent(line).build();

            try {
                if (stream) {
                    runStream(agent, req);
                } else {
                    runSync(agent, req);
                }
            } catch (Exception e) {
                log.error("call failed: {}", e.toString(), e);
                System.out.println("(error: " + e.getMessage() + ")");
            }
            System.out.println();
        }
        System.out.println("bye.");
    }

    private static void runSync(ReActAgent agent, Msg req) {
        Msg resp = agent.call(req).timeout(AppConfig.timeout()).block();
        String text = resp.getTextContent();
        System.out.println("bot > " + text);
        log.info("[BOT] reason={} text={}", resp.getGenerateReason(), text);
    }

    private static void runStream(ReActAgent agent, Msg req) {
        System.out.print("bot > ");
        StringBuilder buf = new StringBuilder();

        agent.stream(req)
            .timeout(AppConfig.timeout())
            .toStream()                                  // Flux → Java Stream（阻塞）
            .forEach(chunk -> {
                // 流式调用每个 chunk 是 AgentResponse，需 .getMessage() 拿到 Msg
                String delta = chunk.getMessage().getTextContent();
                if (delta != null && !delta.isEmpty()) {
                    buf.append(delta);
                    System.out.print(delta);
                    System.out.flush();
                }
            });
        System.out.println();
        log.info("[BOT-STREAM] text={}", buf);
    }
}
```

### 7.2 跑起来验证

```bash
ARK_API_KEY='apikey-xxxxxxxx' mvn -q compile exec:java
```

剧本：
```
you > 你好，请用 2 句话介绍 AgentScope
bot > ...

you > /stream
you (stream) > 用中文逐字解释什么是 ReAct
bot > ... (你能看到逐字吐出)

you > exit
bye.
```

### 7.3 重点观察

- 同一个 `ReActAgent` 实例可以连续调用 — **因为是单线程顺序调用**（[../agents/02-core-concepts.md § Agent 不可并发](../agents/02-core-concepts.md)）
- 第二次提问时，LLM **会记得** 第一次说过的内容 — 因为默认 `InMemoryMemory` 在生效（Day 5 才会显式控制它）
- 流式调用通过 `Flux<Msg>` 增量发出，每个增量含 `TextBlock`

### ✅ Phase 4 验收

- [ ] 同步和流式都能跑
- [ ] 连续提问能保留上下文（让 LLM 复述上一题答案验证）
- [ ] 流式能看到逐字效果
- [ ] `logs/scope.log` 有 `[USER]` / `[BOT]` / `[BOT-STREAM]` 三种条目

---

## 8. Phase 5 · 拓展练习（任选 1-2，60 min）

按兴趣挑，**做不完留到周末**，不影响 Day 2。

### 8.1 拓展 A：切到 DashScope / DeepSeek / 直连 OpenAI

体会 **OpenAI 兼容协议的厂商之间只换 baseUrl + key + 模型名，业务代码 0 修改**。

**A1：DashScope（阿里通义千问，原生 SDK，非 OpenAI 兼容路径）**

修改 `AgentFactory`：

```java
import io.agentscope.core.model.DashScopeChatModel;
// ...
DashScopeChatModel model = DashScopeChatModel.builder()
    .apiKey(AppConfig.modelApiKey())
    .modelName("qwen-max")
    .build();
```

`application.conf` 里把 `apiKey` 改回 `${?DASHSCOPE_API_KEY}`，`baseUrl` 这次用不上（DashScope SDK 内部硬编码了 endpoint）。

**A2：DeepSeek（OpenAI 兼容，只换 baseUrl）**

`AgentFactory` 不动（仍是 `OpenAIChatModel`），只改配置：

```hocon
model {
  provider = "deepseek"
  name     = "deepseek-chat"
  baseUrl  = "https://api.deepseek.com"
  apiKey   = ${?DEEPSEEK_API_KEY}
}
```

**A3：直连 OpenAI**

```hocon
model {
  provider = "openai"
  name     = "gpt-4o-mini"
  baseUrl  = "https://api.openai.com/v1"
  apiKey   = ${?OPENAI_API_KEY}
}
```

A2/A3 这种情况你会发现，Java 代码一行都没改 —— 这就是把 baseUrl 抽到配置里的价值。

### 8.2 拓展 B：用 `ModelRegistry` 解耦模型工厂

```java
ModelRegistry.register("primary",
    OpenAIChatModel.builder()
        .apiKey(...).modelName("doubao-1-5-pro-32k-250115")
        .baseUrl("https://ark.cn-beijing.volces.com/api/v3").build());

ReActAgent agent = ReActAgent.builder()
    .name("...")
    // 仅当用 HarnessAgent 时可以直接 .model("primary")
    // ReActAgent 还得 .model(ModelRegistry.resolve("primary")) ←验证一下
    .build();
```

学一下：怎么在配置里只写字符串 ID，启动时根据配置注册多个模型。

### 8.3 拓展 C：第一个 Hook

写一个最小的 `PreReasoningHook`，把每轮发给 LLM 前的 prompt 长度打到日志。
（API 细节见 AS-Java 文档 Hook 章节；这章我们 Day 5/7 还会深入。）

### 8.4 拓展 D：单元测试 + WireMock 模拟 LLM

引入 [WireMock](https://wiremock.org/)，给 Ark 的 `/chat/completions` 接口打 mock，离线跑 `ScopeApp` 的逻辑。
作用：之后 CI 上不用真实 key 也能测 — 对 Day 7 测试体系是关键铺垫。

```xml
<dependency>
  <groupId>org.wiremock</groupId>
  <artifactId>wiremock-standalone</artifactId>
  <version>3.5.4</version>
  <scope>test</scope>
</dependency>
```

---

## 9. Phase 6 · 收尾（30 min）

### 9.1 README

写一个最小 README（项目根目录）：

```markdown
# agent-scope-app

基于 AgentScope-Java + 火山引擎方舟（Volcengine Ark）的需求分析智能体。

## 启动
1. 设置 `ARK_API_KEY` 环境变量（控制台 → API Key 管理）
2. 在 `src/main/resources/application.conf` 里把 `model.name` 改成你的模型 ID 或接入点 ID
3. `mvn compile exec:java`（macOS/Linux/Git Bash：`ARK_API_KEY=... mvn ...`）
4. 在 REPL 中提问；`/stream` 切换流式；`exit` 退出

## 文档
- 学习路线：`docs/learning.md`
- Day 1：`docs/lessons/Day01_项目骨架 + AS-Java Hello World.md`
- AS-Java 笔记：`docs/agents/`
```

### 9.2 提交

```bash
git init
git add .
git commit -m "day1: bootstrap project skeleton & hello-world agent (Volcengine Ark)"
```

> 💡 后续每天都用 `dayN: <简述>` 作为 commit prefix，便于 `git log --oneline` 回顾。

### 9.3 收工日记（≤ 5 分钟）

在 `docs/lessons/Day01_项目骨架 + AS-Java Hello World.md` 末尾的"我的笔记"区写下：
- 今天最坑的 1 个错
- 最有 aha 时刻的 1 个点
- Day 2 之前想搞清楚的 1 个问题

---

## 10. 当日完整验收清单（对照 learning.md）

- [x] **Phase 0** 环境齐全，`ARK_API_KEY` 可用，curl 自检通过
- [x] **Phase 1** `pom.xml` + 目录结构 + `mvn test` 全绿
- [x] **Phase 2** `logback.xml`（含 UTF-8 charset） + `application.conf` + `AppConfig` 工作
- [x] **Phase 3** `mvn exec:java` 能拿到 LLM 同步回复
- [x] **Phase 4** 流式 + REPL 多轮通过
- [x] **Phase 5** 至少做了 1 个拓展（可选）
- [x] **Phase 6** README + git commit + 当日笔记

---

## 11. Day 2 预告

明天主题：**数据契约 + JSON Schema 校验**。
- 把 App / Module / DataModel / FieldSpec 写成 Java record
- 编写 JSON Schema 文件
- 写校验器并加单元测试
- 为 Day 3 的 LLM 结构化输出做准备

**预读**（晚上 30 分钟）：
- [../learning.md § Day 2](../learning.md)
- [JSON Schema getting started](https://json-schema.org/learn/getting-started-step-by-step)
- 题面 #11、#12、#13 三段 JSON 再读一遍

---

## 附录 A · Maven 阿里云镜像

国内拉依赖慢时，编辑 `~/.m2/settings.xml`（不存在就建）：

```xml
<settings>
  <mirrors>
    <mirror>
      <id>aliyun-public</id>
      <mirrorOf>central</mirrorOf>
      <name>Aliyun Public Mirror</name>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
```

## 附录 B · 故障排查速查

| 报错关键字 | 怀疑方向 |
|-----------|---------|
| `UnknownHostException ark.cn-beijing.volces.com` | 网络/DNS |
| `Connection refused` / `timeout` | 代理设置、JVM 走系统代理需 `-Dhttp.proxyHost=...` |
| `NoClassDefFoundError reactor.core.publisher.Mono` | 依赖没下完，`mvn -U dependency:resolve` |
| `org.slf4j.impl.StaticLoggerBinder` 警告 | 没事，可忽略；想消除就显式加 `slf4j-api` 同版本依赖 |
| `Unable to make field private final ... accessible` | JDK 模块系统警告，加 `--add-opens java.base/java.lang=ALL-UNNAMED`（一般不必） |
| `IllegalStateException: API key 未配置` | 当前 shell 没有 `ARK_API_KEY`；Windows 用户级设过的话需要重开 shell |

### 附录 B-1 · `-Dexec.mainClass=...` 不生效 / Maven 跑了别的 main class

**症状**：你跑 `mvn exec:java -Dexec.mainClass=space.wlshow.scope.ConfigCheck`，但错误信息里出现的却是 `ScopeApp.main` 或别的类。

**原因**：Maven 插件参数解析规则——pom.xml 里 `<configuration>` 的**字面值**优先级高于命令行 `-D` user property，命令行参数会被**静默吞掉**。如果 pom 里写的是：

```xml
<configuration>
  <mainClass>space.wlshow.scope.ScopeApp</mainClass>  <!-- ❌ 字面值，-D 覆盖不了 -->
</configuration>
```

那 `-Dexec.mainClass=...` 永远没用。

**正确写法**（本文 §4.3 已经是这样）：

```xml
<properties>
  <exec.mainClass>space.wlshow.scope.ScopeApp</exec.mainClass>
</properties>
...
<configuration>
  <mainClass>${exec.mainClass}</mainClass>  <!-- ✅ 占位符，-D 可覆盖 -->
</configuration>
```

效果：
- `mvn exec:java` → 走默认 `space.wlshow.scope.ScopeApp`
- `mvn exec:java -Dexec.mainClass=space.wlshow.scope.ConfigCheck` → 临时覆盖成 ConfigCheck

### 附录 B-2 · Windows 中文乱码

**症状**：日志或控制台中文显示成 `��������`，或者一些古怪的 box-drawing 字符。

**根因链**：
1. JVM 默认 `Charset.defaultCharset()` 在 Windows 中文版是 `GBK`
2. logback 的 `ConsoleAppender` / `FileAppender` 默认用 JVM charset 编码 byte 写出去
3. 你的终端（Git Bash mintty / Windows Terminal / IDEA Console）通常按 UTF-8 解析 byte
4. GBK 编码的字节流被当 UTF-8 解码 → 乱码（反之亦然）

**修复**（两层都做最稳）：

1. `logback.xml` 的两个 encoder 都加 `<charset>UTF-8</charset>` —— 强制 logback 按 UTF-8 写
2. `application.conf` 本身保存为 UTF-8（IDE 默认就是）
3. Windows cmd 用户额外：跑 `chcp 65001` 切到 UTF-8 code page
4. IntelliJ IDEA：Settings → Editor → File Encodings → 全部设成 UTF-8 + 勾选 "Transparent native-to-ascii conversion"

**验证**：

```bash
tail logs/scope.log
```

在 Git Bash 里看 `logs/scope.log`，中文应该正常显示。如果文件 OK 但控制台还乱码，那是终端 code page 问题，不是 Java 进程问题。

## 附录 C · 我的笔记（学习时填）

> 在这下面随手记，下次回看是金矿。

- 最坑：
- aha：
- 想搞清楚：
