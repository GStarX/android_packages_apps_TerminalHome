package org.terminalos.terminalhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Termux 启动/探测封装。
 *
 * <p>启动策略：
 * <ol>
 *   <li>优先使用显式组件 {@code com.termux/com.termux.app.TermuxActivity}
 *       （已按 termux-app v0.118.3 的 AndroidManifest 核实）；</li>
 *   <li>用 {@link PackageManager#resolveActivity(Intent, int)} 校验显式 Intent 可解析；</li>
 *   <li>若显式组件不可解析（例如被用户禁用的 activity-alias/组件被改动），回退到
 *       {@link PackageManager#getLaunchIntentForPackage(String)} 的 MAIN/LAUNCHER 入口；</li>
 *   <li>两者都不可用时返回失败原因，由调用方写入限流器并展示。</li>
 * </ol>
 *
 * <p>包可见性：Android 11+ 需要 AndroidManifest 中的
 * {@code <queries><package android:name="com.termux"/></queries>}，否则查询会得到"未安装"。
 *
 * <p>本类不申请任何权限，也不使用网络。
 */
public final class TerminalLauncher {

    private static final String TAG = TerminalOsConfig.TAG_PREFIX + ".TermuxLauncher";

    /** 一次启动请求的结果。 */
    public static final class LaunchResult {
        public final boolean success;
        /** 失败时的人类可读原因（成功时为 null）。 */
        public final String message;

        private LaunchResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        static LaunchResult success() {
            return new LaunchResult(true, null);
        }

        static LaunchResult failure(String message) {
            return new LaunchResult(false, message);
        }
    }

    private final Context appContext;
    private final PackageManager packageManager;
    private final ComponentName termuxActivityComponent;

    public TerminalLauncher(Context context) {
        this.appContext = context.getApplicationContext();
        this.packageManager = appContext.getPackageManager();
        this.termuxActivityComponent = new ComponentName(
                TerminalOsConfig.TERMUX_PACKAGE, TerminalOsConfig.TERMUX_ACTIVITY_CLASS);
    }

    /** Termux 包是否已安装（依赖 manifest 中的 package 可见性声明）。 */
    @SuppressWarnings("deprecation")
    public boolean isTermuxInstalled() {
        try {
            packageManager.getPackageInfo(TerminalOsConfig.TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            Log.i(TAG, "未检测到 Termux 包 " + TerminalOsConfig.TERMUX_PACKAGE
                    + "（可能未安装，或包可见性未生效）");
            return false;
        }
    }

    /**
     * 已安装的 Termux 主 Activity 是否可解析。
     *
     * <p>使用 {@link PackageManager#getActivityInfo(ComponentName, int)} 而不是直接启动，
     * 便于在自动启动前给出明确状态。
     */
    public boolean isTermuxActivityAvailable() {
        if (!isTermuxInstalled()) {
            return false;
        }
        try {
            packageManager.getActivityInfo(termuxActivityComponent, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Termux 已安装，但组件 " + termuxActivityComponent
                    + " 不可解析（可能被禁用或版本结构变化）");
            return false;
        }
    }

    /** Termux 探测结果（供界面映射为本地化文案，避免在非 UI 类里硬编码界面文字）。 */
    public enum TermuxState {
        /** 未安装（或包可见性未生效）。 */
        NOT_INSTALLED,
        /** 已安装，但主 Activity 不可解析。 */
        INSTALLED_ACTIVITY_UNAVAILABLE,
        /** 已安装且启动入口可用。 */
        READY
    }

    /** 当前 Termux 状态。 */
    public TermuxState getTermuxState() {
        if (!isTermuxInstalled()) {
            return TermuxState.NOT_INSTALLED;
        }
        if (!isTermuxActivityAvailable()) {
            return TermuxState.INSTALLED_ACTIVITY_UNAVAILABLE;
        }
        return TermuxState.READY;
    }

    /**
     * 请求启动 Termux。
     *
     * @param activity 调用方 Activity（用于 startActivity；Home 场景下当前界面一定在前台）。
     * @return 是否成功发出启动请求，以及失败原因。
     */
    public LaunchResult launchTermux(Activity activity) {
        if (activity == null) {
            Log.e(TAG, "启动 Termux 失败：activity 为 null");
            return LaunchResult.failure("内部错误：activity 为空");
        }
        Intent launchIntent = buildLaunchIntent();
        if (launchIntent == null) {
            Log.e(TAG, "启动 Termux 失败：没有可解析的启动入口");
            return LaunchResult.failure("未找到可解析的 Termux 启动入口");
        }
        try {
            activity.startActivity(launchIntent);
            Log.i(TAG, "已发出 Termux 启动请求：" + launchIntent);
            return LaunchResult.success();
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "启动 Termux 失败：ActivityNotFoundException", e);
            return LaunchResult.failure("系统未找到 Termux 启动入口");
        } catch (SecurityException e) {
            Log.e(TAG, "启动 Termux 被拒绝：SecurityException（组件未导出或权限限制）", e);
            return LaunchResult.failure("系统拒绝启动 Termux（组件导出或权限限制）");
        } catch (RuntimeException e) {
            Log.e(TAG, "启动 Termux 出现未预期异常", e);
            return LaunchResult.failure("启动 Termux 出现异常："
                    + e.getClass().getSimpleName());
        }
    }

    /**
     * 构造启动 Intent：显式组件优先，MAIN/LAUNCHER 回退。
     *
     * @return 可用的 Intent；无可用入口时返回 null。
     */
    private Intent buildLaunchIntent() {
        Intent explicitIntent = new Intent(Intent.ACTION_MAIN);
        explicitIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        explicitIntent.setComponent(termuxActivityComponent);
        explicitIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            if (packageManager.resolveActivity(explicitIntent, 0) != null) {
                return explicitIntent;
            }
            Log.w(TAG, "显式组件不可解析，回退到 getLaunchIntentForPackage");
            Intent fallbackIntent =
                    packageManager.getLaunchIntentForPackage(TerminalOsConfig.TERMUX_PACKAGE);
            if (fallbackIntent != null) {
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                return fallbackIntent;
            }
            Log.w(TAG, "getLaunchIntentForPackage 也没有返回可用 Intent");
        } catch (RuntimeException e) {
            // 包管理查询在异常厂商 ROM 上可能抛 RuntimeException：记录并降级为失败。
            Log.e(TAG, "解析 Termux 启动入口时出现异常", e);
        }
        return null;
    }
}
