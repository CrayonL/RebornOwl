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
        sectionConstructor = sectionConstructor(section, vector);
        sectionId = section.getField("a");
        sectionIndex = section.getField("b");
        units = section.getField("c");
        levels = unit.getField("b");
        unitCopy = unit.getMethod("a", unit, unit.getField("a").getType(), vector, int.class);
        with = vector.getMethod("with", int.class, Object.class);
    }

    private static Constructor<?> sectionConstructor(Class<?> section, Class<?> vector)
            throws NoSuchMethodException {
        for (Constructor<?> ctor : section.getConstructors()) {
            Class<?>[] types = ctor.getParameterTypes();
            if (java.util.Arrays.equals(types, new Class<?>[]{int.class, String.class, vector})
                    || java.util.Arrays.equals(types, new Class<?>[]{int.class, vector, String.class})) { return ctor; }
        }
        throw new NoSuchMethodException("路径阶段构造参数不匹配");
    }

    static void check(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> section = Class.forName(Targets.C_PATH_SECTION, false, loader);
        Class<?> vector = Class.forName(Targets.C_PATH_VECTOR, false, loader);
        unit(loader, section, vector);
        section.getMethod("b");
        popup(loader);
        sectionFilter(loader, section);
    }

    private static Class<?> unit(ClassLoader loader, Class<?> section, Class<?> vector) {
        return Locator.byContract(loader, Targets.C_PATH_UNITS, type -> new UnlockPopupData(section, type, vector));
    }

    private static Method popup(ClassLoader loader) {
        String[] names = DexIndex.findClassesContaining(Targets.S_PATH_POPUP).stream()
                .map(Locator::descToName).distinct().toArray(String[]::new);
        Class<?> type = Locator.byContract(loader, names, candidate -> candidate.getDeclaredMethod("invokeSuspend", Object.class));
        try { return type.getDeclaredMethod("invokeSuspend", Object.class); }
        catch (NoSuchMethodException error) { throw new IllegalStateException(error); }
    }

    private static Method sectionFilter(ClassLoader loader, Class<?> section) {
        Class<?> type = Locator.byContract(loader, Targets.C_PATH_SECTION_FILTERS,
                candidate -> filterMethod(candidate, section));
        return filterMethod(type, section);
    }

    private static Method filterMethod(Class<?> type, Class<?> section) {
        Method found = null;
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] args = method.getParameterTypes();
            if (args.length != 3 || args[0] != section || method.getReturnType() != section
                    || !args[1].getName().equals("com.duolingo.data.course.Subject")
                    || !(args[2].getName().equals("o8j") || args[2].getName().equals("udi"))) { continue; }
            if (found != null) { throw new IllegalStateException("路径阶段过滤方法不唯一"); }
            found = method;
        }
        if (found == null) { throw new IllegalStateException("路径阶段过滤方法缺失"); }
        return found;
    }

    static void install(ClassLoader loader, UnlockLessonsFeature.SessionCopy copier)
            throws ReflectiveOperationException {
        Class<?> section = Class.forName(Targets.C_PATH_SECTION, false, loader);
        Class<?> vector = Class.forName(Targets.C_PATH_VECTOR, false, loader);
        Class<?> unit = unit(loader, section, vector);
        UnlockPopupData data = new UnlockPopupData(section, unit, vector);
        ThreadLocal<Scope> scope = new ThreadLocal<>();
        XposedBridge.hookMethod(popup(loader), new DiagnosticHook(Config.K_UNLOCK) {
            @Override
            public void before(MethodHookParam param) {
                param.setObjectExtra(PREVIOUS_SCOPE, scope.get());
                scope.set(new Scope());
            }
            @Override
            public void after(MethodHookParam param) {
                Scope previous = (Scope) param.getObjectExtra(PREVIOUS_SCOPE);
                if (previous == null) { scope.remove(); } else { scope.set(previous); }
            }
        });
        Method activeLevel = section.getMethod("b");
        hookAnchor(activeLevel, scope);
        Method method = sectionFilter(loader, section);
        data.hookSection(method, activeLevel, new PopupContext(scope, copier));
    }

    private void hookSection(Method method, Method activeLevel, PopupContext context) {
        XposedBridge.hookMethod(method, new DiagnosticHook(Config.K_UNLOCK) {
            private boolean reported;
            @Override
            public void after(MethodHookParam param) throws Throwable {
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
        XposedBridge.hookMethod(method, new DiagnosticHook(Config.K_UNLOCK) {
            @Override
            public void before(MethodHookParam param) {
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
        if (result == original) { return section; }
        return sectionConstructor.getParameterTypes()[1] == String.class
                ? sectionConstructor.newInstance(sectionIndex.getInt(section), sectionId.get(section), result)
                : sectionConstructor.newInstance(sectionIndex.getInt(section), result, sectionId.get(section));
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
