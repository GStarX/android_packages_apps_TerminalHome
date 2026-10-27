package org.terminalos.terminalhome;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 设备管理员接收器。
 *
 * <p>用途：本应用需要以 <b>Device Owner</b> 身份调用
 * {@code DevicePolicyManager#setLockTaskPackages/ setLockTaskFeatures /
 * addPersistentPreferredActivity}。Device Owner 必须绑定一个在 AndroidManifest 中声明、
 * 且带有 {@code android.app.device_admin} meta-data 的 {@link DeviceAdminReceiver} 子类。
 *
 * <p>{@code res/xml/device_admin.xml} 只声明最小策略集（force-lock），
 * 不申请 wipe-data、reset-password 等破坏性策略；锁任务/默认 HOME 属于
 * Device Owner 能力，由 DO 状态而非 device_admin.xml 的 uses-policies 授权。
 *
 * <p>本类只做日志记录；不在回调里启动 Activity 或做耗时操作。
 */
public class AdminReceiver extends DeviceAdminReceiver {

    private static final String TAG = TerminalOsConfig.TAG_PREFIX + ".AdminReceiver";

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.i(TAG, "设备管理员已启用（组件 "
                + TerminalOsConfig.PACKAGE_NAME + "/.AdminReceiver）");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.i(TAG, "设备管理员已停用：锁任务能力将不可用，应用会降级为不锁定");
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        // 返回给系统的停用确认文案；不阻止停用，只提示后果。
        return context.getString(R.string.admin_disable_warning);
    }

    @Override
    public void onLockTaskModeEntering(Context context, Intent intent, String pkg) {
        super.onLockTaskModeEntering(context, intent, pkg);
        Log.i(TAG, "进入锁任务模式，前台包=" + pkg);
    }

    @Override
    public void onLockTaskModeExiting(Context context, Intent intent) {
        super.onLockTaskModeExiting(context, intent);
        Log.i(TAG, "退出锁任务模式");
    }
}
