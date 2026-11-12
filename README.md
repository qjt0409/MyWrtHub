# MyWrtHub

我自己的 OpenWrt 路由器管理器（Android）。基于 [whykang/wrthub-android](https://github.com/whykang/wrthub-android) 二次开发，在上游功能之上加了两块自家东西：

- **WAN流量统计**：对接自家 [luci-app-wanstats](https://github.com/qjt0409/luci-app-bandwidthd)（固件里的 rpcd `wanstats` 对象），看 WAN 口每日 / 每月总流量，保留 13 个月，与 LuCI 页面同源同数。
- **OpenAppFilter**：对接路由器上的 OpenAppFilter（appfilter），应用过滤与上网记录，功能点子来自 OPAssistant。
- **终端页（1.1）**：底部「终端」页照 OPAssistant 复刻——fwx 实时上下行速率、今日流量与活跃时长、常用 App、3 秒刷新；点开有详情六页签（基本信息 / 管控规则 / App统计 / 今日流量 / 常用App / 应用记录）和「拉黑」（fwx 上网黑名单）。
- **设备限速（1.1）**：对接固件实际装的 `luci-app-eqosplus`（不是老 eqos），按 IP / MAC / IP 段限速，单位 MB/s，带生效星期与时段；终端详情页可直接跳到对应设备的限速设置。
- **1.10**：服务端实例的新增/编辑/删除来了：服务列表右上角「添加」、点任意一行进编辑页，按类型动态生成表单（通用项：备注/启用/类型/端口；Socks 认证、SS-Rust/SSR 的加密与协议/混淆、Hysteria2 混淆、sing-box/Xray 的协议与 SS 密码等），用户绑定按 LuCI 规则（单选 user / 多选 users），编辑页内可新建用户（用户名/密码/UUID，UUID 自动生成）。高级键（传输/TLS/自定义配置）不读不写、原样保留。**同时修正 1.9 的一处关键错误**：服务端数据其实在独立配置 `passwall_server`（PassWall2 为 `passwall2_server`）里、由独立 init 脚本管理，1.9 误读了 `passwall` 配置，页面显示与开关实际都不对，本版全部改走 server 配置。
- **1.9**：PassWall 与 PassWall2 主页新增「服务端 ›」入口行（在订阅管理卡下方），点进二级页原生管理：服务端总开关（全局段 `enable` 键，与客户端总开关 `enabled` 是两个键、同段并存）+ 服务实例列表（`server` 段：备注、类型·协议、端口、逐个开关），实例运行状态走 LuCI 的 `server_status` CGI 与网页版同口径。实例的新增/编辑表单仍在 LuCI 完成，本页只做开关管理。（注：此版服务端读写误用了 `passwall` 配置，实际数据在 `passwall_server`，已在 1.10 修正。）
- **1.8**：服务页去掉「流量监控（vnstat）」与「FileBrowser」两个入口——本固件没有 luci-app-vnstat2 与 luci-app-filebrowser，这两项在服务页一直灰着占位，现从功能目录与服务页分组中一并移除（总览常用功能的候选列表也同步消失；已收藏过这两项的会自动过滤）。
- **1.7**：服务页「网络服务」补上 PassWall / PassWall2 两个入口（1.2 只在 ServiceCatalog 注册了描述符，忘了把它们加进 ServicesFragment 的分组名单，所以服务页一直不显示，只能从总览常用功能进）；PassWall 节点列表的分组改为可折叠——默认全部折叠，只展开当前节点所在的那一组，点组头展开/收起。
- **1.6**：PassWall 三处改走 LuCI 网页版同款 CGI 接口（实机坐实 `file.exec` 在部分固件上被 rpcd ACL 拒，curl/lua 都跑不了）——网络监测改调 `connect_status`、订阅更新改调 `subscribe_manual(_all)`（服务端后台跑，点完即返回）；运行状态新增 **TCP / UDP / DNS / HAPROXY** 组件监测（`index_status`，查核心进程本身），主状态也以 TCP 组件为准（procd 的 service list 对 PassWall 报不准）；国内 IP 改取 WAN 口地址（ubus 接口 dump 的默认路由接口），国外 IP 改由手机经路由器 Socks 出口实测（Socks 只绑 localhost 时取不到，显示 —）。
- **1.5**：PassWall / PassWall2 页按 LuCI 状态页布局重排——运行状态与主开关一行、节点选择卡、网络状态监测（国内百度 / 国外谷歌，路由器侧 curl 实测）、国内 / 国外 IP、订阅管理（订阅增删 + 调 `subscribe.lua` 更新，与网页版同一条命令）、节点列表按订阅分组显示；1.4 的代理设置与 DNS 卡移至页面下方。总览「下行 / 上行」卡数值改为**今日**流量（副行仍显示本月）。
- **1.4**：PassWall / PassWall2 页按 LuCI 全局页 + 本 App 的 OpenClash 页改版——大状态块、快捷模式（GFW/大陆白名单/全局/回国，即网页版的四组键值组合）、TCP/UDP 代理、大陆列表、GFW/本机/局域网开关、DNS 分流/过滤模式/远程 DNS，配置里没有的键整行隐藏（PassWall2 天然只剩三项）；总览指标新增「总下行/总上行」两卡（数据为 wanstats 已存月份累计，副行显示本月，老配置一次性默认开启）。
- **1.3**：修复切到 HTTPS 设备时白屏闪退——首页 onResume 在主线程调 `resetSessionIfStale()` 清连接池，关闭 TLS 连接要写 close_notify，被 StrictMode 判 `NetworkOnMainThreadException`（明文 HTTP 关闭不写应用层数据，所以只炸 HTTPS），已改为后台线程清理；同时改用正式签名发布（keystore 不进仓库，CI 从 Secrets 注入，见 `docs/build.yml`），签名变更后首次安装需先卸载旧版，之后可直接覆盖更新。
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
