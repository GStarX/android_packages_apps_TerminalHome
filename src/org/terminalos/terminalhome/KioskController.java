package org.terminalos.terminalhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 锁任务（Lock Task / kiosk）与默认 HOME 接管控制。
 *
 * <h3>能力与降级策略</h3>
 * <ul>
 *   <li><b>Device Owner</b>：通过 {@link DevicePolicyManager#setLockTaskPackages(ComponentName, String[])}
 *       把本应用与 {@code com.termux} 加入锁任务白名单，通过
 *       {@link DevicePolicyManager#setLockTaskFeatures(ComponentName, int)} 打开
 *       SYSTEM_INFO / NOTIFICATIONS / HOME 特性，然后由当前前台 Activity 调用
 *       {@link Activity#startLockTask()} 进入锁任务模式；同时用
 *       {@link DevicePolicyManager#addPersistentPreferredActivity(ComponentName, IntentFilter, ComponentName)}
 *       把本应用设为持久首选 HOME。</li>
 *   <li><b>非 Device Owner</b>：完全不做锁定相关调用，也不申请任何权限；
 *       {@link Status} 明确返回"未锁定/未接管 HOME"，界面如实展示，<b>绝不伪装为已锁定</b>。
 *       此时设备仍会以本应用为 HOME（用户选择"始终"后），但 Recent/状态栏未被限制，可被用户离开。</li>
 * </ul>
 *
 * <h3>快捷设置（下拉面板）</h3>
 * <p>Android 9-15（公开 API）没有独立的"锁任务快捷设置"常量；通知栏由
 * {@code LOCK_TASK_FEATURE_NOTIFICATIONS} 打开。Android 16（API 36）新增了
 * {@code LOCK_TASK_FEATURE_QUICK_SETTINGS}（源码标注 {@code @hide @SystemApi @FlaggedApi}，
 * AOSP/LineageOS 中由 aconfig flag {@code enable_lock_task_feature_quick_settings} 控制，
 * LineageOS 23.2 的 release 配置为 ENABLED）。
 *
 * <p><b>重要限制（源码核实）</b>：Android 16 的 DevicePolicyManagerService 在
 * setLockTaskFeatures() 中要求调用方（DPC）持有 {@code ROLE_SYSTEM_SUPERVISION}
 * 角色才允许设置 QUICK_SETTINGS 位，否则抛 SecurityException。因此本类：
 * <ol>
 *   <li>只在 API 36+ 通过反射探测该常量（不编译依赖隐藏 API，避免跨版本编译失败）；</li>
 *   <li>先尝试带 QUICK_SETTINGS 位的配置；若被系统拒绝，记录日志并回退为不含该位的配置，
 *       保证锁任务本身仍可用；拒绝状态通过 {@link Status} 如实暴露给界面；</li>
 *   <li>是否最终能下拉快捷设置，取决于 ROM 是否给本应用授予该角色（见 README 待决策项），
 *       属于"未实机验证"项。</li>
 * </ol>
 *
 * <p>所有 DPM 调用都包在 try/catch 中并写日志，避免异常导致主界面崩溃或死循环。
 */
public final class KioskController {

    private static final String TAG = TerminalOsConfig.TAG_PREFIX + ".Kiosk";

    private static final String KEY_HOME_TAKEOVER_STATE = "home_takeover_state";
    private static final int HOME_STATE_UNKNOWN = 0;
    private static final int HOME_STATE_APPLIED = 1;
    private static final int HOME_STATE_NOT_DEVICE_OWNER = 2;
    private static final int HOME_STATE_FAILED = 3;

    /** Android 16 = API 36；这里用字面量避免依赖过新的 Build.VERSION_CODES 常量。 */
    private static final int SDK_API_36 = 36;

    /** 默认 HOME 接管状态（持久化，供界面展示）。 */
    public enum HomeTakeoverState {
        /** 尚未尝试（或应用数据被清除后重新开始）。 */
        UNKNOWN,
        /** 已成功调用 addPersistentPreferredActivity。 */
        APPLIED,
        /** 不是 Device Owner，未尝试接管。 */
        NOT_DEVICE_OWNER,
        /** 尝试失败（SecurityException/参数错误等，见日志）。 */
        FAILED
    }

    /** 供界面展示的锁任务/接管状态快照。 */
    public static final class Status {
        public final boolean deviceOwner;
        public final boolean adminActive;
        public final boolean selfAllowlisted;
        public final boolean termuxAllowlisted;
        public final boolean lockTaskActive;
        public final HomeTakeoverState homeTakeoverState;
        /** 当前系统是否存在（可反射得到的）锁任务快捷设置特性位。 */
        public final boolean quickSettingsFeatureAvailable;
        /** 本次进程内是否已成功把快捷设置特性位下发给系统。 */
        public final boolean quickSettingsFeatureApplied;
        /** 系统拒绝快捷设置特性（通常是缺少 SYSTEM_SUPERVISION 角色）。 */
        public final boolean quickSettingsFeatureRejected;

        private Status(boolean deviceOwner, boolean adminActive, boolean selfAllowlisted,
                boolean termuxAllowlisted, boolean lockTaskActive,
                HomeTakeoverState homeTakeoverState, boolean quickSettingsFeatureAvailable,
                boolean quickSettingsFeatureApplied, boolean quickSettingsFeatureRejected) {
            this.deviceOwner = deviceOwner;
            this.adminActive = adminActive;
            this.selfAllowlisted = selfAllowlisted;
            this.termuxAllowlisted = termuxAllowlisted;
            this.lockTaskActive = lockTaskActive;
            this.homeTakeoverState = homeTakeoverState;
            this.quickSettingsFeatureAvailable = quickSettingsFeatureAvailable;
            this.quickSettingsFeatureApplied = quickSettingsFeatureApplied;
            this.quickSettingsFeatureRejected = quickSettingsFeatureRejected;
        }
    }

    /** 进入锁任务模式的结果。 */
    public static final class LockResult {
        public final boolean success;
        /** 是否真正尝试过 DPM 配置/startLockTask（非 Device Owner 时为 false）。 */
        public final boolean attempted;
        public final String message;

        private LockResult(boolean success, boolean attempted, String message) {
            this.success = success;
            this.attempted = attempted;
            this.message = message;
        }
    }

    private final Context appContext;
    private final DevicePolicyManager devicePolicyManager;
    private final ComponentName adminComponent;
    private final Handler mainHandler;
    private final SharedPreferences preferences;

    private boolean homeTakeoverTaskRunning;
    /** Integer.MIN_VALUE 表示尚未探测。 */
    private int quickSettingsFeatureCache = Integer.MIN_VALUE;
    private boolean quickSettingsFeatureApplied;
    private boolean quickSettingsFeatureRejected;

    public KioskController(Context context) {
        this.appContext = context.getApplicationContext();
        this.devicePolicyManager =
                (DevicePolicyManager) appContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        this.adminComponent = new ComponentName(appContext, AdminReceiver.class);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.preferences = TerminalOsConfig.getPreferences(appContext);
        if (devicePolicyManager == null) {
            Log.e(TAG, "DevicePolicyManager 不可用：所有锁定能力都会降级为不可用");
        }
    }

    // ---- 设备所有者 / 管理员 ----

    /** 本应用是否为设备所有者（Device Owner）。 */
    public boolean isDeviceOwner() {
        if (devicePolicyManager == null) {
            return false;
        }
        try {
            return devicePolicyManager.isDeviceOwnerApp(appContext.getPackageName());
        } catch (SecurityException e) {
            Log.w(TAG, "isDeviceOwnerApp 被系统拒绝", e);
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "isDeviceOwnerApp 出现未预期异常", e);
            return false;
        }
    }

    /** 本应用的设备管理员组件是否已激活。 */
    public boolean isAdminActive() {
        if (devicePolicyManager == null) {
            return false;
        }
        try {
            return devicePolicyManager.isAdminActive(adminComponent);
        } catch (SecurityException e) {
            Log.w(TAG, "isAdminActive 被系统拒绝", e);
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "isAdminActive 出现未预期异常", e);
            return false;
        }
    }

    // ---- 锁任务配置 ----

    /**
     * 配置锁任务白名单与特性（仅 Device Owner 有效）。
     *
     * @return true 表示 setLockTaskPackages 成功（特性配置失败只记日志，不影响返回值）。
     */
    public synchronized boolean configureLockTask() {
        if (devicePolicyManager == null || adminComponent == null || !isDeviceOwner()) {
            Log.i(TAG, "非 Device Owner 或 DPM 不可用：跳过锁任务配置（降级为不锁定）");
            return false;
        }
        List<String> packages = new ArrayList<>();
        packages.add(appContext.getPackageName());
        packages.add(TerminalOsConfig.TERMUX_PACKAGE);
        try {
            devicePolicyManager.setLockTaskPackages(adminComponent,
                    packages.toArray(new String[0]));
            Log.i(TAG, "锁任务白名单已设置：" + packages);
        } catch (SecurityException e) {
            Log.e(TAG, "setLockTaskPackages 被拒绝（Device Owner 状态可能未生效）", e);
            return false;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "setLockTaskPackages 参数非法", e);
            return false;
        } catch (RuntimeException e) {
            Log.e(TAG, "setLockTaskPackages 出现未预期异常", e);
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            applyLockTaskFeatures();
        } else {
            // 本应用 minSdk 为 28，这里理论不可达；保留防御性分支并记录日志。
            Log.i(TAG, "API " + Build.VERSION.SDK_INT
                    + " < 28：不支持 setLockTaskFeatures，使用系统默认特性");
        }
        return true;
    }

    /**
     * 设置锁任务特性。先尝试包含快捷设置位；被拒绝则回退。
     *
     * <p>调用方必须保证 API >= 28（本方法只在该条件下被调用）。
     */
    private void applyLockTaskFeatures() {
        int baseFeatures = DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                | DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                | DevicePolicyManager.LOCK_TASK_FEATURE_HOME;
        int quickSettingsFeature = resolveQuickSettingsFeature();
        if (quickSettingsFeature != 0 && !quickSettingsFeatureRejected) {
            try {
                devicePolicyManager.setLockTaskFeatures(adminComponent,
                        baseFeatures | quickSettingsFeature);
                quickSettingsFeatureApplied = true;
                Log.i(TAG, "锁任务特性已启用（含快捷设置位 " + quickSettingsFeature
                        + "）：SYSTEM_INFO | NOTIFICATIONS | HOME | QUICK_SETTINGS");
                return;
            } catch (SecurityException e) {
                quickSettingsFeatureRejected = true;
                Log.w(TAG, "系统拒绝启用锁任务快捷设置特性（Android 16 需要 DPC 持有"
                        + " SYSTEM_SUPERVISION 角色），回退为不含该位的配置", e);
            } catch (IllegalArgumentException e) {
                quickSettingsFeatureRejected = true;
                Log.w(TAG, "锁任务快捷设置特性位不被系统接受，回退", e);
            } catch (RuntimeException e) {
                quickSettingsFeatureRejected = true;
                Log.w(TAG, "启用锁任务快捷设置特性出现未预期异常，回退", e);
            }
        }
        try {
            devicePolicyManager.setLockTaskFeatures(adminComponent, baseFeatures);
            Log.i(TAG, "锁任务特性已启用：SYSTEM_INFO | NOTIFICATIONS | HOME（API "
                    + Build.VERSION.SDK_INT + "）");
        } catch (SecurityException e) {
            Log.e(TAG, "setLockTaskFeatures 被拒绝", e);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "setLockTaskFeatures 参数非法（NOTIFICATIONS 需要同时开启 HOME）", e);
        } catch (RuntimeException e) {
            Log.e(TAG, "setLockTaskFeatures 出现未预期异常", e);
        }
    }

    /**
     * 反射探测 Android 16 的 {@code LOCK_TASK_FEATURE_QUICK_SETTINGS}。
     *
     * <p>该常量在 AOSP 中标注 {@code @hide @SystemApi @FlaggedApi}，直接编译引用会绑定
     * 特定平台 SDK；这里以反射探测，字段不存在（Android 15 及以下）时返回 0。
     *
     * @return 特性位；不可用时返回 0。
     */
    private int resolveQuickSettingsFeature() {
        if (quickSettingsFeatureCache != Integer.MIN_VALUE) {
            return quickSettingsFeatureCache;
        }
        int feature = 0;
        if (Build.VERSION.SDK_INT >= SDK_API_36) {
            try {
                Field field = DevicePolicyManager.class
                        .getField("LOCK_TASK_FEATURE_QUICK_SETTINGS");
                feature = field.getInt(null);
                Log.i(TAG, "探测到 Android 16 锁任务快捷设置特性位：" + feature);
            } catch (NoSuchFieldException e) {
                Log.i(TAG, "当前系统没有 LOCK_TASK_FEATURE_QUICK_SETTINGS 字段，"
                        + "快捷设置特性不可用");
            } catch (IllegalAccessException e) {
                Log.w(TAG, "反射读取 LOCK_TASK_FEATURE_QUICK_SETTINGS 被拒绝", e);
            } catch (SecurityException e) {
                Log.w(TAG, "反射读取 LOCK_TASK_FEATURE_QUICK_SETTINGS 触发安全异常", e);
            } catch (RuntimeException e) {
                Log.w(TAG, "反射读取 LOCK_TASK_FEATURE_QUICK_SETTINGS 出现未预期异常", e);
            }
        }
        quickSettingsFeatureCache = feature;
        return feature;
    }

    // ---- 进入 / 退出锁任务 ----

    /**
     * 尝试进入锁任务模式（由前台 Activity 调用）。
     *
     * <p>非 Device Owner 时直接返回 {@code attempted=false}，界面据此显示"未锁定"。
     */
    public LockResult enterLockTask(Activity activity) {
        if (activity == null) {
            Log.e(TAG, "enterLockTask：activity 为 null");
            return new LockResult(false, false, "内部错误：activity 为空");
        }
        if (!isDeviceOwner()) {
            Log.i(TAG, "非 Device Owner：不进入锁任务模式（按降级策略不锁定）");
            return new LockResult(false, false, "非 Device Owner，锁任务不可用");
        }
        if (isLockTaskActive()) {
            Log.i(TAG, "已处于锁任务模式，跳过重复进入");
            return new LockResult(true, false, "已处于锁任务模式");
        }
        if (!configureLockTask()) {
            return new LockResult(false, true, "锁任务配置失败（详见 logcat）");
        }
        try {
            activity.startLockTask();
            Log.i(TAG, "已调用 startLockTask()");
            return new LockResult(true, true, "已进入锁任务模式");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "startLockTask 被拒绝（参数或状态非法）", e);
            return new LockResult(false, true, "系统拒绝 startLockTask（状态非法）");
        } catch (IllegalStateException e) {
            Log.e(TAG, "startLockTask 状态非法", e);
            return new LockResult(false, true, "系统拒绝 startLockTask（状态非法）");
        } catch (SecurityException e) {
            Log.e(TAG, "startLockTask 被安全策略拒绝", e);
            return new LockResult(false, true, "系统拒绝 startLockTask（安全限制）");
        } catch (RuntimeException e) {
            Log.e(TAG, "startLockTask 出现未预期异常", e);
            return new LockResult(false, true, "startLockTask 出现异常："
                    + e.getClass().getSimpleName());
        }
    }

    /**
     * 退出锁任务模式。调用方必须位于被锁定的 task 内
     * （维护界面是由主界面启动的，正常情况下与其同 task）。
     *
     * @return true 表示当前已不在锁任务模式（或成功退出）；false 表示退出失败（见日志）。
     */
    public boolean exitLockTask(Activity activity) {
        if (activity == null) {
            Log.e(TAG, "exitLockTask：activity 为 null");
            return false;
        }
        if (!isLockTaskActive()) {
            Log.i(TAG, "当前不在锁任务模式，无需退出");
            return true;
        }
        try {
            activity.stopLockTask();
            Log.i(TAG, "已调用 stopLockTask()");
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "stopLockTask 被系统拒绝", e);
            return false;
        } catch (RuntimeException e) {
            Log.e(TAG, "stopLockTask 出现未预期异常", e);
            return false;
        }
    }

    /** 当前是否处于锁任务模式（含用户主动的屏幕固定 PINNED 状态）。 */
    public boolean isLockTaskActive() {
        return getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    private int getLockTaskModeState() {
        ActivityManager activityManager =
                (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            Log.w(TAG, "ActivityManager 不可用，无法读取锁任务状态");
            return ActivityManager.LOCK_TASK_MODE_NONE;
        }
        try {
            return activityManager.getLockTaskModeState();
        } catch (RuntimeException e) {
            Log.w(TAG, "getLockTaskModeState 出现异常", e);
            return ActivityManager.LOCK_TASK_MODE_NONE;
        }
    }

    // ---- 默认 HOME 接管 ----

    /**
     * 若本应用是 Device Owner，则把本应用设为持久首选 HOME。
     *
     * <p>使用 {@link DevicePolicyManager#addPersistentPreferredActivity(ComponentName, IntentFilter, ComponentName)}：
     * <ul>
     *   <li>不删除/禁用 ROM 里原有的 Launcher3QuickStep，只覆盖 HOME 解析结果；</li>
     *   <li>仅 Device Owner 有效；非 DO 时不做任何事并持久化 NOT_DEVICE_OWNER 状态；</li>
     *   <li>该 API 的 AOSP 注释提示会执行磁盘 I/O，因此放在后台线程调用，
     *       完成后切回主线程回调（{@code onFinished} 可为 null）。</li>
     * </ul>
     */
    public void ensureDefaultHome(final Runnable onFinished) {
        if (!isDeviceOwner()) {
            Log.i(TAG, "非 Device Owner：不尝试接管默认 HOME");
            persistHomeTakeoverState(HOME_STATE_NOT_DEVICE_OWNER);
            if (onFinished != null) {
                onFinished.run();
            }
            return;
        }
        if (getHomeTakeoverState() == HomeTakeoverState.APPLIED) {
            Log.i(TAG, "默认 HOME 已接管过，跳过重复调用");
            if (onFinished != null) {
                onFinished.run();
            }
            return;
        }
        if (homeTakeoverTaskRunning) {
            Log.i(TAG, "默认 HOME 接管任务正在进行，忽略重复请求");
            return;
        }
        homeTakeoverTaskRunning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean applied = applyPersistentPreferredHome();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        homeTakeoverTaskRunning = false;
                        persistHomeTakeoverState(
                                applied ? HOME_STATE_APPLIED : HOME_STATE_FAILED);
                        if (onFinished != null) {
                            onFinished.run();
                        }
                    }
                });
            }
        }, "terminalhome-home-takeover").start();
    }

    private boolean applyPersistentPreferredHome() {
        if (devicePolicyManager == null) {
            Log.e(TAG, "接管默认 HOME 失败：DevicePolicyManager 不可用");
            return false;
        }
        try {
            IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
            homeFilter.addCategory(Intent.CATEGORY_HOME);
            homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
            ComponentName homeActivity = new ComponentName(appContext, MainActivity.class);
            devicePolicyManager.addPersistentPreferredActivity(
                    adminComponent, homeFilter, homeActivity);
            Log.i(TAG, "已调用 addPersistentPreferredActivity，将 " + homeActivity
                    + " 设为默认 HOME");
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "接管默认 HOME 被拒绝（检查 Device Owner 状态）", e);
            return false;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "接管默认 HOME 参数非法（IntentFilter/组件不匹配）", e);
            return false;
        } catch (RuntimeException e) {
            Log.e(TAG, "接管默认 HOME 出现未预期异常", e);
            return false;
        }
    }

    /** 读取持久化的默认 HOME 接管状态。 */
    public HomeTakeoverState getHomeTakeoverState() {
        int state = preferences.getInt(KEY_HOME_TAKEOVER_STATE, HOME_STATE_UNKNOWN);
        switch (state) {
            case HOME_STATE_APPLIED:
                return HomeTakeoverState.APPLIED;
            case HOME_STATE_NOT_DEVICE_OWNER:
                return HomeTakeoverState.NOT_DEVICE_OWNER;
            case HOME_STATE_FAILED:
                return HomeTakeoverState.FAILED;
            default:
                return HomeTakeoverState.UNKNOWN;
        }
    }

    private void persistHomeTakeoverState(int state) {
        preferences.edit().putInt(KEY_HOME_TAKEOVER_STATE, state).apply();
    }

    // ---- 状态快照 ----

    /** 生成供界面展示的状态快照（不修改任何策略）。 */
    public Status describeStatus() {
        boolean deviceOwner = isDeviceOwner();
        boolean adminActive = isAdminActive();
        boolean selfAllowlisted = false;
        boolean termuxAllowlisted = false;
        if (deviceOwner && devicePolicyManager != null) {
            try {
                selfAllowlisted =
                        devicePolicyManager.isLockTaskPermitted(appContext.getPackageName());
                termuxAllowlisted = devicePolicyManager
                        .isLockTaskPermitted(TerminalOsConfig.TERMUX_PACKAGE);
            } catch (SecurityException e) {
                Log.w(TAG, "查询锁任务白名单被拒绝", e);
            } catch (RuntimeException e) {
                Log.w(TAG, "查询锁任务白名单出现异常", e);
            }
        }
        boolean quickAvailable = resolveQuickSettingsFeature() != 0;
        return new Status(
                deviceOwner,
                adminActive,
                selfAllowlisted,
                termuxAllowlisted,
                isLockTaskActive(),
                getHomeTakeoverState(),
                quickAvailable,
                quickSettingsFeatureApplied,
                quickSettingsFeatureRejected);
    }
}
