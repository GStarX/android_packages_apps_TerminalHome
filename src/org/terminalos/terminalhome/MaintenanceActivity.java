package org.terminalos.terminalhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 维护界面（应用内入口，exported=false，无法被外部直接拉起）。
 *
 * <p>进入方式：主界面标题连续点击 7 次。
 * <ul>
 *   <li>已设置 PIN：先弹出 PIN 校验对话框，校验通过才显示维护内容；取消则退出界面；</li>
 *   <li>未设置 PIN：直接进入并引导设置 PIN（危险操作仍要求先设置 PIN）。</li>
 * </ul>
 *
 * <p>提供：
 * <ul>
 *   <li>诊断信息：Termux 安装/入口状态、Device Owner、设备管理员、锁任务状态、
 *       默认 HOME 接管、快捷设置特性、维护模式、PIN 状态、失败重启限流与最近失败原因、
 *       SYSTEM_SUPERVISION 角色持有状态；</li>
 *   <li>按钮：重新打开 Termux（恢复终端模式）/ 进入维护模式（退出锁定，需 PIN）/
 *       设置或修改维护 PIN（修改需先验证旧 PIN）/ 申请监督角色（仅 Android 16+ 且 DO 时显示）/
 *       打开系统设置；</li>
 *   <li>说明：退出终端模式后重启设备会回到终端模式（BootReceiver 清除维护模式）。</li>
 * </ul>
 *
 * <p>所有危险动作（退出锁定、修改 PIN）都要求 PIN 验证。
 */
public class MaintenanceActivity extends Activity {

    private static final String TAG = TerminalOsConfig.TAG_PREFIX + ".MaintenanceActivity";

    /** Android 16 = API 36（字面量避免依赖过新的 Build.VERSION_CODES 常量）。 */
    private static final int API_36 = 36;

    /** 申请 SYSTEM_SUPERVISION 角色的 requestCode。 */
    private static final int REQUEST_SYSTEM_SUPERVISION_ROLE = 1001;

    /**
     * SYSTEM_SUPERVISION 角色名。
     *
     * <p>Android 16 中 {@code RoleManager.ROLE_SYSTEM_SUPERVISION} 标注为 {@code @SystemApi}，
     * 本应用以公开 API 面编译时不能直接引用，因此使用公开的
     * {@link RoleManager#createRequestRoleIntent(String)} + 角色字符串常量。
     */
    private static final String ROLE_SYSTEM_SUPERVISION = "android.app.role.SYSTEM_SUPERVISION";

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

    private AdminPinManager pinManager;
    private TerminalLauncher terminalLauncher;
    private RestartLimiter restartLimiter;
    private KioskController kioskController;

    private LinearLayout contentContainer;
    private TextView diagnosticsView;
    private Button requestSupervisionRoleButton;

    private boolean pinGatePassed;
    private boolean pinDialogShowing;
    private boolean setPinPromptShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "MaintenanceActivity onCreate");
        setContentView(R.layout.activity_maintenance);

        pinManager = new AdminPinManager(this);
        terminalLauncher = new TerminalLauncher(this);
        restartLimiter = new RestartLimiter(this);
        kioskController = new KioskController(this);

        contentContainer = findViewById(R.id.ll_maintenance_content);
        diagnosticsView = findViewById(R.id.tv_diagnostics);

        Button retryTermuxButton = findViewById(R.id.btn_retry_termux);
        retryTermuxButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onRetryTermuxRequested();
            }
        });

        Button exitLockButton = findViewById(R.id.btn_exit_lock);
        exitLockButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onExitLockRequested();
            }
        });

        Button setPinButton = findViewById(R.id.btn_set_pin);
        setPinButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onSetPinRequested();
            }
        });

        Button openSettingsButton = findViewById(R.id.btn_open_settings);
        openSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onOpenSystemSettingsRequested();
            }
        });

        requestSupervisionRoleButton = findViewById(R.id.btn_request_supervision_role);
        requestSupervisionRoleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onRequestSupervisionRoleRequested();
            }
        });

        // 如果管理员是在应用启动后才通过 adb 设置 Device Owner，这里提供一次接管机会。
        kioskController.ensureDefaultHome(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !isDestroyed() && pinGatePassed) {
                    refreshDiagnostics();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pinGatePassed) {
            refreshDiagnostics();
            return;
        }
        if (pinDialogShowing) {
            return;
        }
        requestPinGate();
    }

    // ---- PIN 门禁 ----

    private void requestPinGate() {
        if (!pinManager.isPinSet()) {
            Log.i(TAG, "未设置维护 PIN：直接展示维护内容并引导设置");
            Toast.makeText(this, R.string.toast_pin_not_set_gate, Toast.LENGTH_LONG).show();
            passPinGate(true);
            return;
        }
        showVerifyPinDialog(R.string.dialog_verify_pin_title, new Runnable() {
            @Override
            public void run() {
                passPinGate(false);
            }
        }, true);
    }

    private void passPinGate(boolean promptToSetPin) {
        pinGatePassed = true;
        contentContainer.setVisibility(View.VISIBLE);
        refreshDiagnostics();
        if (promptToSetPin && !setPinPromptShown) {
            setPinPromptShown = true;
            showSetPinDialog(null);
        }
    }

    // ---- 按钮行为 ----

    /**
     * 重新打开 Termux：重置失败重启窗口（不消耗额度）、清除维护模式，然后返回主界面，
     * 由主界面的正常自动启动流程（含锁任务/kiosk 流程）接管。
     */
    private void onRetryTermuxRequested() {
        if (!terminalLauncher.isTermuxInstalled()) {
            Toast.makeText(this, R.string.toast_termux_not_installed, Toast.LENGTH_LONG).show();
            refreshDiagnostics();
            return;
        }
        restartLimiter.resetForManualRetry();
        TerminalOsConfig.setMaintenanceMode(this, false);
        Log.i(TAG, "维护界面：已重置限流并清除维护模式，返回主界面");
        Toast.makeText(this, R.string.toast_manual_retry, Toast.LENGTH_SHORT).show();
        finish();
    }

    /** 进入维护模式（退出锁定）：必须先通过 PIN 验证。 */
    private void onExitLockRequested() {
        if (!pinManager.isPinSet()) {
            Toast.makeText(this, R.string.toast_pin_required_for_exit, Toast.LENGTH_LONG).show();
            showSetPinDialog(null);
            return;
        }
        showVerifyPinDialog(R.string.dialog_verify_pin_title, new Runnable() {
            @Override
            public void run() {
                performExitLock();
            }
        }, false);
    }

    private void performExitLock() {
        boolean stopped = kioskController.exitLockTask(this);
        TerminalOsConfig.setMaintenanceMode(this, true);
        Log.i(TAG, "维护模式已开启（exitLockTask=" + stopped + "）");
        Toast.makeText(this, stopped ? R.string.toast_exit_lock_done
                : R.string.toast_exit_lock_partial, Toast.LENGTH_LONG).show();
        refreshDiagnostics();
    }

    /** 设置/修改 PIN：已设置时必须先验证旧 PIN。 */
    private void onSetPinRequested() {
        if (!pinManager.isPinSet()) {
            showSetPinDialog(null);
            return;
        }
        showVerifyPinDialog(R.string.dialog_verify_pin_title, new Runnable() {
            @Override
            public void run() {
                showSetPinDialog(null);
            }
        }, false);
    }

    private void onOpenSystemSettingsRequested() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
            Log.i(TAG, "已请求打开系统设置");
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "系统设置 Activity 不存在", e);
            Toast.makeText(this, R.string.toast_settings_unavailable, Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            Log.e(TAG, "打开系统设置被安全策略拒绝（可能仍处于锁任务模式）", e);
            Toast.makeText(this, R.string.toast_settings_unavailable, Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            Log.e(TAG, "打开系统设置出现未预期异常", e);
            Toast.makeText(this, R.string.toast_settings_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    // ---- 监督角色（SYSTEM_SUPERVISION）申请 ----

    /**
     * 申请 SYSTEM_SUPERVISION 角色（Android 16+，仅 Device Owner 可用）。
     *
     * <p>背景：Android 16 的锁任务"快捷设置"特性要求 DPC 持有该角色。ROM 层需通过
     * frameworks/base 的 {@code config_allowedSupervisionRolePackages} 允许本应用持有该角色；
     * 应用侧必须主动用 {@link RoleManager#createRequestRoleIntent(String)} 发起申请
     * （角色申请 UI/是否自动授予属未实机验证项）。
     *
     * <p>申请结果通过 {@link #onActivityResult(int, int, Intent)} 回来，并用
     * {@link RoleManager#isRoleHeld(String)} 核实实际持有状态后如实提示。
     */
    @SuppressWarnings("deprecation")
    private void onRequestSupervisionRoleRequested() {
        if (Build.VERSION.SDK_INT < API_36) {
            Toast.makeText(this, R.string.toast_role_requires_android16,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!kioskController.isDeviceOwner()) {
            Toast.makeText(this, R.string.toast_role_requires_device_owner,
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            RoleManager roleManager = (RoleManager) getSystemService(Context.ROLE_SERVICE);
            if (roleManager == null) {
                Log.w(TAG, "RoleManager 不可用，无法申请 SYSTEM_SUPERVISION 角色");
                Toast.makeText(this, R.string.toast_role_unavailable,
                        Toast.LENGTH_LONG).show();
                return;
            }
            Intent requestIntent = roleManager.createRequestRoleIntent(ROLE_SYSTEM_SUPERVISION);
            if (requestIntent == null) {
                // 正常情况下不会发生；显式处理避免把 null Intent 交给 startActivityForResult。
                Log.e(TAG, "createRequestRoleIntent 返回 null，无法申请监督角色");
                Toast.makeText(this, R.string.toast_role_request_failed, Toast.LENGTH_LONG).show();
                return;
            }
            startActivityForResult(requestIntent, REQUEST_SYSTEM_SUPERVISION_ROLE);
            Log.i(TAG, "已发起 SYSTEM_SUPERVISION 角色申请");
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "申请监督角色失败：系统没有角色申请界面", e);
            Toast.makeText(this, R.string.toast_role_request_failed, Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            Log.e(TAG, "申请监督角色被安全策略拒绝", e);
            Toast.makeText(this, R.string.toast_role_request_failed, Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            Log.e(TAG, "申请监督角色出现未预期异常", e);
            Toast.makeText(this, R.string.toast_role_request_failed, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SYSTEM_SUPERVISION_ROLE) {
            return;
        }
        Boolean roleHeld = querySupervisionRoleHeld();
        Log.i(TAG, "监督角色申请返回：resultCode=" + resultCode
                + "，isRoleHeld=" + roleHeld);
        if (Boolean.TRUE.equals(roleHeld)) {
            Toast.makeText(this, R.string.toast_role_granted, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, R.string.toast_role_not_granted, Toast.LENGTH_LONG).show();
        }
        refreshDiagnostics();
    }

    /** 仅在 Android 16+ 且本应用是 Device Owner 时显示"申请监督角色"按钮。 */
    private void updateSupervisionRoleButtonVisibility() {
        if (requestSupervisionRoleButton == null) {
            return;
        }
        boolean canRequest =
                Build.VERSION.SDK_INT >= API_36 && kioskController.isDeviceOwner();
        requestSupervisionRoleButton.setVisibility(canRequest ? View.VISIBLE : View.GONE);
    }

    /**
     * 查询 SYSTEM_SUPERVISION 角色是否已持有。
     *
     * @return true/false；系统低于 Android 16、RoleManager 不可用或查询异常时返回 null（未知）。
     */
    private Boolean querySupervisionRoleHeld() {
        if (Build.VERSION.SDK_INT < API_36) {
            return null;
        }
        try {
            RoleManager roleManager = (RoleManager) getSystemService(Context.ROLE_SERVICE);
            if (roleManager == null) {
                Log.w(TAG, "RoleManager 不可用，无法查询监督角色状态");
                return null;
            }
            return roleManager.isRoleHeld(ROLE_SYSTEM_SUPERVISION);
        } catch (RuntimeException e) {
            Log.w(TAG, "查询 SYSTEM_SUPERVISION 角色状态异常", e);
            return null;
        }
    }

    // ---- 诊断信息 ----

    private void refreshDiagnostics() {
        if (diagnosticsView == null) {
            return;
        }
        updateSupervisionRoleButtonVisibility();
        KioskController.Status kioskStatus = kioskController.describeStatus();
        RestartLimiter.Status limiterStatus = restartLimiter.getStatus();

        String lastFailureReason = limiterStatus.lastFailureReason == null
                ? getString(R.string.value_failure_reason_none)
                : limiterStatus.lastFailureReason;
        String maintenanceState = TerminalOsConfig.isMaintenanceMode(this)
                ? getString(R.string.value_maintenance_on)
                : getString(R.string.value_maintenance_off);
        String pinState = pinManager.isPinSet()
                ? getString(R.string.value_pin_set)
                : getString(R.string.value_pin_not_set);

        String text = String.format(Locale.getDefault(), getString(R.string.diagnostics_format),
                describeTermuxState(),
                kioskStatus.deviceOwner
                        ? getString(R.string.value_yes) : getString(R.string.value_no),
                kioskStatus.adminActive
                        ? getString(R.string.value_yes) : getString(R.string.value_no),
                kioskStatus.lockTaskActive
                        ? getString(R.string.value_locked) : getString(R.string.value_not_locked),
                describeHomeTakeover(kioskStatus.homeTakeoverState),
                describeQuickSettings(kioskStatus),
                maintenanceState,
                pinState,
                limiterStatus.failuresInWindow,
                limiterStatus.maxFailuresInWindow,
                lastFailureReason,
                formatTime(limiterStatus.lastFailureAt),
                formatTime(limiterStatus.lastSuccessAt),
                describeSupervisionRole());
        diagnosticsView.setText(text);
    }

    /** 把监督角色持有状态映射为诊断文案。 */
    private String describeSupervisionRole() {
        if (Build.VERSION.SDK_INT < API_36) {
            return getString(R.string.value_role_not_applicable);
        }
        Boolean roleHeld = querySupervisionRoleHeld();
        if (roleHeld == null) {
            return getString(R.string.value_role_unknown);
        }
        return roleHeld
                ? getString(R.string.value_role_held)
                : getString(R.string.value_role_not_held);
    }

    /** 把 Termux 探测结果映射为诊断界面文案（组件名便于排错）。 */
    private String describeTermuxState() {
        switch (terminalLauncher.getTermuxState()) {
            case READY:
                return getString(R.string.value_installed)
                        + "（" + TerminalOsConfig.TERMUX_ACTIVITY_CLASS + "）";
            case INSTALLED_ACTIVITY_UNAVAILABLE:
                return getString(R.string.value_installed_no_entry);
            case NOT_INSTALLED:
            default:
                return getString(R.string.value_not_installed)
                        + "（" + TerminalOsConfig.TERMUX_PACKAGE + "）";
        }
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

    // ---- PIN 对话框 ----

    /**
     * 校验 PIN 对话框。
     *
     * @param titleRes       对话框标题
     * @param onSuccess      校验通过后的回调（主线程）
     * @param finishOnCancel 取消时是否结束本界面（进入维护界面时 true；退出锁定/改 PIN 时 false）
     */
    private void showVerifyPinDialog(int titleRes, final Runnable onSuccess,
            final boolean finishOnCancel) {
        final EditText input = createPinEditText(R.string.dialog_pin_hint);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(input)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                                if (finishOnCancel) {
                                    finish();
                                }
                            }
                        });
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                AdminPinManager.VerifyResult result =
                                        pinManager.verifyPin(input.getText().toString());
                                switch (result) {
                                    case OK:
                                        dialog.dismiss();
                                        onSuccess.run();
                                        break;
                                    case WRONG:
                                        input.setError(getString(R.string.error_pin_wrong));
                                        break;
                                    case NOT_SET:
                                        dialog.dismiss();
                                        Toast.makeText(MaintenanceActivity.this,
                                                R.string.toast_pin_not_set_gate,
                                                Toast.LENGTH_LONG).show();
                                        if (finishOnCancel) {
                                            finish();
                                        }
                                        break;
                                    case ERROR:
                                    default:
                                        input.setError(getString(R.string.error_pin_corrupted));
                                        break;
                                }
                            }
                        });
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                pinDialogShowing = false;
            }
        });
        pinDialogShowing = true;
        dialog.show();
    }

    /**
     * 设置新 PIN 对话框（输入两次确认）。
     *
     * @param onSuccess 保存成功后的回调，可为 null。
     */
    private void showSetPinDialog(final Runnable onSuccess) {
        final EditText pinInput = createPinEditText(R.string.dialog_pin_hint);
        final EditText confirmationInput =
                createPinEditText(R.string.dialog_pin_confirm_hint);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int horizontalPadding = dpToPixels(20);
        container.setPadding(horizontalPadding, dpToPixels(8), horizontalPadding, 0);
        container.addView(pinInput);
        container.addView(confirmationInput);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_set_pin_title)
                .setView(container)
                .setCancelable(true)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null);
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                String pin = pinInput.getText().toString();
                                String confirmation = confirmationInput.getText().toString();
                                if (!AdminPinManager.isValidPinFormat(pin)) {
                                    pinInput.setError(getString(R.string.error_pin_format));
                                    return;
                                }
                                if (!pin.equals(confirmation)) {
                                    confirmationInput.setError(
                                            getString(R.string.error_pin_mismatch));
                                    return;
                                }
                                if (!pinManager.setPin(pin)) {
                                    Toast.makeText(MaintenanceActivity.this,
                                            R.string.toast_pin_set_failed,
                                            Toast.LENGTH_LONG).show();
                                    return;
                                }
                                Toast.makeText(MaintenanceActivity.this,
                                        R.string.toast_pin_set_ok,
                                        Toast.LENGTH_SHORT).show();
                                dialog.dismiss();
                                refreshDiagnostics();
                                if (onSuccess != null) {
                                    onSuccess.run();
                                }
                            }
                        });
            }
        });
        dialog.show();
    }

    private EditText createPinEditText(int hintResId) {
        EditText editText = new EditText(this);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        editText.setHint(hintResId);
        editText.setSingleLine(true);
        int padding = dpToPixels(12);
        editText.setPadding(padding, padding, padding, padding);
        return editText;
    }

    private int dpToPixels(int dp) {
        return Math.round(getResources().getDisplayMetrics().density * dp);
    }
}
