package com.owl.auxiliary;

import android.content.pm.ApplicationInfo;

/** 在 ART 上用真实 APK 检查全部功能；不安装 Hook、不改开关、不进入课程。 */
public final class HostFeatureContractTest {
    public static void main(String[] args) throws Throwable {
        if (args.length != 1) { throw new IllegalArgumentException("需要宿主 APK 路径"); }
        Logx.setMirror(false);
        ApplicationInfo info = new ApplicationInfo();
        info.sourceDir = args[0];
        DexIndex.ensureLoaded(null, info);
        // 与真实注入一样隔离宿主和框架，避免两者默认包中的混淆类名碰撞。
        ClassLoader loader = new dalvik.system.PathClassLoader(args[0],
                ClassLoader.getSystemClassLoader().getParent());
        de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam lpp =
                (de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam)
                de.robv.android.xposed.XposedHelpers.newInstance(
                        de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam.class,
                        new java.util.concurrent.CopyOnWriteArraySet<de.robv.android.xposed.callbacks.XC_LoadPackage>());
        lpp.classLoader = loader;
        int failed = 0;
        for (Feature feature : FeatureManager.all()) {
            try {
                feature.check(lpp);
                System.out.println("PASS: " + feature.label());
            } catch (Throwable error) {
                failed++;
                System.out.println("FAIL: " + feature.label());
                error.printStackTrace(System.out);
            }
        }
        if (failed != 0) { throw new AssertionError("真实 APK 结构检查失败项：" + failed); }
        System.out.println("ALL_FEATURE_CONTRACTS_PASS（结构检查，不代表实际业务效果）");
    }
}
