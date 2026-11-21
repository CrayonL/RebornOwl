package io.github.crayonl.rebornowl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static io.github.crayonl.rebornowl.DexIndex.*;

/** 从实际字符串拼接与字段赋值定位参数，不依赖混淆名、参数个数或位置。 */
final class ModelContract {
    private static final String BUILDER = "Ljava/lang/StringBuilder;";
    private static final Object SELF = new Object();
    private static final int MAX_HELPER_DEPTH = 8;

    private ModelContract() {}

    static Map<Constructor<?>, Integer> parameters(Class<?> model, String property, Class<?> type) {
        String field = field(model, property, type).getName();
        Map<Constructor<?>, Integer> result = new LinkedHashMap<>();
        for (DexCode method : DexCode.methods(DexCode.descriptor(model))) {
            if (!"<init>".equals(method.name)) { continue; }
            Integer index = storedParameter(method, field);
            if (index == null) { continue; }
            Constructor<?> ctor = constructor(model, method.parameters);
            if (ctor.getParameterTypes()[index] != type) { throw new IllegalStateException("字段与参数类型不一致：" + property); }
            result.put(ctor, index);
        }
        if (result.isEmpty()) { throw new IllegalStateException("未找到直接写入参数：" + model.getName() + "." + property); }
        return result;
    }

    static Class<?> model(ClassLoader loader, String marker, String property, Class<?> type) {
        return Locator.byKeyContract(loader, marker, candidate -> parameters(candidate, property, type));
    }

    static Constructor<?> constructor(Class<?> model, String[] parameters) {
        for (Constructor<?> ctor : model.getDeclaredConstructors()) {
            String[] actual = Arrays.stream(ctor.getParameterTypes()).map(DexCode::descriptor).toArray(String[]::new);
            if (Arrays.equals(actual, parameters)) { return ctor; }
        }
        throw new IllegalStateException("DEX 与反射构造器不一致：" + model.getName());
    }

    static Field field(Class<?> model, String property, Class<?> type) {
        String name = fieldName(DexCode.descriptor(model), property);
        try {
            Field field = model.getDeclaredField(name);
            if (field.getType() != type) { throw new IllegalStateException("字段类型不匹配：" + property); }
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("字段定位失败：" + property, error); }
    }

    static String fieldName(String owner, String property) {
        List<Object> parts = renderParts(owner);
        String name = null;
        for (int i = 1; i < parts.size(); i++) {
            if (!(parts.get(i - 1) instanceof String)) { continue; }
            String label = (String) parts.get(i - 1);
            if (!label.endsWith(", " + property + "=") && !label.endsWith("(" + property + "=")) { continue; }
            String qualified = singleField(parts.get(i));
            if (qualified == null) { throw new IllegalStateException("属性未直接引用字段：" + property); }
            if (!qualified.startsWith(owner + ":") || name != null) {
                throw new IllegalStateException("字段语义不唯一：" + property);
            }
            name = qualified.substring(qualified.indexOf(':') + 1);
        }
        if (name == null) { throw new IllegalStateException("字段语义缺失：" + property); }
        return name;
    }

    private static String singleField(Object value) {
        if (value instanceof FieldToken) { return ((FieldToken) value).name; }
        if (!(value instanceof Parts)) { return null; }
        String result = null;
        for (Object part : ((Parts) value).values) {
            String field = singleField(part);
            if (field == null) { continue; }
            if (result != null) { throw new IllegalStateException("属性包含多个字段"); }
            result = field;
        }
        return result;
    }

    static List<Object> renderParts(String owner) {
        for (DexCode method : DexCode.methods(owner)) {
            if (!"toString".equals(method.name) || method.parameters.length != 0) { continue; }
            Object rendered = trace(method, new Object[]{SELF}, 0);
            if (rendered instanceof Parts) { return ((Parts) rendered).values; }
        }
        throw new IllegalStateException("数据模型的字符串契约缺失：" + owner);
    }

    private static Object trace(DexCode method, Object[] arguments, int depth) {
        if (depth > MAX_HELPER_DEPTH) { throw new IllegalStateException("字符串辅助方法递归过深"); }
        Object[] registers = new Object[u16(method.dex, method.code)];
        int start = registers.length - u16(method.dex, method.code + 2);
        if (arguments.length != registers.length - start) { throw new IllegalStateException("DEX 调用寄存器不匹配"); }
        System.arraycopy(arguments, 0, registers, start, arguments.length);
        Object returned = null;
        for (int p : method.instructions()) {
            int unit = u16(method.dex, p), op = unit & 255;
            if (op == 0x0e) { return null; }
            if (op >= 0x0f && op <= 0x11) { return registers[unit >>> 8]; }
            if (op >= 0x0a && op <= 0x0c) { registers[unit >>> 8] = returned; continue; }
            if (DexCode.isInvoke(op)) { returned = invoke(method, p, new Frame(registers, depth)); continue; }
            traceValue(method, p, registers);
        }
        throw new IllegalStateException("字符串方法没有返回：" + method.owner);
    }

    private static void traceValue(DexCode method, int p, Object[] registers) {
        byte[] dex = method.dex;
        int unit = u16(dex, p), op = unit & 255;
        if (op >= 1 && op <= 9) { registers[DexCode.moveTarget(dex, p)] = registers[DexCode.moveSource(dex, p)]; return; }
        if (op == 0x1a || op == 0x1b) {
            registers[unit >>> 8] = stringAt(dex, op == 0x1a ? u16(dex, p + 2) : u32(dex, p + 2)); return;
        }
        if (op == 0x22 && BUILDER.equals(DexCode.type(dex, u16(dex, p + 2)))) {
            registers[unit >>> 8] = new Parts(); return;
        }
        if (op >= 0x52 && op <= 0x58 && registers[unit >>> 12] == SELF) {
            registers[(unit >>> 8) & 15] = new FieldToken(method.field(u16(dex, p + 2))); return;
        }
        if (op == 0x12) { registers[(unit >>> 8) & 15] = unit >> 12; return; }
        if (op == 0x13) { registers[unit >>> 8] = (int) (short) u16(dex, p + 2); return; }
        if (op == 0) { return; }
        throw new IllegalStateException("不能证明字段语义，字符串指令=" + Integer.toHexString(op) + " " + method.owner);
    }

    private static Object invoke(DexCode caller, int position, Frame frame) {
        DexCode method = caller.invoked(position);
        int[] words = caller.arguments(position);
        Object[] arguments = new Object[words.length];
        for (int i = 0; i < words.length; i++) { arguments[i] = frame.registers[words[i]]; }
        if (!BUILDER.equals(method.owner)) { return trace(method.definition(), arguments, frame.depth + 1); }
        if (arguments.length == 0 || !(arguments[0] instanceof Parts)) { throw new IllegalStateException("字符串构建器不匹配"); }
        Parts builder = (Parts) arguments[0];
        if ("toString".equals(method.name)) { return builder; }
        if (!"<init>".equals(method.name) && !"append".equals(method.name)) { throw new IllegalStateException("未知字符串方法"); }
        if (method.parameters.length == 1) { builder.values.add(arguments[1]); }
        return builder;
    }

    static Integer storedParameter(DexCode method, String field) {
        return DexParameters.stored(method, field);
    }

    private static final class Parts { final List<Object> values = new ArrayList<>(); }
    private static final class FieldToken {
        final String name;
        FieldToken(String name) { this.name = name; }
    }
    private static final class Frame {
        final Object[] registers;
        final int depth;
        Frame(Object[] registers, int depth) { this.registers = registers; this.depth = depth; }
    }
}
