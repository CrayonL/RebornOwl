package com.owl.auxiliary;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 功能基类：每个功能实现 {@link #apply}，逐开关编排。
 * 单个功能失败只记日志，不影响其它功能。
 */
public abstract class Feature {

    /** 对应的配置开关键。 */
    public abstract String key();

    /** 中文名称（设置页展示）。 */
    public abstract String label();

    /** 装载 hook。抛出的异常由 {@link FeatureManager} 捕获并记录。 */
    public abstract void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable;

    /** 是否默认开启。 */
    public boolean defaultOn() {
        return false;
    }
}
