package io.github.crayonl.rebornowl;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;

/** 参数边界及真实宿主的课程结束判断；不创建课程、不发送网络请求。 */
public final class SkipFeatureTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--host".equals(args[0])) {
            verifyHost(args[1], args[2]);
            return;
        }
        Method rewrite = ConstructorValue.Argument.class.getDeclaredMethod("rewrite", Object[].class);
        ConstructorValue.Argument rule = new ConstructorValue.Argument(3, Integer.class, 0);
        rewrite.setAccessible(true);
        Object[] original = {false, Collections.emptySet(), false, null, false, false,
                Collections.emptyList(), false, null, false, ""};
        Object[] result = (Object[]) rewrite.invoke(rule, (Object) original);
        require(Integer.valueOf(0).equals(result[3]), "课程长度应为零");
        require(original[3] == null, "不得修改输入数组");
        for (int i = 0; i < original.length; i++) {
            if (i != 3) { require(result[i] == original[i], "其他设置必须保留"); }
        }
        original[3] = 7;
        require(Integer.valueOf(0).equals(((Object[]) rewrite.invoke(rule, (Object) original))[3]),
                "已有课程长度也应替换");
        expectRejected(rule, rewrite, null);
        expectRejected(rule, rewrite, new Object[3]);
        original[3] = "0";
        expectRejected(rule, rewrite, original);
        System.out.println("PASS SkipFeature: zero length, input isolation, nullable and invalid arguments");
    }

    private static void verifyHost(String version, String apk) throws Exception {
        ClassLoader loader = new dalvik.system.PathClassLoader(apk,
                ClassLoader.getSystemClassLoader().getParent());
        android.content.pm.ApplicationInfo info = new android.content.pm.ApplicationInfo();
        info.sourceDir = apk; DexIndex.ensureLoaded(null, info);
        String[] sample;
        switch (version) {
            case "6.103.6": sample = new String[]{"ep00", "c050", "18"}; break;
            case "6.104.4": sample = new String[]{"k210", "re50", "16"}; break;
            case "6.104.5-china": sample = new String[]{"cgz", "co00", "18"}; break;
            case "6.106.5-china": sample = new String[]{"nq00", "o330", "18"}; break;
            default: throw new IllegalArgumentException("尚未独立核对该版本的课程结束纯函数");
        }
        Class<?> settings = Class.forName(sample[0], true, loader);
        Object defaults = null;
        for (java.lang.reflect.Field field : settings.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == settings) {
                require(defaults == null, "默认课程设置单例必须唯一"); defaults = field.get(null);
            }
        }
        require(defaults != null, "默认课程设置单例缺失");
        java.util.Map<Constructor<?>, Integer> targets = ModelContract.parameters(settings, "maxSessionLength", Integer.class);
        require(targets.size() == 1, "主构造器必须唯一");
        Constructor<?> constructor = targets.keySet().iterator().next();
        Object[] values = settingsValues(defaults, constructor);
        int lengthIndex = targets.get(constructor);
        Class<?> predicate = Class.forName(sample[1], true, loader);
        boolean byteTag = version.equals("6.106.5-china");
        Object tag = byteTag ? (Object) Byte.valueOf(sample[2]) : Integer.valueOf(sample[2]);
        Object endCheck = predicate.getConstructor(byteTag ? byte.class : int.class).newInstance(tag);
        Method apply = predicate.getMethod("apply", Object.class, Object.class, Object.class);
        values[lengthIndex] = null;
        require(Boolean.FALSE.equals(apply.invoke(endCheck, 0, 20, constructor.newInstance(values))),
                "未改动时零答题不得判定结束");
        values[lengthIndex] = 0;
        require(Boolean.TRUE.equals(apply.invoke(endCheck, 0, 20, constructor.newInstance(values))),
                "真实宿主应在长度零时进入结束条件");
        values[lengthIndex] = 1;
        require(Boolean.FALSE.equals(apply.invoke(endCheck, 0, 20, constructor.newInstance(values))),
                "长度一时未答题不得结束");
        require(Boolean.TRUE.equals(apply.invoke(endCheck, 1, 20, constructor.newInstance(values))),
                "长度一时已答一题应结束");
        System.out.println("HOST_SKIP_PREDICATE_PASS: " + version + ", no session or network operation");
    }

    private static Object[] settingsValues(Object defaults, Constructor<?> constructor) throws Exception {
        Object[] values = new Object[constructor.getParameterCount()];
        boolean[] assigned = new boolean[values.length];
        String[] types = java.util.Arrays.stream(constructor.getParameterTypes()).map(DexCode::descriptor).toArray(String[]::new);
        DexCode code = DexCode.methods(DexCode.descriptor(defaults.getClass())).stream()
                .filter(m -> m.name.equals("<init>") && java.util.Arrays.equals(m.parameters, types)).findFirst().get();
        for (java.lang.reflect.Field field : defaults.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) { continue; }
            Integer index = DexParameters.stored(code, field.getName());
            if (index == null) { continue; }
            field.setAccessible(true); values[index] = field.get(defaults); assigned[index] = true;
        }
        for (boolean found : assigned) { require(found, "必须完整保留默认设置"); }
        return values;
    }

    private static void expectRejected(Object rule, Method method, Object[] values) throws Exception {
        try {
            method.invoke(rule, (Object) values);
        } catch (InvocationTargetException expected) {
            require(expected.getCause() instanceof IllegalArgumentException, "必须明确拒绝无效参数");
            return;
        }
        throw new AssertionError("接受了无效参数");
    }

    private static void require(boolean valid, String message) {
        if (!valid) { throw new AssertionError(message); }
    }
}
