package com.owl.auxiliary;

import de.robv.android.xposed.XposedHelpers;

/**
 * 类定位器：按字符串特征定位宿主类。
 *
 * <p>三级回退：字符串特征（{@link DexIndex}）→ 硬编码类名 → 记录 WARN。
 */
public final class Locator {

    private Locator() {}

    interface Contract { void verify(Class<?> type) throws ReflectiveOperationException; }

    /** 候选必须唯一满足完整契约，避免把新版同名的无关类当作兼容目标。 */
    static Class<?> byContract(ClassLoader loader, String[] names, Contract contract) {
        Class<?> resolved = null;
        IllegalStateException failure = new IllegalStateException("目标结构不匹配：" + java.util.Arrays.toString(names));
        for (String name : names) {
            Class<?> candidate;
            try {
                candidate = Class.forName(name, false, loader);
                contract.verify(candidate);
            } catch (ReflectiveOperationException | IllegalStateException error) {
                failure.addSuppressed(error);
                continue;
            }
            if (resolved != null) { throw new IllegalStateException("目标结构匹配不唯一：" + name); }
            resolved = candidate;
        }
        if (resolved == null) { throw failure; }
        return resolved;
    }

    /** 内部描述符 Lx/y; → Java 类名 x.y */
    public static String descToName(String desc) {
        if (desc == null) {
            return null;
        }
        String s = desc;
        if (s.startsWith("L") && s.endsWith(";")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace('/', '.');
    }

    public static Class<?> findByName(String name, ClassLoader cl) {
        if (name == null) {
            return null;
        }
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按字符串特征定位类（代码中 const-string 引用了该串者）。
     *
     * @return 命中的第一个类；未命中返回 null
     */
    public static Class<?> byKey(String key, ClassLoader cl) {
        String desc = DexIndex.findClassContaining(key);
        if (desc == null) {
            Logx.i("特征串未命中: " + key);
            return null;
        }
        Class<?> c = findByName(descToName(desc), cl);
        Logx.i("特征串 " + key + " → " + descToName(desc) + (c == null ? "（findClass 失败）" : ""));
        return c;
    }

    /** 先按特征串定位，失败回退硬编码类名。 */
    public static Class<?> byKeyOrName(String key, String fallbackName, ClassLoader cl) {
        Class<?> c = key == null ? null : byKey(key, cl);
        if (c == null && fallbackName != null) {
            c = findByName(fallbackName, cl);
            if (c != null) {
                Logx.i("回退硬编码类名: " + fallbackName);
            }
        }
        if (c == null) {
            Logx.w("无法定位类: key=" + key + " fallback=" + fallbackName, null);
        }
        return c;
    }

    /** 按类名定位，失败返回 null。 */
    public static Class<?> byName(String name, ClassLoader cl) {
        Class<?> c = findByName(name, cl);
        if (c == null) {
            Logx.w("无法定位类: " + name, null);
        }
        return c;
    }
}
