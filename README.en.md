<p align="center">
  <a href="README.md">🇷🇺</a> ·
  <a href="README.en.md"><strong>🇬🇧</strong></a> ·
  <a href="README.zh-CN.md">🇨🇳</a> ·
  <a href="README.de.md">🇩🇪</a> ·
  <a href="README.fr.md">🇫🇷</a> ·
  <a href="README.es.md">🇪🇸</a> ·
  <a href="README.it.md">🇮🇹</a>
</p>

<h1 align="center">TGX</h1>
<p align="center">A local proxy for Telegram on Android</p>

<p align="center">
  <img src="assets/giftgx.gif" width="85%" alt="TGX logo animation" />
</p>

## What is TGX?

TGX is an Android app that runs a local MTProto proxy for Telegram on your device. It lets you route Telegram through a dedicated proxy without sending all of your phone's internet traffic through TGX. TGX is not a VPN and does not change how other apps connect. Telegram availability depends on your network and the route in use; TGX does not guarantee that it will bypass restrictions, provide anonymity, or work on every network.

## Support the project

Support is voluntary and helps fund TGX development and maintenance.

- **BTC:** `bc1q7pzjs4wuvffvpr69mcw6ln90gqtxptwmk7jvx5`
- **ETH:** `0x20C407bFF67e3ACbDc916d91b11c3E7A63342e7b`
- **USDT (TRON):** `TKRdMNhunBajoujiWd9aKXpiVH2GcJeYAg`

## How it works

<p align="center">
  <img src="assets/TGX.png" width="90%" alt="Conceptual illustration of TGX routes" />
</p>

1. TGX starts an Android foreground service and listens on the local address `127.0.0.1:1443`.
2. Telegram connects to that address as an MTProto proxy. TGX inspects the initial MTProto data and selects a route to a Telegram data center.
3. TGX uses TLS/WebSocket transports: pre-warmed connections, Telegram WebSocket endpoints, and then Cloudflare-proxied domains. A direct TCP route is available for configured data centers when a usable WebSocket route is unavailable.
4. TGX retrieves the Cloudflare domain list from [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy), validates and refreshes it about once an hour, and keeps the last valid local copy if a download fails.
5. The service monitors Android network state and manages the local server and upstream connections. A network change or route failure can interrupt a connection; successful operation is not guaranteed.

TGX handles only Telegram connections sent to its local proxy. It is not a system-wide VPN tunnel.

## Install and get started

### 1. Download the APK

Download the app only from the official [GitHub Releases](https://github.com/sw1ssl0ve/TGX/releases) page. At the time this README was prepared, the repository had no published Releases containing an APK. Do not install builds from third-party sites.

### 2. Install the app

If an APK is published, open it on Android and confirm installation. If Android blocks the install, allow apps from this source in your device settings. Menu names vary by Android version and device manufacturer.

### 3. Permissions

Android may ask for notification permission. It lets TGX show the foreground-service notification while the local proxy is running; the notification also provides a stop action. If you deny permission, TGX still requests service startup, but Android may hide its notification. You do not need to grant internet access manually: the app declares the `INTERNET` permission in its manifest.

### 4. First connection

Open TGX and start the proxy. After the first successful startup, TGX offers once to open Telegram. Confirm in TGX, then add or enable the suggested MTProto proxy in Telegram. TGX opens a `tg://proxy` link with `127.0.0.1:1443` and your Secret. If Telegram is not installed or cannot open the link, TGX displays a message; you can configure the proxy manually in Telegram with the local address, port, and Secret shown in TGX settings.

### 5. Check that it works

In Telegram, check that it connects, loads your chat list and conversations, and can send and receive a test message. You can also try opening an image or other media. The status shown in TGX alone does not confirm that Telegram is successfully transferring data.

### 6. If Telegram does not connect

Check the Secret in TGX settings; it must contain 32 hexadecimal characters. You can generate a new Secret and tap **Save and open Telegram**. TGX saves it and restarts the running service or starts the service, then opens Telegram with the new link. Confirm the proxy in Telegram and select the current entry. TGX cannot read or edit Telegram's saved proxy list. If Telegram keeps an old entry alongside the new one, select the current proxy and remove the stale entry manually if needed. If Telegram did not open, check that it is installed and add the proxy manually.

## Project background and thanks

TGX draws on ideas and compatible technical approaches from [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy), including MTProto over WebSocket and Cloudflare proxy-domain routing. TGX is a separate Android app with its own Kotlin implementation, interface, and Android service lifecycle; it is not an official Flowseal product.

Thanks to Flowseal and the project contributors for publishing this work. The tg-ws-proxy repository specifies the MIT License ([license text](https://github.com/Flowseal/tg-ws-proxy/blob/main/LICENSE)). TGX currently has no `LICENSE` file, so this README does not assign a license to TGX or grant permission to reuse its code. TGX's licensing and the applicability of MIT requirements to any derived technical material need a separate decision by the repository owner.

## Share the project

If TGX is useful to you, star the repository, share it with friends, or tell Telegram communities that may benefit from it: [github.com/sw1ssl0ve/TGX](https://github.com/sw1ssl0ve/TGX).
