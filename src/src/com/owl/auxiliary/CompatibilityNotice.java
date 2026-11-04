package com.owl.auxiliary;

import android.app.Activity;
import android.content.pm.PackageManager;

/** 每个宿主进程检查一次；只提醒未适配版本，不改变功能开关。 */
public final class CompatibilityNotice {
    private static boolean checked;

    private CompatibilityNotice() {}

    static void onResume(Activity activity) {
        if (checked || activity.isFinishing() || activity.isDestroyed()) { return; }
        try {
            String version = activity.getPackageManager().getPackageInfo(Targets.HOST_PKG, 0).versionName;
            if (!isSupported(version)) {
                SettingsInjector.showCompatibilityNotice(activity, message(version));
                Logx.i("版本检测：已提示未适配版本 " + version);
            } else {
                Logx.i("版本检测：已支持 " + version);
            }
            checked = true;
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            Logx.w("版本检测：无法读取版本或显示提示", error);
        }
    }

    static boolean isSupported(String version) {
        return Targets.HOST_CLOCKS.containsKey(version);
    }

    static String message(String version) {
        String display = version == null || version.isEmpty() ? "未知版本" : version;
        return "你正在使用多邻国 " + display
                + "，重生猫头鹰尚未适配此版本，部分功能可能无法正常使用。请等待后续适配更新。";
    }
}
