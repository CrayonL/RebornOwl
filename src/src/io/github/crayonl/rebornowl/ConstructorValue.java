package io.github.crayonl.rebornowl;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.Map;
import de.robv.android.xposed.XposedBridge;

/** 多个功能共享语义参数定位；每次只改指定参数，保留其余值。 */
final class ConstructorValue {
    final String property;
    final Class<?> type;
    final Object value;

    ConstructorValue(String property, Class<?> type, Object value) {
        this.property = property;
        this.type = type;
        this.value = value;
    }

    Map<Constructor<?>, Argument> resolve(ClassLoader loader, String marker) {
        Class<?> model = ModelContract.model(loader, marker, property, type);
        Map<Constructor<?>, Argument> result = new LinkedHashMap<>();
        for (Map.Entry<Constructor<?>, Integer> entry : ModelContract.parameters(model, property, type).entrySet()) {
            result.put(entry.getKey(), new Argument(entry.getValue(), type, value));
        }
        return result;
    }

    static void install(String key, Map<Constructor<?>, Argument> rules) {
        for (Map.Entry<Constructor<?>, Argument> entry : rules.entrySet()) {
            Argument rule = entry.getValue();
            XposedBridge.hookMethod(entry.getKey(), new DiagnosticHook(key) {
                @Override
                public void before(MethodHookParam param) { param.args = rule.rewrite(param.args); }
            });
        }
    }

    static final class Argument {
        final int index;
        private final Class<?> type;
        private final Object value;

        Argument(int index, Class<?> type, Object value) {
            this.index = index;
            this.type = type;
            this.value = value;
        }

        boolean supports(Class<?>[] types) { return index >= 0 && index < types.length && types[index] == type; }

        Object[] rewrite(Object[] args) {
            if (args == null || index < 0 || index >= args.length
                    || args[index] == null && type.isPrimitive()
                    || args[index] != null && !value.getClass().isInstance(args[index])) {
                throw new IllegalArgumentException("目标参数类型不匹配，下标=" + index);
            }
            Object[] result = args.clone();
            result[index] = value;
            return result;
        }
    }
}
