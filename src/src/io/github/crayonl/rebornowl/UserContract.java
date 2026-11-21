package io.github.crayonl.rebornowl;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static io.github.crayonl.rebornowl.DexIndex.*;

/** hasMax 按实际序列化字段表定位；其余构造器只修改唯一的会员枚举参数。 */
final class UserContract {
    private static final int MASK_BITS = Integer.SIZE;

    static Map<Constructor<?>, int[]> parameters(Class<?> user, Class<?> tier) {
        List<String> fields = serializedFields(user.getName());
        int masks = (fields.size() + MASK_BITS - 1) / MASK_BITS;
        int level = requiredIndex(fields, "subscriberLevel") + masks;
        int max = requiredIndex(fields, "hasMax") + masks;
        Map<Constructor<?>, int[]> result = new LinkedHashMap<>();
        int serialized = 0;
        for (Constructor<?> ctor : user.getDeclaredConstructors()) {
            Class<?>[] types = ctor.getParameterTypes();
            int tierIndex = uniqueIndex(types, tier);
            if (tierIndex < 0) { continue; }
            int maxIndex = -1;
            if (types.length == fields.size() + masks) {
                for (int i = 0; i < masks; i++) {
                    if (types[i] != int.class) { throw new IllegalStateException("User 序列化掩码类型不符"); }
                }
                if (tierIndex != level || types[max] != boolean.class) { throw new IllegalStateException("User 序列化字段参数不符"); }
                maxIndex = max;
                serialized++;
            }
            result.put(ctor, new int[]{tierIndex, maxIndex});
        }
        if (serialized != 1) { throw new IllegalStateException("User 序列化构造器不唯一"); }
        return result;
    }

    private static int uniqueIndex(Class<?>[] types, Class<?> type) {
        int result = -1;
        for (int i = 0; i < types.length; i++) {
            if (types[i] != type) { continue; }
            if (result >= 0) { throw new IllegalStateException("会员枚举参数不唯一"); }
            result = i;
        }
        return result;
    }

    private static int requiredIndex(List<String> fields, String name) {
        int index = fields.indexOf(name);
        if (index < 0 || index != fields.lastIndexOf(name)) { throw new IllegalStateException("序列化字段缺失或重复：" + name); }
        return index;
    }

    static List<String> serializedFields(String model) {
        List<String> result = null;
        for (String owner : findClassesContaining(model)) {
            List<DexCode> methods = DexCode.methods(owner);
            boolean serializer = methods.stream().anyMatch(m -> "deserialize".equals(m.name)
                    && invokesConstructor(m, "L" + model.replace('.', '/') + ";"));
            if (!serializer) { continue; }
            for (DexCode method : methods) {
                if (!"<clinit>".equals(method.name)) { continue; }
                List<String> fields = descriptorFields(method, model);
                if (result != null) { throw new IllegalStateException("User 序列化描述不唯一"); }
                result = fields;
            }
        }
        if (result == null) { throw new IllegalStateException("User 序列化描述缺失"); }
        return result;
    }

    private static boolean invokesConstructor(DexCode method, String owner) {
        for (int p : method.instructions()) {
            if (!DexCode.isInvoke(u16(method.dex, p) & 255)) { continue; }
            DexCode called = method.invoked(p);
            if (owner.equals(called.owner) && "<init>".equals(called.name)) { return true; }
        }
        return false;
    }

    private static List<String> descriptorFields(DexCode method, String model) {
        Object[] registers = new Object[u16(method.dex, method.code)];
        List<String> names = new ArrayList<>();
        String descriptor = null;
        int count = -1;
        for (int p : method.instructions()) {
            int unit = u16(method.dex, p), op = unit & 255;
            if (readConstant(method, p, registers)) { continue; }
            if (!DexCode.isInvoke(op)) { continue; }
            DexCode called = method.invoked(p);
            int[] arguments = method.arguments(p);
            if ("<init>".equals(called.name) && arguments.length == 4 && model.equals(registers[arguments[1]])) {
                descriptor = called.owner;
                count = (Integer) registers[arguments[3]];
            }
            if (called.owner.equals(descriptor) && Arrays.equals(called.parameters, new String[]{"Ljava/lang/String;", "Z"})) {
                Object value = registers[arguments[1]];
                if (!(value instanceof String)) { throw new IllegalStateException("序列化字段名不是常量"); }
                names.add((String) value);
            }
        }
        if (count != names.size() || count <= 0) { throw new IllegalStateException("序列化字段数量与描述不符"); }
        return names;
    }

    private static boolean readConstant(DexCode method, int p, Object[] registers) {
        int unit = u16(method.dex, p), op = unit & 255;
        if (op == 0x1a || op == 0x1b) {
            registers[unit >>> 8] = stringAt(method.dex, op == 0x1a ? u16(method.dex, p + 2) : u32(method.dex, p + 2)); return true;
        }
        if (op == 0x12) { registers[(unit >>> 8) & 15] = (short) unit >> 12; return true; }
        if (op == 0x13) { registers[unit >>> 8] = (int) (short) u16(method.dex, p + 2); return true; }
        if (op == 0x14) { registers[unit >>> 8] = u32(method.dex, p + 2); return true; }
        if (op >= 1 && op <= 9) {
            registers[DexCode.moveTarget(method.dex, p)] = registers[DexCode.moveSource(method.dex, p)]; return true;
        }
        if (op >= 0x27 && op <= 0x3d) { throw new IllegalStateException("序列化描述含条件分支"); }
        return false;
    }
}
