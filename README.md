# MixFlip2Hinge

小米 MIX Flip 2（小折叠）转轴测试工程。一个最小化的 Android Demo，用于验证小折叠设备上**转轴角度**与**折叠姿态**的完整获取链路，以及**外屏（合盖副屏）**上的运行条件。


## 功能

- 实时转轴开合角度（0–180°），大字号显示 + 进度条
- 本次会话的最小 / 最大角度、事件计数统计
- 最近 6 条原始传感器事件流（时间戳 + 角度）
- 折叠姿态判定：完全展开（FLAT）/ 半折叠悬停（HALF_OPENED）/ 外屏模式
- 转轴传感器的硬件参数展示（厂商 / 量程 / 分辨率 / 采样间隔）
- 支持内屏与外屏双屏运行，自动切换文案
- 角度数据同步输出到 logcat（`adb logcat -s HingeDemo`，变化 ≥1° 记一条）

## 转轴角度获取路径（重点）

工程里同时使用了**两条互补的标准路径**，这是所有折叠屏角度测试的通用做法：

### 路径一：`Sensor.TYPE_HINGE_ANGLE` —— 拿到具体角度数值

Android 11（API 30）引入的标准传感器类型。获取入口在
`MainActivity.onCreate()`：

```
SensorManager → getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
             → registerListener(SENSOR_DELAY_GAME)
             → onSensorChanged() 里 event.values[0] 即为 0–180° 实时角度
```

- `0°` = 完全合上，`180°` = 完全展开平放；
- 底层链路：铰链霍尔/角度传感器 → 厂商 Sensor HAL → Android 传感器框架，
  无需任何厂商私有 API；
- **合盖（外屏）状态下依然实时上报**，不依赖窗口形态 —— 这是它相比
  路径二最大的优势；
- 代码位置：`app/src/main/java/com/example/hingedemo/MainActivity.java`
  中的 `onSensorChanged()`。

### 路径二：Jetpack WindowManager `FoldingFeature` —— 拿到姿态状态

`androidx.window` 提供的窗口布局监听（`WindowInfoTracker`），回调给出
`FoldingFeature`：

- 姿态状态：`FLAT`（完全展开）/ `HALF_OPENED`（悬停半开）；
- 转轴空间信息：方向（水平/垂直）、是否分隔窗口、是否遮挡内容、
  物理折叠区域矩形（可用于悬停分屏布局）；
- **不提供具体角度数值**，且只有当窗口跨越转轴（内屏展开/半开）时才存在；
- 代码位置：同文件中的 `onWindowLayout()` 与 `renderPosture()`。

### 外屏（合盖）判定

合盖后外屏窗口没有 `FoldingFeature`，所以判定不能只靠 WindowManager，
`isCoverWindow()` 分三层判断：

1. 窗口所在 `Display` 不是 `DEFAULT_DISPLAY` → 外屏（内屏永远是默认显示器）；
2. 有 `FoldingFeature` → 内屏；
3. 兜底：屏幕高度 < 600dp → 外屏（小折叠外屏物理尺寸很小）。

## 小米外屏白名单问题（重点）

想在 MIX Flip 2 **合盖后的外屏上全屏运行**，仅靠标准 Android 能力是不够的。
MIUI 的外屏启动器要求同时满足三个条件：

1. manifest 中声明私有元数据 `miui.supportFlipFullScreen = 1`（application
   与 activity 各一处）；
2. activity 响应 MIUI 私有 action `miui.fliphome.default`（外屏启动器通过
   它拉起应用）；
3. **`applicationId` 必须位于小米内置的外屏显示白名单中**。

前两条是声明式的，见 `AndroidManifest.xml` 中的注释。**第三条白名单是最大的
拦路虎**：小米按包名硬编码放行，普通第三方应用（如默认的
`com.example.hingedemo`）即使声明了前两条，合盖后也无法在外屏全屏运行。

### 本仓库的白名单文件

[`cover-allowlist.txt`](cover-allowlist.txt) 是从小米设备中提取的**外屏显示
白名单**（约 900 个包名，一行一个）。可以用于：

- 确认目标包名是否被放行；
- 挑选一个白名单包名作为测试载体（见下节）。

### 绕过测试方法：覆盖 applicationId

`app/build.gradle` 预留了 `coverAppId` 属性，构建时可以把自己的应用"伪装"成
白名单内的包名，仅改 `applicationId`，代码与 namespace 不需要任何改动：

```bash
# 用白名单内的任意包名安装，即可合盖后在外屏全屏运行、实时观察转角
./gradlew installDebug -PcoverAppId=cn.damai
```

不传该属性则使用默认包名（不在白名单内），正好可以用于**验证白名单拦截行为
本身**。注意：同一台设备上该包名若已安装原版应用会冲突，建议先用
`adb uninstall <包名>` 清理。

### 验证命令

```bash
adb logcat -s HingeDemo        # 实时角度流（变化 ≥1° 记一条）
adb shell dumpsys display      # 查看内外两个 Display 的信息
```

## 构建与运行

- JDK 17、Android SDK 36（`compileSdk 36`、`minSdk 30`）；
- Gradle 9.1.0（wrapper 自带），无需本地安装：

```bash
./gradlew installDebug                       # 默认包名（内屏验证）
./gradlew installDebug -PcoverAppId=cn.damai # 外屏白名单包名
```

`local.properties` 中的 `sdk.dir` 为本机路径，已被 .gitignore 排除，不会入库。

## 工程结构

```
├── app/src/main/java/com/example/hingedemo/MainActivity.java   # 全部逻辑
├── app/src/main/AndroidManifest.xml                            # MIUI 外屏声明
├── app/src/main/res/layout/activity_main.xml                   # 界面布局
├── app/build.gradle                                            # coverAppId 覆盖入口
├── cover-allowlist.txt                                         # 小米外屏白名单（约 900 条）
└── LICENSE                                                     # Apache-2.0
```

根目录的 `shot*.png` 为本地调试截图（不入库），记录了内屏角度、悬停姿态与
外屏模式的实际运行效果。

## 许可证

[Apache-2.0](LICENSE)。所有源文件头部均带
`SPDX-License-Identifier: Apache-2.0` 标识。

`gradlew` / `gradlew.bat` / `gradle/wrapper/*` 为 Gradle 官方分发文件，
版权归 Gradle 团队所有（Apache-2.0），不做修改。
