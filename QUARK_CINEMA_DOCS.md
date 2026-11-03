# CleanYourTV - 夸克网盘云端大屏家庭影院系统

## 一、 项目概述

CleanYourTV 是一款面向 Android TV / 电视盒子 / 投影仪的现代极简原生启动器（Launcher）与家庭影院中心。
本项目深度集成了**夸克网盘官方 TV OpenAPI**，将用户个人云盘中的电影、电视剧、动漫、综艺等高清/4K 原画资源，直接转换为媲美 Apple TV 的沉浸式海报墙与极速流媒体影院体验。

---

## 二、 核心特性

1. **官方 TV OpenAPI 规范授权**：
   - 采用夸克官方电视端设备授权规范，扫码或 Token 直连，不依赖模拟网页登录或高风险第三方私有接口。
   - 稳定换取官方 CDN 直链与音视频数据流。

2. **云盘全自动影视聚合与海报墙**：
   - **四级智能归类**：自动递归索引网盘中的「电影」、「电视剧」、「动漫」、「综艺」。
   - **系列大片合集模式**：对多部电影系列（如漫威电影宇宙、蜘蛛侠系列等）自动聚合为单张大片卡片，点击呼出大片展架。
   - **多季与全集抽屉**：电视剧/动漫支持多季自动归类（第一季、第二季...），选集弹窗与播放器内无缝呼出切集抽屉。
   - **大屏海报与动态背景**：遥控器焦点移动时，全屏沉浸式无缝切换影视 4K 背景画卷（Backdrop Artwork）。

3. **突破网盘限速的 `QuarkChunkedDataSource` 分片并发引擎**：
   - **单连接限速痛点**：夸克/阿里等网盘 CDN 对开放式长连接（`Range: bytes=0-`）通常有严格的单线程带宽 QoS 限制（约 1.2~1.8MB/s），导致 4K 高码率原画播放几秒便频繁缓冲。
   - **闭区间 Range 突破机制**：将流切分为固定 4MB 闭区间（`Range: bytes=A-B`），将播放流伪装为常规分块下载，单连接即可拉满宽带下行（单连接 8~15MB/s）。
   - **滑动窗口 3 线程并发预取**：常驻 3 线程并发拉取后续分片，内存中维持最多 4 个 4MB Chunk（总内存仅 16MB），彻底消除 OOM 与卡顿。
   - **元数据探测直通**：对 MP4 `moov` 或 MKV `Cues` 索引的小范围探测请求（≤ 2MB）直接走单连直通，不触发无谓预取，首帧索引 1~2 秒内极速完成。
   - **零 Socket 拷贝架构**：纯在 ExoPlayer DataSource 内存管道内组装数据，彻底摒弃传统 Local HTTP Proxy（NanoHTTPD）的双倍内核态内存拷贝、端口冲突与退后台孤儿线程偷跑问题。

4. **原生 4K/HEVC 极速影院播放器**：
   - 基于 Google AndroidX Media3 (1.2.1) ExoPlayer 构建。
   - 调优 `DefaultLoadControl`（64MB 弹性内存池、500ms 极速安全起播、1500ms 快速重连）。
   - 音轨多路容灾（优先硬件直通 AC-3 / E-AC-3 / AAC）。
   - 播放中按遥控器【下键】或【菜单键】随时呼出半透明毛玻璃选集抽屉，无缝换集不退出播放器。
   - 自动记忆断点续播进度，毫秒级快进快退。
   - 播完自动 5 秒无感倒计时连播下一集（OK 键秒开，Back 键取消）。

---

## 三、 解码机制与运行环境说明

### 1. 真实电视盒子（生产环境）
* **设备配置**：小米电视盒子、外贸 S905X3/S905X4 盒子、当贝盒子、Apple TV、索尼/TCL/海信等智能电视。
* **硬件特性**：主板搭载专用硬件视频解码芯片（如 Amlogic、MediaTek Pentonic、Rockchip、HiSilicon）。
* **解码表现**：系统底层注册硬件解码器（`OMX.amlogic.hevc.decoder`、`c2.mtk.hevc.decoder` 等），支持硬件直通显存，**原生流畅硬解 4K 60fps 10-bit HDR / 蓝光 REMUX 码流，2秒内秒开起播**。

### 2. 电脑模拟器（MuMu / 开发测试环境）
* **运行机制**：模拟器是运行在电脑虚拟机中的虚拟 Android 系统，缺少真实的电视硬件解码芯片。
* **解码限制**：模拟器只能依赖系统自带的 CPU 纯软件解码器（`c2.android.hevc.decoder`）。Android 系统底层对纯 CPU 软解做了安全上限防护，当遇到 3840x2160 蓝光超高码率时，软解器会直接拒绝分配 Buffer（报 `format_supported=NO_EXCEEDS_CAPABILITIES`，`Error 0xe`）。
* **测试建议**：在模拟器上可流畅测试 1080P 高码率或 H.264 片源；4K 蓝光原盘请在真实电视盒子上体验。

---

## 四、 项目源码目录结构

```text
cleanyourtv/
├── launcher/
│   ├── app/
│   │   ├── src/main/java/com/gathin/tvhome/
│   │   │   ├── MainActivity.java                # 电视桌面主入口与大屏海报展架
│   │   │   ├── player/
│   │   │   │   ├── PlayerActivity.java          # 极速 4K 原生影院播放器
│   │   │   │   └── QuarkChunkedDataSource.java  # 突破限速的分片并发数据源内核
│   │   │   └── cloud/
│   │   │       ├── quark/
│   │   │       │   └── QuarkTvClient.java       # 夸克 TV OpenAPI 客户端
│   │   │       └── proxy/
│   │   │           └── LocalStreamServer.java   # 外部兼容流媒体代理
│   │   ├── src/main/res/                        # 电视端布局与高品质 UI 资源
│   │   └── build.gradle                         # 依赖配置（Media3, OkHttp, Glide）
│   ├── build.gradle
│   ├── settings.gradle
│   └── gradlew
├── README.md
└── QUARK_CINEMA_DOCS.md                         # 本技术架构文档
```

---

## 五、 编译与安装指南

### 1. 编译 Debug APK
```bash
cd launcher
./gradlew assembleDebug
```
产物位置：`launcher/app/build/outputs/apk/debug/app-debug.apk`

### 2. 一键安装至电视端 / 模拟器
```bash
adb connect <电视IP>:5555
adb install -r launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.gathin.tvhome/.MainActivity
```
