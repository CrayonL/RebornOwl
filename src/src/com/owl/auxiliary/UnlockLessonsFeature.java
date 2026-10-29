package com.owl.auxiliary;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 解锁关卡。
 *
 * <p>按 {@code RetainedObjectTrackingStartupTask} 特征串定位「启动任务」类，hook 其构造器，
 * 并在构造完成后把 boolean 字段置为 true。
 *
 * <p><b>待实机确认</b>：本实现把该类的全部 boolean 实例字段置 true（等价于「放行」），
 * 并在日志中打印被改写的字段名，便于在真机核对。
 */
public class UnlockLessonsFeature extends Feature {

    @Override
    public String key() {
        return Config.K_UNLOCK;
    }

    @Override
    public String label() {
        return "解锁关卡";
    }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        Class<?> c = Locator.byKey(Targets.S_RETAINED_TASK, lpp.classLoader);
        if (c == null) {
            throw new IllegalStateException("解锁关卡：未定位启动任务类");
        }
        if (!hasBooleanState(c)) {
            throw new UnsupportedOperationException("解锁关卡：当前目标没有可改写的布尔状态，"
                    + "本版本未适配，未安装 Hook：" + c.getName());
        }
        Logx.i("解锁关卡：命中 " + c.getName());
        installHooks(c);
    }

    private static void installHooks(Class<?> c) {
        XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                Object[] args = param.args;
                if (args == null) {
                    return;
                }
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof Boolean) {
                        args[i] = Boolean.TRUE;
                    }
                }
            }

            @Override
            public void afterHookedMethod(MethodHookParam param) {
                Object self = param.thisObject;
                if (self == null) {
                    return;
                }
                for (Field f : self.getClass().getDeclaredFields()) {
                    if (f.getType() != boolean.class || Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        f.setBoolean(self, true);
                        Logx.i("解锁关卡：置真字段 " + f.getName());
                    } catch (Throwable t) {
                        Logx.w("解锁关卡：置字段失败 " + f.getName(), t);
                    }
                }
            }
        });
    }

    static boolean hasBooleanState(Class<?> target) {
        for (Field field : target.getDeclaredFields()) {
            if (field.getType() == boolean.class && !Modifier.isStatic(field.getModifiers())) {
                return true;
            }
        }
        for (Constructor<?> ctor : target.getDeclaredConstructors()) {
            for (Class<?> type : ctor.getParameterTypes()) {
                if (type == boolean.class || type == Boolean.class) {
                    return true;
                }
            }
        }
        return false;
    }
}
