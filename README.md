# MyWrtHub

我自己的 OpenWrt 路由器管理器（Android）。基于 [whykang/wrthub-android](https://github.com/whykang/wrthub-android) 二次开发，在上游功能之上加了两块自家东西：

- **WAN流量统计**：对接自家 [luci-app-wanstats](https://github.com/qjt0409/luci-app-bandwidthd)（固件里的 rpcd `wanstats` 对象），看 WAN 口每日 / 每月总流量，保留 13 个月，与 LuCI 页面同源同数。
- **OpenAppFilter**：对接路由器上的 OpenAppFilter（appfilter），应用过滤与上网记录，功能点子来自 OPAssistant。
- **终端页（1.1）**：底部「终端」页照 OPAssistant 复刻——fwx 实时上下行速率、今日流量与活跃时长、常用 App、3 秒刷新；点开有详情六页签（基本信息 / 管控规则 / App统计 / 今日流量 / 常用App / 应用记录）和「拉黑」（fwx 上网黑名单）。
- **设备限速（1.1）**：对接固件实际装的 `luci-app-eqosplus`（不是老 eqos），按 IP / MAC / IP 段限速，单位 MB/s，带生效星期与时段；终端详情页可直接跳到对应设备的限速设置。
- **PassWall（1.2）**：服务页新增 PassWall 与 PassWall2——运行状态与总开关、当前节点、节点列表点选切换、重启服务，走 UCI `passwall` / `passwall2`（全局段 `enabled`/`node` + `nodes` 段），与 LuCI 同源。1.2 同时修复 HTTPS 下 LuCI 会话 cookie 名（`sysauth_https`）未识别导致 CGI 类功能失效的问题，并内置崩溃记录器（闪退后重开 App 可查看并复制上次的崩溃堆栈）。

其余功能与上游 WrtHub 一致：总览（CPU / 内存 / 温度 / 实时流量）、在线设备、防火墙、网络接口、OpenClash、设备限速、vnStat、LAN 测速、Samba / FTP / FileBrowser、AI 终端（ttyd）、网络唤醒、日志、软件包、定时任务、LED、WiFi 管理等。路由器侧不需要装任何额外代理，App 通过 LuCI 的 ubus JSON-RPC 与路由器通信；依赖特定包的功能在未安装时会自动灰掉并给出安装入口。

## 使用要求

- Android 8.0（API 26）以上
- OpenWrt / ImmortalWrt 路由器，带 LuCI 与 rpcd，手机能访问到
- WAN流量统计需要路由器装有 `luci-app-wanstats`；OpenAppFilter 页需要装有 `appfilter`（luci-app-oaf）

## 打包（GitHub Actions）

推送到 `main` 后，Actions 会自动跑单元测试并打出 debug APK，在该次构建的 Artifacts 里下载 `MyWrtHub-debug-apk` 即可安装。

说明：

- debug APK 用构建机临时签名，**每次构建的签名可能不同**，覆盖安装失败时先卸载旧版再装（已保存的设备信息会随卸载清掉，需要重新添加）。后续如需固定签名，再配 keystore 密钥。
- 本仓库只存纯文本源码：启动图标已改为矢量图，Gradle wrapper 的 jar 未入库；云端构建由 setup-gradle 提供 Gradle 8.11.1。本地用 Android Studio 打开即可，或先执行一次 `gradle wrapper` 生成 wrapper。

应用内更新检测读取本仓库根目录的 `update.json`（发新版时同步抬高里面的 versionCode）。

## 与上游的关系与许可证

本项目是 whykang/wrthub-android 的分支（fork 改造）：包名改为 `com.qjt0409.mywrthub`，可与原版共存安装；更新检测已改指本仓库，不会再被原版更新提示带跑。原作者版权声明保留在源码与 LICENSE 中。

Copyright (C) 2026 Wang Hongyue ([whykang](https://github.com/whykang))（上游部分）

本项目以 [GNU General Public License v3.0](LICENSE) 发布：可以自由使用、研究、修改和分享，但分发基于本项目的应用时，必须同样以 GPL-3.0 公开完整源码并保留版权声明。不提供任何担保。
