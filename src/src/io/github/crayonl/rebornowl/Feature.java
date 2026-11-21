package io.github.crayonl.rebornowl;

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

    /** 检查所需目标结构；不得注册 Hook、修改宿主数据或更改开关。 */
    public abstract void check(XC_LoadPackage.LoadPackageParam lpp) throws Throwable;

    /** 装载 hook。抛出的异常由 {@link FeatureManager} 捕获并记录。 */
    public abstract void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable;

    /** 是否默认开启。 */
    public boolean defaultOn() {
        return false;
    }

    /**
     * 是否在设置面板里出现开关行。
     *
     * <p>调试模式是隐藏功能：入口只有标题猫头鹰的连击手势，不占设置行。
     */
    public boolean settingsVisible() {
        return true;
    }

    /**
     * 是否无视配置始终启用。
     *
     * <p>设置面板里没有开关的功能必须返回 true，否则用户既看不到开关、也没法把它打开。
     */
    public boolean alwaysOn() {
        return false;
    }
}
