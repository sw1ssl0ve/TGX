<p align="center">
  <a href="README.md">Русский 🇷🇺</a> ·
  <a href="README.en.md">English 🇺🇸</a> ·
  <a href="README.zh-CN.md"><strong>中文 🇨🇳</strong></a> ·
  <a href="README.de.md">Deutsch 🇩🇪</a> ·
  <a href="README.fr.md">Français 🇫🇷</a> ·
  <a href="README.es.md">Español 🇪🇸</a> ·
  <a href="README.it.md">Italiano 🇮🇹</a>
</p>

<h1 align="center">TGX</h1>
<p align="center">Android 上供 Telegram 使用的本地代理</p>

<p align="center">
  <img src="assets/giftgx.gif" width="85%" alt="TGX 标志动画" />
</p>

## TGX 是什么？

TGX 是一款 Android 应用，可在设备上为 Telegram 启动本地 MTProto 代理。它让 Telegram 通过专用代理连接，而无需让手机的全部互联网流量经过 TGX。TGX 不是 VPN，也不会改变其他应用的网络路由。Telegram 是否可用取决于网络和所选路由；TGX 不保证绕过封锁、匿名性或在所有网络中正常工作。

## 支持项目

支持完全自愿，可帮助 TGX 持续开发和维护。

- **BTC：** `bc1q7pzjs4wuvffvpr69mcw6ln90gqtxptwmk7jvx5`
- **ETH：** `0x20C407bFF67e3ACbDc916d91b11c3E7A63342e7b`
- **USDT（TRON）：** `TKRdMNhunBajoujiWd9aKXpiVH2GcJeYAg`

## 工作原理

<p align="center">
  <img src="assets/TGX.png" width="90%" alt="TGX 路由概念示意图" />
</p>

1. TGX 启动 Android 前台服务，并监听本地地址 `127.0.0.1:1443`。
2. Telegram 将其作为 MTProto 代理连接。TGX 检查 MTProto 初始数据，并选择通往 Telegram 数据中心的路由。
3. TGX 使用 TLS/WebSocket 传输：预热连接、Telegram WebSocket 地址，以及 Cloudflare 代理域名。对于已配置的数据中心，如果 WebSocket 路由不可用，也可使用直接 TCP 路由。
4. TGX 从 [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) 获取 Cloudflare 域名列表，验证后大约每小时更新一次；下载失败时会保留本地最后一份有效列表。
5. 服务监控 Android 网络状态，并管理本地服务器和上游连接。网络变化或路由故障可能中断连接，无法保证始终可用。

TGX 只处理发送到本地代理的 Telegram 连接，不是覆盖整个设备的 VPN 隧道。

## 安装与开始使用

### 1. 下载 APK

请只从官方 [GitHub Releases](https://github.com/sw1ssl0ve/TGX/releases) 页面下载。编写本 README 时，仓库尚未发布包含 APK 的 Release。请勿从第三方网站安装构建版本。

### 2. 安装应用

如果之后发布 APK，请在 Android 上打开并确认安装。如果系统阻止安装，请在设备设置中允许来自该来源的应用。菜单名称因 Android 版本和设备厂商而异。

### 3. 权限

Android 可能会请求通知权限。TGX 通过通知显示正在运行的前台服务，通知中也提供停止操作。即使拒绝权限，TGX 仍会请求启动服务，但 Android 可能隐藏通知。无需手动授予互联网权限：应用清单已声明 `INTERNET` 权限。

### 4. 首次连接

打开 TGX 并启动代理。首次成功启动后，TGX 会提示一次是否打开 Telegram。在 TGX 中确认后，在 Telegram 中添加或启用提示的 MTProto 代理。TGX 会打开包含 `127.0.0.1:1443` 和 Secret 的 `tg://proxy` 链接。如果未安装 Telegram 或无法打开链接，TGX 会显示提示；也可以在 Telegram 中手动填写 TGX 设置里显示的本地地址、端口和 Secret。

### 5. 检查是否正常工作

在 Telegram 中确认可以连接、加载聊天列表和对话，并发送和接收一条测试消息。还可以尝试打开图片或其他媒体。TGX 中显示的状态本身不能证明 Telegram 已成功传输数据。

### 6. Telegram 无法连接时

在 TGX 设置中检查 Secret，它必须是 32 个十六进制字符。可以生成新的 Secret 并点击 **保存并打开 Telegram**。TGX 会保存它，并重启正在运行的服务或启动服务，然后用新链接打开 Telegram。在 Telegram 中确认代理并选择当前条目。TGX 无法读取或编辑 Telegram 内保存的代理列表。如果 Telegram 同时保留旧条目和新条目，请选择当前代理，并在需要时手动删除过期条目。如果 Telegram 未打开，请检查是否已安装，并手动添加代理。

## 项目背景与致谢

TGX 借鉴了 [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) 的思路和兼容技术方案，包括通过 WebSocket 传输 MTProto，以及 Cloudflare 代理域名路由。TGX 是独立的 Android 应用，拥有自己的 Kotlin 实现、界面和 Android 服务生命周期管理；它不是 Flowseal 官方产品。

感谢 Flowseal 和项目贡献者公开这些工作。tg-ws-proxy 仓库采用 MIT 许可证（[许可证文本](https://github.com/Flowseal/tg-ws-proxy/blob/main/LICENSE)）。目前 TGX 仓库没有 `LICENSE` 文件，因此本 README 不为 TGX 指定许可证，也不授予复用其代码的许可。TGX 的授权方式以及 MIT 要求是否适用于派生技术内容，需要由仓库所有者另行决定。

## 分享项目

如果 TGX 对你有帮助，可以给仓库点 ⭐、分享给朋友，或告诉可能需要它的 Telegram 社区：[github.com/sw1ssl0ve/TGX](https://github.com/sw1ssl0ve/TGX)。
