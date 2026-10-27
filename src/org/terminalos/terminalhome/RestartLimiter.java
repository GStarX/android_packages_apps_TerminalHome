package org.terminalos.terminalhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动重启限流器（滑动窗口 + 冷静期，只统计"失败重启"）。
 *
 * <p>语义（按 Lead 决策调整，保证任何崩溃/启动循环都有上界）：
 * <ul>
 *   <li>只有<b>失败重启</b>计入窗口：{@code startActivity} 抛出异常/返回失败，或
 *       Termux 启动后不足 {@link TerminalOsConfig#RESTART_QUICK_RETURN_THRESHOLD_MS}（8 秒）
 *       又回到 HOME（疑似 Termux 退出/崩溃，由 MainActivity 判定后上报）；</li>
 *   <li>Termux 成功进入前台并保持 ≥8 秒后用户按 Home 返回，<b>不消耗</b>重启额度；</li>
 *   <li>滑动窗口 {@link TerminalOsConfig#RESTART_WINDOW_MS}（10 分钟）内失败重启最多
 *       {@link TerminalOsConfig#RESTART_MAX_FAILURES_IN_WINDOW}（5）次；</li>
 *   <li>任意两次自动启动之间仍强制间隔 {@link TerminalOsConfig#RESTART_MIN_INTERVAL_MS}（4 秒），
 *       期间拒绝启动并返回剩余等待毫秒数，由调用方安排一次受控延迟重试；</li>
 *   <li>窗口内失败次数用尽后进入"限流锁定"状态：不再自动启动，必须等待窗口自然过期、
 *       管理员在维护界面手动重试，或设备重启（{@link BootReceiver} 会重置）；</li>
 *   <li>成功启动不立即清零失败计数（失败记录按窗口自然过期）；维护界面手动重试会清空失败窗口、
 *       但保留冷静期，且手动重试本身不消耗额度。</li>
 * </ul>
 *
 * <p>状态全部保存在 SharedPreferences 中，进程被杀/主界面重建后仍然生效，
 * 避免通过反复重建界面绕过限流。
 *
 * <p>时间源使用 {@link System#currentTimeMillis()}（墙钟）。检测到时钟回拨时会重置
 * "上次启动时间"并记录日志，避免出现不可退出的超长冷静期。BOOT_COMPLETED 会重置全部状态。
 */
public final class RestartLimiter {

    private static final String TAG = TerminalOsConfig.TAG_PREFIX + ".RestartLimiter";

    private static final String KEY_FAILURE_TIMES = "restart_failure_times";
    private static final String KEY_LAST_ATTEMPT_AT = "restart_last_attempt_at";
    private static final String KEY_LAST_SUCCESS_AT = "restart_last_success_at";
    private static final String KEY_LAST_FAILURE_AT = "restart_last_failure_at";
    private static final String KEY_LAST_FAILURE_REASON = "restart_last_failure_reason";

    private static final String FAILURE_SEPARATOR = ",";

    /** 一次"是否允许自动启动"的判定结果。 */
    public static final class Decision {
        /** 是否允许本次自动启动。 */
        public final boolean allowed;
        /** 是否因为窗口内失败重启次数用尽而拒绝（true 时不应再做自动重试）。 */
        public final boolean windowExhausted;
        /** 被拒绝时建议的等待时间（毫秒）；allowed 时为 0。 */
        public final long retryAfterMs;
        /** 允许时为 null；拒绝时为人类可读原因。 */
        public final String reason;

        private Decision(boolean allowed, boolean windowExhausted, long retryAfterMs,
                String reason) {
            this.allowed = allowed;
            this.windowExhausted = windowExhausted;
            this.retryAfterMs = retryAfterMs;
            this.reason = reason;
        }
    }

    /** 供诊断界面展示的只读状态快照。 */
    public static final class Status {
        /** 最近 10 分钟内计入限流的失败重启次数。 */
        public final int failuresInWindow;
        public final int maxFailuresInWindow;
        public final long windowRemainingMs;
        public final boolean lockedOut;
        /** 最近一次失败原因；没有失败记录时为 null。 */
        public final String lastFailureReason;
        public final long lastFailureAt;
        public final long lastSuccessAt;
        public final long lastAttemptAt;

        private Status(int failuresInWindow, int maxFailuresInWindow, long windowRemainingMs,
                boolean lockedOut, String lastFailureReason, long lastFailureAt,
                long lastSuccessAt, long lastAttemptAt) {
            this.failuresInWindow = failuresInWindow;
            this.maxFailuresInWindow = maxFailuresInWindow;
            this.windowRemainingMs = windowRemainingMs;
            this.lockedOut = lockedOut;
            this.lastFailureReason = lastFailureReason;
            this.lastFailureAt = lastFailureAt;
            this.lastSuccessAt = lastSuccessAt;
            this.lastAttemptAt = lastAttemptAt;
        }
    }

    private final SharedPreferences preferences;

    public RestartLimiter(Context context) {
        this.preferences = TerminalOsConfig.getPreferences(context);
    }

    /**
     * 请求一次自动启动许可。允许时会记录"上次启动时间"（用于冷静期），
     * 但<b>不</b>计入失败窗口——失败由后续 {@link #recordLaunchFailed(String)} 记录。
     *
     * <p>返回 {@link Decision#allowed} 为 false 时：
     * <ul>
     *   <li>{@link Decision#windowExhausted} 为 true：失败预算用尽，界面应停在维护状态，不要再自动重试；</li>
     *   <li>否则处于冷静期，调用方可按 {@link Decision#retryAfterMs} 安排一次延迟重试。</li>
     * </ul>
     */
    public synchronized Decision beginAutomaticAttempt() {
        final long now = System.currentTimeMillis();
        List<Long> failures = loadFailureTimes();
        trimFailureTimes(failures, now);

        long lastAttemptAt = preferences.getLong(KEY_LAST_ATTEMPT_AT, 0L);
        if (lastAttemptAt > 0L) {
            long elapsed = now - lastAttemptAt;
            if (elapsed < 0L) {
                Log.w(TAG, "检测到系统时钟回拨（elapsed=" + elapsed + "ms），重置上次启动时间");
                preferences.edit().putLong(KEY_LAST_ATTEMPT_AT, now).apply();
            } else {
                long cooldownRemaining = TerminalOsConfig.RESTART_MIN_INTERVAL_MS - elapsed;
                if (cooldownRemaining > 0L) {
                    Log.i(TAG, "拒绝自动启动：冷静期剩余 " + cooldownRemaining + "ms");
                    return new Decision(false, false, cooldownRemaining,
                            "冷静期未结束，剩余 " + cooldownRemaining + "ms");
                }
            }
        }

        if (failures.size() >= TerminalOsConfig.RESTART_MAX_FAILURES_IN_WINDOW) {
            long oldestFailureAt = failures.get(0);
            long windowRemainingMs =
                    oldestFailureAt + TerminalOsConfig.RESTART_WINDOW_MS - now;
            if (windowRemainingMs < 0L) {
                windowRemainingMs = 0L;
            }
            Log.w(TAG, "拒绝自动启动：10 分钟窗口内失败重启 " + failures.size()
                    + " 次，达到上限，窗口预计 " + windowRemainingMs + "ms 后释放");
            return new Decision(false, true, windowRemainingMs,
                    "10 分钟窗口内失败重启次数已达上限（"
                            + TerminalOsConfig.RESTART_MAX_FAILURES_IN_WINDOW + " 次）");
        }

        preferences.edit().putLong(KEY_LAST_ATTEMPT_AT, now).apply();
        Log.i(TAG, "允许自动启动：窗口内已记录失败重启 " + failures.size() + " / "
                + TerminalOsConfig.RESTART_MAX_FAILURES_IN_WINDOW + " 次（本次不计入）");
        return new Decision(true, false, 0L, null);
    }

    /** 记录一次成功启动（仅用于诊断；不重置失败计数，原因见类注释）。 */
    public synchronized void recordLaunchSucceeded() {
        long now = System.currentTimeMillis();
        preferences.edit().putLong(KEY_LAST_SUCCESS_AT, now).apply();
        Log.i(TAG, "记录成功启动 Termux，时间=" + now);
    }

    /**
     * 记录一次<b>失败重启</b>，计入限流窗口。
     *
     * <p>调用场景（由 MainActivity 判定）：
     * <ul>
     *   <li>startActivity 抛异常或返回失败；</li>
     *   <li>Termux 启动后 8 秒内又回到 HOME（疑似崩溃循环）。</li>
     * </ul>
     */
    public synchronized void recordLaunchFailed(String reason) {
        long now = System.currentTimeMillis();
        String safeReason = (reason == null || reason.trim().isEmpty())
                ? "未知原因" : reason.trim();
        List<Long> failures = loadFailureTimes();
        failures.add(now);
        trimFailureTimes(failures, now);
        saveFailureTimes(failures);
        preferences.edit()
                .putLong(KEY_LAST_FAILURE_AT, now)
                .putString(KEY_LAST_FAILURE_REASON, safeReason)
                .apply();
        Log.w(TAG, "记录失败重启：" + safeReason + "（时间=" + now + "，窗口内失败 "
                + failures.size() + " / " + TerminalOsConfig.RESTART_MAX_FAILURES_IN_WINDOW + " 次）");
    }

    /** 设备重启后重置：清空失败窗口与上次启动/成功时间，保留最近失败原因用于诊断。 */
    public synchronized void resetForBoot() {
        preferences.edit()
                .remove(KEY_FAILURE_TIMES)
                .remove(KEY_LAST_ATTEMPT_AT)
                .remove(KEY_LAST_SUCCESS_AT)
                .putLong(KEY_LAST_ATTEMPT_AT, 0L)
                .putLong(KEY_LAST_SUCCESS_AT, 0L)
                .apply();
        Log.i(TAG, "BOOT_COMPLETED 重置：失败重启窗口已清空");
    }

    /**
     * 管理员在维护界面手动重试后重置失败窗口（不消耗额度）。
     *
     * <p>同时把"上次启动时间"设为当前时刻，因此紧接着的自动启动仍要经过一个冷静期，
     * 由主界面安排一次受控的延迟重试（不会立刻产生紧密循环）。
     */
    public synchronized void resetForManualRetry() {
        long now = System.currentTimeMillis();
        preferences.edit()
                .remove(KEY_FAILURE_TIMES)
                .putLong(KEY_LAST_ATTEMPT_AT, now)
                .apply();
        Log.i(TAG, "管理员手动重试：失败重启窗口已清空（不消耗额度），冷静期从 " + now + " 开始");
    }

    /** 读取诊断状态快照。 */
    public synchronized Status getStatus() {
        final long now = System.currentTimeMillis();
        List<Long> failures = loadFailureTimes();
        trimFailureTimes(failures, now);
        long windowRemainingMs = 0L;
        if (!failures.isEmpty()) {
            long oldestFailureAt = failures.get(0);
            windowRemainingMs = oldestFailureAt + TerminalOsConfig.RESTART_WINDOW_MS - now;
            if (windowRemainingMs < 0L) {
                windowRemainingMs = 0L;
            }
        }
        boolean lockedOut = failures.size() >= TerminalOsConfig.RESTART_MAX_FAILURES_IN_WINDOW;
        return new Status(
                failures.size(),
                TerminalOsConfig.RESTART_MAX_FAILURES_IN_WINDOW,
                windowRemainingMs,
                lockedOut,
                preferences.getString(KEY_LAST_FAILURE_REASON, null),
                preferences.getLong(KEY_LAST_FAILURE_AT, 0L),
                preferences.getLong(KEY_LAST_SUCCESS_AT, 0L),
                preferences.getLong(KEY_LAST_ATTEMPT_AT, 0L));
    }

    // ---- 内部实现 ----

    private List<Long> loadFailureTimes() {
        List<Long> failures = new ArrayList<>();
        String raw = preferences.getString(KEY_FAILURE_TIMES, "");
        if (raw == null || raw.isEmpty()) {
            return failures;
        }
        String[] tokens = raw.split(FAILURE_SEPARATOR);
        for (String token : tokens) {
            if (token == null || token.isEmpty()) {
                continue;
            }
            try {
                failures.add(Long.parseLong(token));
            } catch (NumberFormatException e) {
                // 数据损坏不应该导致崩溃：记录并丢弃这一项。
                Log.w(TAG, "忽略损坏的限流时间戳：" + token, e);
            }
        }
        return failures;
    }

    private void saveFailureTimes(List<Long> failures) {
        if (failures.isEmpty()) {
            preferences.edit().remove(KEY_FAILURE_TIMES).apply();
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < failures.size(); i++) {
            if (i > 0) {
                builder.append(FAILURE_SEPARATOR);
            }
            builder.append(failures.get(i));
        }
        preferences.edit().putString(KEY_FAILURE_TIMES, builder.toString()).apply();
    }

    private void trimFailureTimes(List<Long> failures, long now) {
        long windowStart = now - TerminalOsConfig.RESTART_WINDOW_MS;
        while (!failures.isEmpty() && failures.get(0) < windowStart) {
            failures.remove(0);
        }
    }
}
