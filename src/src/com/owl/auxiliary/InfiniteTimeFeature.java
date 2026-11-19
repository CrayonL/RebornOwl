package com.owl.auxiliary;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.Map;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 限时练习时长设为 99 分钟；不修改课程长度或其他整数参数。 */
public class InfiniteTimeFeature extends Feature {
    private static final int GAME_TIME_SEC = 99 * 60;

    @Override public String key() { return Config.K_GAMETIME; }
    @Override public String label() { return "无限时间"; }
    @Override public void check(XC_LoadPackage.LoadPackageParam lpp) { targets(lpp.classLoader); }
    @Override public void apply(XC_LoadPackage.LoadPackageParam lpp) {
        ConstructorValue.install(key(), targets(lpp.classLoader));
    }

    private static Map<Constructor<?>, ConstructorValue.Argument> targets(ClassLoader loader) {
        Map<Constructor<?>, ConstructorValue.Argument> result = new LinkedHashMap<>();
        result.putAll(new ConstructorValue("initialXpRampSessionTime", int.class, GAME_TIME_SEC)
                .resolve(loader, Targets.S_TIMED_MULTI));
        result.putAll(new ConstructorValue("initialSessionTime", int.class, GAME_TIME_SEC)
                .resolve(loader, Targets.S_TIMED_MATCH));
        return result;
    }
}
