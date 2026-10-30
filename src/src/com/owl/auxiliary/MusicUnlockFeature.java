package com.owl.auxiliary;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/** 统一音乐路径、歌曲入口和练习题的客户端权限；不改认证信息或全局枚举。 */
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
        Class<?> access = XposedHelpers.findClass(Targets.C_MUSIC_ACCESS, lpp.classLoader);
        Object full = access.getField(Targets.F_MUSIC_FULL).get(null);
        if (!access.isEnum() || !access.isInstance(full)) {
            throw new IllegalStateException("LicensedMusicAccess.FULL 类型不匹配");
        }
        Class<?> music = Locator.byKeyOrName(Targets.S_MUSIC, null, lpp.classLoader);
        if (music == null) { throw new IllegalStateException("未定位 Music 模型"); }
        List<Constructor<?>> constructors = constructors(music, access);
        // 先核对全部目标，签名失配时不留下只覆盖部分入口的 Hook。
        for (String name : Targets.C_MUSIC_ACCESS_MODELS) {
            constructors.addAll(constructors(XposedHelpers.findClass(name, lpp.classLoader), access));
        }
        for (Constructor<?> constructor : constructors) {
            XposedBridge.hookMethod(constructor,
                    new AccessHook(accessIndex(constructor, access), (Enum<?>) full));
        }
        Logx.i("音乐解锁：已适配 " + constructors.size() + " 个权限构造入口，NONE/TASTER → FULL");
    }

    private static List<Constructor<?>> constructors(Class<?> model, Class<?> access) {
        List<Constructor<?>> result = new ArrayList<>();
        for (Constructor<?> constructor : model.getDeclaredConstructors()) {
            if (accessIndex(constructor, access) >= 0) { result.add(constructor); }
        }
        if (result.isEmpty()) { throw new IllegalStateException("音乐权限构造签名缺失：" + model.getName()); }
        return result;
    }

    private static int accessIndex(Constructor<?> constructor, Class<?> access) {
        int result = -1;
        Class<?>[] types = constructor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] != access) { continue; }
            if (result >= 0) { throw new IllegalStateException("音乐权限参数不唯一：" + constructor); }
            result = i;
        }
        return result;
    }

    static Object[] rewrite(Object[] original, int index, Enum<?> full) {
        if (original == null || index < 0 || index >= original.length) {
            throw new IllegalArgumentException("音乐构造参数长度不匹配");
        }
        Object value = original[index];
        if (value == null) { return original; }
        if (!full.getDeclaringClass().isInstance(value)) {
            throw new IllegalArgumentException("音乐权限参数类型不匹配");
        }
        String name = ((Enum<?>) value).name();
        if (!Targets.F_MUSIC_NONE.equals(name) && !Targets.F_MUSIC_TASTER.equals(name)) { return original; }
        Object[] rewritten = original.clone();
        rewritten[index] = full;
        return rewritten;
    }

    private static final class AccessHook extends XC_MethodHook {
        private final int index;
        private final Enum<?> full;
        private boolean reported;

        AccessHook(int index, Enum<?> full) {
            this.index = index;
            this.full = full;
        }

        @Override
        public void beforeHookedMethod(MethodHookParam param) {
            Object[] rewritten = rewrite(param.args, index, full);
            if (rewritten == param.args) { return; }
            param.args = rewritten;
            if (!reported) {
                reported = true;
                Logx.i("音乐解锁：权限替换实际命中 " + param.method.getDeclaringClass().getName());
            }
        }
    }
}
