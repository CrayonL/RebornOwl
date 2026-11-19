package com.owl.auxiliary;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import static com.owl.auxiliary.DexIndex.*;

/** 适配所需的 DEX 方法、寄存器及字段读写信息，不执行宿主代码。 */
final class DexCode {
    final byte[] dex;
    final String owner;
    final String name;
    final String returned;
    final String[] parameters;
    int access;
    int code;

    DexCode(byte[] dex, int index) {
        this.dex = dex;
        int offset = u32(dex, 0x5c) + index * 8;
        owner = type(dex, u16(dex, offset));
        name = stringAt(dex, u32(dex, offset + 4));
        int proto = u32(dex, 0x4c) + u16(dex, offset + 2) * 12;
        returned = type(dex, u32(dex, proto + 4));
        int list = u32(dex, proto + 8);
        parameters = new String[list == 0 ? 0 : u32(dex, list)];
        for (int i = 0; i < parameters.length; i++) { parameters[i] = type(dex, u16(dex, list + 4 + i * 2)); }
    }

    static String descriptor(Class<?> type) {
        if (type.isArray()) { return type.getName().replace('.', '/'); }
        if (!type.isPrimitive()) { return "L" + type.getName().replace('.', '/') + ";"; }
        return type == boolean.class ? "Z" : type == int.class ? "I" : type == byte.class ? "B"
                : type == short.class ? "S" : type == char.class ? "C" : type == long.class ? "J"
                : type == float.class ? "F" : type == double.class ? "D" : "V";
    }

    static String type(byte[] dex, int index) {
        return stringAt(dex, u32(dex, u32(dex, 0x44) + index * 4));
    }

    static List<DexCode> methods(String owner) {
        byte[] dex = definingDex(owner);
        for (int i = 0; i < u32(dex, 0x60); i++) {
            int base = u32(dex, 0x64) + i * 32;
            if (owner.equals(type(dex, u32(dex, base)))) { return readMethods(dex, u32(dex, base + 24)); }
        }
        throw new IllegalStateException("DEX 类方法缺失：" + owner);
    }

    private static List<DexCode> readMethods(byte[] dex, int offset) {
        List<DexCode> result = new ArrayList<>();
        if (offset == 0) { return result; }
        int[] counts = new int[4];
        for (int i = 0; i < counts.length; i++) {
            long value = uleb(dex, offset); counts[i] = (int) (value >>> 32); offset = (int) value;
        }
        for (int i = 0; i < (counts[0] + counts[1]) * 2; i++) { offset = (int) uleb(dex, offset); }
        for (int group = 2; group < 4; group++) {
            int index = 0;
            for (int i = 0; i < counts[group]; i++) {
                long value = uleb(dex, offset); index += (int) (value >>> 32); offset = (int) value;
                DexCode method = new DexCode(dex, index);
                value = uleb(dex, offset); method.access = (int) (value >>> 32); offset = (int) value;
                value = uleb(dex, offset); method.code = (int) (value >>> 32); offset = (int) value;
                result.add(method);
            }
        }
        return result;
    }

    DexCode definition() {
        for (DexCode method : methods(owner)) {
            if (method.name.equals(name) && method.returned.equals(returned)
                    && Arrays.equals(method.parameters, parameters)) { return method; }
        }
        throw new IllegalStateException("DEX 方法体缺失：" + owner + "." + name);
    }

    List<Integer> instructions() {
        if (code == 0) { throw new IllegalStateException("DEX 方法体为空：" + owner + "." + name); }
        List<Integer> result = new ArrayList<>();
        int end = code + 16 + u32(dex, code + 12) * 2;
        for (int p = code + 16; p < end; p += instructionUnits(dex, p) * 2) { result.add(p); }
        return result;
    }

    String field(int index) {
        int offset = u32(dex, 0x54) + index * 8;
        return type(dex, u16(dex, offset)) + ":" + stringAt(dex, u32(dex, offset + 4));
    }

    DexCode invoked(int position) { return new DexCode(dex, u16(dex, position + 2)); }

    boolean hasString(String text) {
        if (code == 0) { return false; }
        for (int p : instructions()) {
            int op = u16(dex, p) & 255;
            if ((op == 0x1a || op == 0x1b) && text.equals(stringAt(dex, op == 0x1a ? u16(dex, p + 2) : u32(dex, p + 2)))) {
                return true;
            }
        }
        return false;
    }

    java.lang.reflect.Method reflect(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = Class.forName(Locator.descToName(owner), false, loader);
        for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
            if (name.equals(method.getName()) && returned.equals(descriptor(method.getReturnType()))
                    && Arrays.equals(parameters, Arrays.stream(method.getParameterTypes()).map(DexCode::descriptor).toArray(String[]::new))) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner + "." + name);
    }

    int[] arguments(int position) {
        int unit = u16(dex, position);
        boolean range = (unit & 255) >= 0x74;
        int[] result = new int[range ? unit >>> 8 : unit >>> 12];
        int registers = u16(dex, position + 4);
        for (int i = 0; i < result.length; i++) {
            result[i] = range ? registers + i : i == 4 ? (unit >>> 8) & 15 : (registers >>> (4 * i)) & 15;
        }
        return result;
    }

    static boolean isInvoke(int op) { return op >= 0x6e && op <= 0x72 || op >= 0x74 && op <= 0x78; }

    static int moveSource(byte[] dex, int position) {
        int unit = u16(dex, position), form = ((unit & 255) - 1) % 3;
        return form == 0 ? unit >>> 12 : u16(dex, position + (form == 1 ? 2 : 4));
    }

    static int moveTarget(byte[] dex, int position) {
        int unit = u16(dex, position), form = ((unit & 255) - 1) % 3;
        return form == 0 ? (unit >>> 8) & 15 : form == 1 ? unit >>> 8 : u16(dex, position + 2);
    }
}
