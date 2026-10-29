package com.owl.auxiliary;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 音乐课程解锁。
 *
 * <p>定位 {@code LicensedMusicAccess} 与 {@code Music} 模型类，hook 其构造器，
 * 把参数中的 {@code LOCKED} / {@code TASTER} 枚举/单例替换为 {@code FULL}。
 */
public class MusicUnlockFeature extends Feature {

    @Override
    public String key() {
        return Config.K_MUSIC;
    }

    @Override
    public String label() {
        return "音乐课程解锁";
    }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        final Class<?> access = Locator.byKeyOrName(Targets.S_MUSIC, null, lpp.classLoader);
        Class<?> accessClass = null;
        for (String n : new String[]{Targets.C_MUSIC_ACCESS, Targets.C_MUSIC_ACCESS_ALT}) {
            accessClass = Locator.findByName(n, lpp.classLoader);
            if (accessClass != null) {
                break;
            }
        }
        if (accessClass == null) {
            Logx.w("音乐解锁：未找到 LicensedMusicAccess", null);
        }
        final Class<?> fullHolder = accessClass;
        final Object full = getStatic(fullHolder, Targets.F_MUSIC_FULL);
        Logx.i("音乐解锁：Music=" + (access == null ? "?" : access.getName())
                + " Access=" + (accessClass == null ? "?" : accessClass.getName())
                + " FULL=" + full);

        XC_MethodHook rewrite = new XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                Object[] args = param.args;
                if (args == null || full == null || fullHolder == null) {
                    return;
                }
                for (int i = 0; i < args.length; i++) {
                    Object a = args[i];
                    if (a != null && fullHolder.isInstance(a) && a != full) {
                        args[i] = full;
                    }
                }
            }
        };
        if (accessClass != null) {
            XposedBridge.hookAllConstructors(accessClass, rewrite);
        }
        if (access != null) {
            XposedBridge.hookAllConstructors(access, rewrite);
        }
    }

    static Object getStatic(Class<?> c, String name) {
        if (c == null) {
            return null;
        }
        try {
            return XposedHelpers.getStaticObjectField(c, name);
        } catch (Throwable t) {
            Logx.i("getStaticObjectField(" + c.getSimpleName() + "," + name + ") 失败: " + t);
            return null;
        }
    }
}
