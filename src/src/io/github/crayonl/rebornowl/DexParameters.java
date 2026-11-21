package io.github.crayonl.rebornowl;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import static io.github.crayonl.rebornowl.DexIndex.*;

/** 构造器控制流合并时只保留所有路径一致的参数来源。 */
final class DexParameters {
    private static final int UNKNOWN = -1;
    private static final int SELF = -2;

    static int entryRead(DexCode method, String field) {
        int[] origins = initial(method);
        int result = UNKNOWN;
        for (int p : method.instructions()) {
            int unit = u16(method.dex, p), op = unit & 255;
            if (op >= 0x27 && op <= 0x3d || op >= 0x0e && op <= 0x11) { break; }
            if (op >= 0x52 && op <= 0x58 && field.equals(method.field(u16(method.dex, p + 2)))) {
                int index = origins[unit >>> 12];
                if (index < 0 || result >= 0 && result != index) { throw new IllegalStateException("节点标识参数来源不唯一"); }
                result = index;
            }
            origins = transfer(method, p, origins);
        }
        if (result < 0) { throw new IllegalStateException("未证明节点标识来自哪个参数"); }
        return result;
    }

    static Integer stored(DexCode method, String field) {
        Map<Integer, int[]> states = states(method);
        Integer result = null;
        for (Map.Entry<Integer, int[]> entry : states.entrySet()) {
            int p = entry.getKey(), unit = u16(method.dex, p), op = unit & 255;
            if (op < 0x59 || op > 0x5f || !method.field(u16(method.dex, p + 2)).equals(method.owner + ":" + field)) { continue; }
            int[] origins = entry.getValue();
            int value = origins[(unit >>> 8) & 15];
            if (origins[unit >>> 12] != SELF || value < 0 || result != null && result != value) { return null; }
            result = value;
        }
        return result;
    }

    static int read(DexCode method, String field) {
        int result = UNKNOWN;
        for (Map.Entry<Integer, int[]> entry : states(method).entrySet()) {
            int p = entry.getKey(), unit = u16(method.dex, p), op = unit & 255;
            if (op < 0x52 || op > 0x58 || !field.equals(method.field(u16(method.dex, p + 2)))) { continue; }
            int index = entry.getValue()[unit >>> 12];
            if (index < 0 || result >= 0 && result != index) { throw new IllegalStateException("字段读取参数来源不唯一：" + field); }
            result = index;
        }
        if (result < 0) { throw new IllegalStateException("没有读取目标字段：" + field); }
        return result;
    }

    private static Map<Integer, int[]> states(DexCode method) {
        if (u16(method.dex, method.code + 6) != 0) { throw new IllegalStateException("参数分析暂不接受异常分支"); }
        Map<Integer, int[]> result = new LinkedHashMap<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        int first = method.code + 16, end = first + u32(method.dex, method.code + 12) * 2;
        result.put(first, initial(method));
        pending.add(first);
        while (!pending.isEmpty()) {
            int p = pending.remove();
            int[] next = transfer(method, p, result.get(p));
            for (int target : successors(method, p)) {
                if (target < first || target >= end) { throw new IllegalStateException("构造器跳转超出方法范围"); }
                int[] previous = result.get(target);
                int[] merged = previous == null ? next.clone() : merge(previous, next);
                if (Arrays.equals(previous, merged)) { continue; }
                result.put(target, merged);
                pending.add(target);
            }
        }
        return result;
    }

    private static int[] initial(DexCode method) {
        int[] result = new int[u16(method.dex, method.code)];
        Arrays.fill(result, UNKNOWN);
        int register = result.length - u16(method.dex, method.code + 2);
        if ((method.access & 8) == 0) { result[register++] = SELF; }
        for (int i = 0; i < method.parameters.length; i++) {
            result[register++] = i;
            if ("J".equals(method.parameters[i]) || "D".equals(method.parameters[i])) { result[register++] = i; }
        }
        return result;
    }

    private static int[] merge(int[] left, int[] right) {
        int[] result = left.clone();
        for (int i = 0; i < result.length; i++) { if (left[i] != right[i]) { result[i] = UNKNOWN; } }
        return result;
    }

    private static int[] successors(DexCode method, int p) {
        int unit = u16(method.dex, p), op = unit & 255;
        int next = p + instructionUnits(method.dex, p) * 2;
        if (op >= 0x0e && op <= 0x11 || op == 0x27) { return new int[0]; }
        if (op == 0x28) { return new int[]{p + (byte) (unit >>> 8) * 2}; }
        if (op == 0x29) { return new int[]{p + (short) u16(method.dex, p + 2) * 2}; }
        if (op == 0x2a) { return new int[]{p + u32(method.dex, p + 2) * 2}; }
        if (op >= 0x32 && op <= 0x3d) { return new int[]{next, p + (short) u16(method.dex, p + 2) * 2}; }
        if (op == 0x2b || op == 0x2c) { return switchTargets(method, p, next); }
        return new int[]{next};
    }

    private static int[] switchTargets(DexCode method, int p, int next) {
        int payload = p + u32(method.dex, p + 2) * 2;
        int count = u16(method.dex, payload + 2);
        int first = payload + ((u16(method.dex, p) & 255) == 0x2b ? 8 : 4 + count * 4);
        int[] result = new int[count + 1];
        result[0] = next;
        for (int i = 0; i < count; i++) { result[i + 1] = p + u32(method.dex, first + i * 4) * 2; }
        return result;
    }

    private static int[] transfer(DexCode method, int p, int[] previous) {
        int[] result = previous.clone();
        int unit = u16(method.dex, p), op = unit & 255;
        if (op >= 1 && op <= 9) {
            int target = DexCode.moveTarget(method.dex, p), source = DexCode.moveSource(method.dex, p);
            result[target] = previous[source];
            if (op >= 4 && op <= 6) { result[target + 1] = previous[source + 1]; }
            return result;
        }
        int target = destination(unit);
        if (target >= 0) { result[target] = UNKNOWN; }
        if (target >= 0 && isWideWrite(op)) { result[target + 1] = UNKNOWN; }
        return result;
    }

    private static final int[] DESTINATIONS = destinations();
    private static final boolean[] WIDE_WRITES = wideWrites();

    private static int destination(int unit) {
        int op = unit & 255, format = DESTINATIONS[op];
        if (format == 0) { return -1; }
        if (format == 1) { return (unit >>> 8) & 15; }
        if (format == 2) { return unit >>> 8; }
        throw new IllegalStateException("未识别的构造器指令=" + Integer.toHexString(op));
    }

    private static int[] destinations() {
        int[] result = new int[256];
        Arrays.fill(result, -1);
        int[][][] ranges = {
            {{0, 0}, {0x0e, 0x11}, {0x1d, 0x1f}, {0x24, 0x2c}, {0x32, 0x3d},
             {0x4b, 0x51}, {0x59, 0x5f}, {0x67, 0x72}, {0x74, 0x78}},
            {{0x12, 0x12}, {0x20, 0x21}, {0x23, 0x23}, {0x52, 0x58}, {0x7b, 0x8f}, {0xb0, 0xd7}},
            {{0x0a, 0x0d}, {0x13, 0x1c}, {0x22, 0x22}, {0x2d, 0x31}, {0x44, 0x4a},
             {0x60, 0x66}, {0x90, 0xaf}, {0xd8, 0xe2}}
        };
        for (int format = 0; format < ranges.length; format++) {
            for (int[] range : ranges[format]) { Arrays.fill(result, range[0], range[1] + 1, format); }
        }
        return result;
    }

    private static boolean isWideWrite(int op) { return WIDE_WRITES[op]; }

    private static boolean[] wideWrites() {
        boolean[] result = new boolean[256];
        int[][] ranges = {{0x16, 0x19}, {0x7d, 0x7e}, {0x80, 0x81}, {0x88, 0x89},
                {0x9b, 0xa5}, {0xab, 0xaf}, {0xbb, 0xc5}, {0xcb, 0xcf}};
        for (int[] range : ranges) { Arrays.fill(result, range[0], range[1] + 1, true); }
        for (int op : new int[]{0x0b, 0x45, 0x53, 0x61, 0x83, 0x86, 0x8b}) { result[op] = true; }
        return result;
    }
}
