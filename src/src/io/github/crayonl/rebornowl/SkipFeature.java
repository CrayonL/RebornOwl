package io.github.crayonl.rebornowl;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 将宿主课程长度设为零，由宿主自己的结束判断及结算流程处理。 */
public class SkipFeature extends Feature {
    private static final ConstructorValue LENGTH = new ConstructorValue("maxSessionLength", Integer.class, 0);

    @Override public String key() { return Config.K_SKIP; }
    @Override public String label() { return "直接通关"; }
    @Override public void check(XC_LoadPackage.LoadPackageParam lpp) {
        LENGTH.resolve(lpp.classLoader, Targets.S_SESSION_DEBUG);
    }
    @Override public void apply(XC_LoadPackage.LoadPackageParam lpp) {
        ConstructorValue.install(key(), LENGTH.resolve(lpp.classLoader, Targets.S_SESSION_DEBUG));
        Logx.i("直接通关：已接入宿主 maxSessionLength=0，课程结束与结算交由宿主执行");
    }
}
