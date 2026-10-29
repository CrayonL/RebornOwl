package com.owl.auxiliary;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 用最小 DEX 样本验证查询行为，长度取自 AOSP 指令规范。 */
public final class DexIndexRegressionTest {
    private static final int CODE_OFFSET = 256;
    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) {
        check("normal", new int[]{0x001a, 0, 0x000e}, true);
        check("jumbo", new int[]{0x001b, 0, 0, 0x000e}, true);
        check("absent", new int[]{0x000e}, false);
        check("packedData", new int[]{0x0100, 2, 0, 0, 0, 0, 0x001a, 0}, false);
        check("sparseData", new int[]{0x0200, 1, 0, 0, 0x001a, 0}, false);
        check("arrayData", new int[]{0x0300, 2, 2, 0, 0x001a, 0}, false);
        check("invokeCustom", new int[]{0x00fc, 0, 0, 0x001a, 0, 0x000e}, true);
        check("invokeCustomRange", new int[]{0x00fd, 0, 0, 0x001a, 0, 0x000e}, true);
        check("shiftLiteral", new int[]{0x00e2, 0x001a, 0, 0x000e}, false);
        if (!FAILURES.isEmpty()) {
            throw new AssertionError(String.join("; ", FAILURES));
        }
        System.out.println("PASS: 9 DEX instruction regression cases");
    }

    private static void check(String name, int[] instructions, boolean expected) {
        DexIndex.addDexBytes(fixture(name, instructions));
        boolean found = DexIndex.findClassesContaining(name).contains("L" + name + ";");
        if (found != expected) {
            FAILURES.add(name + ": expected=" + expected + ", actual=" + found);
        }
    }

    private static byte[] fixture(String name, int[] instructions) {
        ByteBuffer dex = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        dex.put("dex\n035\0".getBytes(StandardCharsets.US_ASCII));
        dex.putInt(0x38, 2).putInt(0x3c, 0x70);
        dex.putInt(0x40, 1).putInt(0x44, 0x78);
        dex.putInt(0x60, 1).putInt(0x64, 0x7c);
        dex.putInt(0x70, 192).putInt(0x74, 224).putInt(0x78, 1);
        dex.putInt(0x7c + 24, 160);
        dex.position(160);
        dex.put(new byte[]{0, 0, 1, 0, 0, 1, (byte) 0x80, 2});
        putString(dex, 192, name);
        putString(dex, 224, "L" + name + ";");
        dex.putInt(CODE_OFFSET + 12, instructions.length);
        dex.position(CODE_OFFSET + 16);
        for (int unit : instructions) {
            dex.putShort((short) unit);
        }
        return dex.array();
    }

    private static void putString(ByteBuffer dex, int offset, String value) {
        dex.position(offset);
        dex.put((byte) value.length()).put(value.getBytes(StandardCharsets.US_ASCII));
        dex.put((byte) 0);
    }
}
