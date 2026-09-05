<p align="center">
  <img src="docs/images/banner.jpg" alt="DSH Mobile — 装进口袋的 DeepSeek Harness" width="100%">
</p>

<h1 align="center">DSH Mobile — DeepSeek Harness 远程端</h1>

<p align="center">
  一款开源的 Android 伴侣应用，把你的 <b>DeepSeek Harness</b> 装进口袋。<br>
  在局域网内用手机驱动会话、查看计划与目标、回应审批与提问，
  并在 harness 干完活时收到通知。
</p>

<p align="center">
  <a href="https://dshm.zyphite.com"><img alt="Website" src="https://img.shields.io/badge/website-dshm.zyphite.com-4176E6?style=flat-square"></a>
  <a href="https://github.com/sorsama/deepseek-harness-mobile/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/sorsama/deepseek-harness-mobile?style=flat-square"></a>
  <a href="https://github.com/sorsama/deepseek-harness-mobile/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/sorsama/deepseek-harness-mobile/ci.yml?branch=main&style=flat-square"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square">
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/license-MIT-blue?style=flat-square"></a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <b>中文</b> ·
  <a href="README.hi.md">हिन्दी</a> ·
  <a href="README.es.md">Español</a> ·
  <a href="README.fr.md">Français</a> ·
  <a href="README.th.md">ไทย</a>
</p>

DSH Mobile 是 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（MIT）的
**非官方伴侣应用**，用 harness 自己的视觉语言逐项还原它的网页 GUI。仅支持 Android，使用
Kotlin + Jetpack Compose。

另一端的搭档是 [**dsh-relay**](https://github.com/sorsama/deepseek-harness-relay) —— 一个 harness
插件，补上 harness 自己承认缺失的那层身份验证，让这个应用能凭真正的凭据和固定的密钥去连接
harness，而不是对着一个敞开的端口。参见
[Relay](https://github.com/sorsama/deepseek-harness-mobile/wiki/Relay)。

**[dshm.zyphite.com](https://dshm.zyphite.com)** 是项目主页 —— 一页讲清这个应用是什么、
长什么样、怎么跑起来。

[**wiki**](https://github.com/sorsama/deepseek-harness-mobile/wiki) 是面向用户的指南：
[入门](https://github.com/sorsama/deepseek-harness-mobile/wiki/Getting-Started)、
[连接](https://github.com/sorsama/deepseek-harness-mobile/wiki/Connecting)、
[疑难解答](https://github.com/sorsama/deepseek-harness-mobile/wiki/Troubleshooting)、
[功能巡览](https://github.com/sorsama/deepseek-harness-mobile/wiki/Feature-Tour) 和
[常见问题](https://github.com/sorsama/deepseek-harness-mobile/wiki/FAQ)。

---

## 截图

| 连接 | 聊天 | 轨迹 |
|:--:|:--:|:--:|
| <img src="docs/images/home.png" width="240" alt="连接界面：最近使用的 harness 及其实时可达性、发现、手动输入与自动连接开关"> | <img src="docs/images/chat.png" width="240" alt="聊天：流式输出的回合、每种工具的图标、工具卡片、目标停靠栏与输入框"> | <img src="docs/images/trajectory.png" width="240" alt="轨迹：按回合排列的账本，附带用量合计"> |
| 最近使用的 harness 及实时可达性、局域网发现、手动 `host:port`、自动连接。 | 流式输出的回合、每种工具一个字形、可展开的工具卡片、权限选择器。 | 同一个会话，以按回合排列的账本呈现，并给出用量合计。 |

| 会话详情 | 子代理 |
|:--:|:--:|
| <img src="docs/images/session-info.png" width="240" alt="详情面板：上下文构成、目标、计划模式、任务、队列、子代理、主机信息"> | <img src="docs/images/subagent.png" width="240" alt="子代理目录，可继续与子代理对话"> |
| 上下文构成、目标、计划模式、后台任务、排队的回合、主机信息、会话日志导出。 | 子代理目录 —— 打开子代理的对话记录、追问，或将其中断。 |

## 功能

- **轻松连接** —— 自动发现同一 Wi-Fi 下的 harness（主动子网扫描 + 就绪握手），
  记住用过的主机并在进入时探测其存活状态，支持手动输入 `host:port`、同设备回环连接，
  以及自动连接开关（上次使用 / 局域网 / 同一设备）。
- **Discord 式导航** —— 从屏幕左缘右滑打开按工作区分组的聊天列表，左滑关闭；
  从右缘左滑打开会话详情面板。
- **完整的聊天体验** —— 流式输出的回合与可展开的推理过程、Markdown、
  终端/差异/读取/搜索/网页工具卡片、队列停靠栏（编辑 / 移除 / 引导）、历史分页、图片与文件附件。
- **斜杠命令与技能** —— 输入框会先拿 `/` 开头的一行去比对会话自己的命令目录，
  命中就交给 harness 的命令网关执行；目录不认领的内容按普通提示发送，技能就是这样被调用的。
- **GUI 有的都有** —— 目标（阶段、轮次、暂停/继续/编辑）、计划模式与计划审阅、
  权限审批、用户提问、待办停靠栏、子代理（目录、追问、中断）、后台任务、工作流运行、技能、
  模型选择、代理预设、会话搜索、轨迹账本、会话导出、消息反馈。
- **通知** —— 回合完成、目标完成 / 受阻、有审阅或提问在等你；
  通过前台服务维持后台连接。
- **和 harness 长得一样** —— 完全采用 DeepSeek Harness 的设计令牌（颜色、字体、圆角、
  披露行、微光、墨水按钮），支持浅色 / 深色 / 跟随系统主题。
- **11 种语言** —— English、中文、हिन्दी、Español、Français、العربية、বাংলা、Português、
  Русский、اردو、ไทย（支持 RTL）。

## 环境要求

- Android 8.0 及以上（minSdk 26）。
- 一个正在运行的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
  （已针对 `0.1.3-alpha.1` 测试）。**0.10.0 需要 harness 0.1.3** —— 该版本不再把回复的增量写入日志，
  改为通过 App 必须主动订阅的实时流传输，因此 App 与 harness 必须同时升级：旧版 App 在 0.1.3 上看不到
  正在生成的回答，而本版 App 在 0.1.2 上无法执行斜杠命令。参见 [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)。

## 快速开始

1. 从 [Releases](https://github.com/sorsama/deepseek-harness-mobile/releases/latest)
   安装最新的 APK。
2. 打开应用，选择连接方式。这几种不是同一个设置的不同变体 ——
   挑与你在电脑上配置好的那一种。

   **中继** —— 加密、有身份验证，在 Wi-Fi 之外也能用。把
   [`dsh-relay`](https://github.com/sorsama/deepseek-harness-relay) 装进 harness 的 web 配置：

   ```sh
   dsh plugin --profile web add dsh-relay
   dsh web
   ```

   **在那台电脑上**打开打印出来的 URL，设置密码，然后打开 `/relay/pair`。
   在应用里：**中继 → 配对中继**，扫描二维码。等你用的每台客户端都配对完，
   就关掉中继的 `compat.addressGrants` —— 这里没有任何东西需要它。

   **局域网** —— 手机上不用配置，也完全没有身份验证。按
   [`harness/README.md`](harness/README.md) 打上单文件局域网补丁，重启 `dsh web`，
   然后点**扫描网络**。只在你信任的网络上用。

   **走你自己的 HTTPS 反向代理** —— 把 `https://` 地址粘进局域网模式。
   代理可以转发到回环地址，所以 harness 不需要打补丁；但它只加密链路，不验证任何人的身份。
   参见 [`harness/README.md`](harness/README.md)。

   **USB / 模拟器** —— 运行 `dsh web`，再执行 `adb reverse tcp:3080 tcp:3080`，
   然后在局域网模式下连接 `127.0.0.1:3080`。不需要打补丁。
3. 选一个会话开始聊，harness 干完活会通知你。

如果连接失败，应用会直接说明原因；wiki 的
[疑难解答](https://github.com/sorsama/deepseek-harness-mobile/wiki/Troubleshooting)
页面就是按那一句话组织的。

## 兼容性与安全

> **0.1.2:** 从 harness 0.1.2 起，harness 会对整个 API 进行认证：在 App 询问时，把它启动时打印的链接粘贴一次。这会认证本机，但不会加密连接，因此仍然只应在可信网络上使用。

- harness 版本矩阵和仅回环可用的接口见
  [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)。
- **请先读 [docs/SECURITY.md](docs/SECURITY.md)。** 裸的 harness 没有任何身份验证，
  所以局域网模式只适合在你信任的网络上用 —— 应用在连接界面这么提醒也是出于同样的原因。
  中继模式加上了真正的凭据和固定证书，但即便通过了验证，拿到的权力仍然等同于在那台电脑上
  开一个 shell，因为代理就是在那里执行命令的。

## 构建

```sh
./gradlew :app:assembleDebug      # 调试版 APK
./gradlew :app:assembleRelease    # 发布版 APK（设置了 keystore 环境变量时会签名）
```

发布的版本号来自 git 标签：发布工作流从标签名导出 `DSH_VERSION_NAME`，`versionCode` 由它推导。
本地构建则回退到 `app/build.gradle.kts` 里写死的值。

针对真实 harness 的开发流程、模块划分和发布流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 仓库结构

| 路径 | 内容 |
|---|---|
| `core/` | 纯 JVM 协议核心：线路 DTO、RPC 客户端、WebSocket 下行流、重连循环、会话折叠、通知分类器 |
| `app/` | Android UI：各界面、发现与连接、前台服务、通知、国际化 |
| `mock-harness/` | 用于测试的 harness `/api` 服务端 Ktor 模拟实现 |
| `tools/capture/` | 把真实 harness 流量录制成一致性测试夹具 |
| `harness/` | 局域网模式的配套补丁与指南 |
| — | 中继本身在 [sorsama/deepseek-harness-relay](https://github.com/sorsama/deepseek-harness-relay) |
| `docs/` | [架构](docs/ARCHITECTURE.md)、[协议说明](docs/PROTOCOL.md)、[兼容性](docs/COMPATIBILITY.md)、[安全](docs/SECURITY.md) |

## 许可证

[MIT](LICENSE)。随附的第三方材料列在
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。DeepSeek Harness 及其品牌归各自所有者所有；
本项目是一个独立的、由社区构建的远程端。
