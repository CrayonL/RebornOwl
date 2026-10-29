package com.owl.auxiliary;

import android.content.pm.ApplicationInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 纯 Java 的 DEX 扫描器 —— 用于「按字符串特征反查类」。
 *
 * <p>纯 Java 实现（零 native 依赖），绕开 NPatch「声明 native 的模块走增强加载、
 * legacy 入口不被调用」的陷阱。
 *
 * <p>能力：给定字符串常量，找出【代码中 const-string 引用了该串】的类，
 * 足以覆盖 强制正确 / 无限时间 等「宿主类名随版本变化」的定位需求。
 */
public final class DexIndex {

    private static volatile boolean sLoaded = false;
    private static boolean sEnabled = true;
    private static final List<byte[]> DEX = new ArrayList<>();
    private static final List<String> SOURCES = new ArrayList<>();

    private DexIndex() {}

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /** 仅供离线单元测试：直接注入 dex 字节并置为已载入。 */
    static void addDexBytes(byte[] data) {
        addDex(data);
        sLoaded = true;
    }

    public static boolean isReady() {
        return sLoaded;
    }

    // ------------------------------------------------------------------ 载入

    public static void ensureLoaded(ClassLoader cl, ApplicationInfo ai) {
        if (sLoaded || !sEnabled) {
            return;
        }
        synchronized (DexIndex.class) {
            if (sLoaded) {
                return;
            }
            long t0 = System.currentTimeMillis();
            Set<String> paths = new LinkedHashSet<>();
            collectClassLoaderPaths(cl, paths);
            collectAppPaths(ai, paths);
            for (String p : paths) {
                try {
                    int before = DEX.size();
                    readDexFrom(p);
                    if (DEX.size() > before) {
                        SOURCES.add(p);
                    }
                } catch (Throwable t) {
                    Logx.w("读取 dex 失败: " + p, t);
                }
            }
            sLoaded = true;
            Logx.i("DexIndex: 载入 " + DEX.size() + " 个 dex，耗时 "
                    + (System.currentTimeMillis() - t0) + "ms；来源=" + SOURCES);
        }
    }

    private static void collectClassLoaderPaths(ClassLoader cl, Set<String> out) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> bdcl = Class.forName("dalvik.system.BaseDexClassLoader");
            Field pathListF = bdcl.getDeclaredField("pathList");
            pathListF.setAccessible(true);
            Object pathList = null;
            ClassLoader cur = cl;
            while (cur != null) {
                if (bdcl.isInstance(cur)) {
                    pathList = pathListF.get(cur);
                    if (pathList != null) {
                        break;
                    }
                }
                cur = cur.getParent();
            }
            if (pathList == null) {
                return;
            }
            Field elementsF = pathList.getClass().getDeclaredField("dexElements");
            elementsF.setAccessible(true);
            Object[] elements = (Object[]) elementsF.get(pathList);
            if (elements == null) {
                return;
            }
            for (Object e : elements) {
                String path = reflectString(e, "path");
                if (path == null) {
                    path = reflectString(e, "mFileName");
                }
                if (path != null) {
                    out.add(path);
                }
            }
        } catch (Throwable t) {
            Logx.w("枚举 ClassLoader dex 失败", t);
        }
    }

    private static String reflectString(Object o, String field) {
        if (o == null) {
            return null;
        }
        try {
            Field f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(o);
            return v instanceof String ? (String) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void collectAppPaths(ApplicationInfo ai, Set<String> out) {
        if (ai == null) {
            return;
        }
        if (ai.sourceDir != null) {
            out.add(ai.sourceDir);
        }
        if (ai.publicSourceDir != null) {
            out.add(ai.publicSourceDir);
        }
        if (ai.splitSourceDirs != null) {
            for (String s : ai.splitSourceDirs) {
                if (s != null) {
                    out.add(s);
                }
            }
        }
    }

    private static void readDexFrom(String path) throws Exception {
        File f = new File(path);
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs == null) {
                return;
            }
            for (File c : fs) {
                if (c.isFile() && c.getName().endsWith(".dex")) {
                    try (FileInputStream in = new FileInputStream(c)) {
                        addDex(readAll(in));
                    }
                }
            }
            return;
        }
        if (!f.isFile()) {
            return;
        }
        try (ZipFile zip = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String n = ze.getName();
                if (ze.isDirectory() || !n.endsWith(".dex")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(ze)) {
                    addDex(readAll(in));
                }
            }
        }
    }

    private static void addDex(byte[] data) {
        if (data != null && data.length > 0x70
                && data[0] == 'd' && data[1] == 'e' && data[2] == 'x') {
            DEX.add(data);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(8192, in.available()));
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ 查询

    /** 找出代码中引用了 key 的类（内部描述符）。 */
    public static List<String> findClassesContaining(String key) {
        List<String> out = new ArrayList<>();
        if (!sLoaded || !sEnabled) {
            return out;
        }
        for (byte[] dex : DEX) {
            try {
                scanDex(dex, key, out);
            } catch (Throwable t) {
                Logx.w("DexIndex 查询异常: " + key, t);
            }
        }
        return out;
    }

    public static String findClassContaining(String key) {
        List<String> all = findClassesContaining(key);
        if (all.isEmpty()) {
            return null;
        }
        if (all.size() > 1) {
            Logx.i("特征串 " + key + " 命中 " + all.size() + " 个类，取首个: " + all);
        }
        return all.get(0);
    }

    private static void scanDex(byte[] dex, String key, List<String> result) {
        int stringIdsSize = u32(dex, 0x38);
        int stringIdsOff = u32(dex, 0x3C);
        int typeIdsSize = u32(dex, 0x40);
        int typeIdsOff = u32(dex, 0x44);
        int classDefsSize = u32(dex, 0x60);
        int classDefsOff = u32(dex, 0x64);

        byte[] keyUtf8 = key.getBytes(StandardCharsets.UTF_8);
        Set<Integer> want = new LinkedHashSet<>();
        for (int i = 0; i < stringIdsSize; i++) {
            int dataOff = u32(dex, stringIdsOff + 4 * i);
            if (stringEquals(dex, dataOff, keyUtf8)) {
                want.add(i);
            }
        }
        if (want.isEmpty()) {
            return;
        }

        for (int ci = 0; ci < classDefsSize; ci++) {
            int base = classDefsOff + 32 * ci;
            int classIdx = u32(dex, base);
            int dataOff = u32(dex, base + 24);
            if (dataOff == 0) {
                continue;
            }
            if (classReferencesAny(dex, dataOff, want)) {
                String desc = typeDesc(dex, typeIdsOff, typeIdsSize, classIdx);
                if (desc != null) {
                    result.add(desc);
                }
            }
        }
    }

    private static boolean classReferencesAny(byte[] dex, int dataOff, Set<Integer> want) {
        int o = dataOff;
        long r = uleb(dex, o);
        int staticFields = (int) (r >>> 32);
        o = (int) (r & 0xFFFFFFFFL);
        r = uleb(dex, o);
        int instanceFields = (int) (r >>> 32);
        o = (int) (r & 0xFFFFFFFFL);
        r = uleb(dex, o);
        int directMethods = (int) (r >>> 32);
        o = (int) (r & 0xFFFFFFFFL);
        r = uleb(dex, o);
        int virtualMethods = (int) (r >>> 32);
        o = (int) (r & 0xFFFFFFFFL);

        for (int i = 0; i < staticFields + instanceFields; i++) {
            o = (int) (uleb(dex, o) & 0xFFFFFFFFL);   // field_idx_diff
            o = (int) (uleb(dex, o) & 0xFFFFFFFFL);   // access_flags
        }
        int total = directMethods + virtualMethods;
        for (int i = 0; i < total; i++) {
            o = (int) (uleb(dex, o) & 0xFFFFFFFFL);   // method_idx_diff
            o = (int) (uleb(dex, o) & 0xFFFFFFFFL);   // access_flags
            long c = uleb(dex, o);
            int codeOff = (int) (c >>> 32);
            o = (int) (c & 0xFFFFFFFFL);
            if (codeOff != 0 && codeContainsAnyString(dex, codeOff, want)) {
                return true;
            }
        }
        return false;
    }

    private static boolean codeContainsAnyString(byte[] dex, int codeOff, Set<Integer> want) {
        int insnsSize = u32(dex, codeOff + 12);
        int p = codeOff + 16;
        int i = 0;
        while (i < insnsSize) {
            int unit = u16(dex, p + 2 * i);
            int op = unit & 0xFF;
            if (op == 0x1A) {
                if (want.contains(u16(dex, p + 2 * i + 2))) {
                    return true;
                }
                i += 2;
            } else if (op == 0x1B) {
                if (want.contains(u32(dex, p + 2 * i + 2))) {
                    return true;
                }
                i += 3;
            } else if (op == 0x00 && (unit >>> 8) != 0) {
                int n = payloadUnits(dex, p + 2 * i, unit >>> 8);
                i += Math.max(1, n);
            } else {
                i += LEN[op];
            }
        }
        return false;
    }

    private static int payloadUnits(byte[] dex, int off, int kind) {
        if (kind == 0x01) {
            return 4 + u16(dex, off + 2) * 2;
        } else if (kind == 0x02) {
            return 2 + u16(dex, off + 2) * 4;
        } else if (kind == 0x03) {
            int elemWidth = u16(dex, off + 2);
            int size = u32(dex, off + 4);
            return 4 + (size * elemWidth + 1) / 2;
        }
        return 1;
    }

    private static String typeDesc(byte[] dex, int typeIdsOff, int typeIdsSize, int typeIdx) {
        if (typeIdx < 0 || typeIdx >= typeIdsSize) {
            return null;
        }
        return stringAt(dex, u32(dex, typeIdsOff + 4 * typeIdx));
    }

    private static String stringAt(byte[] dex, int stringIdx) {
        int tableOff = u32(dex, 0x3C);
        int dataOff = u32(dex, tableOff + 4 * stringIdx);
        long r = uleb(dex, dataOff);
        int size = (int) (r >>> 32);
        int p = (int) (r & 0xFFFFFFFFL);
        return new String(dex, p, size, StandardCharsets.UTF_8);
    }

    private static boolean stringEquals(byte[] dex, int dataOff, byte[] keyUtf8) {
        long r = uleb(dex, dataOff);
        int size = (int) (r >>> 32);
        int p = (int) (r & 0xFFFFFFFFL);
        if (size != keyUtf8.length) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if (dex[p + i] != keyUtf8[i]) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 字节工具

    /** 读取 uleb128：返回值高 32 位为数值，低 32 位为「读完后的绝对偏移」。 */
    private static long uleb(byte[] b, int off) {
        int result = 0, shift = 0, p = off;
        while (true) {
            int x = b[p++] & 0xFF;
            result |= (x & 0x7F) << shift;
            if ((x & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 28) {
                break;
            }
        }
        return ((long) result << 32) | (p & 0xFFFFFFFFL);
    }

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static final int[] LEN = buildLen();

    private static int[] buildLen() {
        int[] t = new int[256];
        for (int i = 0; i < 256; i++) {
            t[i] = 1;
        }
        int[] two = {
                0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1A, 0x1C, 0x1F, 0x20, 0x22, 0x23,
                0x29, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38,
                0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B,
                0x4C, 0x4D, 0x4E, 0x4F, 0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
                0x59, 0x5A, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65,
                0x66, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D,
                0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0x9B, 0x9C,
                0x9D, 0x9E, 0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xA9,
                0xAA, 0xAB, 0xAC, 0xAD, 0xAE, 0xAF,
                0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xDB, 0xDC,
                0xDD, 0xDE, 0xDF, 0xE0, 0xE1, 0xE2, 0xFE, 0xFF
        };
        for (int o : two) {
            t[o] = 2;
        }
        int[] three = {
                0x03, 0x06, 0x09, 0x14, 0x17, 0x1B, 0x24, 0x25, 0x26, 0x2A, 0x2B, 0x2C,
                0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78, 0xFC, 0xFD
        };
        for (int o : three) {
            t[o] = 3;
        }
        t[0x18] = 5;
        t[0xFA] = 4;
        t[0xFB] = 4;
        return t;
    }
}
