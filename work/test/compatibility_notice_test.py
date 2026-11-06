"""执行生产功能检测和提醒状态机；Android 窗口边界使用受控替身。"""
import argparse
from pathlib import Path
import subprocess
import tempfile

STUBS = {
    'XC_MethodHook.java': """package de.robv.android.xposed;
public class XC_MethodHook {
    public static class MethodHookParam { public java.lang.reflect.Member method; }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}""",
    'Activity.java': """package android.app;
public class Activity {
    public boolean finishing, destroyed;
    public boolean isFinishing() { return finishing; }
    public boolean isDestroyed() { return destroyed; }
    public android.content.pm.PackageManager getPackageManager() { return new android.content.pm.PackageManager(); }
}""",
    'PackageInfo.java': """package android.content.pm;
public class PackageInfo { public String versionName; public int versionCode = 2494; }""",
    'PackageManager.java': """package android.content.pm;
public class PackageManager {
    public static String version = "6.104.5-china";
    public static boolean fail;
    public static class NameNotFoundException extends Exception {}
    public PackageInfo getPackageInfo(String name, int flags) throws NameNotFoundException {
        if (!name.equals("com.duolingo")) { throw new AssertionError("宿主包名错误"); }
        if (fail) { throw new NameNotFoundException(); }
        PackageInfo result = new PackageInfo(); result.versionName = version; return result;
    }
}""",
    'SettingsInjector.java': """package com.owl.auxiliary;
public class SettingsInjector {
    public static int shown;
    public static String message, fingerprint;
    public static boolean fail;
    static android.app.Dialog showCompatibilityNotice(android.app.Activity act, String text, String key) {
        if (fail) { throw new IllegalStateException("测试窗口失败"); }
        shown++; message = text; fingerprint = key; return null;
    }
}""",
    'CompatibilityNoticeProbe.java': r"""package com.owl.auxiliary;
import android.app.Activity;
import android.content.pm.PackageManager;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Field;
import java.util.*;
public class CompatibilityNoticeProbe {
    private static final Activity ACTIVITY = new Activity();
    private static final FakeFeature OFF = new FakeFeature("test_off");
    private static final FakeFeature ON = new FakeFeature("test_on");
    public static void main(String[] args) throws Throwable {
        Logx.setMirror(false);
        Config.init(args[0]);
        FeatureManager.all().clear(); FeatureManager.all().add(OFF); FeatureManager.all().add(ON);
        verifyHealthy(); verifyDisabled(); verifySuppression(); verifyRetry(); verifyRuntime(); verifyWriteFailure();
        System.out.println("COMPATIBILITY_NOTICE_PASS: 全功能检测、关闭项无 Hook、无版本告警、持久忽略、新问题与更新重提、失败重试、回调异常");
    }
    private static void verifyHealthy() throws Throwable {
        reset(); FeatureManager.applyAll(null);
        check(OFF.checks == 1 && ON.checks == 1, "全部已有功能必须检查");
        check(OFF.applies == 0 && ON.applies == 1, "关闭项不得安装 Hook");
        for (String version : new String[]{"6.103.6", "6.104.5-china", "99.999.9", null}) {
            PackageManager.version = version; CompatibilityNotice.onResume(ACTIVITY);
        }
        check(SettingsInjector.shown == 0, "没有异常不能根据版本号弹窗");
        check(FeatureDiagnostics.summary().contains("结构检查通过，未开启"), "关闭与结构正常分别表达");
    }
    private static void verifyDisabled() throws Throwable {
        reset(); OFF.broken = true; FeatureManager.applyAll(null);
        CompatibilityNotice.onResume(ACTIVITY); CompatibilityNotice.onResume(ACTIVITY);
        check(SettingsInjector.shown == 1, "关闭功能的明确异常也必须提示，且同进程不重复");
        check(SettingsInjector.message.contains("test_off"), "包含实际异常功能");
        check(OFF.applies == 0 && ON.applies == 1, "检查失败不改变开关或阻断其他功能");
        check(FeatureDiagnostics.summary().contains("检测到异常，未开启"), "不得把关闭误报为跳过检测");
        Config.set(OFF.key(), true);
        check(FeatureDiagnostics.summary().contains("开关已更改，待重启"), "选择开关不等于已经加载");
    }
    private static void verifySuppression() throws Throwable {
        reset(); OFF.broken = true; FeatureManager.applyAll(null); CompatibilityNotice.onResume(ACTIVITY);
        String first = SettingsInjector.fingerprint;
        check(Config.suppressProblem(first), "保存用户忽略选择");
        // 重新从磁盘加载，不能仅依赖内存里的选择。
        ((Properties) field(Config.class, "P").get(null)).clear();
        field(Config.class, "sLoaded").setBoolean(null, false); Config.load();
        clearShown(); SettingsInjector.shown = 0; CompatibilityNotice.onResume(ACTIVITY);
        check(SettingsInjector.shown == 0, "同问题重启后不再提示");
        FeatureDiagnostics.failure(OFF.key(), "另一个异常", null); CompatibilityNotice.onResume(ACTIVITY);
        check(SettingsInjector.shown == 1, "新问题必须再次提示");
        check(Config.suppressProblem(SettingsInjector.fingerprint), "保存新问题");
        clearShown(); PackageManager.version = "6.105.1"; CompatibilityNotice.onResume(ACTIVITY);
        check(SettingsInjector.shown == 2, "更新宿主后重新提醒");
        check(Config.suppressProblem(SettingsInjector.fingerprint), "保存新版问题");
        clearShown(); CompatibilityNotice.modulePath = "/module/new/base.apk"; CompatibilityNotice.onResume(ACTIVITY);
        check(SettingsInjector.shown == 3, "更新模块后重新提醒");
    }
    private static void verifyRetry() throws Throwable {
        reset(); OFF.broken = true; FeatureManager.applyAll(null);
        ACTIVITY.finishing = true; CompatibilityNotice.onResume(ACTIVITY); ACTIVITY.finishing = false;
        ACTIVITY.destroyed = true; CompatibilityNotice.onResume(ACTIVITY); ACTIVITY.destroyed = false;
        PackageManager.fail = true; CompatibilityNotice.onResume(ACTIVITY); PackageManager.fail = false;
        SettingsInjector.fail = true; CompatibilityNotice.onResume(ACTIVITY); SettingsInjector.fail = false;
        check(SettingsInjector.shown == 0, "无效页面与显示失败不能消耗提示机会");
        CompatibilityNotice.onResume(ACTIVITY); check(SettingsInjector.shown == 1, "恢复后仍会提示");
    }
    private static void verifyRuntime() throws Throwable {
        reset(); FeatureManager.applyAll(null);
        RuntimeException original = new RuntimeException("callback");
        DiagnosticHook hook = new DiagnosticHook(ON.key()) {
            public void before(MethodHookParam param) { throw original; }
        };
        de.robv.android.xposed.XC_MethodHook.MethodHookParam param =
                new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
        param.method = CompatibilityNoticeProbe.class.getDeclaredMethod("verifyRuntime");
        try { hook.beforeHookedMethod(param); throw new AssertionError("异常被吞掉"); }
        catch (RuntimeException actual) { check(actual == original, "必须保留原异常"); }
        check(FeatureDiagnostics.hasProblems(ON.key()), "回调异常必须进入汇总");
    }
    private static void verifyWriteFailure() throws Throwable {
        Field path = field(Config.class, "sFile"); Object previous = path.get(null);
        String ignored = Config.getStr(Config.P_IGNORED_PROBLEM, "");
        path.set(null, ((java.io.File) previous).getParentFile());
        check(!Config.suppressProblem("cannot-save"), "写失败不可假报保存成功");
        check(ignored.equals(Config.getStr(Config.P_IGNORED_PROBLEM, "")), "写失败恢复内存偏好");
        path.set(null, previous);
    }
    private static void reset() throws Throwable {
        clearShown(); ((Map<?, ?>) field(FeatureDiagnostics.class, "RESULTS").get(null)).clear();
        field(FeatureManager.class, "sApplied").setBoolean(null, false);
        Config.setStr(Config.P_IGNORED_PROBLEM, null); Config.set(OFF.key(), false); Config.set(ON.key(), true);
        OFF.reset(); ON.reset(); SettingsInjector.shown = 0; SettingsInjector.fail = false;
        PackageManager.fail = false; PackageManager.version = "6.104.5-china";
        CompatibilityNotice.modulePath = "/module/old/base.apk";
    }
    private static void clearShown() throws Exception { ((Set<?>) field(CompatibilityNotice.class, "SHOWN").get(null)).clear(); }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean valid, String message) { if (!valid) { throw new AssertionError(message); } }
    private static final class FakeFeature extends Feature {
        final String key; int checks, applies; boolean broken;
        FakeFeature(String key) { this.key = key; }
        public String key() { return key; }
        public String label() { return key; }
        public void check(XC_LoadPackage.LoadPackageParam lpp) {
            checks++; if (broken) { throw new IllegalStateException("目标不存在"); }
        }
        public void apply(XC_LoadPackage.LoadPackageParam lpp) { applies++; }
        void reset() { checks = applies = 0; broken = false; }
    }
}""",
}



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    dependencies = [args.sdk / 'platforms/android-35/android.jar',
                    root / 'work/libxposed-api-101.0.1.jar', root / 'work/xposed-api.jar']
    with tempfile.TemporaryDirectory(prefix='compatibility-', dir=root / 'work') as temp:
        folder = Path(temp)
        classes = folder / 'classes'
        classes.mkdir()
        classpath = ';'.join(map(str, [classes] + dependencies))
        compiler = [str(args.jdk / 'bin/javac.exe'), '-J-Dstderr.encoding=UTF-8',
                    '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes)]
        subprocess.run(compiler + list(map(str, (root / 'src/src').rglob('*.java'))), check=True, timeout=60)
        for name, content in STUBS.items():
            (folder / name).write_text(content, encoding='utf-8')
        subprocess.run(compiler + [str(folder / name) for name in STUBS], check=True, timeout=60)
        subprocess.run([str(args.jdk / 'bin/java.exe'), '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                        '-cp', classpath, 'com.owl.auxiliary.CompatibilityNoticeProbe', str(folder)], check=True, timeout=60)


if __name__ == '__main__':
    main()
