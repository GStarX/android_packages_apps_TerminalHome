# TerminalHome — Polaris TerminalOS 的终端 kiosk HOME

> **状态声明（重要）**
>
> 本仓库/本目录中的代码与文档均处于 **"未编译、未实机验证"** 状态：
>
> - 未在 AOSP / LineageOS 源码树中执行过 Soong 构建（`m TerminalHome`）；
> - 未安装到小米 MIX 2S（polaris / SDM845）实机，未在 LineageOS 23.2（Android 16，实验性）
>   上运行过；
> - 未验证 Device Owner 配置、锁任务（Lock Task）行为、默认 HOME 接管、Termux 启动、
>   PIN 流程、开机广播等任何运行时行为；
> - 本文中"已核实"仅指 **源码/清单级核对**（附参考链接），不代表本机行为正确。
>
> 目标设备官方支持停留在 LineageOS 22.2 / Android 15；23.2 / Android 16 属实验性，
> 系统行为差异需实机确认。本文档不包含任何虚构的构建或测试结果。

---

## 1. 用途

TerminalHome 是 Polaris-TerminalOS 项目的 Android 应用：

- 作为系统 **HOME（默认主屏）** 被预置进 ROM，开机后自动把用户带进 **Termux 终端**；
- 正常操作（Home 键、返回键、Recent、状态栏）不能轻易离开终端；
- 顶部快捷设置/通知栏在 kiosk 场景下尽量保留可下拉（Android 16 需 ROM 授予
  `SYSTEM_SUPERVISION` 角色，见 §5.4 与 §6）；
- 保留 **隐藏的管理员维护入口**（标题连点 7 次 + PIN），用于诊断、重新打开终端、退出锁定、
  修改 PIN、申请监督角色、打开系统设置；
- 不做物理锁机（不恢复出厂、不锁硬件），所有限制都建立在 Android 官方 Device Owner /
  Lock Task 能力之上。

代码只依赖 Android SDK 公开 API（minSdk 28，targetSdk 35），Java 实现，不引入第三方库、
不使用 Kotlin、没有 Gradle 工程。

---

## 2. 组件职责

| 文件 | 职责 |
| --- | --- |
| `Android.bp` | Soong 构建：`android_app`，平台证书，不安装到 priv-app，无优化。 |
| `AndroidManifest.xml` | HOME Activity、维护 Activity、DeviceAdminReceiver、BootReceiver、包可见性、`RECEIVE_BOOT_COMPLETED`。 |
| `MainActivity` | 作为 HOME；`onResume` 后自动启动 Termux；执行失败重启限流（记录启动时间戳、按 8 秒阈值判定快速返回）；状态展示；标题 7 连击进维护入口；返回键不离开。 |
| `MaintenanceActivity` | 维护界面（`exported=false`）：诊断信息、操作按钮（含 Android 16+ 且 DO 时显示的"申请监督角色"）；进入前 PIN 门禁；危险操作二次 PIN 验证。 |
| `TerminalLauncher` | 封装 Termux 探测（`getPackageInfo` / `getActivityInfo`）与启动（显式组件 + MAIN/LAUNCHER 回退），返回明确成败原因。 |
| `RestartLimiter` | SharedPreferences + 滑动窗口的**失败重启**限流器：10 分钟 / 5 次失败、4 秒冷静期、配合 8 秒快速返回判定、失败原因记录、开机/手动重置。 |
| `KioskController` | Device Owner 下的锁任务白名单/特性配置、`startLockTask`/`stopLockTask`、默认 HOME 接管、状态快照；非 DO 时如实降级。 |
| `AdminPinManager` | 维护 PIN：PBKDF2WithHmacSHA256 + 16 字节随机 salt + 120000 迭代 + `MessageDigest.isEqual`，不存明文。 |
| `AdminReceiver` | `DeviceAdminReceiver` 子类，作为 Device Owner 的 admin 组件；只记录日志与停用提示。 |
| `BootReceiver` | `BOOT_COMPLETED` 时重置失败重启窗口、清除维护模式、记录启动时间；**不启动 Activity**。 |
| `TerminalOsConfig` | 集中常量：包名、组件名、prefs 名、失败重启限流参数（含 8 秒快速返回阈值）、日志 TAG、7 连击/延迟参数；维护模式读写辅助。 |

---

## 3. 构建与产品预置

### 3.1 目录与模块

本目录按 Soong 应用目录约定组织（`Android.bp` + `AndroidManifest.xml` + `src/` + `res/`）。
典型放入源码树的路径：

```
packages/apps/TerminalHome/        ← 本目录内容
```

模块名：`TerminalHome`，包名：`org.terminalos.terminalhome`。

### 3.2 产品集成步骤（草案，未验证）

1. 把本目录放进 LineageOS 23.2 源码树（例如 `packages/apps/TerminalHome`，或通过 local manifest
   挂载本仓库到该路径）；
2. 在 polaris 产品 makefile（`device/xiaomi/polaris/lineage_polaris.mk`，具体文件以实际设备树为准）
   中加入：

   ```make
   PRODUCT_PACKAGES += TerminalHome
   ```

3. 构建：`m TerminalHome`，或整包构建；
4. Termux 需另行安装/预置（本应用不包含 Termux，只负责探测与启动 `com.termux`）；
5. 设置 Device Owner（§6）后，由 DPC 的 persistent preferred activity 接管 HOME。

**不删除 LineageOS 自带启动器**：LineageOS 23.2 默认启动器为 Launcher3QuickStep
（据 Lead 核实；包名请以实机 `adb shell pm list packages | grep -i launcher` 为准）。
本应用不修改产品配置里的启动器，而是在成为 Device Owner 后调用
`DevicePolicyManager.addPersistentPreferredActivity(...)` 把 HOME 解析指向 TerminalHome。
该行为**未实机验证**（见 §9）。

### 3.3 构建配置决策

- **`platform_apis: true`**：本模块位于平台源码树内、以平台证书签名。当前 Java 代码只使用
  公开 API（`DevicePolicyManager`、`Activity`、`PackageManager` 等均为公开常量/方法），
  但选择平台 API 编译面可以避免依赖 SDK stub 构建产物，并在将来需要系统内行为时留出空间。
  代码中唯一的隐藏 API（Android 16 的 `LOCK_TASK_FEATURE_QUICK_SETTINGS`）用**反射**探测，
  不编译依赖，因此即便换到 Android 15 分支也能编译（反射失败则功能降级）。
  若团队要求严格"公开 API 编译"，可改为 `sdk_version: "current"` + `platform_apis: false`，
  当前代码改动量为零（但需验证设备构建会产出对应的 SDK stub）。
- **`privileged: false`**：本应用**不申请任何 privileged / signature 权限**（见 §7），
  因此不需要安装进 `priv-app`，也**不需要 privapp-permissions 白名单**。
- **`certificate: "platform"`**：作为 ROM 内置应用以平台证书签名。
- **`target_sdk_version: "35"`** 与 Manifest 中 `targetSdkVersion=35` 一致
  （targetSdk 35 左右；平台实际为 API 36，行为差异需实机验证）；
- **`min_sdk_version: "28"`** 与 Manifest 中 `minSdkVersion=28` 一致（Android 9 / API 28），
  避免平台构建默认值覆盖 Manifest；
- **`optimize.enabled: false`**：预置应用不做 R8/资源压缩，行为可审查、便于 logcat 调试。
- 没有 Gradle 工程、没有依赖注入、没有第三方库；主界面不使用 Material/AppCompat，
  只使用系统 `Theme.Material.NoActionBar` 与 TextView/Button/ScrollView。

---

## 4. 运行流程

### 4.1 开机到终端

1. 系统启动、用户解锁后，系统按 HOME 解析启动 TerminalHome（若已设为默认 HOME）；
2. `MainActivity.onResume()` 刷新状态，延迟 500 ms 后尝试启动 Termux
   （优先显式组件 `com.termux/com.termux.app.TermuxActivity`）；
3. 若本应用是 Device Owner：先把本应用与 `com.termux` 加入锁任务白名单、配置锁任务特性、
   调用 `startLockTask()`，再启动 Termux；
4. 用户从 Termux 按 Home 回到 TerminalHome 时，流程重新执行（受 §4.2 限流约束）；
5. `BootReceiver` 在 BOOT_COMPLETED 时清理失败重启窗口与维护模式，保证重启后回到终端模式。

Home Activity 不等于物理锁机：设备仍可被 adb/恢复出厂/刷机改变。

### 4.2 失败重启限流（只统计失败，严格上界）

`RestartLimiter` 的语义（实现见类注释；按 Lead 决策调整）：

- **只统计失败重启**：
  - `startActivity` 抛异常/返回失败 → 立即计一次失败；
  - Termux 启动后 **不足 8 秒**又回到 HOME（疑似 Termux 退出/崩溃）→ 由 `MainActivity`
    在 `onResume` 时按 `elapsedRealtime()` 判定并计一次失败；
  - Termux 成功进入前台并停留 **≥8 秒**后用户按 Home 返回 → **不消耗**重启额度；
- 滑动窗口 **10 分钟**内失败重启最多 **5 次**；
- 任意两次自动启动之间至少 **4 秒**冷静期（与成功/失败无关，防止紧密循环）；
- 失败次数用尽 → 进入"限流锁定"：**停止自动启动**，主界面显示原因与"进入维护界面"按钮，
  不再自动重试；只有三种方式恢复：
  1. 窗口自然过期（最旧的失败记录滑出 10 分钟）；
  2. 管理员在维护界面点"重新打开 Termux"（清空失败窗口，不消耗额度，但仍先经过一次冷静期）；
  3. 设备重启（`BOOT_COMPLETED` 重置）。
- 成功启动不立即清零失败计数（失败记录按窗口自然过期），避免"成功—崩溃"交替绕过限流。
- 失败原因与次数持久化在 SharedPreferences 中，杀进程/重建界面不能绕过。
- 判定实现：`MainActivity` 在成功发出启动请求时记录启动时间戳（`elapsedRealtime`），
  下次 `onResume` 与 8 秒阈值比较后调用 `RestartLimiter.recordLaunchFailed(...)`，
  见日志 `TerminalHome.MainActivity`。
- 已知边界：快速返回判定依赖 MainActivity 内存中的时间戳；若进程在 Termux 前台期间被杀，
  返回时无法判定，本次选择"不计数"（保守，不过度惩罚用户），见 §8。

### 4.3 隐藏维护入口与 PIN

- 主界面标题连续点击 **7 次**（相邻点击间隔 > 1.5 秒会重新计数）打开 `MaintenanceActivity`；
- 已设置 PIN：先弹 PIN 校验框，校验通过才展示维护内容，取消则退出；
- 未设置 PIN：直接进入维护界面，并弹出"设置维护 PIN"引导（危险操作仍要求先设置 PIN）；
- 维护界面（`exported=false`）按钮：
  1. **重新打开 Termux（恢复终端模式）**：重置失败重启窗口（不消耗额度）、清除维护模式、
     返回主界面，由正常流程启动；
  2. **进入维护模式（退出锁定）**：必须 PIN 验证；调用 `stopLockTask()` 并置维护模式，
     之后主界面不再自动启动终端；
  3. **设置 / 修改维护 PIN**：已设置时必须先验证旧 PIN；
  4. **申请监督角色**（仅 Android 16+ 且 Device Owner 时显示）：用 `RoleManager` 请求
     `SYSTEM_SUPERVISION`（`startActivityForResult`），结果经 `isRoleHeld` 核实后如实提示；
  5. **打开系统设置**：`Intent(Settings.ACTION_SETTINGS)`；若仍处于锁任务模式，系统可能阻止，
     界面文案已提示先退出锁定。
- 诊断信息展示：Termux 状态、Device Owner、设备管理员、锁任务状态、默认 HOME 接管状态、
  锁任务快捷设置特性状态、维护模式、PIN 状态、最近 10 分钟失败重启计数、最近失败原因/时间、
  最近成功启动时间、SYSTEM_SUPERVISION 角色持有状态。

### 4.4 维护模式与"退出后重启回终端"

- "进入维护模式（退出锁定）"后：维护模式写入 SharedPreferences，主界面不再自动启动 Termux；
- **重启设备**：`BootReceiver` 清除维护模式并重置失败重启窗口 → 回到终端模式；
- 也可在维护界面点"重新打开 Termux"恢复终端模式（无需重启）；
- 维护模式在开机广播后被清除，属于"重启后回到终端"的产品约定。

---

## 5. Kiosk（锁任务）与降级策略

### 5.1 Device Owner 时的行为

`KioskController` 在 `isDeviceOwnerApp()` 为 true 时：

1. `setLockTaskPackages(admin, [TerminalHome, com.termux])` — 白名单；
2. `setLockTaskFeatures(admin, SYSTEM_INFO | NOTIFICATIONS | HOME)`（**API 28+ 才有该 API 与常量**，
   代码用 `Build.VERSION.SDK_INT >= P` 保护；概览/最近任务（OVERVIEW）**不**开启，
   避免从最近任务逃逸）；
3. 由前台 `MainActivity` 调用 `startLockTask()` 进入锁任务；
4. 调用 `addPersistentPreferredActivity(...)` 把本应用设为持久首选 HOME（后台线程调用，
   因为 AOSP 注释提示该 API 会做磁盘 I/O）；
5. 所有 DPM 调用均在 try/catch 中记录日志；失败只降级、不崩溃。

**未开启** `LOCK_TASK_FEATURE_OVERVIEW`；默认只有 `GLOBAL_ACTIONS` 开启，本应用显式设置特性集合。
本应用刻意**不**启用 OVERVIEW（避免最近任务逃逸）、GLOBAL_ACTIONS（电源菜单）与 KEYGUARD；
是否需要在实机上保留"长按电源关机"等能力由产品决定（未验证项）。

### 5.2 非 Device Owner：如实降级，不伪装

- 不做任何锁定相关调用（不申请权限，`setLockTaskPackages` 等不会被调用）；
- 主界面状态行明确显示"锁定：未启用（非 Device Owner，已降级为不锁定）"；
- 此时设备仍可通过用户手动选择"始终使用 TerminalHome"作为 HOME，但 Recent/状态栏未受限，
  用户可以离开终端——这是如实反映的能力边界，**不伪装为已锁定**；
- 维护界面同样如实显示 Device Owner = 否。

### 5.3 默认 HOME 接管

- 仅 Device Owner 生效：`IntentFilter(ACTION_MAIN + CATEGORY_HOME + CATEGORY_DEFAULT)` +
  `ComponentName(this, MainActivity)`，`addPersistentPreferredActivity`；
- 结果持久化（`home_takeover_state`），状态在界面展示：已接管 / 未接管（非 DO）/ 失败 / 未尝试；
- 不删除、不禁用 ROM 自带启动器；恢复出厂/移除 DO 后行为回到系统默认；
- **未实机验证**。

### 5.4 快捷设置下拉（Android 16）与 ROM 角色方案（项目决策 D-11）

源码核对结论（LineageOS `lineage-23.2` 分支）：

- Android 9–15 公开的锁任务特性只有：
  `SYSTEM_INFO`、`NOTIFICATIONS`、`HOME`、`OVERVIEW`、`GLOBAL_ACTIONS`、`KEYGUARD`、
  `BLOCK_ACTIVITY_START_IN_TASK`（无独立的"快捷设置"公开常量）；
- Android 16 新增 `LOCK_TASK_FEATURE_QUICK_SETTINGS = 1 << 7`，但源码标注
  **`@hide` + `@SystemApi` + `@FlaggedApi(enable_lock_task_feature_quick_settings)`**；
- 该 aconfig flag 在 LineageOS 23.2 的 release 配置中为 `ENABLED`（`READ_ONLY`）；
- **关键**：`DevicePolicyManagerService.setLockTaskFeatures()` 在 Android 16 要求调用方
  （DPC）持有 **`RoleManager.ROLE_SYSTEM_SUPERVISION`** 角色，否则抛 `SecurityException`
  （`isCallerSystemSupervisionRoleHolder(...)` 检查）；且 QUICK_SETTINGS 必须与
  NOTIFICATIONS 同时使用。

因此本应用的实现是：

- 只在 API 36+ 用**反射**读取该常量（不编译依赖隐藏 API）；探测不到则跳过；
- 先尝试带 QUICK_SETTINGS 位的配置；若被系统拒绝（典型原因：没有 SYSTEM_SUPERVISION 角色），
  记录日志并**回退**为 `SYSTEM_INFO | NOTIFICATIONS | HOME`，保证锁任务本身仍生效；
- 界面如实显示"不支持 / 被系统拒绝（需要 SYSTEM_SUPERVISION 角色）/ 已启用 / 等待确认"。

**ROM 层方案（项目决策 D-11，已确定路线）**：不改 `frameworks/base` 源码，而是通过
`vendor/terminalos` 的静态 overlay 设置
`config_allowedSupervisionRolePackages = org.terminalos.terminalhome`
（frameworks/base 的角色白名单配置），允许本应用持有 `ROLE_SYSTEM_SUPERVISION`。
应用侧需主动用 `RoleManager.createRequestRoleIntent(...)` 请求该角色：维护界面在
Android 16+ 且 Device Owner 时显示"申请监督角色"按钮，申请结果用
`RoleManager.isRoleHeld(...)` 核实后如实提示；授予角色后需重启应用/设备，让锁任务特性重新下发。
**ROM overlay 是否生效、角色申请 UI 与授予结果、以及该角色是否足以让快捷设置真正可下拉，
均属未实机验证项**（见 §9）。

参考（源码级核对，2026-10 时点）：

- Termux Manifest：<https://github.com/termux/termux-app/blob/master/app/src/main/AndroidManifest.xml>
- LineageOS 23.2 `DevicePolicyManager.java`（QUICK_SETTINGS 常量与标注）：
  <https://github.com/LineageOS/android_frameworks_base/blob/lineage-23.2/core/java/android/app/admin/DevicePolicyManager.java>
- LineageOS 23.2 `DevicePolicyManagerService.java`（SYSTEM_SUPERVISION 角色校验）：
  <https://github.com/LineageOS/android_frameworks_base/blob/lineage-23.2/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java>
- LineageOS 23.2 `LockTaskController.java`（特性位到状态栏开关的映射）：
  <https://github.com/LineageOS/android_frameworks_base/blob/lineage-23.2/services/core/java/com/android/server/wm/LockTaskController.java>
- aconfig flag 值（ENABLED）：
  <https://github.com/LineageOS/android_build_release/blob/lineage-23.2/aconfig/bp4a/android.app.supervision.flags/enable_lock_task_feature_quick_settings_flag_values.textproto>
- Soong `target_sdk_version` 属性：
  <https://android.googlesource.com/platform/build/soong/+/refs/heads/main/java/base.go>

---

## 6. Device Owner 部署步骤草案（全部未验证）

前提（未验证，按 Android 设备所有者通用约束）：

- 设备已完成初始设置、**没有添加任何账号**（Google/LineageOS/工作资料等）；
- 设备上没有已存在的 Device Owner / 其他管理应用；
- TerminalHome 已安装或已作为系统应用预置；
- 通常需要"恢复出厂后首次进入系统、跳过账号"的状态；部分 ROM 允许 adb 在已初始化设备上设置，
  但不可依赖。

```sh
# 0) 前置检查（输出以实机为准）
adb shell pm list packages | grep com.termux
adb shell dumpsys device_policy | grep -i "Device Owner"
adb shell dumpsys account | grep -i "Accounts"

# 1) 设置 Device Owner（草案；未验证）
adb shell dpm set-device-owner org.terminalos.terminalhome/.AdminReceiver

# 2) 验证（dpm 子命令在不同版本上可能不同）
adb shell dumpsys device_policy
# 部分版本可用：adb shell dpm list-owners

# 3) 撤销（DO 撤销通常受限；未验证，失败时以恢复出厂为兜底）
adb shell dpm remove-active-admin org.terminalos.terminalhome/.AdminReceiver
```

补充（未验证）：

- 若未设置 DO，也可在系统设置里手动把 TerminalHome 设为默认主屏
  （设置 → 应用 → 默认应用 → 主屏幕），或尝试
  `adb shell cmd package set-home-activity org.terminalos.terminalhome/.MainActivity`；
- 开发/调试时可直接拉起主界面（HOME Activity 之外的唯一界面 `MaintenanceActivity`
  是 `exported=false`，外部拉起会被系统拒绝）：
  `adb shell am start -n org.terminalos.terminalhome/.MainActivity`；
- QUICK_SETTINGS 角色（见 §5.4）：ROM 层已确定通过 `vendor/terminalos` 静态 overlay 的
  `config_allowedSupervisionRolePackages = org.terminalos.terminalhome` 允许本应用持有
  `ROLE_SYSTEM_SUPERVISION`（项目决策 D-11）；应用侧在维护界面提供"申请监督角色"按钮
  （Android 16+ 且 DO 时显示，`RoleManager.createRequestRoleIntent` + `startActivityForResult`，
  结果用 `isRoleHeld` 核实）。注意：overlay 配置项名称/位置、是否自动授予、角色申请 UI 行为
  均**未验证**，以实机为准；
- 设置 DO 后如果应用进程已在运行，建议重启设备或 `adb shell am force-stop
  org.terminalos.terminalhome` 后重新进入，让默认 HOME 接管与锁任务配置在干净状态下执行。

---

## 7. 权限与安全边界

### 7.1 权限清单

| 权限/声明 | 用途 | 说明 |
| --- | --- | --- |
| `RECEIVE_BOOT_COMPLETED` | 接收开机广播 | **normal** 权限，安装即授予，无需 privapp 白名单 |
| `<queries><package com.termux/></queries>` | Android 11+ 包可见性 | 用于检测/启动 Termux，不是权限 |
| `BIND_DEVICE_ADMIN`（receiver 的 permission 属性） | 系统绑定设备管理员组件 | 由系统持有，应用**不**在 uses-permission 中申请 |
| Device Owner 相关 DPM API | 锁任务白名单/特性、默认 HOME | Device Owner 身份本身即可调用；不需要额外 uses-permission（Android 16 源码中这些 API 标注为 `@RequiresPermission(MANAGE_DEVICE_POLICY_LOCK_TASK, conditional = true)`，DO/PO 调用方豁免） |

- **没有申请任何网络权限**（无 INTERNET / ACCESS_NETWORK_STATE 等）；
- **没有 analytics / 崩溃上报 / 第三方 SDK**；
- 没有读取存储、位置、通讯录、相机、麦克风等敏感权限；
- 没有 `MANAGE_DEVICE_POLICY_*`、`WRITE_SECURE_SETTINGS` 等私有/系统权限。

### 7.2 privapp 说明

本应用不申请 privileged/signature 权限，因此：

- `Android.bp` 中 `privileged: false`，安装到普通系统应用分区即可；
- **不需要** `privapp-permissions-org.terminalos.terminalhome.xml`；
- 若未来确实加入 privileged 权限，必须同时在产品中放置 privapp 白名单文件，
  否则系统在开机校验时会报错并可能无法启动。

### 7.3 PIN 安全

- PIN 只保存 **PBKDF2WithHmacSHA256(120000 迭代, 256-bit, 16 字节随机 salt)** 的结果；
- 不保存明文，不使用 MD5/SHA1 等弱哈希；
- 比较使用 `MessageDigest.isEqual`（定长、常量时间比较）；派生出的密钥与 PIN 字符数组用后清零；
- 存储位置：应用私有 SharedPreferences（凭据加密存储），随应用数据清除而删除；
- **限制**：无硬件安全模块/Keystore 绑定；拥有 root、可刷写系统或可清数据的人可以绕过；
- **未设置 PIN 的风险**：任何能操作设备的人都可以先设置 PIN（通过 7 连击入口）再退出锁定。
  因此首次部署后应立即设置 PIN；
- **PIN 遗忘**：无法从界面找回，只能清除应用数据/重新部署（`adb shell pm clear
  org.terminalos.terminalhome`，需要 adb 调试授权；若同时是 DO，清除数据通常也会影响 DO，
  以实机为准）。README 不做"后门 PIN"。

### 7.4 威胁模型与边界

- 不做物理锁机：不修改硬件、不恢复出厂、不锁定 bootloader；
- 只依赖 Android 官方 Device Owner / Lock Task；非 DO 时如实降级；
- 无法防御：持有 adb/root/恢复出厂能力的人、Termux 自身被替换成恶意应用、系统 ROM 被替换；
- 维护入口是"隐藏入口 + PIN"，不是密码学意义上的安全边界；
- 状态栏/快捷设置/通知的可用性由 ROM/SystemUI 决定（见 §5.4）。

---

## 8. 已知限制

1. **未编译、未实机验证**——所有行为结论均为设计与源码核对结论。
2. LineageOS 23.2（Android 16）为实验性，SystemUI / Lock Task / Device Policy 行为与官方支持的
   22.2（Android 15）可能不同。
3. 锁任务模式在"从 HOME 启动 Termux（另一个 package、可能在不同 task）"时是否始终保持锁定，
   未实机验证；**若实测发现 Termux 新 task 不在锁任务内，先用 logcat 记录
   `LockTaskController` / `ActivityTaskManager` 相关日志，再决定是否调整启动方式**（当前不预设改代码）。
4. 失败重启限流按 8 秒阈值判定"快速返回"：用户从 Termux 按 Home 时如果停留不足 8 秒，
   会被计为一次失败重启（10 分钟内 5 次后触发限流）；判定依赖 MainActivity 内存中的启动时间戳，
   进程被杀时本次不计数（保守）。阈值/窗口/冷却参数可能需要按实机体验调参。
5. Android 16 的锁任务快捷设置特性需要 DPC 持有 `SYSTEM_SUPERVISION` 角色（见 §5.4）：
   ROM 侧采用 `vendor/terminalos` 静态 overlay 的 `config_allowedSupervisionRolePackages`
   路线（项目决策 D-11），应用侧维护界面提供"申请监督角色"动作；角色申请流程与 QS
   实际可下拉性均未实机验证，无角色时自动回退为"通知栏可用、快捷设置未必可下拉"。
6. `BootReceiver` 依赖 BOOT_COMPLETED（用户解锁后送达）；设备首次开机到解锁前的行为由
   系统锁屏/默认 HOME 决定，本应用不做加密前(Direct Boot)接管。
7. Termux 需独立安装/预置；Termux 自身版本、targetSdk 兼容性与 Android 16 的适配不在本仓库范围。
8. 维护模式是"不清除 DO、不卸载应用"的软退出；若要彻底恢复普通手机形态需要移除 DO 并恢复出厂
   （未验证）。
9. 默认 HOME 接管依赖 `addPersistentPreferredActivity`（AOSP 注释提示磁盘 I/O，已在后台线程调用），
   若未来 Android 版本废弃该 API，需要迁移方案。
10. 应用界面只做中文，未做多语言；字体使用 monospace，可能因 ROM 字体配置而外观不同。
11. PIN 校验在 UI 线程同步执行 PBKDF2（120000 次迭代）；若低端机出现卡顿/ANR 需要改为后台线程
    （SDM845 预期可接受，但**未实测**）。
12. 没有单元测试/仪器测试（本环境无法编译 Android 工程）。

---

## 9. 待实机验证清单

按优先级排列，全部 **未验证**：

- [ ] Soong 构建：`m TerminalHome` 在 LineageOS 23.2 源码树中通过（`Android.bp` 属性、
      `target_sdk_version: "35"`、资源编译）；
- [ ] 安装/预置后 HOME 候选出现，能选择"始终使用 TerminalHome"；
- [ ] 设置 Device Owner 成功（`dpm set-device-owner`），`isDeviceOwnerApp` 返回 true；
- [ ] 开机后自动进入 Termux（Termux 已安装且未被系统休眠/限制）；
- [ ] Termux 缺失时停在主界面提示，不崩溃、不循环启动；
- [ ] Termux 启动失败/立即退出时，失败重启限流生效：10 分钟内 5 次失败后停止自动启动，
      界面显示原因；Termux 停留 ≥8 秒后按 Home 不消耗额度；
- [ ] 8 秒边界：启动后 <8 秒返回 HOME 记为失败、≥8 秒返回不计数（用 logcat 核对判定日志）；
- [ ] 从 Termux 按 Home 能回到 TerminalHome 并重新进入 Termux；连续 5 次失败触发限流后的恢复路径；
- [ ] Device Owner 下 `startLockTask()` 生效：Recent/返回/状态栏行为符合预期，
      Termux 前台时锁任务是否延续（关键风险项）；**若 Termux 新 task 不在锁任务内，
      先抓 `logcat -b system` 中 `LockTaskController` / `ActivityTaskManager` 相关日志再决策**；
- [ ] 顶部通知栏/快捷设置能否下拉；ROM overlay `config_allowedSupervisionRolePackages`
      生效后，维护界面"申请监督角色"（Android 16+ 且 DO）能成功授予
      `SYSTEM_SUPERVISION`，重启后 QS 特性从"被拒绝"变为"已启用"且实际可下拉；
- [ ] 默认 HOME 接管：`addPersistentPreferredActivity` 成功、重启后仍生效、启动器仍保留可恢复；
- [ ] 标题 7 连击 + PIN 门禁 + 维护界面各按钮；
- [ ] PIN 设置/修改/错误 PIN/未设置 PIN 的全部分支；PBKDF2 耗时是否可接受；
- [ ] 维护模式：退出锁定后不再自动进终端；重启后恢复终端模式；
- [ ] 打开系统设置（锁任务模式下是否被阻止、退出锁定后是否正常）；
- [ ] 返回键、任务键、Home 键在锁任务/非锁任务两种状态下的行为；
- [ ] 进程被杀/系统重启后的失败重启计数与维护模式持久性（进程被杀时快速返回不计数，属已知边界）；
- [ ] `adb logcat -s TerminalHome` 下的异常/降级日志是否符合预期。

---

## 10. 排错

日志 TAG 统一前缀 `TerminalHome`，各组件后缀：

```
TerminalHome.MainActivity
TerminalHome.MaintenanceActivity
TerminalHome.TermuxLauncher
TerminalHome.RestartLimiter
TerminalHome.Kiosk
TerminalHome.Pin
TerminalHome.AdminReceiver
TerminalHome.BootReceiver
```

```sh
adb logcat -s TerminalHome:V
adb shell dumpsys device_policy
adb shell dumpsys activity activities | grep -i locktask
adb shell dumpsys package org.terminalos.terminalhome
adb shell dumpsys package com.termux
```

常见排查点：

- 状态显示"Termux 未安装"但实际已装 → 检查 AndroidManifest 的 `<queries>` 是否被构建保留；
- 状态显示"无需锁定/未启用" → 检查 Device Owner 是否设置成功（`dpm` 输出）；
- 快捷设置不可下拉 → 见 §5.4（先确认 ROM overlay `config_allowedSupervisionRolePackages`
  是否生效、应用是否 DO、维护界面"申请监督角色"是否授予成功；可配合
  `adb shell dumpsys role | grep -i supervision` 与 `TerminalHome.Kiosk` 日志排查）；
- 监督角色申请按钮不显示 → 仅 Android 16+ 且 Device Owner 时显示（检查 `dpm` 输出与系统版本）；
- 一直停在主界面 → 看 `RestartLimiter` / `MainActivity` 日志与维护界面"最近失败原因"。

---

## 11. 文件清单

```
Android.bp
AndroidManifest.xml
README.md
.gitignore
res/drawable/ic_terminal.xml
res/layout/activity_main.xml
res/layout/activity_maintenance.xml
res/values/colors.xml
res/values/strings.xml
res/values/styles.xml
res/xml/device_admin.xml
src/org/terminalos/terminalhome/AdminPinManager.java
src/org/terminalos/terminalhome/AdminReceiver.java
src/org/terminalos/terminalhome/BootReceiver.java
src/org/terminalos/terminalhome/KioskController.java
src/org/terminalos/terminalhome/MainActivity.java
src/org/terminalos/terminalhome/MaintenanceActivity.java
src/org/terminalos/terminalhome/RestartLimiter.java
src/org/terminalos/terminalhome/TerminalLauncher.java
src/org/terminalos/terminalhome/TerminalOsConfig.java
```

---

## 12. 已决策项与剩余待验证项

1. **快捷设置可下拉（已决策：D-11，ROM 标准角色路线）**：不改框架源码；ROM 侧
   `vendor/terminalos` 静态 overlay 设置 `config_allowedSupervisionRolePackages` 允许本应用
   持有 `ROLE_SYSTEM_SUPERVISION`，应用侧在维护界面用 `RoleManager` 主动申请。
   剩余验证：overlay 是否生效、角色申请流程、QS 是否真正可下拉。
2. **锁任务跨任务持续性（决策：保持当前实现）**：保持显式组件 + `FLAG_ACTIVITY_NEW_TASK`
   启动 Termux；若实测发现 Termux 新 task 不在锁任务内，先用 logcat 记录
   `LockTaskController` / `ActivityTaskManager` 日志，再决定是否调整启动方式（不预设改代码）。
3. **限流语义（已决策）**：只统计失败重启；成功停留 ≥8 秒后按 Home 不消耗额度；
   启动后 <8 秒返回 HOME 或 `startActivity` 失败才计数；维护界面手动重试不消耗额度、
   仍保留冷静期。剩余验证：8 秒阈值/窗口参数在实机上的体验是否需要调参。

---

## 13. 参考来源（源码核对，2026-10）

- Termux AndroidManifest（`.app.TermuxActivity` 为 MAIN+LAUNCHER；`.HomeActivity` 是 alias）：
  <https://github.com/termux/termux-app/blob/master/app/src/main/AndroidManifest.xml>
- AOSP/LineageOS `DevicePolicyManager`（锁任务特性常量、`target_sdk_version` 构建属性等）：
  <https://android.googlesource.com/platform/build/soong/+/refs/heads/main/java/base.go>
- LineageOS 23.2 锁任务与 QUICK_SETTINGS 相关：
  见 §5.4 的链接列表。

再次强调：以上均为源码级核对；**本应用本身未编译、未实机验证**。
