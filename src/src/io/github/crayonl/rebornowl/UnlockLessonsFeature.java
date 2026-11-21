package io.github.crayonl.rebornowl;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 为路径显示及点击会话创建可学习副本，原始路径保留真实进度和当前位置。 */
public class UnlockLessonsFeature extends Feature {
    private static final int COPY_ARGUMENT_COUNT = 5;
    private static final int KEEP_COMPLETED_SESSIONS = 1 << 2;
    private static final int KEEP_CLIENT_DATA = 1 << 4;

    @Override
    public String key() { return Config.K_UNLOCK; }

    @Override
    public String label() { return "解锁关卡"; }

    @Override
    public void check(XC_LoadPackage.LoadPackageParam lpp) throws ReflectiveOperationException {
        Class<?> level = level(lpp.classLoader);
        Class<?> state = SemanticTypes.pathLevelState(lpp.classLoader, level);
        if (level == null || !state.isEnum()) { throw new IllegalStateException("路径节点或状态枚举缺失"); }
        findStateField(level, state);
        findCopyMethod(level, state);
        state.getField("ACTIVE");
        converter(lpp.classLoader, level);
        sessionTargets(ModelContract.model(lpp.classLoader, Targets.S_PATH_SESSION_STATE, "level", level), level);
        UnlockPopupData.check(lpp.classLoader);
    }

    private static Class<?> level(ClassLoader loader) {
        return Locator.byKeyContract(loader, Targets.S_PATH_LEVEL, type -> {
            Class<?> state = SemanticTypes.pathLevelState(loader, type);
            findStateField(type, state);
            findCopyMethod(type, state);
        });
    }

    private static Class<?> converter(ClassLoader loader, Class<?> level) {
        return Locator.byKeyContract(loader, Targets.S_PATH_CONVERTER, type -> findNodeConverter(type, level));
    }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        Class<?> level = level(lpp.classLoader);
        Class<?> state = SemanticTypes.pathLevelState(lpp.classLoader, level);
        Class<?> converter = converter(lpp.classLoader, level);
        if (level == null || converter == null || state == null || !state.isEnum()) {
            throw new IllegalStateException("解锁关卡：未定位新版路径节点或状态枚举");
        }
        SessionCopy copier = new SessionCopy(level, state);
        Class<?> session = ModelContract.model(lpp.classLoader, Targets.S_PATH_SESSION_STATE, "level", level);
        hookSessions(session, level, copier);
        UnlockPopupData.install(lpp.classLoader, copier);
        Method node = findNodeConverter(converter, level);
        final int levelIndex = nodeLevelIndex(node, level);
        XposedBridge.hookMethod(node, new DiagnosticHook(Config.K_UNLOCK) {
            private final java.util.concurrent.atomic.AtomicBoolean reported =
                    new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public void before(MethodHookParam param) throws Throwable {
                Object[] rewritten = rewriteLevel(param.args, copier, levelIndex);
                if (rewritten != param.args && reported.compareAndSet(false, true)) {
                    Logx.i("解锁关卡：新版路径节点副本已实际生成");
                }
                param.args = rewritten;
            }
        });
        Logx.i("解锁关卡：已接入新版路径节点；原始进度和定位数据保持不变");
    }

    private static void hookSessions(Class<?> session, Class<?> level, SessionCopy copier) {
        for (java.util.Map.Entry<Constructor<?>, Integer> entry : sessionTargets(session, level).entrySet()) {
            int index = entry.getValue();
            XposedBridge.hookMethod(entry.getKey(), new DiagnosticHook(Config.K_UNLOCK) {
                @Override
                public void before(MethodHookParam param) throws Throwable {
                    param.args = rewriteLevel(param.args, copier, index);
                }
            });
        }
    }

    private static java.util.Map<Constructor<?>, Integer> sessionTargets(Class<?> session, Class<?> level) {
        if (session == null) { throw new IllegalStateException("解锁关卡：点击会话模型缺失"); }
        return ModelContract.parameters(session, "level", level);
    }

    static Method findNodeConverter(Class<?> converter, Class<?> level) {
        java.util.List<String> nodes = DexIndex.findClassesContaining("Node(key=");
        Method target = null;
        for (Method method : converter.getDeclaredMethods()) {
            if (!nodes.contains(DexCode.descriptor(method.getReturnType()))
                    || !java.util.Arrays.asList(method.getParameterTypes()).contains(level)) { continue; }
            nodeLevelIndex(method, level);
            if (target != null) { throw new IllegalStateException("解锁关卡：节点转换入口不唯一"); }
            target = method;
        }
        if (target == null) { throw new IllegalStateException("解锁关卡：节点转换入口缺失"); }
        return target;
    }

    private static int nodeLevelIndex(Method method, Class<?> level) {
        String[] signature = java.util.Arrays.stream(method.getParameterTypes()).map(DexCode::descriptor).toArray(String[]::new);
        String field = DexCode.descriptor(level) + ":" + ModelContract.fieldName(DexCode.descriptor(level), "id");
        for (DexCode code : DexCode.methods(DexCode.descriptor(method.getDeclaringClass()))) {
            if (!code.name.equals(method.getName()) || !java.util.Arrays.equals(code.parameters, signature)) { continue; }
            int index = DexParameters.entryRead(code, field);
            if (method.getParameterTypes()[index] != level) { throw new IllegalStateException("节点标识参数类型不匹配"); }
            return index;
        }
        throw new IllegalStateException("节点转换器 DEX 方法缺失");
    }

    static Object[] rewriteLevel(Object[] args, SessionCopy copier, int index)
            throws ReflectiveOperationException {
        if (args == null || index < 0 || index >= args.length) { throw new IllegalArgumentException("关卡参数缺失"); }
        Object original = args[index];
        Object unlocked = copier.copyLevel(original);
        if (unlocked == original) { return args; }
        Object[] result = args.clone();
        result[index] = unlocked;
        return result;
    }

    static final class SessionCopy {
        private final Field stateField;
        private final Method copy;
        private final Enum<?> active;

        SessionCopy(Class<?> level, Class<?> state) throws ReflectiveOperationException {
            stateField = findStateField(level, state);
            copy = findCopyMethod(level, state);
            active = (Enum<?>) state.getField("ACTIVE").get(null);
        }

        Object copyLevel(Object level) throws ReflectiveOperationException {
            if (level == null || !stateField.getDeclaringClass().isInstance(level)) {
                throw new IllegalArgumentException("解锁关卡：操作目标不是关卡模型");
            }
            Enum<?> state = (Enum<?>) stateField.get(level);
            if (state == null) { throw new IllegalArgumentException("解锁关卡：状态为空"); }
            if (!"LOCKED".equals(state.name()) && !"UNLOCKED".equals(state.name())) { return level; }
            // 宿主 copy 默认参数位掩码保留已学次数与课程数据，不写回原始路径。
            return copy.invoke(null, level, active, 0, null,
                    KEEP_COMPLETED_SESSIONS | KEEP_CLIENT_DATA);
        }
    }

    private static Field findStateField(Class<?> level, Class<?> state) {
        Field target = null;
        for (Field field : level.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != state) { continue; }
            if (target != null) { throw new IllegalStateException("解锁关卡：状态字段不唯一"); }
            target = field;
        }
        if (target == null) { throw new IllegalStateException("解锁关卡：状态字段缺失"); }
        target.setAccessible(true);
        return target;
    }

    private static Method findCopyMethod(Class<?> level, Class<?> state) {
        Method target = null;
        for (Method method : level.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != level
                    || types.length != COPY_ARGUMENT_COUNT || types[0] != level
                    || types[1] != state || types[2] != int.class || types[4] != int.class) { continue; }
            if (target != null) { throw new IllegalStateException("解锁关卡：副本方法不唯一"); }
            target = method;
        }
        if (target == null) { throw new IllegalStateException("解锁关卡：副本方法缺失"); }
        target.setAccessible(true);
        return target;
    }
}
