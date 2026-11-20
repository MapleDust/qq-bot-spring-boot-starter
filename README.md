# qq-bot-spring-boot-starter

基于 QQ 机器人 API v2 的 Spring Boot Starter：WebSocket 网关接入、消息日志、注解式消息监听、消息发送封装。

[![Maven Central](https://img.shields.io/maven-central/v/top.fcidd/qq-bot-spring-boot-starter?label=Maven%20Central)](https://central.sonatype.com/artifact/top.fcidd/qq-bot-spring-boot-starter)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1%2B-6DB33F.svg)](https://spring.io/projects/spring-boot)

## 一、安装

已发布到 Maven Central，坐标 `top.fcidd:qq-bot-spring-boot-starter`。强烈推荐使用版本1.1.1修复了若干的bug，提升了框架的稳定性

**Gradle（Kotlin DSL）**

```kotlin
dependencies {
    implementation("top.fcidd:qq-bot-spring-boot-starter:1.1.1")
}
```

**Gradle（Groovy）**

```groovy
dependencies {
    implementation 'top.fcidd:qq-bot-spring-boot-starter:1.1.1'
}
```

**Maven**

```xml
<dependency>
    <groupId>top.fcidd</groupId>
    <artifactId>qq-bot-spring-boot-starter</artifactId>
    <version>1.1.1</version>
</dependency>
```

环境要求：**JDK 25+**、**Spring Boot 4.1.x**。starter 通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 自动装配，全部版本可在 [Maven Central](https://central.sonatype.com/artifact/top.fcidd/qq-bot-spring-boot-starter/versions) 查看。

### 在启动类上添加 `@EnableQQBot`

引入依赖后，**需要在 Spring Boot 启动类上添加 `@EnableQQBot`**，显式启用 QQ 机器人自动装配（导入 `QQBotAutoConfiguration`，注册网关客户端、事件处理、消息发送、注解监听等全部 Bean）：

```java
import org.springframework.boot.autoconfigure.SpringBootApplication;
import top.fcidd.qqbot.starter.annotation.EnableQQBot;

@SpringBootApplication
@EnableQQBot                     // 必须：启用 QQ 机器人自动装配
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

不加该注解时，若自动装配未生效，网关不会连接、消息监听不会生效（日志中看不到 `收到 READY`）。

## 二、配置

```yaml
qq:
  bot:
    app-id: 你的AppID
    app-secret: 你的AppSecret
    # 事件订阅位掩码，33554432 = 1 << 25 (GROUP_AND_C2C_EVENT)
    # 覆盖单聊 C2C_MESSAGE_CREATE、群聊@机器人 GROUP_AT_MESSAGE_CREATE、群聊全量 GROUP_MESSAGE_CREATE
    intents: 33554432
    auto-start: true          # 应用就绪后自动连接网关
    reconnect-delay-ms: 3000  # 断线重连间隔
```

应用启动后会自动拉取 access_token、连接 gateway、鉴权并在日志中打出 `收到 READY`。

## 三、注解式监听

前提：启动类已添加 `@EnableQQBot`。

```java
@QQBotListener                       // 标记监听器类，会被组件扫描注册
public class MyBotListener {

    // 群内 @机器人 发送 /ping → 回复 pong（返回值作为被动回复自动发送）
    @GroupAtMessage("/ping")
    public String ping() {
        return "pong";
    }

    // 群内 @机器人 发送 /weather 合肥 → 参数为 "合肥"
    @GroupAtMessage("/weather")
    public String weather(String args, GroupMessagePayload message) {
        return args + " 的天气：晴，群 " + message.getGroupOpenid();
    }

    // 监听群内全部 @机器人 消息（不区分指令）
    @GroupAtMessage
    public void onAnyGroupAt(GroupMessagePayload message) {
        // 也可以注入 QQBotMessageSender 主动发送
    }

    // 单聊指令
    @C2CMessage("/help")
    public String help(C2CMessagePayload message) {
        return "发送 /echo 内容 可以原样回显";
    }

    // 群聊全量消息（需群主开启「接收所有消息」）
    @GroupMessage("/report")
    public void report(GroupMessagePayload message) { }
}
```

### 方法参数（可选，最多 4 个，类型任意组合）

| 参数类型 | 含义 |
|---|---|
| `GroupMessagePayload` / `C2CMessagePayload` / `Object` | 消息事件体（消息 ID、发送者、内容、附件等） |
| `BotEvent` | 原始网关事件（含 `op`、`t`、`s`、`d`） |
| `QQBotMessageSender` | 消息发送器，可直接调用 |
| `String` | 指令之后的参数（未配置指令时为整条消息内容，已去首尾空白） |

### 返回值

- 返回 `String` 且非空：自动作为**被动回复**发给消息来源（群消息回群、单聊回用户）。
- 返回 `void`：由你在方法内自行调用发送器。

### 指令匹配规则

- `value = ""`（默认）：匹配该场景下的全部消息。
- 否则按**前缀**匹配并做边界判断：`/ping` 命中 `/ping`、`/ping 参数`、`/ping?`，不命中 `/pingpong`。
- `ignoreCase = true`（默认）时忽略大小写；中文指令同样按前缀匹配。
- 一个事件可以命中多个方法（例如精确指令 + 通配监听），都会被执行。

处理器在弹性线程池（`boundedElastic`）中执行，方法内可以安全地做阻塞调用。

## 四、发送消息

注入 `QQBotMessageSender` 即可：

```java
@Service
public class MyService {
    private final QQBotMessageSender sender;

    public MyService(QQBotMessageSender sender) { this.sender = sender; }

    // 被动回复：自动带 msg_id 并自增 msg_seq，是最稳的回复方式
    public void reply(GroupMessagePayload message) {
        sender.replyGroup(message, "收到你的消息");
    }

    public void replyPrivate(C2CMessagePayload message) {
        sender.replyC2C(message, "你好");
    }

    // 主动消息（受平台频控，用户可关闭接收）
    public void broadcast(String groupOpenid) {
        sender.sendGroupMessage(groupOpenid, "公告内容");
    }

    public void notifyUser(String userOpenid) {
        sender.sendC2CMessage(userOpenid, "私聊通知");
    }

    // 响应式调用（Mono<SendResult>）
    public Mono<SendResult> async(String groupOpenid) {
        return sender.sendGroupMessageAsync(groupOpenid, "异步发送");
    }
}
```

`SendResult` 包含 `success`、`httpStatus`、`messageId`、`code`、`message`、`raw`，失败原因可直接用于排查。

### 平台限制（务必注意）

| 场景 | 被动回复有效期 | 单条消息可回复次数 |
|---|---|---|
| 群聊 | 5 分钟 | 5 次 |
| 单聊 | 60 分钟 | 4 次 |

- 超过有效期或次数，被动回复会失败，需要改用主动消息（受频控限制）。
- 同一 `msg_id` 可能被平台重复推送，回复时建议自行按 `msg_id` 去重。
- 群聊全量消息 `GROUP_MESSAGE_CREATE` 只在群主给机器人开启「获取群内全部消息」后才会推送，排查方法见下一节。

## 五、群全量消息收不到怎么排查

`GROUP_MESSAGE_CREATE` 与 `GROUP_AT_MESSAGE_CREATE` 共用同一个 intent（`GROUP_AND_C2C_EVENT 1<<25`），代码不需要额外订阅；收不到几乎都是 QQ 群侧开关没开。

**第一步：群主在手机 QQ 里放开权限（必须由群主操作，需最新版 iOS/Android QQ）**

1. 群设置 → 群成员列表里点机器人头像 → 进入机器人资料页；
2. 点右上角「设置」；
3. 「机器人可获取的群聊消息范围」选择**「获取群内全部消息」**（另有「获取@机器人的最近10条消息」「仅获取@机器人的消息」两个选项，都不推送全量消息）；
4. 如需机器人主动发言，再打开「机器人主动在群聊内发言」。

注意不是「机器人权限设置」那一项。个人主体机器人只能加入自己是群主/管理员的群。

**第二步：看日志判断是哪一环断的**

| 日志现象 | 结论 |
|---|---|
| 群里发了非 @ 的普通消息，日志连 `收到网关事件 op=0, t=GROUP_MESSAGE_CREATE` 都没有 | 平台没推送，属于群侧开关没开或机器人不在该群 |
| 有 `收到网关事件 ... GROUP_MESSAGE_CREATE`，但没有 `[群聊全量消息] ...` 明细 | 代码侧问题，把该行原始事件发出来排查 |
| 出现 `[群状态] 群消息接收开关：开启` | 群主刚放开了权限，之后的全量消息才会开始推送 |

**第三步：直接查机器人在群内的状态（硬证据）**

```java
@Autowired
private QQBotGroupApi groupApi;

// group_openid 可从任意群消息事件（如 @机器人 收到的消息）的 group_openid 字段获取
BotState state = groupApi.getBotState("群openid");
log.info(state.describe());
// 输出形如：群内角色=member，入群时间=...，可接收主动推送=false，消息范围=获取群内全部消息（群全量消息已开启）

boolean receiveAll = groupApi.isReceiveAllMessages("群openid");
```

对应接口 `GET /v2/groups/{group_openid}/bot_state`，`recv_msg_setting` 取值：`all`（群内全部消息）、`only_mention`（仅 @机器人）、`mention_and_context`（@机器人及上下文）。该接口部分机器人需要白名单权限，返回 `11253 应用无接口访问权限` 时只能靠第一步的开关状态判断。

### 全量模式下「@机器人」的消息怎么区分

开启全量后，群内**每一条**消息（包括 @机器人 的）都会以 `GROUP_MESSAGE_CREATE` 推送，事件名本身不再能说明“是否 @ 了机器人”。因此本 starter 的归类规则是：

- `GROUP_AT_MESSAGE_CREATE` → 一定按「群聊@机器人」处理；
- `GROUP_MESSAGE_CREATE` → 看 `mentions[].is_you`：为 `true` 即本条消息 @ 了本机器人，按「群聊@机器人」分发；否则按「群聊全量消息」分发。

带来的效果：

- @机器人 的 `/ping` 在全量模式下也会命中 `@GroupAtMessage("/ping")` 并自动回复（以前只认事件名，会误判为全量消息而“不回复”）；
- 同一条消息若被平台同时以两种事件推送，只分发一次（按 `消息ID + 场景` 去重），不会重复回复、不会多消耗被动回复次数；
- 若开头带 `<@xxx>` 这类 @机器人 标记，匹配指令前会先剥掉，保证 `/ping` 仍能匹配；
- 日志同时给出判定结果与原始事件名，便于核对：

```
[群聊@机器人]（全量模式下收到，按@机器人处理） 事件类型=GROUP_MESSAGE_CREATE ... @成员=1人机器人(@我)
```

## 六、断线重连

网关客户端内置自动重连，以下情况都会触发，不需要业务侧干预：

| 触发条件 | 说明 |
|---|---|
| 连接结束 / 异常 | WebSocket 断开、握手失败、连接建立异常 |
| 服务端 `op=7` RECONNECT | 平台主动要求重连，按需 Resume（保留 `session_id` 与 `seq`） |
| 服务端 `op=9` INVALID_SESSION | 清空 `session_id` / `seq`，重新 Identify |
| 心跳超时 | 心跳发出后 2 个间隔内没收到 `op=11` 应答，判定连接已死 |
| 长时间无入站消息 | 超过 3 个心跳间隔没有任何入站报文，判定半开连接假死 |
| 无活动连接 | 存活探测发现没有会话也没有排队中的重连，主动补一次 |

- 退避策略：`qq.bot.reconnect-delay-ms`（默认 3000）起，按 3s → 6s → 12s … 指数翻倍，上限 60 秒；连上并收到 `READY` 后计数清零。
- 存活探测每 5 秒执行一次，日志形如：

```
连接存活探测已启动，周期 5000 ms
3000 ms 后执行第 1 次重连
开始第 1 次重连
收到 READY，session_id = ...，机器人已上线
已 123750 ms 未收到任何网关消息（心跳间隔 41250 ms），判定连接已断开，强制重连
```

- 重连期间不会并发建连（有建连中标记与重连任务去重），因此不会出现两个连接同时在线把事件分流的情况；但仍建议只部署一个实例。

## 七、日志

收到三类消息事件时会打印明细（INFO）：

```
[群聊@机器人] 事件ID=... 序号s=12 消息ID=ROBOT1.0_... 群openid=C9F7... 发送者=小明(member_openid=..., 身份=成员(member)) 类型=0(文本) 内容="/ping" 附件=无 @成员=无 时间=...
[单聊] 事件ID=... 序号s=13 消息ID=ROBOT1.0_... 发送者=小红(user_openid=E8F2...) 类型=0(文本) 内容="测试" 附件=无 时间=...
```

附件 URL 带鉴权参数，所以日志只打印类型/文件名/尺寸/大小，不落 URL。

## 八、日志（Log4j2）

**写日志只需要在类上加一个 `@Log4j2`，不需要再写 `LoggerFactory`、不需要额外配置、不需要额外加依赖。**

```java
@Log4j2
@Component
public class MyBotListener {

    @GroupAtMessage("/ping")
    public String ping() {
        log.info("收到 ping");        // 直接用 log，不用自己声明 Logger
        return "pong";
    }
}
```

Lombok 会自动生成 `private static final org.apache.logging.log4j.Logger log = LogManager.getLogger(...)`，直接用 `log.info(...)` 即可。

**不需要**自己声明 Logger，也**不需要**加日志依赖或写日志配置文件——Spring Boot 默认的日志栈里已有 `log4j-api` 与 `log4j-to-slf4j`，Log4j2 API 调用会被桥接到 Spring Boot 的日志实现正常输出（本项目实测 INFO 正常打印）。

日志级别沿用 Spring Boot 标准配置：

```yaml
logging:
  level:
    top.fcidd.qqbot: info     # 想看更详细可调 debug
```

全部 11 个类都已使用 `@Log4j2`：`AccessTokenProvider`、`QQBotWebSocketClient`、`QQBotAutoConfiguration`、`QQBotStarterBootstrap`、`EventHandler`、`MessageEventLogger`、`MessageEventDispatcher`、`MessageHandlerRegistry`、`QQBotMessageSender`、`QQBotGroupApi`、`DemoBotListener`。

输出样例：

```
2026-11-20T23:08:04.277+08:00  INFO 23952 --- [qq-bot-spring-boot-starter] [main] t.f.q.s.a.QQBotWebSocketClient : 连接存活探测已启动，周期 5000 ms
2026-11-20T23:08:05.454+08:00  INFO 23952 --- [qq-bot-spring-boot-starter] [ctor-http-nio-3] t.f.q.s.a.AccessTokenProvider : access_token 获取成功，expires_in = 6211 s
```

补充：如果哪天把 Spring Boot 默认的 Logback 换成 Log4j2 原生实现（排除 `spring-boot-starter-logging` 并引入 `spring-boot-starter-log4j2`），那才需要额外提供一份 `log4j2.xml`——因为 Log4j2 无配置时默认只打 ERROR。当前项目不需要这一步。


## 九、其它

- 网关事件仍会通过 `ApplicationEventPublisher` 发布 `BotEvent`，需要原始事件时可 `@EventListener` 监听。
- 内置 `DemoBotListener` 仅在 `qq.bot.demo-listener=true` 时启用：群内 `/ping` 回 `pong`，私聊回显，用于自测链路。

## 十、稳定性修复记录

本轮排查共修复 8 处缺陷，均已在代码中落地并通过 `gradlew build` 与实机跑通：

| # | 问题 | 修复 |
|---|---|---|
| 1 | 建连/握手没有超时，连不上时永远卡在“建连中”，存活探测见到 `connecting=true` 就跳过，结果进程活着但一直离线 | 给 `HttpClient` 配 `CONNECT_TIMEOUT_MILLIS=10s`、`responseTimeout=20s`（握手超时） |
| 2 | 单个事件处理抛异常会打断 `session.receive()` 流，被当成连接故障而整条断开 | `EventHandler.onRawEvent` 对“日志打印 / 注解分发 / 事件发布”三段分别 try-catch，单条事件出错只记 ERROR |
| 3 | 调度器 `final` 且 `stop()` 后不重建，`start()` 无法二次启动；调度器关闭后还抛 `RejectedExecutionException` | 调度器改 `volatile` + 按需重建（守护线程 `qqbot-gateway-scheduler`）；`scheduleReconnect` / `startWatchdog` 捕获拒绝异常并撤销排队标记 |
| 4 | `stop()` 未复位 `connecting` / `reconnectScheduled`，且调度器可能为 `null` | `stop()` 复位两个标记、判空后再 `shutdown()` |
| 5 | 旧连接的收尾动作会把新连接的 `outbound` 与心跳清掉（重启 / 快速重连时踩到） | 每条连接独占一个出站队列，`doFinally` 用队列身份判断，“旧连接”直接忽略清理 |
| 6 | `EventHandler` 对每个控制帧都打 INFO，心跳应答每 41 秒一条刷屏 | `op=0`（Dispatch）打 INFO，其余（Hello / 心跳应答）降为 DEBUG |
| 7 | 被动回复 `msg_seq` 计数在超过 2000 条时整表 `clear()`，会把正在回复的 `msg_id` 序号归零，重复 `msg_seq` 会被平台去重丢消息 | 改 `LinkedHashMap` 有序淘汰最旧条目，并加 `synchronized` |
| 8 | 应用关闭时没有关闭钩子，`stop()` 永不被调用，残留连接与调度线程 | `QQBotStarterBootstrap` 监听 `ContextClosedEvent`，调用 `webSocketClient.stop()` |

另外两点易踩坑的行为变化：

- 日志里不再出现 `收到网关事件 op=10 / op=11`（已降为 DEBUG），Hello 仍有独立的 `收到 Hello，heartbeat_interval = ...` INFO；想看控制帧把 `logging.level.top.fcidd.qqbot` 调成 `debug`。
- 想覆盖默认的网关客户端或事件处理器，现在直接自己声明 `QQBotWebSocketClient` / `EventHandler` 类型的 Bean 即可，自动配置会退让（`@ConditionalOnMissingBean`）。
