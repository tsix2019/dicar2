# DiCar 车况 · DiCar Vehicle HMI

[简体中文](#简体中文) · [English](#english)

<p align="center">
  <img src="docs/images/twin-dark.png" width="100%" alt="孪生主页 / Twin dashboard">
</p>

---

## 简体中文

运行在比亚迪 DiLink 车机上的本地车辆仪表与控制 App：读取整车数据、显示孪生仪表盘、
控制空调与车窗，并提供可配置的悬浮窗。**纯本地运行，不上传任何数据。**

> ⚠️ 个人项目，与比亚迪官方无关。接口全部来自公开逆向资料，不同车型/固件差异很大，
> 请自行评估风险。控制类功能（车窗、天窗、空调）会让车辆真实动作。

### 功能

| | |
|---|---|
| **孪生主页** | 横屏三栏：左转速大表盘、中大号车速 + 车辆俯视图（四门两盖、车窗与天窗开度、四轮胎压胎温、转向灯、泊车雷达实时联动）、右驱动/回收双向功率条与电量油量；底栏近 3 分钟趋势曲线（发动机介入时自动增加转速曲线）+ 空调/胎压/车门状态。竖屏自动改为上下堆叠 |
| **卡片视图** | 动力、电池、空调、车身、其他五类全量读数 |
| **控制** | 空调开关 / 温度 / 风量 / 出风模式 / 循环、座椅加热通风、四门车窗、天窗、遮阳帘、前挡除霜 |
| **悬浮窗** | 浮在其他应用之上，内容（主读数 / 仪表 / 曲线 / 孪生图 / 明细）可自由勾选，透明度可调，可拖动 |
| **前台服务** | 后台持续采集，通知栏常驻「车速 \| SOC \| 空调」 |
| **接口探测** | 一键导出车机上全部 `android.hardware.bydauto.*` 的类、常量、方法与当前读数，用于适配新车型 |

<p align="center">
  <img src="docs/images/cards-dark.png" width="48%" alt="卡片视图">
  <img src="docs/images/twin-light.png" width="48%" alt="浅色模式">
  <br>
  <img src="docs/images/floating.png" width="32%" alt="悬浮窗">
</p>

### 它是怎么拿到数据的

车辆数据由车机的 `android.hardware.bydauto.*` 框架提供，但读写被 `BYDAUTO_*_GET/SET`
**签名级权限**保护，第三方 App 自己调用会被车机服务端拒绝（`permission deny`）。
本项目提供两条通道，自动择优：

1. **无线调试通道（主）** — App 内置 ADB 客户端连接车机本机 `127.0.0.1:5555`，
   以调试身份用 `app_process` 启动辅助进程，由它调用 `BYDAutoDeviceManager` 读写。
   首次连接时车机会弹出「允许调试」，授权一次即可。一轮轮询所有字段只用**一次**跨进程往返。
2. **进程内通道（备）** — 直接反射调用。部分权限较宽松的车型可用。

另有迪加（DiPlus）HTTP 接口作为备用数据源。

### 构建

需要 JDK 17 与 Android SDK（platform 35 / build-tools 35），在 `local.properties` 指定 SDK 路径。

```bash
./gradlew assembleRelease   # 产物在 app/build/outputs/apk/release/
./gradlew testDebugUnitTest
```

Release 版的签名密钥不在仓库里。自己编译时会自动退回 debug 签名，能正常安装使用；
要用自己的正式密钥，在项目根目录放一个 `keystore.properties`（已被 `.gitignore` 忽略）：

```properties
storeFile=/path/to/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

### 安装与首次使用

APK 可以直接从 [Releases](https://github.com/tsix2019/dicar2/releases) 下载，也可以自己编译。

```bash
adb connect <车机IP>:5555
adb install -r app-release.apk
```

> 如果你装过 0.1.0 之前自行编译的版本（调试签名），覆盖安装会报
> `INSTALL_FAILED_UPDATE_INCOMPATIBLE`——Android 不允许签名变更。
> 先 `adb uninstall com.dicar.vehicle` 再装即可，此后各版本都能直接覆盖升级。

1. 车机开启无线 ADB。
2. 打开 App，设置里选「自动」或「仅 BYDAuto」。
3. 车机弹出「允许调试」时勾选**一律允许**。
4. 若仍无数据：`adb logcat -s AdbTransport HelperSession BydAutoDataSource`。

### 适配到其它车型

`BydApiMap.kt` 集中了所有接口映射（方法名、FID 符号、数值编码），适配新车基本只改这一个文件。
每一项都带来源标记：

| 标记 | 含义 |
|---|---|
| `[D4]` | 在 DiLink 4.0（Android 10）探测报告中确认存在 |
| `[D3]` / `[D5]` | 公开资料中在 DiLink 3 / 5 实车验证 |
| `[?]` | 推测，需用「设置 → 探测 BYDAuto 接口」核对 |

### 许可

MIT。参考过的公开资料：[BYDMate](https://github.com/AndyShaman/BYDMate)、
[byd-apps](https://github.com/wheregoes/byd-apps)。

---

## English

A local vehicle dashboard and control app for BYD DiLink head units: reads live vehicle data,
renders a digital-twin dashboard, controls climate and windows, and offers a configurable
floating overlay. **Everything runs on-device; nothing is uploaded.**

> ⚠️ Personal project, not affiliated with BYD. All interfaces come from public
> reverse-engineering work and vary a lot across models and firmware — use at your own risk.
> Control features (windows, sunroof, climate) physically actuate the car.

### Features

| | |
|---|---|
| **Twin dashboard** | Three columns in landscape: a large RPM dial on the left; big speed readout plus a top-down car view (doors, window/sunroof position, tyre pressure and temperature, turn signals, parking radar — all live) in the middle; a bidirectional drive/regen power bar with battery and fuel levels on the right. A bottom strip carries the 3-minute trend chart (an engine-RPM series appears automatically once the engine kicks in) and climate/tyre/door status pills. Stacks vertically in portrait |
| **Card view** | Full readouts grouped into powertrain, battery, climate, body and misc |
| **Controls** | A/C power, temperature, fan, vent mode, recirculation, seat heating/ventilation, all four windows, sunroof, sunshade, windshield defrost |
| **Floating overlay** | Floats above other apps; which blocks to show (readout / gauges / chart / twin view / details) and the opacity are configurable, and it is draggable |
| **Foreground service** | Keeps polling in the background with a persistent "speed \| SoC \| A/C" notification |
| **Interface probe** | One tap dumps every `android.hardware.bydauto.*` class, constant, method and current value from the head unit — the basis for porting to other models |

### How it gets the data

Vehicle data lives behind the head unit's `android.hardware.bydauto.*` framework, but reads and
writes are guarded by **signature-level** `BYDAUTO_*_GET/SET` permissions, so a third-party app
calling them directly is rejected by the vehicle service (`permission deny`). The app provides two
channels and picks whichever works:

1. **Wireless-debugging channel (primary)** — a built-in ADB client connects to the head unit's own
   `127.0.0.1:5555`, launches a helper process via `app_process` under the shell identity, and that
   helper talks to `BYDAutoDeviceManager`. The head unit shows its "allow debugging" prompt once.
   A full polling round costs a **single** round trip thanks to request batching.
2. **In-process channel (fallback)** — plain reflection, which works on more permissive firmware.

A DiPlus local HTTP API is supported as an alternative data source.

### Build

Requires JDK 17 and the Android SDK (platform 35 / build-tools 35); point `local.properties` at your SDK.

```bash
./gradlew assembleRelease   # output in app/build/outputs/apk/release/
./gradlew testDebugUnitTest
```

### Install

Grab the APK from [Releases](https://github.com/tsix2019/dicar2/releases), or build it yourself.

```bash
adb connect <head-unit-ip>:5555
adb install -r app-release.apk
```

> If you previously side-loaded a self-built (debug-signed) build from before 0.1.0, installing over
> it fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — Android does not allow a signing-key change.
> Run `adb uninstall com.dicar.vehicle` once; every release from 0.1.0 on upgrades in place.

Enable wireless ADB on the head unit, open the app, pick "Auto" or "BYDAuto only" in settings, and
accept the "allow debugging" prompt on the head unit.

### Porting to other models

`BydApiMap.kt` holds every interface mapping (method names, FID symbols, value encodings) in one
place — porting to a different car is mostly editing that single file. Each entry carries a
provenance tag: `[D4]` confirmed present in a DiLink 4.0 probe report, `[D3]`/`[D5]` verified on a
real DiLink 3/5 car in public research, `[?]` guessed and needing verification via
**Settings → Probe BYDAuto interfaces**.

### License

MIT. Built on public research from [BYDMate](https://github.com/AndyShaman/BYDMate) and
[byd-apps](https://github.com/wheregoes/byd-apps).
