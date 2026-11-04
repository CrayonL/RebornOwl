"""运行生产版本检测；受控 Android 边界验证提示频率、精确匹配和失败重试。"""
import argparse
from pathlib import Path
import subprocess
import tempfile

STUBS = {
    'Activity.java': '''package android.app;
public class Activity {
    public boolean finishing, destroyed;
    public boolean isFinishing() { return finishing; }
    public boolean isDestroyed() { return destroyed; }
    public android.content.pm.PackageManager getPackageManager() { return new android.content.pm.PackageManager(); }
}''',
    'PackageInfo.java': '''package android.content.pm;
public class PackageInfo { public String versionName; }''',
    'PackageManager.java': '''package android.content.pm;
public class PackageManager {
    public static String version;
    public static boolean fail;
    public static int reads;
    public static class NameNotFoundException extends Exception {}
    public PackageInfo getPackageInfo(String name, int flags) throws NameNotFoundException {
        if (!name.equals("com.duolingo")) { throw new AssertionError("必须读取多邻国版本"); }
        reads++;
        if (fail) { throw new NameNotFoundException(); }
        PackageInfo info = new PackageInfo(); info.versionName = version; return info;
    }
}''',
    'SettingsInjector.java': '''package com.owl.auxiliary;
public class SettingsInjector {
    public static int shown;
    public static String message;
    public static boolean fail;
    static android.app.Dialog showCompatibilityNotice(android.app.Activity act, String text) {
        if (fail) { throw new IllegalStateException("测试窗口失败"); }
        shown++; message = text; return null;
    }
}''',
    'CompatibilityNoticeProbe.java': '''package com.owl.auxiliary;
import android.app.Activity;
import android.content.pm.PackageManager;
public class CompatibilityNoticeProbe {
    public static void main(String[] args) throws Exception {
        Logx.setMirror(false);
        check(Targets.HOST_CLOCKS.size() <= 5, "滚动兼容表最多五个版本");
        verify("6.103.6", false); verify("6.104.4", false);
        for (String version : new String[]{"6.103.5", "6.104.5", "6.104.40", "6.104.4-beta", "", null}) {
            verify(version, true);
        }
        verifyLifecycle(); verifyFailure(false); verifyFailure(true);
        System.out.println("COMPATIBILITY_NOTICE_PASS: 精确版本、未知版本、每进程一次、生命周期、错误重试");
    }
    private static void verify(String version, boolean warning) throws Exception {
        reset(version);
        Activity activity = new Activity();
        CompatibilityNotice.onResume(activity); CompatibilityNotice.onResume(activity);
        check(PackageManager.reads == 1, "重复恢复页面不重复查询");
        check(SettingsInjector.shown == (warning ? 1 : 0), "只对未适配版本提示一次");
        if (warning) {
            String display = version == null || version.isEmpty() ? "未知版本" : version;
            check(SettingsInjector.message.equals("你正在使用多邻国 " + display
                    + "，重生猫头鹰尚未适配此版本，部分功能可能无法正常使用。请等待后续适配更新。"), "使用已确认文案");
        }
    }
    private static void verifyLifecycle() throws Exception {
        reset("6.104.5");
        Activity activity = new Activity(); activity.finishing = true;
        CompatibilityNotice.onResume(activity);
        activity.finishing = false; activity.destroyed = true;
        CompatibilityNotice.onResume(activity);
        check(PackageManager.reads == 0 && SettingsInjector.shown == 0, "无效窗口不消耗提示机会");
        CompatibilityNotice.onResume(new Activity());
        check(SettingsInjector.shown == 1, "后续有效页面仍可提示");
    }
    private static void verifyFailure(boolean window) throws Exception {
        reset("6.104.5");
        SettingsInjector.fail = window; PackageManager.fail = !window;
        CompatibilityNotice.onResume(new Activity());
        check(SettingsInjector.shown == 0, "查询或显示失败不能假报成功");
        SettingsInjector.fail = false; PackageManager.fail = false;
        CompatibilityNotice.onResume(new Activity());
        check(SettingsInjector.shown == 1, "恢复后允许重新提示");
    }
    private static void reset(String version) throws Exception {
        java.lang.reflect.Field checked = CompatibilityNotice.class.getDeclaredField("checked");
        checked.setAccessible(true); checked.setBoolean(null, false);
        PackageManager.version = version; PackageManager.reads = 0; PackageManager.fail = false;
        SettingsInjector.shown = 0; SettingsInjector.message = null; SettingsInjector.fail = false;
    }
    private static void check(boolean valid, String reason) {
        if (!valid) { throw new AssertionError(reason); }
    }
}''',
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
                        '-cp', classpath, 'com.owl.auxiliary.CompatibilityNoticeProbe'], check=True, timeout=60)


if __name__ == '__main__':
    main()
