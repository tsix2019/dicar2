# DiCar 车况 —— 比亚迪 DiLink 4.0 车辆信息与控制 App

按《BYD_DiLink4_VehicleApp_Requirements_v2》实现的可编译基础版本：Kotlin + Jetpack Compose + MVVM，
单 Activity + 前台服务，纯本地运行（只访问本机 127.0.0.1:8988 的迪加接口，不连外网）。

> ⚠️ 所有 BYDAuto 接口名 / 编码都来自 DiLink 3、DiLink 5 车型的公开逆向资料，**没有一条在 DiLink 4.0 上验证过**。
> 上车后请先跑一遍「探测 BYDAuto 接口」，再按报告修正 `BydApiMap.kt`。

---

## 1. 构建

环境：JDK 17、Android SDK（platform 35 / build-tools 35），`local.properties` 指向本机 SDK。
依赖仓库优先用阿里云镜像（`settings.gradle.kts`），官方仓库兜底。

```bash
./gradlew assembleRelease        # 产物 app/build/outputs/apk/release/app-release.apk（R8 压缩后约 2.3 MB）
./gradlew assembleDebug          # 调试包（约 30 MB）
./gradlew testDebugUnitTest      # 单元测试
```

release 包为个人侧载方便，直接使用 debug 签名（见 `app/build.gradle.kts`）。

## 2. 安装到车机

车机需先开启无线 ADB（社区方法可参考 [BYDMate README](https://github.com/AndyShaman/BYDMate)）。

```bash
adb connect <车机IP>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

首次打开会自动启动前台服务，通知栏常驻「车速 | SOC | 空调」，通知上的「停止采集」可关闭服务。

**界面**：顶栏可在「孪生」（主页：车辆俯视图 + 仪表 + 趋势曲线）和「卡片」（全量读数 + 空调控制）之间切换。
顶栏的「悬浮窗」可在其他应用之上常驻一个小窗（车速 / SOC / 功率 / 转速），可拖动，点本体回主界面、点 ✕ 关闭；
首次需要在系统设置里允许「显示在其他应用上层」。

## 3. 上车验证流程（重要）

1. **设置 → 数据源选「自动」**：先尝试 BYDAuto，失败自动降级迪加；两者都不可用时首页顶部提示原因，所有数值显示 N/A。
2. **设置 → 探测 BYDAuto 接口**：扫描 framework 中所有 `android.hardware.bydauto.*` 类，导出常量、方法签名和所有无参 getter 的当前值：
   ```bash
   adb pull /sdcard/Android/data/com.dicar.vehicle/files/probe/
   ```
3. **对照报告修改 `BydApiMap.kt`**：首页某项 N/A 时，在报告里找对应的方法或 `BYDAutoFeatureIds` 符号，改映射表即可，其它代码不用动。
4. **用迪加交叉核对**：装有迪加时切到「仅迪加」，对比两边读数，确认编码（档位、模式等）是否一致。
5. 查看反射失败日志：`adb logcat -s BydDevice BydFeatureIds VehicleRepository`

`BydApiMap.kt` 中每一项都有来源标记：

| 标记 | 含义 |
|---|---|
| `[D3]` | wheregoes/byd-apps 在 DiLink 3（海豚 / 宋 Pro）实车验证 |
| `[D5]` | AndyShaman/BYDMate 在 DiLink 5（豹 3）实车验证的 FID 符号与编码 |
| `[X]` | 第三方反射代码里出现过，未见实车验证 |
| `[?]` | 推测，必须用探测报告核对 |

## 4. 架构

```
app/src/main/java/com/dicar/vehicle/
├── VehicleApp.kt                 Application + 手动依赖注入（AppContainer）
├── MainActivity.kt               单 Activity，申请通知权限并启动前台服务
├── service/VehicleMonitorService.kt   前台服务：持有轮询、刷新通知（限流 2s）
├── helper/                       辅助进程（app_process 以调试身份运行，最小依赖）
│   ├── HelperMain.kt                 入口：拿系统 Context，按行协议读写 BYDAutoDeviceManager
│   └── HelperReflect.kt              辅助进程内的反射封装
├── data/
│   ├── model/VehicleState.kt     全量状态（全部可空 → N/A）+ 推算字段（压差/电池功率/瞬时电耗/能量流）
│   ├── model/VehicleCommand.kt   控制指令：防抖 key、乐观更新、回读期望值
│   ├── VehicleRepository.kt      数据源选择/降级、定时轮询、控制下发与回读确认
│   ├── SettingsStore.kt          数据源模式、刷新间隔（0.5–2 s）
│   └── source/
│       ├── VehicleDataSource.kt  数据源接口
│       ├── bydauto/              主数据源：反射调用 android.hardware.bydauto.*
│       │   ├── BydApiMap.kt          ★ 接口映射表（上车后主要改这里）
│       │   ├── BydAutoDataSource.kt  按表读取 → 换算 → 范围校验；控制下发；自动选通道
│       │   ├── BydAccess.kt          读写通道抽象 + 进程内 / 无线调试两种实现
│       │   ├── BydDevice.kt          单个设备的反射包装（缓存 Method、错误只记一次日志）
│       │   ├── BydFeatureIds.kt      FID 符号 → 本车数值（运行时解析 BYDAutoFeatureIds）
│       │   ├── BydPermissionContext.kt  进程内放行 BYDAUTO_* 权限检查
│       │   ├── BydAutoProbe.kt       接口探测工具
│       │   ├── DexTypeScanner.kt     最小 dex 解析（找 bydauto 类）
│       │   └── adb/                  内置 ADB 客户端（dadb）+ 辅助进程会话/行协议
│       ├── diplus/DiPlusDataSource.kt  备用数据源：迪加 HTTP 127.0.0.1:8988
│       └── mock/MockDataSource.kt      模拟数据（无车调 UI）
├── ui/  MainViewModel + Compose 界面
│   ├── screen/TwinScreen.kt          孪生主页：车辆俯视图 + 四块仪表 + 趋势曲线
│   ├── screen/DashboardScreen.kt     顶栏（孪生/卡片切换、悬浮窗、设置）+ 卡片视图
│   └── components/                   仪表、车辆俯视图、曲线图、卡片与控件
└── util/ ValueSanitizer（错误码/无效值 → null）、Format（null → N/A）
```

### 数据源

- **BYDAuto**：两条通道自动择一（见 `BydAccess`）——
  - **无线调试通道**（`AdbBydAccess`，车机实际走这条）：App 用内置 ADB 客户端（dadb）连车机本机 `127.0.0.1:5555`，
    以调试身份启动辅助进程 `HelperMain`（`app_process`），由它调 `BYDAutoDeviceManager` 读写。
    DiLink 4.0 的 `BYDAUTO_*_GET/SET` 是签名级权限，App 自身进程调用会被车机服务端拒绝（`permission deny`），
    只有调试身份（shell）能通过。**首次连接车机会弹「允许调试」，勾一律允许即可。**
  - **进程内通道**（`InProcessBydAccess`）：App 直接反射。少数权限宽松的车型可用；被拒时自动改走上面的无线调试通道。
  - 每个字段先试命名 getter（如 `BYDAutoSpeedDevice.getCurrentSpeed()`），失败再按 `BYDAutoFeatureIds`
    符号解析出 FID 走 `getInt/getDouble`。FID 符号→数值在 App 进程内解析（读常量不需要权限）。
- **迪加**：`/api/getDiPars?text=别名:{中文参数名}|…` 一次读全部参数；控制用 `/api/sendCmd?cmd=迪加<指令>`。
  sendCmd 对无效指令也返回成功，所以只能靠回读确认。
- **模拟**：行驶数据随时间变化；空调状态可被控制，副驾通风和一键净化故意返回「不支持」，用来验证提示链路。

### 启用 BYDAuto（车机首次）

1. 车机开启无线 ADB（见 §2）。
2. App 选「自动」或「仅 BYDAuto」。首次会读到本机调试口 → 车机弹「是否允许 USB/无线调试」，勾**一律允许**并确定。
3. 之后 App 会自动起辅助进程读数；授权只需一次（密钥存在 App 私有目录）。
4. 若仍全 N/A：`adb logcat -s AdbTransport HelperSession BydAutoDataSource` 看连接/辅助进程日志。

### 控制链路

点击 → 乐观更新界面 → 同类指令防抖 350 ms 合并 → 下发 → 400 ms 后回读
→ 3 s 内读到目标值即视为生效，否则提示「未生效」。

- 「温度+」这类加减操作在仓库线程上按点击顺序累加，连点不会丢步。
- 读不到的字段（如迪加的座椅档位）会保留最后下发的值并提示「无法回读确认」。

## 5. 需求对照

| 需求 | 状态 |
|---|---|
| F01–F08 动力 | 已实现。两个数据源都只有整车功率，发动机功率/电机功率暂无单独来源，显示 N/A |
| F09–F17 电池 | 已实现。BYDAuto 暂无平均电池温度、行程能耗；瞬时电耗由「功率 ÷ 车速」推算 |
| F18–F24 空调/座椅 | 已实现，带防抖与回读确认 |
| F25 一键净化/速冷 | 按钮已提供：前挡除霜可用；速冷（BYDAuto）未实测；净化暂无接口 →「暂不支持」 |
| F26–F32 车身 | 已实现。BYDAuto 暂无方向盘转角来源（迪加有） |
| F33–F37 其他 | 总里程、坡度、时间、能量流已实现；小计里程、海拔、PM2.5 暂无来源 |
| F38 快捷面板、F40 刷新频率、F41 前台服务、F42 N/A | 已实现 |
| F39 卡片自定义、P2 全部 | 未实现（后期） |

## 6. 已知限制

- **温度步进**：BYDAuto 的 `setAcTemperature` 和迪加的「设置温度N」都只收整数 ℃，所以这两个数据源下温度按 1 ℃ 调节（由数据源上报 `acTempStep`）；模拟数据源仍是 0.5 ℃。
- **风量**：命名接口 `setAcWindLevel` 在实车上无效，改写 FID `Ac.AC_WIND_LEVEL_SET`（解析不到时用 `0x1DE0000C`，该值在 DiLink 3 和 DiLink 5 上一致）。
- **座椅**：BYDAuto 只能写 FID（豹 3 验证；宋 L / 汉 EV 上开关写入会返回成功但不动作，界面会提示「未生效」）。只使用车机上按符号解析到的 FID，不写死数值。迪加只支持 1–2 档。
- **出风模式**：BYDAuto 只确认了 吹面=1、吹脚=5、除霜=0 三个编码，其余为推测。
- **服务端权限校验**：如果 DiLink 4.0 的 autoservice 在服务端再校验一次权限，BYDAuto 调用会抛 `SecurityException`（探测报告里能看到），「自动」模式会降级到迪加。
