package com.owl.auxiliary;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Constructor;

/** 将宿主课程长度设为零，由宿主自己的结束判断及结算流程处理。 */
public class SkipFeature extends Feature {
    private static final int SETTINGS_ARGUMENT_COUNT = 11;
    private static final int MAX_LENGTH_INDEX = 3;
    private static final int NO_CHALLENGES = 0;

    @Override
    public String key() {
        return Config.K_SKIP;
    }

    @Override
    public String label() {
        return "直接通关";
    }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        Class<?> settings = Locator.byKey(Targets.S_SESSION_DEBUG, lpp.classLoader);
        if (settings == null) { throw new IllegalStateException("直接通关：未定位课程调试设置"); }
        Constructor<?> target = null;
        for (Constructor<?> constructor : settings.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length != SETTINGS_ARGUMENT_COUNT || types[MAX_LENGTH_INDEX] != Integer.class) {
                continue;
            }
            if (target != null) { throw new IllegalStateException("直接通关：课程设置构造器不唯一"); }
            target = constructor;
        }
        if (target == null) { throw new IllegalStateException("直接通关：课程设置构造器不匹配"); }
        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                param.args = rewriteSession(param.args);
            }
        });
        Logx.i("直接通关：已接入宿主 maxSessionLength=0，课程结束与结算交由宿主执行");
    }

    static Object[] rewriteSession(Object[] args) {
        if (args == null || args.length != SETTINGS_ARGUMENT_COUNT
                || (args[MAX_LENGTH_INDEX] != null && !(args[MAX_LENGTH_INDEX] instanceof Integer))) {
            throw new IllegalArgumentException("直接通关：课程长度参数不匹配");
        }
        Object[] result = args.clone();
        result[MAX_LENGTH_INDEX] = NO_CHALLENGES;
        return result;
    }
}
