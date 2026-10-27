package org.terminalos.terminalhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机广播接收器。
 *
 * <p>职责只有一件事：记录启动事件，并把"重启后可重新进入终端"所需的状态清掉：
 * <ul>
 *   <li>重置自动重启限流窗口（{@link RestartLimiter#resetForBoot()}）；</li>
 *   <li>清除维护模式，使设备重启后回到终端模式（满足"退出终端模式后重启回终端"的说明）；</li>
 *   <li>记录最近启动时间用于诊断。</li>
 * </ul>
 *
 * <p>刻意<b>不</b>在这里 startActivity：
 * <ul>
 *   <li>HOME Activity 由系统在需要主屏时启动，广播里再拉 Activity 会产生竞态与重复启动；</li>
 *   <li>Android 10+ 对后台启动 Activity 有严格限制，广播里启动 Activity 容易失败或被系统记录为滥用；</li>
 *   <li>接收器应快速返回，避免占用广播队列。</li>
 * </ul>
 *
 * <p>directBootAware 未开启（见 AndroidManifest）：BOOT_COMPLETED 在用户解锁后送达，
 * 此时可以安全访问凭据加密存储（SharedPreferences）；加密解锁前的 LOCKED_BOOT_COMPLETED
 * 由系统跳过本接收器，符合"解锁前不访问用户数据"的要求。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = TerminalOsConfig.TAG_PREFIX + ".BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            Log.w(TAG, "onReceive 参数为空，忽略");
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Log.w(TAG, "忽略非 BOOT_COMPLETED 广播：" + action);
            return;
        }
        Log.i(TAG, "收到 BOOT_COMPLETED：重置限流与维护模式，等待系统启动 HOME");
        try {
            RestartLimiter restartLimiter = new RestartLimiter(context);
            restartLimiter.resetForBoot();

            // 重启后回到终端模式（管理员若需维护可在重启后重新用 7 击 + PIN 进入）。
            TerminalOsConfig.setMaintenanceMode(context, false);

            TerminalOsConfig.getPreferences(context)
                    .edit()
                    .putLong(TerminalOsConfig.KEY_LAST_BOOT_AT, System.currentTimeMillis())
                    .apply();
        } catch (RuntimeException e) {
            // 广播里抛异常会导致系统记录崩溃；这里记录完整堆栈后返回。
            Log.e(TAG, "处理 BOOT_COMPLETED 时出现异常", e);
        }
    }
}
