package io.github.crayonl.rebornowl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** 弹层同步计算时使用路径副本，定位仍读取该次计算的原始当前关卡。 */
final class UnlockPopupData {
    private static final int KEEP_UNIT_INDEX = 1;
    private static final int UNIT_COPY_ARGUMENT_COUNT = 4;
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
        sectionId = Locator.uniqueField(section, String.class);
        sectionIndex = Locator.uniqueField(section, int.class);
        units = Locator.uniqueField(section, vector);
        levels = Locator.uniqueField(unit, vector);
        unitCopy = unitCopy(unit, vector);
        with = vector.getMethod("with", int.class, Object.class);
    }

    private static Constructor<?> sectionConstructor(Class<?> section, Class<?> vector)
            throws NoSuchMethodException {
        Constructor<?> result = null;
        List<Class<?>> expected = java.util.Arrays.asList(int.class, String.class, vector);
        for (Constructor<?> ctor : section.getConstructors()) {
            List<Class<?>> types = java.util.Arrays.asList(ctor.getParameterTypes());
            if (types.size() != expected.size() || !types.containsAll(expected)) { continue; }
            if (result != null) { throw new NoSuchMethodException("路径阶段构造参数不唯一"); }
            result = ctor;
        }
        if (result == null) { throw new NoSuchMethodException("路径阶段构造参数不匹配"); }
        return result;
    }

    private static Method unitCopy(Class<?> unit, Class<?> vector) throws NoSuchMethodException {
        Method result = null;
        for (Method method : unit.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length != UNIT_COPY_ARGUMENT_COUNT) { continue; }
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != unit
                    || types[1].isPrimitive()
                    || !java.util.Arrays.equals(types, new Class<?>[]{unit, types[1], vector, int.class})) { continue; }
            Locator.uniqueField(unit, types[1]);
            if (result != null) { throw new NoSuchMethodException("路径单元副本方法不唯一"); }
            result = method;
        }
        if (result == null) { throw new NoSuchMethodException("路径单元副本方法缺失"); }
        result.setAccessible(true);
        return result;
    }

    static void check(ClassLoader loader) throws ReflectiveOperationException {
        DexCode popup = popupCode();
        if (directLevelIndex(popup, loader) >= 0) { return; }
        Class<?> section = SemanticTypes.coursePathSection(loader);
        Class<?> vector = SemanticTypes.pathVector(loader, section);
        unit(loader, section, vector);
        activeLevel(section, loader);
        popup(loader);
        sectionFilter(loader, section);
    }

    private static Class<?> unit(ClassLoader loader, Class<?> section, Class<?> vector) {
        return Locator.byKeyContract(loader, Targets.S_PATH_UNIT, type -> new UnlockPopupData(section, type, vector));
    }

    private static DexCode popupCode() {
        DexCode result = null;
        for (String owner : DexIndex.findClassesContaining(Targets.S_PATH_POPUP)) {
            for (DexCode method : DexCode.methods(owner)) {
                if (!method.hasString(Targets.S_PATH_POPUP)) { continue; }
                if (result != null) { throw new IllegalStateException("路径弹层计算方法不唯一"); }
                result = method;
            }
        }
        if (result == null) { throw new IllegalStateException("路径弹层计算方法缺失"); }
        return result;
    }

    private static Method popup(ClassLoader loader) throws ReflectiveOperationException { return popupCode().reflect(loader); }

    private static int directLevelIndex(DexCode popup, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> level = Locator.byKeyContract(loader, Targets.S_PATH_LEVEL,
                type -> Locator.uniqueField(type, Class.forName(Targets.C_PATH_LEVEL_STATE, false, loader)));
        if (java.util.Arrays.asList(popup.parameters).contains(DexCode.descriptor(level))) {
            Field state = Locator.uniqueField(level, Class.forName(Targets.C_PATH_LEVEL_STATE, false, loader));
            int index = DexParameters.read(popup, DexCode.descriptor(level) + ":" + state.getName());
            if (!popup.parameters[index].equals(DexCode.descriptor(level))) { throw new IllegalStateException("弹层关卡参数类型错误"); }
            return index;
        }
        if (!"invokeSuspend".equals(popup.name) || !java.util.Arrays.equals(popup.parameters, new String[]{"Ljava/lang/Object;"})) {
            throw new IllegalStateException("弹层既没有关卡参数，也不是已验证的同步计算入口");
        }
        return -1;
    }

    private static Method sectionFilter(ClassLoader loader, Class<?> section) {
        Class<?> treatment = Locator.byKeyContract(loader, Targets.S_PATH_TREATMENT,
                type -> type.getConstructor(java.util.ArrayList.class, java.util.ArrayList.class));
        String descriptor = "L" + section.getName().replace('.', '/') + ";";
        String[] names = DexIndex.findClassesWithMethod(null, descriptor, descriptor).stream()
                .map(Locator::descToName).toArray(String[]::new);
        Class<?> type = Locator.byContract(loader, names, candidate -> filterMethod(candidate, section, treatment));
        return filterMethod(type, section, treatment);
    }

    private static Method filterMethod(Class<?> type, Class<?> section, Class<?> treatment) {
        Method found = null;
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] args = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers()) || args.length != 3
                    || args[0] != section || method.getReturnType() != section
                    || !args[1].getName().equals("com.duolingo.data.course.Subject")
                    || args[2] != treatment) { continue; }
            if (found != null) { throw new IllegalStateException("路径阶段过滤方法不唯一"); }
            found = method;
        }
        if (found == null) { throw new IllegalStateException("路径阶段过滤方法缺失"); }
        return found;
    }

    private static Method activeLevel(Class<?> section, ClassLoader loader) {
        Class<?> level = Locator.byKeyContract(loader, Targets.S_PATH_LEVEL,
                type -> Locator.uniqueField(type, Class.forName(Targets.C_PATH_LEVEL_STATE, false, loader)));
        Method result = null;
        for (Method method : section.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != level
                    || method.getParameterCount() != 0) { continue; }
            if (result != null) { throw new IllegalStateException("当前关卡读取方法不唯一"); }
            result = method;
        }
        if (result == null) { throw new IllegalStateException("当前关卡读取方法缺失"); }
        return result;
    }

    static void install(ClassLoader loader, UnlockLessonsFeature.SessionCopy copier)
            throws ReflectiveOperationException {
        DexCode popup = popupCode();
        int index = directLevelIndex(popup, loader);
        if (index >= 0) {
            XposedBridge.hookMethod(popup.reflect(loader), new DiagnosticHook(Config.K_UNLOCK) {
                private final java.util.concurrent.atomic.AtomicBoolean reported = new java.util.concurrent.atomic.AtomicBoolean();
                @Override public void before(MethodHookParam param) throws Throwable {
                    Object[] rewritten = UnlockLessonsFeature.rewriteLevel(param.args, copier, index);
                    if (rewritten != param.args && reported.compareAndSet(false, true)) {
                        Logx.i("解锁关卡：点击弹层关卡副本已实际生成，当前关卡参数保持原值");
                    }
                    param.args = rewritten;
                }
            });
            return;
        }
        Class<?> section = SemanticTypes.coursePathSection(loader);
        Class<?> vector = SemanticTypes.pathVector(loader, section);
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
        Method activeLevel = activeLevel(section, loader);
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
        Class<?>[] types = sectionConstructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int index = 0; index < types.length; index++) {
            args[index] = types[index] == int.class ? sectionIndex.getInt(section)
                    : types[index] == String.class ? sectionId.get(section) : result;
        }
        return sectionConstructor.newInstance(args);
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
