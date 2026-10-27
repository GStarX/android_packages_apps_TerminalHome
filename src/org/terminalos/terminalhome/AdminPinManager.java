package org.terminalos.terminalhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 维护 PIN 的设置与校验。
 *
 * <p>安全设计：
 * <ul>
 *   <li>只保存 PBKDF2WithHmacSHA256 派生值，不保存 PIN 明文；</li>
 *   <li>每个 PIN 使用 16 字节 {@link SecureRandom} 随机 salt；</li>
 *   <li>迭代次数随派生结果一起保存（当前 120000），便于将来升级参数；</li>
 *   <li>使用 {@link MessageDigest#isEqual(byte[], byte[])} 做定长比较，避免普通 equals 的时序侧信道；</li>
 *   <li>派生出的密钥字节与 PIN 字符数组在比较/使用后清零（Java 堆内尽量降低残留）。</li>
 * </ul>
 *
 * <p>已知边界（见 README）：
 * <ul>
 *   <li>PIN 校验在本进程内完成，无硬件安全模块；拥有 root/物理刷写能力的人可以绕过。</li>
 *   <li>未设置 PIN 时，能操作设备的人可以先设置 PIN；首次部署后应立即设置。</li>
 *   <li>PIN 遗忘后无法从界面找回，只能清除应用数据/重新部署。</li>
 * </ul>
 *
 * <p>PBKDF2WithHmacSHA256 需要 Android 8.0（API 26）以上；本应用 minSdk 为 28。
 */
public final class AdminPinManager {

    private static final String TAG = TerminalOsConfig.TAG_PREFIX + ".Pin";

    private static final String KEY_PIN_SALT = "admin_pin_salt";
    private static final String KEY_PIN_HASH = "admin_pin_hash";
    private static final String KEY_PIN_ITERATIONS = "admin_pin_iterations";

    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 120_000;

    private static final int PIN_MIN_LENGTH = 4;
    private static final int PIN_MAX_LENGTH = 12;
    private static final Pattern PIN_PATTERN =
            Pattern.compile("^[0-9]{" + PIN_MIN_LENGTH + "," + PIN_MAX_LENGTH + "}$");

    /** PIN 校验结果。 */
    public enum VerifyResult {
        /** 校验通过。 */
        OK,
        /** 已设置 PIN，但输入不匹配。 */
        WRONG,
        /** 尚未设置 PIN。 */
        NOT_SET,
        /** 存储数据损坏或密码学组件不可用（已记录日志）。 */
        ERROR
    }

    private final SharedPreferences preferences;

    public AdminPinManager(Context context) {
        this.preferences = TerminalOsConfig.getPreferences(context);
    }

    /** 是否已经设置过 PIN。 */
    public boolean isPinSet() {
        String salt = preferences.getString(KEY_PIN_SALT, null);
        String hash = preferences.getString(KEY_PIN_HASH, null);
        int iterations = preferences.getInt(KEY_PIN_ITERATIONS, 0);
        return salt != null && hash != null && iterations > 0;
    }

    /** PIN 格式是否合法（4-12 位纯数字）。 */
    public static boolean isValidPinFormat(String pin) {
        return pin != null && PIN_PATTERN.matcher(pin).matches();
    }

    /**
     * 设置（或覆盖）维护 PIN。
     *
     * @return true 表示已成功持久化；false 表示格式非法或写入失败（均记录日志）。
     */
    public boolean setPin(String pin) {
        if (!isValidPinFormat(pin)) {
            Log.w(TAG, "拒绝设置 PIN：格式非法（要求 " + PIN_MIN_LENGTH + "-"
                    + PIN_MAX_LENGTH + " 位纯数字）");
            return false;
        }
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] derivedHash = deriveKey(pin, salt, PBKDF2_ITERATIONS);
        if (derivedHash == null) {
            Log.e(TAG, "设置 PIN 失败：PBKDF2 派生失败");
            return false;
        }
        boolean saved = false;
        try {
            saved = preferences.edit()
                    .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(KEY_PIN_HASH, Base64.encodeToString(derivedHash, Base64.NO_WRAP))
                    .putInt(KEY_PIN_ITERATIONS, PBKDF2_ITERATIONS)
                    .commit();
        } catch (RuntimeException e) {
            // 部分异常 ROM 的 SharedPreferences 写入可能抛运行时异常；记录并降级为失败。
            Log.e(TAG, "设置 PIN 时 SharedPreferences 写入异常", e);
        } finally {
            Arrays.fill(derivedHash, (byte) 0);
        }
        if (!saved) {
            Log.e(TAG, "设置 PIN 失败：SharedPreferences.commit() 返回 false");
        } else {
            Log.i(TAG, "维护 PIN 已更新（PBKDF2 迭代次数 " + PBKDF2_ITERATIONS + "）");
        }
        return saved;
    }

    /**
     * 校验 PIN。
     *
     * <p>返回值区分"未设置 / 错误 / 通过 / 存储损坏"四种状态，调用方据此给出明确提示。
     */
    public VerifyResult verifyPin(String pin) {
        if (!isPinSet()) {
            Log.i(TAG, "校验 PIN：尚未设置");
            return VerifyResult.NOT_SET;
        }
        if (pin == null) {
            return VerifyResult.WRONG;
        }
        String saltBase64 = preferences.getString(KEY_PIN_SALT, null);
        String hashBase64 = preferences.getString(KEY_PIN_HASH, null);
        int iterations = preferences.getInt(KEY_PIN_ITERATIONS, 0);
        if (saltBase64 == null || hashBase64 == null || iterations <= 0) {
            Log.e(TAG, "校验 PIN：存储的 salt/hash/迭代次数不完整");
            return VerifyResult.NOT_SET;
        }
        byte[] salt;
        byte[] expectedHash;
        try {
            salt = Base64.decode(saltBase64, Base64.DEFAULT);
            expectedHash = Base64.decode(hashBase64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "校验 PIN：存储数据不是合法 Base64，无法校验", e);
            return VerifyResult.ERROR;
        }
        byte[] actualHash = deriveKey(pin, salt, iterations);
        if (actualHash == null) {
            Log.e(TAG, "校验 PIN：PBKDF2 派生失败");
            return VerifyResult.ERROR;
        }
        try {
            boolean matched = MessageDigest.isEqual(expectedHash, actualHash);
            Log.i(TAG, "校验 PIN：" + (matched ? "通过" : "不匹配"));
            return matched ? VerifyResult.OK : VerifyResult.WRONG;
        } finally {
            Arrays.fill(actualHash, (byte) 0);
        }
    }

    /**
     * 派生 PBKDF2 密钥；失败时记录日志并返回 null（不吞异常）。
     */
    private static byte[] deriveKey(String pin, byte[] salt, int iterations) {
        if (pin == null || salt == null || salt.length == 0 || iterations <= 0) {
            Log.e(TAG, "PBKDF2 参数非法：pin/salt/iterations 不合法");
            return null;
        }
        char[] pinChars = pin.toCharArray();
        PBEKeySpec keySpec = null;
        try {
            keySpec = new PBEKeySpec(pinChars, salt, iterations, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
            return factory.generateSecret(keySpec).getEncoded();
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "系统不支持算法 " + PBKDF2_ALGORITHM, e);
            return null;
        } catch (InvalidKeySpecException e) {
            Log.e(TAG, "PBKDF2 参数被密码学实现拒绝", e);
            return null;
        } finally {
            Arrays.fill(pinChars, '\0');
            if (keySpec != null) {
                keySpec.clearPassword();
            }
        }
    }
}
