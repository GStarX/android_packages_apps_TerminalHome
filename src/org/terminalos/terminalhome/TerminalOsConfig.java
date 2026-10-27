package org.terminalos.terminalhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * TerminalHome 的集中配置：包名、组件名、SharedPreferences 名、限流参数与日志 TAG。
 *
 * <p>本类除常量外只提供"维护模式"（maintenance mode）相关的极少量偏好读写辅助，
 * 用于避免多个 Activity 各自复制 key 字符串。其余状态键由各组件自己私有持有。
 *
 * <p>注意：本文件属于"未编译验证"的 ROM 内应用代码，见 README。
 */
public final class TerminalOsConfig {

    /** 全局日志 TAG 前缀；各组件使用 TAG_PREFIX + ".组件名"，便于 logcat 过滤。 */
    public static final String TAG_PREFIX = "TerminalHome";

    public static final String PACKAGE_NAME = "org.terminalos.terminalhome";
    public static final String MAIN_ACTIVITY_CLASS = "org.terminalos.terminalhome.MainActivity";
    public static final String MAINTENANCE_ACTIVITY_CLASS =
            "org.terminalos.terminalhome.MaintenanceActivity";
    public static final String ADMIN_RECEIVER_CLASS = "org.terminalos.terminalhome.AdminReceiver";

    /** Termux 包名；AndroidManifest 中已用 {@code <queries>} 声明包可见性。 */
    public static final String TERMUX_PACKAGE = "com.termux";

    /**
     * Termux 主 Activity。
     *
     * <p>已按 termux-app v0.118.3 的 AndroidManifest 核实：MAIN + LAUNCHER 声明在
     * {@code com.termux.app.TermuxActivity} 上；{@code com.termux.HomeActivity} 只是指向它的
     * activity-alias（携带 IOT_LAUNCHER，供 Android Things 开机启动），不作为启动入口依赖。
     */
    public static final String TERMUX_ACTIVITY_CLASS = "com.termux.app.TermuxActivity";

    /** 应用内 SharedPreferences 文件名。 */
    public static final String PREFS_NAME = "terminalhome_prefs";

    // ---- 自动重启限流参数（语义见 RestartLimiter）----

    /** 限流滑动窗口：10 分钟。 */
    public static final long RESTART_WINDOW_MS = 10L * 60L * 1000L;

    /** 窗口内允许的失败重启次数上限（只统计失败重启，成功进入终端不消耗额度）。 */
    public static final int RESTART_MAX_FAILURES_IN_WINDOW = 5;

    /** 任意两次自动启动之间的最小间隔（冷静期），防止紧密循环。 */
    public static final long RESTART_MIN_INTERVAL_MS = 4_000L;

    /**
     * "快速返回"阈值：Termux 启动后不足该时长又回到 HOME，视为疑似崩溃循环，
     * 计入失败重启额度；停留超过该时长后按 Home 返回不消耗额度。
     */
    public static final long RESTART_QUICK_RETURN_THRESHOLD_MS = 8_000L;

    // ---- 界面与流程参数 ----

    /** 连续点击标题多少次打开维护入口。 */
    public static final int TITLE_TAPS_TO_MAINTENANCE = 7;

    /** 两次点击之间超过该间隔则重新计数。 */
    public static final long TITLE_TAP_TIMEOUT_MS = 1_500L;

    /** 主界面恢复后延迟多久自动尝试启动 Termux（先让界面完成首帧绘制）。 */
    public static final long AUTO_LAUNCH_DELAY_MS = 500L;

    // ---- SharedPreferences keys（跨组件共享的部分）----

    /** 维护模式：开启后主界面不再自动启动 Termux，直到手动恢复或设备重启。 */
    public static final String KEY_MAINTENANCE_MODE = "maintenance_mode";

    /** 最近一次 BOOT_COMPLETED 的时间戳，仅用于诊断。 */
    public static final String KEY_LAST_BOOT_AT = "last_boot_at";

    private TerminalOsConfig() {
        // 纯常量类，禁止实例化。
    }

    /** 返回本应用私有的 SharedPreferences。 */
    public static SharedPreferences getPreferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 是否处于维护模式（不自动打开终端，等待管理员恢复）。 */
    public static boolean isMaintenanceMode(Context context) {
        return getPreferences(context).getBoolean(KEY_MAINTENANCE_MODE, false);
    }

    /** 写入维护模式开关。 */
    public static void setMaintenanceMode(Context context, boolean enabled) {
        getPreferences(context).edit().putBoolean(KEY_MAINTENANCE_MODE, enabled).apply();
    }
}
