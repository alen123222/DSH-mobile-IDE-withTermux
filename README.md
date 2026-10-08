# DSH Pocket

**在 Android 上使用完整 Termux 环境的原生 AI 编程助手。**

DSH Pocket 把 Kotlin / Jetpack Compose 界面、运行在手机本地的服务，以及适配 Android arm64 的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 连成一体：可以在聊天里让模型改文件、跑命令，也可以直接开终端自己敲。

当前版本：**0.6.2-dev（开发预览）**。工作区文件保留在原来的位置，不导入 App 私有目录。本项目与 DeepSeek 官方 Android App、Termux 官方项目均无隶属关系。

## 目录

- [功能与当前状态](#功能与当前状态)
- [架构与执行方式](#架构与执行方式)
- [运行要求](#运行要求)
- [首次安装](#首次安装)
- [手机控制（免 root）](#手机控制免-root)
- [附件：图片与文件](#附件图片与文件)
- [会话统计](#会话统计)
- [自定义 API 与多个预设](#自定义-api-与多个预设)
- [工作区、文件与终端](#工作区文件与终端)
- [从源码构建](#从源码构建)
- [测试与验证](#测试与验证)
- [Android arm64 适配](#android-arm64-适配)
- [数据存储与权限](#数据存储与权限)
- [升级与故障排查](#升级与故障排查)
- [已知限制与后续方向](#已知限制与后续方向)
- [项目结构](#项目结构)
- [第三方项目与许可证](#第三方项目与许可证)

## 功能与当前状态

| 功能 | 当前实现 |
| --- | --- |
| 原生聊天界面 | Compose 实现；一轮对话折叠成一张卡片，展开后按「读取 / 写入 / 运行 / 思考」分类，可再展开看单条命令与输出 |
| 会话统计 | 顶栏三个按钮（轮数·步数 / Token / 上下文），各点开各自的明细弹窗 |
| DSH 编程代理 | 运行真实 DSH SDK，通过持久 Shell 修改文件、执行命令 |
| 手机控制（免 root） | 无障碍服务 + 本机回环 API + DSH 工具插件，可读屏、点击、输入中文、滚动、返回、开应用、截图 |
| Shizuku 增强（可选） | 运行 Shizuku 后截图改由 shell 用户执行，不再受「只能有一个窗口」和空白界面树的限制 |
| 附件 | 图片压缩后作为图片内容块直达模型；其他文件存进工作区并给出路径 |
| 多工作区 | 注册 Termux 有权限访问的绝对路径，直接操作原始目录 |
| 文件浏览与查看 | 目录浏览、新建目录；文本/代码查看与编辑保存、语法高亮、查找；图片、PDF（可缩放）、十六进制、ZIP 解压 |
| 可运行文件 | 文件行内 ▶ 直接运行；支持 py/sh/js/rb/php/lua/pl/ts/java/kt/c/cpp/go/rs/swift/dart |
| 交互终端 | Python POSIX PTY + 本地 xterm.js，支持输入、尺寸变化和持续 Shell 状态 |
| 自定义 API | OpenAI Chat Completions、OpenAI Responses、DeepSeek Messages 三种协议 |
| 多连接预设 | 保存、切换、删除多组端点、Key、协议、模型、上下文长度、输出上限与推理强度 |
| 模型发现 | 直接向端点查询可用模型列表，勾选后加入预设 |
| 地址补全 | 支持基础地址或完整请求地址，自动处理协议后缀 |
| 密钥存储 | Android Keystore + AES-GCM 加密保存 API 配置 |
| 会话记录 | 消息与工具事件统一编号保存；重启后可继续，停止/出错的会话也能接着问 |
| 实时推送 | 会话事件经 SSE 推送，不可用时退回按修订号的增量轮询 |
| 双语界面 | 中文 / English，跟随系统语言（`Lang.kt` 以中文原文为键，未知键回退原文） |

## 架构与执行方式

```text
Compose 界面（App 进程）
   │  HTTP + SSE  →  127.0.0.1:<BRIDGE_PORT>   带 Bearer 令牌
   ▼
Termux 中的桥接 node 进程（bridge/server.mjs）
   │  工作区 / 文件 / 终端 / 会话 / 提供方 / 附件
   ▼  spawn + JSON-RPC（stdio）
DSH 引擎进程（sdk-minimal 配置，runtime/bin/dsh-pocket）
   ▲
   └── 无障碍服务（App 进程内）← 手机控制回环 API  127.0.0.1:<BRIDGE_PORT+1>
```

要点：

- **一个对话 = 一个引擎进程**。进程启动时注入模型、API 补丁和手机控制环境，之后跨轮复用。
- 引擎只在**连接签名**变化时重建。签名里包含补丁版本号（`PATCH_REVISION`），所以补丁或启动环境一变，下一次发送就会换新进程——否则会出现「改了没反应」。
- 桥接资产（`bridge/`、`termux/`）**只在版本号变化时**同步到 Termux。改了这些文件必须同时提升版本号，否则手机侧仍是旧代码。
- App 与桥接之间只有回环 HTTP，不经过任何外部网络。

## 运行要求

| | |
| --- | --- |
| 手机 | Android 8.0（API 26）及以上，**arm64** |
| Termux | 官方 com.termux 安装；首次需允许外部应用运行命令 |
| Node | Termux 中 22.19+ 或 24+（安装脚本会检查） |
| 构建机 | JDK 17 + Android SDK（Platform 35 / Build-Tools） |
| 引擎 | `@deepseek-ai/dsh@0.2.0-rc.2`（安装脚本固定版本） |

## 首次安装

### 1. 安装 App 并授予基础权限

安装 APK，然后按需开启无障碍（用于手机控制，可稍后再开）。运行中的提示由无障碍悬浮窗承担，不需要通知权限。

### 2. 一键完成首次连接

环境页只有两个入口：

| 按钮 | 行为 |
| --- | --- |
| **一键完成首次连接** | 直接执行：装 Node/Python → 释放桥接资产 → 写连接配置 → 启动本地服务；成功后自动打开 Termux 让你看进度 |
| **需要什么权限？** | 只显示说明与可复制的命令 |

Termux 出于安全默认**不允许其他应用运行命令**，这个开关必须由你手动打开一次，任何 App 都无法代劳。在这个开关打开之前，一键按钮会因为 Termux 拒绝而失败，此时 App 会自动弹出说明（并带上失败原因）。

在 Termux 里执行一次：

```bash
mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties
```

然后回到 App 再点一键连接，或在弹出的说明里按「已设置，开始连接」。

### 3. 安装 Android 引擎

环境页 → 「安装 DSH 引擎」会执行 `termux/install-dsh.sh`：装编译工具、下载固定版本 DSH、生成 Android 原生模块、应用补丁、跑自检。这一步耗时较长（要编译原生代码）。

### 4. 配置模型

环境页 → 添加模型：填端点、Key、协议与模型 ID；或先用「测试全部 API」拉取端点模型列表再勾选。保存后即可在对话页底部的模型选择里切换。

## 手机控制（免 root）

不需要 root，也不需要电脑。由三部分组成：**无障碍服务**（读屏与操作）、**本机回环 API**（带令牌校验）、**DSH 工具插件**（把能力暴露成工具）。

### 使用步骤

1. 环境页 → 手机控制：开启无障碍服务，并勾选允许控制的应用（白名单）；
2. 对话页底部打开「手机控制」开关；
3. 正常发指令即可，例如「打开微信给某人发消息」。

### 安全模型

- 令牌只在**每次发送时**生成，仅存内存，进程结束、手动停止或超时即失效；
- 所有动作都要求前台应用在白名单内；不在白名单的 App 一律拒绝；
- 每个请求最多 **120 次手机动作**；同**一改动性动作**（点击/输入/滚动/按键/开应用，含参数）连续重复到第 4 次会失败并要求模型停下来报告。只读动作（观察、截图、状态）不计入重复判定——连续观察两次是"等界面稳定"，不是死循环；
- 密码字段不读取、不写入；日志与错误信息中的令牌会被脱敏；输入连续失败 3 次只停用**输入**，点击/滚动仍可用，模型可以换路或如实报告。

### 工具

| 工具 | 用途 |
| --- | --- |
| `phone_state` | 列出允许控制的应用 |
| `phone_observe` | 读取前台界面为扁平控件列表（文本、描述、绝对坐标、可点击/可输入/可滚动等标志） |
| `phone_tap` | 按文字/描述点击（**在执行那一刻重新定位**），或按绝对坐标、或按 fx/fy 分数坐标 |
| `phone_type` | 向当前聚焦的输入框写入文本（支持中文） |
| `phone_scroll` | 按方向滚动一屏 |
| `phone_key` | 返回 / 主页 |
| `phone_launch` | 打开白名单内的应用 |
| `phone_screenshot` | 截图为图片内容块（需要 Android 11+ 与支持图片的模型） |

设计上刻意**不使用「观测编号」**：目标按文字或坐标在执行时解析，界面重绘不会让动作失效，模型也不必每次动作前重新读屏。

### 界面提示与自动返回

- 执行期间屏幕顶部有一条**无障碍悬浮提示**（「DSH 正在工作 · phone_tap」），不遮挡、不可点击、不影响操作；
- 一轮结束时（成功、失败或被停止）提示自动消失，并把 DSH 拉回前台，你不需要去通知栏里找；
- 截图前会临时收起该提示：它属于本应用，会被系统的「多窗口」检查误判为第二个应用，从而拒绝截图。

### 读不到界面的应用

部分应用（例如微信）**不向无障碍暴露界面树**——系统的 `uiautomator` 读它们同样只有一个根节点。此时 `phone_observe` 返回空列表和一个说明；若模型支持图片，插件会**自动附上一张截图**并提示用 fx/fy 坐标操作，而不是让模型反复重试。

### Shizuku（可选，用于精确截图）

无障碍截图有两处硬限制：屏幕上出现第二个窗口就整体拒绝，且部分应用完全截不到。运行 Shizuku 可以绕开：

1. 打开 Shizuku → 「通过无线调试启动」；
2. 环境页 → 手机控制 → 点「授权 Shizuku」；
3. 此后 `phone_screenshot` 优先走 shell 的 `screencap`，未授权或失败时回退无障碍路径。

实现上，Shizuku 的用户服务进程以 shell 身份执行 `screencap`，把图写进本应用的外部目录，App 再读文件：一张全屏 PNG 有 2–5MB，远超 binder 单次事务约 1MB 的上限，因此不能直接回传字节。白名单检查在两条路径上都保留，且在截图**之前**执行。

### 完全不开无障碍（可选）

无障碍未连接时，环境页会多出一个「不开无障碍也能用（Shizuku）」。它由一个前台服务托管**同一个** `/phone` 接口，动作全部交给 shell：读界面（`uiautomator dump`，不需要用户开任何无障碍服务）、点击、滚动、返回、开应用、截图。工具层、插件与模型看到的契约完全一致，只有执行者换了人。

代价说清楚：

| | 无障碍模式 | 纯 Shizuku 模式 |
| --- | --- | --- |
| 中文输入 | ✅ 原生支持 | ❌ 只能英文（`input text` 不支持中文） |
| 重启后 | 仍在 | 需要重新用无线调试激活 Shizuku |
| 常驻通知 | 无 | 有（前台服务，否则任务中途会被系统回收） |
| 截图 | 有限制 | 无限制 |
| 停止应用 / 授权 / 改设置 | 做不到 | 可以 |

## 附件：图片与文件

输入框左侧的 📎 可选择任意文件，按类型分流：

| 类型 | 处理方式 |
| --- | --- |
| 图片 | App 本地压缩（长边 1600px、JPEG 85，超限自动降质量），作为**图片内容块**随消息发送，模型直接「看见」 |
| 其他文件 | 上传到当前工作区的 `.dsh-attachments/`，把**路径写进消息**，模型用它已有的文件/终端工具读取 |

非图片走「落盘 + 给路径」比塞进对话更省 Token，也不受模型是否支持图片的限制。图片经压缩后通常只有几百 KB；其他文件建议不超过 4MB——请求体上限 8MB，而 base64 会膨胀约三分之一。一次最多 4 个附件，发送成功后输入框上方的附件条自动清空。

> 图片需要端点支持图片输入。声明是**恒定**的（始终声明支持图片），因此给纯文本模型发图会得到接口方的报错——能力判断交给模型侧，不需要你在预设里勾选。

## 会话统计

顶栏第二行是三个独立按钮，**各点一次直达明细**：

| 按钮 | 明细内容 |
| --- | --- |
| 轮数 · 步数 | 轮数/步数、模型用时、工具调用用时、平均首 Token（TTFT）、输出速度（TPS） |
| Token | 总用量、缓存命中率、未缓存输入、缓存读取、缓存写入、输出 |
| 上下文 | 已用百分比与进度条、系统提示词/工具定义/对话消息的估算占比 |

数据来自 DSH 官方的 `session-stats` 与 `token-meter` 投影（`bridge/metrics-plugin.mjs`），包含引擎保留的历史；Token 用量取提供商上报值，上下文组成为 DSH 估算，缺失显示 `—`。旧会话首次更新统计需要再运行一次。

## 自定义 API 与多个预设

- **协议**：OpenAI Chat Completions / OpenAI Responses / DeepSeek Messages；
- **地址**：填基础地址或完整请求地址都可以，会自动补全后缀；
- **模型发现**：「测试全部 API」向端点查询 `/models`（自动尝试多种根路径），勾选后批量加入预设；
- **推理强度**：按协议提供不同档位（DeepSeek 为 off/low/high/max，其余含 minimal/medium/xhigh 等），默认使用模型自身设置；
- **密钥**：Keystore + AES-GCM 加密存储，界面上不回显。

## 工作区、文件与终端

- **工作区**：注册 Termux 可访问的绝对路径；快捷入口只有「主目录 / 内部存储 / 外接存储卷」，避免误选不可写目录；
- **文件查看**：文本与代码可编辑保存（保存采用临时文件 + 重命名，并用修改时间检测外部冲突）、语法高亮、查找与跳转；图片与 PDF 只读（PDF 支持双指缩放）；二进制提供十六进制视图；ZIP 可解压到当前目录（含 zip-slip 防护）；GBK 文件会正确解码，编辑时提示将转为 UTF-8；
- **可运行文件**：列表行与查看器都提供 ▶，按扩展名生成命令（编译型语言会先编译再执行，缺工具链时在终端里明确报错）；
- **终端**：持久 PTY，可在工作区目录打开，也能从文件视图「跳转并运行」。

## 从源码构建

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.cache\gradle'
$env:ANDROID_USER_HOME = Join-Path (Get-Location) '.cache\android'
.\gradlew.bat --no-daemon :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。安装后桥接资产会在版本号变化时自动同步到 Termux。

## 发布新版本

一次发布要改三处版本号，缺一处就会"改了没生效"：

| 位置 | 何时必须改 |
| --- | --- |
| `app/build.gradle.kts` 的 `versionCode` / `versionName` | 每次发布 |
| `app/src/main/java/dev/dsh/pocket/BridgeApi.kt` 的 `BRIDGE_VERSION` | **改动了 `bridge/` 或 `termux/` 时**，且必须与下一项一致 |
| `bridge/server.mjs` 健康检查返回的 `version` | 同上 |

桥接资产只在健康检查版本号变化时同步到 Termux。这两处不一致，改动就不会生效，症状是出现「接口不存在」这类本不该有的错误。

构建：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

产物在 `app/build/outputs/apk/{debug,release}/`。release 使用项目自带 keystore 签名，因此可以和 debug 版互相覆盖安装；它不含调试夹具且不可调试。换正式签名前请注意：换签名等于换应用，无法覆盖安装，数据会清空。

**用 Release 附件发布，不要把 APK 提交进 git。** `artifacts/` 与 `*.apk` 都在 `.gitignore` 里，这是有意的：二进制一旦进入历史就永远删不掉，此后每次克隆都要下载全部历史版本。

```powershell
$tag = 'v0.6.2'
$nl = [string][char]10
$cred = ('protocol=https' + $nl + 'host=github.com' + $nl + $nl) | git credential fill
$token = ($cred | Where-Object { $_ -like 'password=*' }).ToString().Substring(9)
$h = @{ Authorization = 'token ' + $token; Accept = 'application/vnd.github+json'; 'User-Agent' = 'dsh-pocket-build' }
$api = 'https://api.github.com/repos/alen123222/DSH-mobile-IDE-withTermux'
$body = @{ tag_name = $tag; name = "DSH Pocket $tag"; body = '版本说明' } | ConvertTo-Json
$rel = Invoke-RestMethod -Method Post -Uri ($api + '/releases') -Headers $h -Body $body -ContentType 'application/json'
foreach ($f in @('artifacts\apk\dsh-pocket-0.6.2-release.apk', 'artifacts\apk\dsh-pocket-0.6.2-debug.apk')) {
    Invoke-RestMethod -Method Post -Headers $h -InFile $f -ContentType 'application/vnd.android.package-archive' `
        -Uri ('https://uploads.github.com/repos/alen123222/DSH-mobile-IDE-withTermux/releases/' + $rel.id + '/assets?name=' + (Split-Path $f -Leaf))
}
```

发布说明里要如实写出尚未验证的部分：真机装一次，并在设备上的 Termux 运行一次 `node --test`——Windows 无法创建管道，Node 回归只能在设备上跑。

## 测试与验证

| 层次 | 方式 |
| --- | --- |
| Node 回归 | `node --test` 覆盖桥接路由、工作区/文件/压缩包、提供方补丁与会话恢复；**必须在设备上的 Termux 运行**（Windows 无法创建管道） |
| Android 单元测试 | 文案表、时间线分组、引擎设置、输入守卫 |
| Android Lint | 随构建一起跑 |
| 仪器测试 | `ProviderSmokeInstrumentation`：对本地模拟 API 验证端点归一化、模型发现、三种协议、401 脱敏、多预设加密；加 `-e phone true` 可跑手机控制自检 |

在设备上跑 Node 套件：把仓库推到 `/data/local/tmp` 后 `run-as com.termux` 执行（注意 `adb push` 到已存在目录会嵌套，先删除目标目录）。

## Android arm64 适配

安装脚本 `termux/install-dsh.sh` 与一组补丁共同把上游引擎搬上 Bionic：

| 文件 | 作用 |
| --- | --- |
| `termux/patch-runtime.mjs` | 校验版本与预期源码片段后应用补丁，保留备份与审计信息 |
| `termux/android-loader.cjs`、`android-system.mjs`、`publish.c` | 提供 `@pocket/android-loader` 与 `@pocket/android-system`，把文件锁与「原子且不覆盖」的发布换成 Android 可用实现 |
| `termux/sdk-session-patch.mjs` | SDK 只能创建会话不能接管已有会话；补丁让它先 `resume`、失败才 `create`，这样换模型/换连接后同一对话能继续 |
| `termux/ensure-images.mjs` | 安装 `@img/sharp-wasm32`，让引擎在没有原生 libvips 的 Android 上也能处理截图 |
| `termux/attachment-patch.mjs` | 把附件仓库的目录边界、发布与清理改为 Android 语义 |

原生模块（flock、publish、koffi、node-pty）由安装脚本用 clang 编译；`termux/smoke.mjs` 在结束时验证文件锁、原子发布、PTY 与 SDK 握手。

## 数据存储与权限

**Android App**：加密的 API 预设（Keystore/AES-GCM）、读写工作区文件的权限、可选的通知与无障碍权限。

**Termux**（`~/.local/share/dsh-pocket/`）：

```text
bridge/ termux/          # 同步过去的桥接资产与脚本
runtime/                 # 固定版本 DSH 与编译产物
dsh-home/                # 引擎的会话、附件与缓存
provider-patches/        # 不含 Key 的会话适配器补丁
chats/                   # 会话记录
connection.json          # 回环端口与令牌（0600）
```

工作区文件始终留在你自己的目录里；删除对话不会触碰项目文件。

应用包名固定为 `dev.dsh.pocket`。**升级必须使用同一签名**：项目当前用 `.cache/debug.keystore` 签名，换签名会变成另一个应用（数据与已授予的权限都会丢失）。

## 升级与故障排查

**改了桥接却像没生效**：App 只在健康检查版本号变化时同步桥接资产。改动 `bridge/` 或 `termux/` 后必须提升版本号，否则手机上是旧代码（症状：出现「接口不存在」这类不该有的错误）。

**引擎像是还在用旧补丁**：引擎进程跨轮复用，只在连接签名变化时重建。签名已包含 `PATCH_REVISION`；若你改了补丁或启动环境，记得同时提升它。

**发图片报 `SDK image prompt requires an attachment store`**：附件仓库没挂上。它现在**无条件**随补丁加载，不依赖手机控制是否开启。

**发图片报 `EACCES ... '/data/data'`**：Android 上 Node 在缺少 `HOME` 时会退回 `getpwuid()`，而应用 UID 没有 passwd 条目，`homedir()` 便返回 `/data/data`。桥接现在**无条件**给引擎注入 `HOME`，并把附件仓库的 `dshHome` 显式指向状态目录。

**界面显示「Shizuku 未运行」，但 Shizuku 确实在跑**：binder 是通过清单里的 `rikka.shizuku.ShizukuProvider` 交给 App 的。只依赖 `dev.rikka.shizuku:api` 而没有这个 provider 时，`pingBinder()` 永远为 false。

**加了 provider 反而一启动就崩**：`ShizukuProvider` 会断言 `android:multiprocess="false"`，写 `true` 会在 Application 创建阶段抛 `IllegalStateException`。另外用户服务的 `debuggable` 必须与应用自身一致——调试版必须传 `true`，否则绑定被拒且**没有任何日志**。

**用户服务进程起不来，日志只有 `Found multiple conflicting per-domain rules`**：网络配置同时写了 `base-config` 和重复同规则的 `domain-config`，系统初始化附加进程时判定冲突。只保留 `base-config` 即可。

**微信之类截不了屏**：截图要求「只有一个应用窗口」，而我们自己的悬浮提示会被算作第二个应用。现在截图前会收起提示、截完恢复，并把本应用窗口从检查里排除；运行 Shizuku 则完全不受此限制。

**服务版本落后**：环境页出现「更新本地服务」时，说明 APK 自带版本更高；点击即会重启本地服务并保留工作区与对话记录。

**打开 Termux 后安装无反应**：确认 `allow-external-apps=true` 已写入 `~/.termux/termux.properties`；一键按钮失败时会主动弹出这段说明与失败原因。

**终端无法输入或尺寸无效**：确认 Python 存在（安装脚本会装），并重新进入终端页。

## 已知限制与后续方向

- 使用 `sdk-minimal` 配置，没有完整的桌面插件管理界面；
- 手机控制依赖各应用的无障碍实现：不暴露界面树的应用只能靠截图 + 坐标（更慢、更易错），且需要模型支持图片。运行 Shizuku 可改善截图，但读控件树仍受各应用限制；
- 输入对不同控件的兼容性不一致：网页/自定义输入框可能只落到候选栏；密码字段一律拒绝；
- SDK 没有工具审批与取消接口，执行同意目前是**隐式**的（发送即允许在该工作区执行命令与改文件）；
- 会话恢复依赖非官方补丁，上游升级需要重新核对补丁锚点。

计划方向：逐工具审批、Git diff 视图、后台连接保活、终端多标签、虚拟屏。这些是方向，不代表当前 APK 已实现。

## 项目结构

```text
app/src/main/java/dev/dsh/pocket/   # 界面、状态、无障碍服务、加密存储
app/src/main/res/xml/              # 无障碍服务配置
app/src/debug/                     # 手机控制真机自检用的夹具（不进正式界面）
bridge/
  server.mjs        # 回环 HTTP API、SSE 与鉴权
  dsh.mjs           # 引擎进程生命周期、事件转录与手机控制通知
  providers.mjs     # 端点处理、适配器补丁、模型发现、进程指纹
  phone-plugin.mjs  # 手机控制工具（含空树自动截图）
  metrics-plugin.mjs# 会话统计投影
  workspaces.mjs    # 目录、文件、压缩包与附件落盘
  terminals.mjs     # PTY 管理
scripts/            # 构建辅助与终端资源准备
termux/             # 安装脚本与全部 Android 运行时补丁
tests/              # Node 回归与模拟 SDK / API
```

缓存、上游参考副本、构建产物、设备日志与签名密钥均被 Git 忽略；APK 不作为源码提交。

## 第三方项目与许可证

- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)：真实代理引擎，固定 `0.2.0-rc.2`，上游 MIT；
- [Termux](https://github.com/termux/termux-app)：Android 执行环境，由用户独立安装；
- [Termux RUN_COMMAND](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)：外部应用命令调用接口；
- [xterm.js](https://github.com/xtermjs/xterm.js)：终端渲染，附带资源保留许可证；
- Jetpack Compose、OkHttp、Python、Node.js 等遵循各自许可证。
