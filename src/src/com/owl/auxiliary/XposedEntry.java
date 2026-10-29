package com.owl.auxiliary;

import java.util.concurrent.CopyOnWriteArraySet;

import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.libxposed.api.XposedModule;

/**
 * 静态作用域入口；等待宿主最终 ClassLoader 就绪后调用现有业务 Hook。
 */
public class XposedEntry extends XposedModule {
    private String processName;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!Targets.HOST_PKG.equals(param.getPackageName())) {
            return;
        }
        // shortcut: target API 101 允许旧 Hook API；升级到 102 时须迁移业务 Hook。
        // 官方编译 API 不公开此构造方法，由当前框架解析实际参数类型。
        XC_LoadPackage.LoadPackageParam lpp = (XC_LoadPackage.LoadPackageParam)
                XposedHelpers.newInstance(XC_LoadPackage.LoadPackageParam.class,
                        new CopyOnWriteArraySet<XC_LoadPackage>());
        lpp.packageName = param.getPackageName();
        lpp.processName = processName;
        lpp.appInfo = param.getApplicationInfo();
        lpp.classLoader = param.getClassLoader();
        lpp.isFirstApplication = param.isFirstPackage();
        handleLoadPackage(lpp);
    }

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (lpp == null || lpp.packageName == null) {
            return;
        }
        if (!Targets.HOST_PKG.equals(lpp.packageName)) {
            if (Targets.MODULE_PKG.equals(lpp.packageName)) {
                Logx.i("加载于模块自身进程: " + lpp.packageName);
            }
            return;
        }
        Logx.i("handleLoadPackage 命中宿主: " + lpp.packageName
                + " ver=" + Targets.hostVersion(lpp.appInfo)
                + " process=" + lpp.processName);
        try {
            String dataDir = lpp.appInfo == null ? null : lpp.appInfo.dataDir;
            Config.init(dataDir);
            Config.load();
            DexIndex.ensureLoaded(lpp.classLoader, lpp.appInfo);
            FeatureManager.applyAll(lpp);
            SettingsInjector.install(lpp);
        } catch (Throwable t) {
            Logx.w("初始化失败", t);
        }
    }
}
