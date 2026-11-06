package com.owl.auxiliary;

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
        String problems = FeatureDiagnostics.problems();
        if (problems.isEmpty()) { return; }
        try {
            PackageInfo host = activity.getPackageManager().getPackageInfo(Targets.HOST_PKG, 0);
            String identity = host.versionName + ":" + host.versionCode + ":" + modulePath;
            String fingerprint = fingerprint(identity, problems);
            if (SHOWN.contains(fingerprint)
                    || fingerprint.equals(Config.getStr(Config.P_IGNORED_PROBLEM, ""))) { return; }
            SettingsInjector.showCompatibilityNotice(activity, problems, fingerprint);
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
