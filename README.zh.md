# Voice SIP Agent

[English](README.md)

把一台**未 root** 的 Android 11+ 手机变成单线 **蜂窝 ↔ SIP 网关**。手机以 SIP trunk 的方式注册到你的 PBX
（Asterisk / FreePBX / IssabelPBX）：

- **蜂窝来电 → SIP**：蜂窝来电会呼叫 PBX 上的一个 DID；SIP 侧有人接听后才接听蜂窝来电，主叫号码通过
  `P-Asserted-Identity` 传递。
- **SIP 去电 → 蜂窝**：PBX 把呼叫送到 trunk，由手机经蜂窝网络拨出。
- 通话音频通过直接电话路由或 Android 的通话重定向 API 截取和注入。桥接期间可静音本机通话输出，本机麦克风始终保持静音。

特权操作（通话音频拦截、Telecom 伴侣覆盖、appops）通过以 shell 用户（uid 2000）运行的
[Shizuku](https://shizuku.rikka.app/) 完成，**不需要 root**。项目依赖隐藏的系统 API，因此不会在 Google Play 上架。

> [!WARNING]
> 这是一个基于未公开 Android 行为的个人项目，任何一次系统更新都可能让它失效，也可能掉话或把呼叫送错地方。
> 本软件**按原样提供，不附带任何形式的担保**。使用前请阅读[免责声明](#免责声明)。

> [!IMPORTANT]
> 该APP的逻辑代码大多由 LLM agent 编写，开发者可能无法理解其中的某些代码逻辑。

## 状态

测试过的设备：

| 设备 | Android | 备注 |
|---|---|---|
| Google Pixel 11 Pro | 17 | 主力开发机 |
| Motorola | 16 | 正常 |
| POCO F6（HyperOS 2） | 15 | Direct telephony + 关闭本机通话输出静音时双向语音正常¹ |
| vivo V30 Lite | 15 | 同上¹ |
| POCO F3（MIUI 14） | 13 | 同上¹ |
| Samsung Galaxy A15 5G（One UI） | 16 | 同上¹ |
| Redmi Note 13 Pro（HyperOS 2，MT6789） | 15 | 同上¹ |
| Infinix X678B | 14 | 可注册，通话音频未测试¹ |

¹ 社区反馈（[#1](../../issues/1)）。在这些设备上，其他音频设置组合都无法双向通话，见[故障排查](#故障排查)。

已验证：来电与去电、双向语音、DTMF（RFC 4733）、Wi-Fi 断线重连（含通话中）、Doze 一整晚待机、长通话无时钟漂移、
本机听筒静音；局域网 UDP 通话，以及经 Flexisip 代理的公网 TLS + SRTP 通话。

尚未验证：通话等待、pjsua2 栈下的 DTMF 与断网恢复、仅用蜂窝数据的公网通话。

默认 pjsua2 栈支持 Opus，并保留 G.711 作为回退。
编解码设置和其他验证事项见英文[音频格式与路由说明](TECHNICAL.md#audio-formats-and-routing)。

限制：同一时间只有一路通话；尚未实现早期媒体（early media，即回铃音、忙音等）；APK 包含 arm64-v8a；最低为 Android 11（minSdk 30）。
Android 11 尚未测试。各厂商的隐藏 API 和音频 HAL 路由不同，其他机型可能需要不同的音频设置，也可能完全无法使用。
默认拨号器模式提供网关状态和恢复界面，可通过切换拨号器恢复手动通话。紧急号码一律拒绝，绝不会经网关拨出。

## 安装

1. 安装并启动 [Shizuku](https://shizuku.rikka.app/zh-hans/guide/setup/)（无线调试或 adb）。每次重启后都需要手动重新启动 Shizuku。
2. 从 [Releases](../../releases) 下载 `voice-sip-agent-<版本>.apk` 并安装。可以校验签名证书：

   ```bash
   apksigner verify --print-certs voice-sip-agent-0.1.0.apk
   ```

   正式版签名证书的 SHA-256 为 `b8fe38aa02f9657c6785fb6faeb20abb20deba410f9c643fcd93269b642850c4`。
3. 打开 App，在 **Health** 中点 **Grant Shizuku** 并允许（若接管已关闭，请先开启），App 会自动完成 setup。
   Android 11 上，开启 **Take over calls** 也会将本 App 设为默认拨号器。
   较新版本通常以 InCallService 伴侣应用的身份与现有拨号器并行工作。
4. 在 **Settings** 中填写 PBX 地址/端口、传输方式、SRTP、用户名、密码等，点 **Save & apply**。
5. 开启 **Take over calls**，将呼叫桥接到 PBX；关闭时会等待当前通话结束。
   默认拨号器模式下，Shizuku 会保存原来的拨号器，在关闭接管时恢复，除非在高级设置中选择保留角色。
   恢复失败时，可使用 **Choose dialer in Settings** 手动切换。

通话访问模式、音频选项和拨号器恢复见[高级设置](#高级设置)。

若要在手机旁监听，在通话开始前到 **Takeover** 关闭 **Mute call audio on this phone**。
下行音频按手机当前的通话路由（听筒、扬声器或耳机）和音量播放。
桥接期间本机麦克风仍保持静音，因此本地监听不会启用本机语音输入。

## 配置

| 设置项 | 含义 |
|---|---|
| SIP stack | `pjsua2`（默认；UDP/TCP/TLS、SRTP）或 `builtin`（纯 Kotlin，仅 UDP；已弃用，新功能只做 pjsua2） |
| PBX host / port | 注册服务器 / 代理地址 |
| Transport | `udp`（局域网）、`tcp` 或 `tls`（仅 pjsua2） |
| SRTP | `off`、`optional`（提供 SDES，对端不支持时退回 RTP）或 `mandatory`（不支持则 488）；仅在 TLS 下生效 |
| SIP domain | 默认等于 PBX host |
| User / password | PBX 上 trunk 的凭据 |
| Cellular inbound → SIP target | 蜂窝来电时在 PBX 上呼叫的 DID；留空则使用 SIP 用户名 |
| Allowed dial-out prefixes | 逗号分隔；留空表示允许拨出任何号码 |
| Unregister while the phone cannot take calls | 开启（默认）：启动或打开接管开关后，等待蜂窝侧（Shizuku、通话接入和通话音频）就绪才向 PBX 注册。首次就绪后，问题持续 45 秒才注销，让 PBX 将呼叫转移到其他线路。注册状态的改变等待通话结束；手机恢复就绪后立即重新注册。SIP 仍注册但手机不可用时，呼入返回 `480 Temporarily Unavailable`。关闭此选项后，无论本地是否就绪都允许 SIP 注册；不可用时仍返回 `480`。 |

点击 **Save & apply** 保存；通话中提交的修改会等待当前通话结束后生效。
接管关闭时保存的 SIP 设置将在下次开启接管时使用。

TLS 用 Android 系统 CA 库校验服务器证书链和主机名，仅支持 TLS 1.2/1.3，接受最左一级的 `*.` 通配证书（RFC 6125）。
不做降级：TLS 失败就注册失败，每 30 s 重试。

### 网关状态与恢复

在 **Health** 中查看 setup 或注册错误。接管开启时，可在授权 Shizuku 后使用 **Re-run setup** 重新设置，
或使用 **Reconnect SIP** 根据已保存的凭据重新注册。

### 高级设置

高级设置默认折叠。通常将 **Cellular audio** 保持为 Automatic。**Call access** 可选：

| 选项 | 行为 |
|---|---|
| Automatic | Android 11 使用默认拨号器模式；Android 12 及以上默认使用伴侣模式，已持有拨号器角色时使用默认拨号器模式。 |
| Alongside dialer（伴侣模式） | 保留平常使用的电话 App 和通话界面，仅适用于 Android 12 及以上。 |
| Default dialer（默认拨号器模式） | 下次开启接管时将网关设为默认电话 App；手动通话时请切回平常使用的拨号器。 |

选择模式不会切换默认电话 App。默认拨号器模式在关闭接管时恢复原来的拨号器，
除非开启 **Keep this app as the default phone app when gateway is off**。
可用 **Make default phone app** 主动切换，或用 **Choose dialer in Settings** 恢复拨号器。

**Cellular audio** 还可选 Direct telephony 和 Call redirection（Android 13 及以上）。
角色控制、测试选项和诊断详见英文[通话访问与接管说明](TECHNICAL.md#call-access-and-takeover)和
[音频格式与路由说明](TECHNICAL.md#audio-formats-and-routing)。

### adb 控制

也可以用 adb 控制 App（receiver 需要 `DUMP` 权限，只有 shell 用户具备）：

```bash
R=io.github.sparkcyf.voicesipagent/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver
adb shell am broadcast -n $R --es op provision --es server sip.example.org --ei port 5061 \
  --es transport tls --es srtp optional --es domain sip.example.org \
  --es user android-gw --es password "'<secret>'" --es target android-gw   # 只覆盖给出的字段
adb shell am broadcast -n $R --es op status            # 返回一行摘要；完整报告在 logcat
adb shell am broadcast -n $R --es op takeover --ez on false
adb shell am broadcast -n $R --es op earpiece-mute --ez on true
adb shell am broadcast -n $R --es op sip-stack --es stack pjsua2
adb shell am broadcast -n $R --es op default-sim --es sim system   # 不带 X-VSA-SIMSLOT 时用哪张卡：system|refuse|<卡槽>
adb shell am broadcast -n $R --es op sim-inbound --ei slot 0 --ez on false   # 卡 0 的来电不接管，留给手机
adb shell am broadcast -n $R --es op loopback --ez on true   # 回声测试：蜂窝来电直接回声
adb shell am broadcast -n $R --es op sip-debug --ez on true  # pjsip 详细日志
adb shell am broadcast -n $R --es op provision --ez hold_sip false   # 未就绪时也保持注册
adb shell am broadcast -n $R --es op provision --ei audio_method 2   # 0 Automatic，1 Call redirection，2 Direct telephony
adb logcat -s VoiceSipAgent VsaPrivileged VsaTap
```

广播结果：`result=1` 表示确认接收，`2` 表示错误，`3` 表示等待 5 秒后超时。
确认接收不代表操作已完成；变更可能等待当前通话结束后应用。重试前请查看 logcat 或 App 内日志。
`result=0` 且没有 data 不代表命令已被接收，请检查组件名。
未知参数和错误类型会被拒绝；请按示例使用对应的 extra 类型。

日志中的电话号码只保留后两位；通话音频不会写入日志，也不会落盘。

## 故障排查

- **对方听不到你，或者你只听到静音**：把 **Cellular audio** 设为 *Direct telephony*，并关闭
  **Mute call audio on this phone**（adb 下为 `--ei audio_method 2` 和 `earpiece-mute --ez on false`）。
  在上表的社区测试设备上，只有这个组合能双向通话。
- **某张卡的来电接通几秒后被挂断，日志出现 `not in a cellular call: audio mode=3`**：这张卡的通话被运营商 App 当作 VoIP
  处理（例如 Pixel 上开启 Wi-Fi 通话的 Google Fi），通话音频不经过基带。请关闭这张卡的 Wi-Fi 通话。
- **双卡手机上不带 `X-VSA-SIMSLOT` 的 PBX 去电被拒绝**：Android 没有设置默认通话卡（"每次询问"）。可以在 App 里指定一张卡、
  在 Android 的 SIM 卡设置里选一张，或者由 PBX 发送该头（见[双卡](#双卡)）。
- **MediaTek / 传音（如 Infinix）手机上的 Shizuku 13.6.0**：App 的特权服务始终无法启动
  （`LoadedApk.makeApplicationInner` 中的 `NullPointerException`；
  [RikkaApps/Shizuku#1198](https://github.com/RikkaApps/Shizuku/issues/1198)）。请改装 Shizuku 13.5.4。
- **重启 Shizuku 后请打开一次 App**（或点一下网关通知）。重启后的 Shizuku 服务可能不会把连接交给已在运行的 App
  （在 Android 17 + Shizuku 13.6.0 上观察到），App 回到前台时才会交付。在此之前网关显示 *Shizuku not running*，
  并默认保持未注册。adb 方式：`adb shell monkey -p io.github.sparkcyf.voicesipagent -c android.intent.category.LAUNCHER 1`。
- **厂商设置**：小米、POCO、Redmi 需要登录小米账号才能开启 USB 安装和 USB 调试（安全设置），HyperOS 还可能拒绝
  `pm grant`。Infinix 和 Redmi 可能禁止 `adb install`，可把 APK 拷到手机上用文件管理器安装。三星需关闭
  **Auto Blocker**。请留意 Android 大版本升级可能带来的兼容性变化。

## 呼叫路由

网关以 **trunk** 而不是分机的身份接入 PBX，所有路由都在 PBX 的 Inbound/Outbound Routes 中管理。

- **蜂窝来电**：手机发送 `INVITE sip:<target>@<domain>`，`target` 是一个 DID（默认就是 trunk 的用户名），主叫号码放在
  `P-Asserted-Identity` 中。PBX 上的 Inbound Route 按 DID 把来电送到你想要的地方。
- **SIP 去电**：要拨的号码取自 `X-VSA-Dial` 头，没有该头时取 Request-URI 的用户部分（局域网直连时用
  `PJSIP/$OUTNUM$@<trunk>` 即可）。蜂窝通话接通后才向 PBX 回 200 OK。
  - 中间隔着按注册表路由的代理（Flexisip 等）时，Request-URI 的用户部分必须保持为网关自己的 AoR，否则代理会按号码查注册表并返回
    404，所以号码要放进 `X-VSA-Dial`（见下方拨号方案）。

### 双卡

所有 SIM 卡共用一个 trunk，用 `X-VSA-SIMSLOT` 头按 Android 的**逻辑卡槽号**指定卡（0、1……，即
`SubscriptionInfo.getSimSlotIndex()`；同一块 eSIM 芯片上同时启用的两个配置算两个卡槽）。
`adb … --es op status` 会列出 `sims=0:<运营商>(sub<id>),1:…`。

- **蜂窝来电**：INVITE 带有 `X-VSA-SIMSLOT`，值为来电所在的卡槽；需要按卡区分去向时，可在拨号方案中用
  `${PJSIP_HEADER(read,X-VSA-SIMSLOT)}` 读取。App 里的 **Take over incoming calls on SIM n** 开关（默认全开）
  关掉后，这张卡的来电照常在手机上响铃，例如只接管副卡。这些开关不影响去电。
- **SIP 去电**：用 `X-VSA-SIMSLOT` 选卡。不带该头时由 App 设置 **SIM for PBX calls without X-VSA-SIMSLOT** 决定：
  *System*（默认）跟随 Android 的默认通话卡，Android 设为"每次询问"时拒绝呼叫；*SIM n* 固定用该卡槽；*Refuse* 要求必须带头。
  卡槽里没有启用的卡，或 Telecom 把电话放到了别的卡上，回 `480`（不会改用另一张卡）；值格式错误回 `400`。
  网关呼叫永远不会弹出 Android 的选卡框。
- 无论用哪张卡，同一时间只桥接一路通话。

### PBX 配置示例（FreePBX / IssabelPBX + chan_pjsip）

`pjsip_custom_post.conf`：

```ini
[android-gw]
type=endpoint
transport=transport-udp
context=from-trunk          ; 来电按 Inbound Route 处理，不具备内部分机权限
aors=android-gw
auth=android-gw-auth
identify_by=username
disallow=all
allow=ulaw,alaw
dtmf_mode=rfc4733
direct_media=no
rtp_symmetric=yes
force_rport=yes
rewrite_contact=no          ; 经 Flexisip 时 Contact 已由代理改写，不能再改
media_encryption=sdes       ; 与 SRTP optional 配合；局域网明文 RTP 照常
media_encryption_optimistic=yes
trust_id_inbound=yes        ; 采信网关的 P-Asserted-Identity 作为主叫号码

[android-gw-auth]
type=auth
auth_type=userpass
username=android-gw
password=<secret>

[android-gw]
type=aor
max_contacts=1
remove_existing=yes         ; 手机换 IP/端口后立即接管注册
qualify_frequency=60
```

`extensions_custom.conf`（Request-URI 保持为网关 AoR，号码放在头里）：

```ini
[vsa-android-gw-out]
exten => _X.,1,Dial(PJSIP/android-gw,,b(vsa-android-gw-hdr^s^1(${EXTEN})))
exten => _X.,n,Hangup(${HANGUPCAUSE})

[vsa-android-gw-hdr]
exten => s,1,Set(PJSIP_HEADER(add,X-VSA-Dial)=${ARG1})
exten => s,n,ExecIf($["${ARG2}" != ""]?Set(PJSIP_HEADER(add,X-VSA-SIMSLOT)=${ARG2}))
exten => s,n,Return()

; 双卡：每个卡槽一个上下文（见上文"双卡"）
[vsa-android-gw-out-sim0]
exten => _X.,1,Dial(PJSIP/android-gw,,b(vsa-android-gw-hdr^s^1(${EXTEN},0)))
exten => _X.,n,Hangup(${HANGUPCAUSE})

[vsa-android-gw-out-sim1]
exten => _X.,1,Dial(PJSIP/android-gw,,b(vsa-android-gw-hdr^s^1(${EXTEN},1)))
exten => _X.,n,Hangup(${HANGUPCAUSE})
```

Web 后台：

- **Trunk**：Custom，拨号串 `Local/$OUTNUM$@vsa-android-gw-out`（局域网直连、没有代理时也可以直接用 `PJSIP/$OUTNUM$@android-gw`）。
- **Outbound Route**：专用前缀（如 `9#7#`），匹配 `X.`，走上述 trunk；必须排在 `9.` 之类更宽的路由之前。
- **双卡**：每个卡槽一个 custom trunk（`Local/$OUTNUM$@vsa-android-gw-out-sim0`、`…-sim1`），每个 trunk 一个路由前缀，
  例如 `9#7#` → 卡槽 0，`9#6#` → 卡槽 1。
- **Inbound Route**：DID `android-gw` → 任意目标。

### 公网接入：Flexisip

手机以 TLS 注册到 [Flexisip](https://www.linphone.org/en/flexisip-sip-server/)（`sips:<domain>:5061`），Flexisip
把请求转给 Asterisk（例如经 WireGuard），并用 MediaRelay 中继媒体（SRTP 在手机与 Asterisk 之间端到端加密）。
Flexisip 不需要为网关做任何专门配置：

- REGISTER 由 Asterisk 鉴权（Flexisip 关闭 Authentication，`reg-on-response=true`）。`ContactRouteInserter` 把 Contact
  改写成指向 Flexisip，NatHelper 修正 NAT 地址，来电沿手机已建立的 TLS 连接送达，手机无需开放端口。
- Asterisk 发回的请求按 Request-URI 的用户部分路由，所以需要上面的 `X-VSA-Dial` 拨号方案。
- 手机配置：`server = domain = <flexisip 域名>`、`port = 5061`、`transport = tls`、`srtp = optional`。

## 从源码构建

需要 JDK 17+、带 NDK 的 Android SDK，以及 `swig`（例如 `brew install swig`）。

```bash
ABIS="arm64-v8a x86_64" APP_PLATFORM=android-30 sip-pjsua2/native/build-pjsua2.sh   # 只需一次；产物在 git 忽略的 src/generated/
./gradlew :sip:test :gateway:test   # JVM 单元测试
./gradlew :app:assembleDebug
```

原生构建默认面向 arm64-v8a，需与 App 的 Gradle `abiFilters` 一致。

脚本使用版本固定、经 SHA-256 校验的源码构建 OpenSSL、libopus 和 PJSIP。
构建目录布局和重新构建行为见英文[原生依赖说明](TECHNICAL.md#native-dependencies)。

## 架构

| 模块 | 类型 | 职责 |
|---|---|---|
| `:voice-api` | JVM | 解耦契约：`CallLeg` / `CallEndpoint` / `MediaPort`（8/16 kHz 单声道 PCM16，20 ms 帧）/ `HangupCause` |
| `:sip` | JVM | 自研 SIP UA：UDP、事务层、Digest、REGISTER、INVITE/CANCEL/BYE/re-INVITE、SDP、RTP + 抖动缓冲、G.711、RFC 4733 |
| `:gateway` | JVM | `CallBridge` 状态机（任意两个 `CallEndpoint` 互桥）、`MediaBridge`（单时钟域）、`EchoEndpoint` |
| `:telephony` | Android | 伴侣 `InCallService` + `CellularEndpoint`：把 Telecom 通话映射为 `CallLeg` |
| `:privileged` | Android | Shizuku UserService（AIDL）：setup/health、通话音频 tap（SOCK_SEQPACKET socketpair） |
| `:sip-pjsua2` | Android | pjsua2 实现的 `CallEndpoint`：空声卡 + `AudioMediaPort`，pjsua 不碰 Android 音频 |
| `:app` | Android | 运行时装配、前台服务、设置（Keystore 加密密码）、adb 控制、Compose 界面 |

接入新的语音/控制后端：实现一个 `CallEndpoint`（及其 `CallLeg` / `MediaPort`），交给 `CallBridge` 即可，网关核心不需要改。
设计细节和踩过的坑见 [AGENT.md](AGENT.md)。

## 参考

- [AirSIM](https://github.com/kai-wu-cortex/AirSIM)：通话音频拦截的思路最初来自阅读它的代码；本项目是独立实现，不依赖它。
- [Shizuku](https://github.com/RikkaApps/Shizuku)、[pjsip](https://github.com/pjsip/pjproject)、
  [Flexisip](https://gitlab.linphone.org/BC/public/flexisip)。

## 许可证

Copyright (C) 2026 sparkcyf

本程序是自由软件：你可以依据自由软件基金会发布的 GNU 通用公共许可证第 2 版或（由你选择）任何更新的版本，再分发和/或修改它。详见 [LICENSE](LICENSE)（以英文原文为准）。例外：允许将本程序与任何依据 Apache License 2.0 许可的库链接，前提是其余所有代码均遵守 GPL。

Release APK 静态链接了 pjsip（GPL-2.0-or-later）、OpenSSL（Apache-2.0）和 libopus（BSD-3-Clause），并打包了采用 Apache 2.0 许可证的 Java/Kotlin 组件（AndroidX/Jetpack、Kotlin、kotlinx.coroutines 和 Shizuku API/provider），见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
每个 release 对应的源码是本仓库对应 tag 的提交，加上 `sip-pjsua2/native/build-pjsua2.sh` 下载的版本固定的上游源码包。

## 免责声明

本软件按“原样”提供，不附带任何明示或暗示的担保，包括但不限于对适销性、特定用途适用性和不侵权的担保。在任何情况下，作者或版权持有人
均不对因本软件、使用本软件或以其他方式处理本软件而产生的任何索赔、损害或其他责任负责，无论是合同诉讼、侵权诉讼还是其他诉讼。
使用本软件的风险完全由你自行承担。如与英文版有出入，以英文版为准。

特别提醒：

- **不能用于紧急呼叫。** 紧急号码按设计会被拒绝。不要依赖本网关或运行它的手机联系紧急服务。
- **通话可能失败。** 通话可能被挂断、拒接、送错目标或接通后没有声音，任何系统更新都可能让 App 失效。漏接电话及其后果由你自行承担。
- **费用。** 经网关拨出的电话由你的运营商计费。请限制允许拨出的号码前缀并保护好 PBX；被入侵的 PBX 可能让手机拨打高额付费号码。
- **法律与服务条款。** 在你所在的司法辖区，转接、录制或截取通话，以及把移动线路接入 PBX，可能受到法律或运营商条款的限制。
  遵守这些规定、并取得通话各方必要的同意，完全是你自己的责任。
- **安全。** Shizuku 会授予本 App shell 级权限。只安装你信任的构建，并妥善保管 SIP 凭据和 PBX。

本项目与 Google、Motorola、Shizuku、pjsip、Asterisk、Flexisip 等项目或任何运营商均无关联，也未获得其认可。
