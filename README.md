# qq-bot-spring-boot-starter

基于 QQ 机器人 API v2 的 Spring Boot Starter：WebSocket 网关接入、消息日志、注解式消息监听、消息发送封装。

[![Maven Central](https://img.shields.io/maven-central/v/top.fcidd/qq-bot-spring-boot-starter?label=Maven%20Central)](https://central.sonatype.com/artifact/top.fcidd/qq-bot-spring-boot-starter)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1%2B-6DB33F.svg)](https://spring.io/projects/spring-boot)

## 一、安装

已发布到 Maven Central，坐标 `top.fcidd:qq-bot-spring-boot-starter`。

**Gradle（Kotlin DSL）**

```kotlin
dependencies {
    implementation("top.fcidd:qq-bot-spring-boot-starter:1.1.0")
}
```

**Gradle（Groovy）**

```groovy
dependencies {
    implementation 'top.fcidd:qq-bot-spring-boot-starter:1.1.0'
}
```

**Maven**

```xml
<dependency>
    <groupId>top.fcidd</groupId>
    <artifactId>qq-bot-spring-boot-starter</artifactId>
    <version>1.1.0</version>
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

## 六、日志

收到三类消息事件时会打印明细（INFO）：

```
[群聊@机器人] 事件ID=... 序号s=12 消息ID=ROBOT1.0_... 群openid=C9F7... 发送者=小明(member_openid=..., 身份=成员(member)) 类型=0(文本) 内容="/ping" 附件=无 @成员=无 时间=...
[单聊] 事件ID=... 序号s=13 消息ID=ROBOT1.0_... 发送者=小红(user_openid=E8F2...) 类型=0(文本) 内容="测试" 附件=无 时间=...
```

附件 URL 带鉴权参数，所以日志只打印类型/文件名/尺寸/大小，不落 URL。

## 七、其它

- 网关事件仍会通过 `ApplicationEventPublisher` 发布 `BotEvent`，需要原始事件时可 `@EventListener` 监听。
- 内置 `DemoBotListener` 仅在 `qq.bot.demo-listener=true` 时启用：群内 `/ping` 回 `pong`，私聊回显，用于自测链路。
