package com.owl.auxiliary;

import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.libxposed.api.XposedModuleInterface;

public final class XposedEntryTest {
    private static final String PROCESS = "com.duolingo:practice";
    private static final ClassLoader READY_LOADER = new ClassLoader() {};
    private static final ClassLoader DEFAULT_LOADER = new ClassLoader() {};
    private static final ApplicationInfo APP_INFO = new ApplicationInfo();

    public static void main(String[] args) {
        CaptureEntry entry = new CaptureEntry();
        entry.onModuleLoaded(new XposedModuleInterface.ModuleLoadedParam() {
            public boolean isSystemServer() { return false; }
            public String getProcessName() { return PROCESS; }
        });
        verifyTarget(entry, true);
        verifyTarget(entry, false);
        entry.captured = null;
        entry.onPackageReady(ready("com.example.unrelated", true));
        check(entry.captured == null, "不得向其他应用注册 Hook");
        System.out.println("PASS XposedEntry: final loader, process, package flags, unrelated package");
    }

    private static void verifyTarget(CaptureEntry entry, boolean first) {
        entry.onPackageReady(ready("com.duolingo", first));
        XC_LoadPackage.LoadPackageParam actual = entry.captured;
        check(actual != null, "必须传递宿主加载回调");
        check("com.duolingo".equals(actual.packageName), "保留宿主包名");
        check(PROCESS.equals(actual.processName), "保留实际子进程名称");
        check(actual.classLoader == READY_LOADER, "必须使用最终而非默认 ClassLoader");
        check(actual.isFirstApplication == first, "保留首包标记");
        check(actual.appInfo == APP_INFO, "必须传递原始 ApplicationInfo");
    }

    private static XposedModuleInterface.PackageReadyParam ready(String name, boolean first) {
        return new XposedModuleInterface.PackageReadyParam() {
            public String getPackageName() { return name; }
            public ApplicationInfo getApplicationInfo() { return APP_INFO; }
            public boolean isFirstPackage() { return first; }
            public ClassLoader getDefaultClassLoader() { return DEFAULT_LOADER; }
            public ClassLoader getClassLoader() { return READY_LOADER; }
            public AppComponentFactory getAppComponentFactory() { return null; }
        };
    }

    private static void check(boolean valid, String message) {
        if (!valid) { throw new AssertionError(message); }
    }

    private static final class CaptureEntry extends XposedEntry {
        XC_LoadPackage.LoadPackageParam captured;

        @Override
        public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
            captured = param;
        }
    }
}
