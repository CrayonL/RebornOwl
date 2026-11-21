package io.github.crayonl.rebornowl;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/** 只提醒实际异常；版本和模块安装身份仅用于让旧的忽略选择失效。 */
public final class CompatibilityNotice {
    private static final Set<String> SHOWN = new HashSet<>();
    static String modulePath;

    private CompatibilityNotice() {}

    static void onResume(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) { return; }
        String details = FeatureDiagnostics.problems();
        // 只有「已开启但当前不可用」的功能才值得打扰用户；技术细节已在 FeatureDiagnostics 里写进日志。
        String user = FeatureDiagnostics.userProblems();
        if (details.isEmpty() || user.isEmpty()) {
            if (!details.isEmpty()) {
                Logx.i("功能检测：存在结构异常但相关开关未开启，不打扰用户\n" + details);
            }
            return;
        }
        try {
            PackageInfo host = activity.getPackageManager().getPackageInfo(Targets.HOST_PKG, 0);
            String identity = host.versionName + ":" + host.versionCode + ":" + modulePath;
            String fingerprint = fingerprint(identity, details);
            if (SHOWN.contains(fingerprint)
                    || fingerprint.equals(Config.getStr(Config.P_IGNORED_PROBLEM, ""))) { return; }
            SettingsInjector.showCompatibilityNotice(activity, user, fingerprint);
            SHOWN.add(fingerprint);
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            Logx.w("功能检测：无法读取应用信息或显示提示", error);
        }
    }

    static String fingerprint(String identity, String problems) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((identity + "\n" + problems).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte part : digest) { value.append(String.format(java.util.Locale.ROOT, "%02x", part & 0xff)); }
            return value.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法计算异常标识", error);
        }
    }
}
