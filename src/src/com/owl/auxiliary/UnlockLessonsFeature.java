package com.owl.auxiliary;

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
    private static final int NODE_ARGUMENT_COUNT = 23;
    private static final int NODE_LEVEL_INDEX = 2;
    private static final int PREVIOUS_LEVEL_INDEX = 3;
    private static final int SESSION_ARGUMENT_COUNT = 11;
    private static final int DEFAULT_SESSION_ARGUMENT_COUNT = 9;

    @Override
    public String key() { return Config.K_UNLOCK; }

    @Override
    public String label() { return "解锁关卡"; }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        Class<?> level = Locator.byKey(Targets.S_PATH_LEVEL, lpp.classLoader);
        Class<?> state = Locator.byName(Targets.C_PATH_LEVEL_STATE, lpp.classLoader);
        Class<?> converter = Locator.byName(Targets.C_NEW_PATH_CONVERTER, lpp.classLoader);
        if (level == null || converter == null || state == null || !state.isEnum()) {
            throw new IllegalStateException("解锁关卡：未定位新版路径节点或状态枚举");
        }
        SessionCopy copier = new SessionCopy(level, state);
        Class<?> session = Locator.byKey(Targets.S_PATH_SESSION_STATE, lpp.classLoader);
        hookSessions(session, level, copier);
        UnlockPopupData.install(lpp.classLoader, copier);
        XposedBridge.hookMethod(findNodeConverter(converter, level), new XC_MethodHook() {
            private final java.util.concurrent.atomic.AtomicBoolean reported =
                    new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object[] rewritten = rewriteNode(param.args, copier);
                if (rewritten != param.args && reported.compareAndSet(false, true)) {
                    Logx.i("解锁关卡：新版路径节点副本已实际生成");
                }
                param.args = rewritten;
            }
        });
        Logx.i("解锁关卡：已接入新版路径节点；原始进度和定位数据保持不变");
    }

    private static void hookSessions(Class<?> session, Class<?> level, SessionCopy copier) {
        if (session == null) { throw new IllegalStateException("解锁关卡：点击会话模型缺失"); }
        java.util.List<Constructor<?>> targets = new java.util.ArrayList<>();
        for (Constructor<?> constructor : session.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if ((types.length == SESSION_ARGUMENT_COUNT || types.length == DEFAULT_SESSION_ARGUMENT_COUNT)
                    && types[0] == level) { targets.add(constructor); }
        }
        if (targets.size() != 2) { throw new IllegalStateException("解锁关卡：点击会话构造契约不匹配"); }
        XC_MethodHook callback = new XC_MethodHook() {
            private final java.util.concurrent.atomic.AtomicBoolean reported =
                    new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object[] rewritten = rewriteSession(param.args, copier);
                if (rewritten != param.args && reported.compareAndSet(false, true)) {
                    Logx.i("解锁关卡：点击会话副本已实际生成");
                }
                param.args = rewritten;
            }
        };
        for (Constructor<?> constructor : targets) { XposedBridge.hookMethod(constructor, callback); }
    }

    static Method findNodeConverter(Class<?> converter, Class<?> level) {
        Method target = null;
        for (Method method : converter.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers()) || types.length != NODE_ARGUMENT_COUNT
                    || types[0] != converter || types[NODE_LEVEL_INDEX] != level
                    || types[PREVIOUS_LEVEL_INDEX] != level || method.getReturnType() == void.class) { continue; }
            if (target != null) { throw new IllegalStateException("解锁关卡：新版节点转换入口不唯一"); }
            target = method;
        }
        if (target == null) { throw new IllegalStateException("解锁关卡：新版节点转换入口缺失"); }
        return target;
    }

    static Object[] rewriteNode(Object[] args, SessionCopy copier) throws ReflectiveOperationException {
        if (args == null || args.length != NODE_ARGUMENT_COUNT) {
            throw new IllegalArgumentException("解锁关卡：新版节点参数不匹配");
        }
        return rewriteLevel(args, copier, NODE_LEVEL_INDEX);
    }

    static Object[] rewriteSession(Object[] args, SessionCopy copier) throws ReflectiveOperationException {
        if (args == null || (args.length != SESSION_ARGUMENT_COUNT
                && args.length != DEFAULT_SESSION_ARGUMENT_COUNT)) {
            throw new IllegalArgumentException("解锁关卡：点击会话参数不匹配");
        }
        return rewriteLevel(args, copier, 0);
    }

    private static Object[] rewriteLevel(Object[] args, SessionCopy copier, int index)
            throws ReflectiveOperationException {
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
