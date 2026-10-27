package org.terminalos.terminalhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 终端主屏（HOME Activity）。
 *
 * <p>行为：
 * <ul>
 *   <li>作为 HOME：设备回到主屏时本界面会被系统拉起；</li>
 *   <li>onResume 后（延迟一小段时间）自动尝试打开 Termux；</li>
 *   <li>启动受 {@link RestartLimiter} 限流：10 分钟内最多 5 次<b>失败重启</b>、两次启动之间至少
 *       4 秒冷静期；成功进入 Termux 并停留 ≥8 秒后按 Home 返回不消耗额度；只有
 *       startActivity 失败或启动后 8 秒内即返回 HOME 才计入失败；失败额度用尽则停在本界面的
 *       维护提示状态（显示原因与"进入维护界面"按钮），不再自动重试；</li>
 *   <li>Termux 未安装/入口不可解析时，不循环重试，直接显示维护提示；</li>
 *   <li>标题连续点击 7 次打开维护入口；维护界面会再做 PIN 校验（PIN 未设置时引导设置）；</li>
 *   <li>返回键不会离开本界面（正常操作不能退出终端）；</li>
 *   <li>状态行如实展示：Termux 状态 / 锁定状态 / PIN 状态 / 最近失败原因 / HOME 接管状态；
 *       非 Device Owner 时明确显示"未启用锁定"，不伪装。</li>
 * </ul>
 *
 * <p>本类只使用公开 API（Android 8.0+）。
 */
public class MainActivity extends Activity {

    private static final String TAG = TerminalOsConfig.TAG_PREFIX + ".MainActivity";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable autoLaunchRunnable = new Runnable() {
        @Override
        public void run() {
            launchScheduled = false;
            attemptTermuxLaunch();
        }
    };

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

    private TerminalLauncher terminalLauncher;
    private RestartLimiter restartLimiter;
    private KioskController kioskController;
    private AdminPinManager pinManager;

    private TextView statusTerminalView;
    private TextView statusLockView;
    private TextView statusPinView;
    private TextView statusDetailView;
    private Button openMaintenanceButton;

    private int titleTapCount;
    private long lastTitleTapAt;
    private boolean launchScheduled;
    private boolean defaultHomeRequested;
    /**
     * 最近一次成功发出 Termux 启动请求时的 {@link SystemClock#elapsedRealtime()}。
     * 0 表示没有待判定的启动；用于在回到 HOME 时判定"成功停留"还是"疑似崩溃循环"。
     */
    private long termuxLaunchStartedAtElapsedRealtime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "MainActivity onCreate");
        setContentView(R.layout.activity_main);

        terminalLauncher = new TerminalLauncher(this);
        restartLimiter = new RestartLimiter(this);
        kioskController = new KioskController(this);
        pinManager = new AdminPinManager(this);

        bindViews();
        setupTitleTapEntry();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "MainActivity onResume");
        // 先判定上一次 Termux 启动的结果（成功停留 vs 8 秒内快速返回），再刷新/调度。
        evaluateTermuxReturnIfNeeded();
        refreshUi();
        requestDefaultHomeTakeoverIfNeeded();
        scheduleAutoLaunchIfAllowed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 界面被 Termux / 维护界面覆盖时，取消尚未触发的自动启动，避免在后台拉 Activity。
        cancelScheduledLaunch();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /**
     * 返回键不退出主屏：维护模式下提示，其余情况重新触发一次受控的终端启动流程。
     *
     * <p>使用旧式 onBackPressed（未启用 predictive back）；在 Android 13+ 上该回调仍然有效。
     */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (TerminalOsConfig.isMaintenanceMode(this)) {
            Toast.makeText(this, R.string.toast_back_in_maintenance, Toast.LENGTH_SHORT).show();
            return;
        }
        Log.i(TAG, "返回键：重新触发终端启动流程（受重启限流约束）");
        scheduleAutoLaunchIfAllowed();
    }

    /**
     * 判定上一次 Termux 启动是"成功停留"还是"疑似崩溃循环"。
     *
     * <p>触发时机：MainActivity 重新回到前台（用户从 Termux 按 Home 返回，或 Termux 退出/崩溃）。
     * 阈值 {@link TerminalOsConfig#RESTART_QUICK_RETURN_THRESHOLD_MS}（8 秒）：
     * 停留 ≥ 8 秒 → 不消耗重启额度；不足 8 秒 → 由 {@link RestartLimiter} 记为失败重启。
     *
     * <p>时间戳只保存在内存中：若本进程在 Termux 前台期间被杀，返回时无法判定，
     * 本次选择"不计数"（保守，不影响额度），见 README。
     */
    private void evaluateTermuxReturnIfNeeded() {
        if (termuxLaunchStartedAtElapsedRealtime <= 0L) {
            return;
        }
        long elapsedMs =
                SystemClock.elapsedRealtime() - termuxLaunchStartedAtElapsedRealtime;
        termuxLaunchStartedAtElapsedRealtime = 0L;
        if (elapsedMs >= TerminalOsConfig.RESTART_QUICK_RETURN_THRESHOLD_MS) {
            Log.i(TAG, "Termux 前台停留 " + elapsedMs + "ms（≥ "
                    + TerminalOsConfig.RESTART_QUICK_RETURN_THRESHOLD_MS
                    + "ms），本次返回不消耗重启额度");
        } else {
            Log.w(TAG, "Termux 启动后 " + elapsedMs + "ms 即返回 HOME（< "
                    + TerminalOsConfig.RESTART_QUICK_RETURN_THRESHOLD_MS
                    + "ms），按失败重启计入限流");
            restartLimiter.recordLaunchFailed("Termux 启动后 " + elapsedMs
                    + "ms 即返回 HOME（疑似退出/崩溃）");
        }
    }

    private void bindViews() {
        statusTerminalView = findViewById(R.id.tv_status_terminal);
        statusLockView = findViewById(R.id.tv_status_lock);
        statusPinView = findViewById(R.id.tv_status_pin);
        statusDetailView = findViewById(R.id.tv_status_detail);
        openMaintenanceButton = findViewById(R.id.btn_open_maintenance);
        openMaintenanceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openMaintenanceActivity();
            }
        });
    }

    /**
     * 连续点击标题 7 次进入维护入口；两次点击间隔超过阈值会重新计数。
     */
    private void setupTitleTapEntry() {
        TextView titleView = findViewById(R.id.tv_title);
        titleView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastTitleTapAt > TerminalOsConfig.TITLE_TAP_TIMEOUT_MS) {
                    titleTapCount = 0;
                }
                lastTitleTapAt = now;
                titleTapCount++;
                if (titleTapCount >= TerminalOsConfig.TITLE_TAPS_TO_MAINTENANCE) {
                    titleTapCount = 0;
                    Log.i(TAG, "标题连续点击达到 "
                            + TerminalOsConfig.TITLE_TAPS_TO_MAINTENANCE + " 次，打开维护入口");
                    openMaintenanceActivity();
                } else if (titleTapCount >= TerminalOsConfig.TITLE_TAPS_TO_MAINTENANCE - 3) {
                    int remaining = TerminalOsConfig.TITLE_TAPS_TO_MAINTENANCE - titleTapCount;
                    Toast.makeText(MainActivity.this,
                            getString(R.string.toast_title_taps_remaining, remaining),
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void openMaintenanceActivity() {
        try {
            startActivity(new Intent(this, MaintenanceActivity.class));
        } catch (RuntimeException e) {
            Log.e(TAG, "无法打开维护界面", e);
            Toast.makeText(this, R.string.toast_maintenance_open_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 首次进入时请求把本应用设为持久首选 HOME（仅 Device Owner 生效）。 */
    private void requestDefaultHomeTakeoverIfNeeded() {
        if (defaultHomeRequested) {
            return;
        }
        defaultHomeRequested = true;
        kioskController.ensureDefaultHome(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !isDestroyed()) {
                    // 异步状态变化后统一刷新，避免把已显示的阻塞提示覆盖掉。
                    refreshUi();
                }
            }
        });
    }

    /**
     * 决定是否安排一次自动启动：
     * 维护模式 / Termux 缺失 / 入口不可用时只显示维护提示，不做自动重试。
     */
    private void scheduleAutoLaunchIfAllowed() {
        if (launchScheduled || isFinishing() || isDestroyed()) {
            return;
        }
        if (TerminalOsConfig.isMaintenanceMode(this)) {
            showMaintenanceNotice(getString(R.string.notice_maintenance_mode));
            return;
        }
        if (!terminalLauncher.isTermuxInstalled()) {
            showMaintenanceNotice(getString(R.string.notice_termux_not_installed));
            return;
        }
        if (!terminalLauncher.isTermuxActivityAvailable()) {
            showMaintenanceNotice(getString(R.string.notice_termux_activity_unavailable));
            return;
        }
        if (restartLimiter.getStatus().lockedOut) {
            // 失败额度已用尽：refreshUi 已显示"限流"提示，不做自动调度（避免无意义的重试链）。
            return;
        }
        scheduleLaunch(TerminalOsConfig.AUTO_LAUNCH_DELAY_MS);
    }

    /**
     * 真正尝试打开 Termux 的唯一入口。所有自动启动都必须先经过 {@link RestartLimiter}，
     * 因此任何崩溃/退出循环都有严格上界。
     */
    private void attemptTermuxLaunch() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (TerminalOsConfig.isMaintenanceMode(this)) {
            refreshStatus();
            showMaintenanceNotice(getString(R.string.notice_maintenance_mode));
            return;
        }
        if (!terminalLauncher.isTermuxInstalled()) {
            refreshStatus();
            showMaintenanceNotice(getString(R.string.notice_termux_not_installed));
            return;
        }
        if (!terminalLauncher.isTermuxActivityAvailable()) {
            refreshStatus();
            showMaintenanceNotice(getString(R.string.notice_termux_activity_unavailable));
            return;
        }

        RestartLimiter.Decision decision = restartLimiter.beginAutomaticAttempt();
        if (!decision.allowed) {
            refreshStatus();
            if (decision.windowExhausted) {
                // 失败额度用尽：停在此界面，绝不自动重试，等待管理员介入或窗口自然过期。
                showMaintenanceNotice(getString(R.string.notice_limiter_exhausted,
                        secondsCeil(decision.retryAfterMs)));
            } else {
                // 冷静期：只安排一次受控的延迟重试（重试仍要重新经过限流判定）。
                statusDetailView.setText(getString(R.string.notice_retry_cooldown,
                        secondsCeil(decision.retryAfterMs)));
                scheduleLaunch(decision.retryAfterMs + 250L);
            }
            return;
        }

        KioskController.LockResult lockResult = kioskController.enterLockTask(this);
        if (!lockResult.success) {
            Log.w(TAG, "锁任务未启用：" + lockResult.message);
        }

        TerminalLauncher.LaunchResult launchResult = terminalLauncher.launchTermux(this);
        if (launchResult.success) {
            restartLimiter.recordLaunchSucceeded();
            // 记录本次启动时刻，供下次回到 HOME 时判定成功停留/快速返回（见 onResume）。
            termuxLaunchStartedAtElapsedRealtime = SystemClock.elapsedRealtime();
            refreshStatus();
        } else {
            restartLimiter.recordLaunchFailed(launchResult.message);
            refreshStatus();
            showMaintenanceNotice(getString(R.string.notice_launch_failed,
                    launchResult.message));
        }
    }

    private void scheduleLaunch(long delayMs) {
        if (launchScheduled) {
            return;
        }
        launchScheduled = true;
        mainHandler.postDelayed(autoLaunchRunnable, Math.max(0L, delayMs));
    }

    private void cancelScheduledLaunch() {
        mainHandler.removeCallbacks(autoLaunchRunnable);
        launchScheduled = false;
    }

    private void showMaintenanceNotice(String message) {
        statusDetailView.setText(message);
        openMaintenanceButton.setVisibility(View.VISIBLE);
    }

    /**
     * 刷新界面的统一入口：先刷新状态行，再按当前阻塞条件显示维护提示
     * （避免异步回调只刷新状态行、把维护提示覆盖掉）。
     */
    private void refreshUi() {
        refreshStatus();
        showBlockingNoticeIfNeeded();
    }

    /**
     * 按优先级显示"为什么没有自动进入终端"的提示与维护入口按钮。
     * 这些条件都对应"不应自动重试"的状态，因此只展示原因，不安排自动启动。
     */
    private void showBlockingNoticeIfNeeded() {
        if (statusDetailView == null) {
            return;
        }
        if (TerminalOsConfig.isMaintenanceMode(this)) {
            showMaintenanceNotice(getString(R.string.notice_maintenance_mode));
            return;
        }
        if (!terminalLauncher.isTermuxInstalled()) {
            showMaintenanceNotice(getString(R.string.notice_termux_not_installed));
            return;
        }
        if (!terminalLauncher.isTermuxActivityAvailable()) {
            showMaintenanceNotice(getString(R.string.notice_termux_activity_unavailable));
            return;
        }
        RestartLimiter.Status limiterStatus = restartLimiter.getStatus();
        if (limiterStatus.lockedOut) {
            showMaintenanceNotice(getString(R.string.notice_limiter_exhausted,
                    secondsCeil(limiterStatus.windowRemainingMs)));
        }
    }

    /** 刷新三行状态 + 明细行；明细行可能随后被 showMaintenanceNotice 覆盖。 */
    private void refreshStatus() {
        if (statusTerminalView == null) {
            return;
        }
        openMaintenanceButton.setVisibility(View.GONE);

        if (!terminalLauncher.isTermuxInstalled()) {
            statusTerminalView.setText(R.string.status_terminal_missing);
        } else if (!terminalLauncher.isTermuxActivityAvailable()) {
            statusTerminalView.setText(R.string.status_terminal_installed_no_activity);
        } else {
            statusTerminalView.setText(R.string.status_terminal_installed);
        }

        KioskController.Status kioskStatus = kioskController.describeStatus();
        if (!kioskStatus.deviceOwner) {
            statusLockView.setText(R.string.status_lock_not_device_owner);
        } else if (kioskStatus.lockTaskActive) {
            statusLockView.setText(R.string.status_lock_active);
        } else {
            statusLockView.setText(R.string.status_lock_ready);
        }

        statusPinView.setText(pinManager.isPinSet()
                ? R.string.status_pin_set : R.string.status_pin_not_set);

        RestartLimiter.Status limiterStatus = restartLimiter.getStatus();
        StringBuilder detail = new StringBuilder();
        detail.append(getString(R.string.status_attempts_format,
                limiterStatus.failuresInWindow, limiterStatus.maxFailuresInWindow));
        if (limiterStatus.lastFailureReason != null) {
            detail.append('\n').append(getString(R.string.status_last_failure_format,
                    formatTime(limiterStatus.lastFailureAt), limiterStatus.lastFailureReason));
        }
        if (kioskStatus.deviceOwner) {
            detail.append('\n').append(getString(R.string.status_home_takeover_format,
                    describeHomeTakeover(kioskStatus.homeTakeoverState)));
            detail.append('\n').append(getString(R.string.status_quick_settings_format,
                    describeQuickSettings(kioskStatus)));
        }
        statusDetailView.setText(detail.toString());
    }

    private String describeHomeTakeover(KioskController.HomeTakeoverState state) {
        switch (state) {
            case APPLIED:
                return getString(R.string.value_home_takeover_applied);
            case NOT_DEVICE_OWNER:
                return getString(R.string.value_home_takeover_not_owner);
            case FAILED:
                return getString(R.string.value_home_takeover_failed);
            case UNKNOWN:
            default:
                return getString(R.string.value_home_takeover_unknown);
        }
    }

    private String describeQuickSettings(KioskController.Status status) {
        if (!status.quickSettingsFeatureAvailable) {
            return getString(R.string.value_not_supported);
        }
        if (status.quickSettingsFeatureApplied) {
            return getString(R.string.value_enabled);
        }
        if (status.quickSettingsFeatureRejected) {
            return getString(R.string.value_rejected);
        }
        return getString(R.string.value_pending);
    }

    private String formatTime(long timestampMs) {
        if (timestampMs <= 0L) {
            return getString(R.string.value_none);
        }
        return timeFormat.format(new Date(timestampMs));
    }

    private static long secondsCeil(long milliseconds) {
        long seconds = (milliseconds + 999L) / 1000L;
        return seconds < 1L ? 1L : seconds;
    }
}
