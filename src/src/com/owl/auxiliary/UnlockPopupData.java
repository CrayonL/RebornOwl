package com.owl.auxiliary;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** 弹层同步计算时使用路径副本，定位仍读取该次计算的原始当前关卡。 */
final class UnlockPopupData {
    private static final int KEEP_UNIT_INDEX = 1;
    private static final String PREVIOUS_SCOPE = "owl.unlock.popup.previous";
    private final Constructor<?> sectionConstructor;
    private final Field sectionId;
    private final Field sectionIndex;
    private final Field units;
    private final Field levels;
    private final Method unitCopy;
    private final Method with;

    UnlockPopupData(Class<?> section, Class<?> unit, Class<?> vector) throws ReflectiveOperationException {
        sectionConstructor = section.getConstructor(int.class, String.class, vector);
        sectionId = section.getField("a");
        sectionIndex = section.getField("b");
        units = section.getField("c");
        levels = unit.getField("b");
        unitCopy = unit.getMethod("a", unit, unit.getField("a").getType(), vector, int.class);
        with = vector.getMethod("with", int.class, Object.class);
    }

    static void install(ClassLoader loader, UnlockLessonsFeature.SessionCopy copier)
            throws ReflectiveOperationException {
        Class<?> section = Class.forName(Targets.C_PATH_SECTION, false, loader);
        Class<?> unit = Class.forName(Targets.C_PATH_UNIT, false, loader);
        Class<?> vector = Class.forName(Targets.C_PATH_VECTOR, false, loader);
        UnlockPopupData data = new UnlockPopupData(section, unit, vector);
        ThreadLocal<Scope> scope = new ThreadLocal<>();
        Class<?> popup = Class.forName(Targets.C_NEW_PATH_POPUP, false, loader);
        XposedBridge.hookMethod(popup.getDeclaredMethod("invokeSuspend", Object.class), new XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                param.setObjectExtra(PREVIOUS_SCOPE, scope.get());
                scope.set(new Scope());
            }
            @Override
            public void afterHookedMethod(MethodHookParam param) {
                Scope previous = (Scope) param.getObjectExtra(PREVIOUS_SCOPE);
                if (previous == null) { scope.remove(); } else { scope.set(previous); }
            }
        });
        Method activeLevel = section.getMethod("b");
        hookAnchor(activeLevel, scope);
        Class<?> filter = Class.forName(Targets.C_PATH_SECTION_FILTER, false, loader);
        Method method = filter.getMethod("b", section,
                Class.forName("com.duolingo.data.course.Subject", false, loader),
                Class.forName("o8j", false, loader));
        data.hookSection(method, activeLevel, new PopupContext(scope, copier));
    }

    private void hookSection(Method method, Method activeLevel, PopupContext context) {
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            private boolean reported;
            @Override
            public void afterHookedMethod(MethodHookParam param) throws Throwable {
                Scope current = context.scope.get();
                if (current == null || param.hasThrowable()) { return; }
                Object original = param.getResult();
                Object anchor = activeLevel.invoke(original);
                Object copy = copySection(original, context.copier);
                current.copy = copy;
                current.anchor = anchor;
                param.setResult(copy);
                if (copy != original && !reported) {
                    reported = true;
                    Logx.i("解锁关卡：新版弹层副本已实际生成，当前关卡保持原值");
                }
            }
        });
    }

    private static void hookAnchor(Method method, ThreadLocal<Scope> scope) {
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                Scope current = scope.get();
                if (current != null && current.copy == param.thisObject) { param.setResult(current.anchor); }
            }
        });
    }

    Object copySection(Object section, UnlockLessonsFeature.SessionCopy copier)
            throws ReflectiveOperationException {
        List<?> original = (List<?>) units.get(section);
        Object result = original;
        for (int index = 0; index < original.size(); index++) {
            Object unit = original.get(index);
            Object copy = copyUnit(unit, copier);
            if (copy != unit) { result = with.invoke(result, index, copy); }
        }
        return result == original ? section : sectionConstructor.newInstance(
                sectionIndex.getInt(section), sectionId.get(section), result);
    }

    private Object copyUnit(Object unit, UnlockLessonsFeature.SessionCopy copier)
            throws ReflectiveOperationException {
        List<?> original = (List<?>) levels.get(unit);
        Object result = original;
        for (int index = 0; index < original.size(); index++) {
            Object level = original.get(index);
            Object copy = copier.copyLevel(level);
            if (copy != level) { result = with.invoke(result, index, copy); }
        }
        return result == original ? unit : unitCopy.invoke(null, unit, null, result, KEEP_UNIT_INDEX);
    }

    private static final class Scope {
        Object copy;
        Object anchor;
    }

    private static final class PopupContext {
        final ThreadLocal<Scope> scope;
        final UnlockLessonsFeature.SessionCopy copier;
        PopupContext(ThreadLocal<Scope> scope, UnlockLessonsFeature.SessionCopy copier) {
            this.scope = scope;
            this.copier = copier;
        }
    }
}
