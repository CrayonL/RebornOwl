package com.owl.auxiliary;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;

/** 参数边界及真实宿主的课程结束判断；不创建课程、不发送网络请求。 */
public final class SkipFeatureTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--host".equals(args[0])) {
            verifyHost();
            return;
        }
        Method rewrite = SkipFeature.class.getDeclaredMethod("rewriteSession", Object[].class);
        rewrite.setAccessible(true);
        Object[] original = {false, Collections.emptySet(), false, null, false, false,
                Collections.emptyList(), false, null, false, ""};
        Object[] result = (Object[]) rewrite.invoke(null, (Object) original);
        require(Integer.valueOf(0).equals(result[3]), "课程长度应为零");
        require(original[3] == null, "不得修改输入数组");
        for (int i = 0; i < original.length; i++) {
            if (i != 3) { require(result[i] == original[i], "其他设置必须保留"); }
        }
        original[3] = 7;
        require(Integer.valueOf(0).equals(((Object[]) rewrite.invoke(null, (Object) original))[3]),
                "已有课程长度也应替换");
        expectRejected(rewrite, null);
        expectRejected(rewrite, new Object[3]);
        original[3] = "0";
        expectRejected(rewrite, original);
        System.out.println("PASS SkipFeature: zero length, input isolation, nullable and invalid arguments");
    }

    private static void verifyHost() throws Exception {
        Class<?> settings = Class.forName("ep00");
        Object defaults = settings.getField("l").get(null);
        Object[] values = new Object[11];
        for (int i = 0; i < values.length; i++) {
            values[i] = settings.getField(String.valueOf((char) ('a' + i))).get(defaults);
        }
        Constructor<?> constructor = settings.getConstructors()[0];
        Class<?> predicate = Class.forName("c050");
        Object endCheck = predicate.getConstructor(int.class).newInstance(18);
        Method apply = predicate.getMethod("apply", Object.class, Object.class, Object.class);
        values[3] = null;
        require(Boolean.FALSE.equals(apply.invoke(endCheck, 0, 20, constructor.newInstance(values))),
                "未改动时零答题不得判定结束");
        values[3] = 0;
        require(Boolean.TRUE.equals(apply.invoke(endCheck, 0, 20, constructor.newInstance(values))),
                "真实宿主应在长度零时进入结束条件");
        values[3] = 1;
        require(Boolean.FALSE.equals(apply.invoke(endCheck, 0, 20, constructor.newInstance(values))),
                "长度一时未答题不得结束");
        require(Boolean.TRUE.equals(apply.invoke(endCheck, 1, 20, constructor.newInstance(values))),
                "长度一时已答一题应结束");
        System.out.println("HOST_SKIP_PREDICATE_PASS: real 6.103.6 classes, no session or network operation");
    }

    private static void expectRejected(Method method, Object[] values) throws Exception {
        try {
            method.invoke(null, (Object) values);
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
