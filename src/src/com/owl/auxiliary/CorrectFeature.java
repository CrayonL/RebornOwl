package com.owl.auxiliary;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 强制正确。
 *
 * <p>按字符串特征定位判题结果模型，按当前构造参数类型改写。
 * 下表为具体动作（下标为构造参数序号）：
 *
 * <pre>
 * SessionDebugSettings(allowSessionOverride=  -> args[2] = Boolean.TRUE
 * SpeechRecognitionResult(score=              -> args[0] = Double 1.0
 * PassageCorrectnessInfo(percentCorrect=      -> args[0] = Float 1.0
 * Graded(blameType=                           -> args[1] = Boolean.TRUE (correct)
 * CharacterMatch/WordMatch/ListenMatch        -> 判定布尔方法 setResult(TRUE)
 * </pre>
 */
public class CorrectFeature extends Feature {
    private static final int SCORE_INDEX = 0;
    private static final int GRADED_CORRECT_INDEX = 1;
    private static final int SESSION_CORRECT_INDEX = 2;

    @Override
    public String key() {
        return Config.K_CORRECT;
    }

    @Override
    public String label() {
        return "强制正确";
    }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        rewrite(lpp, Targets.S_SESSION_DEBUG,
                new ArgumentOverride(SESSION_CORRECT_INDEX, boolean.class, Boolean.TRUE));
        rewrite(lpp, Targets.S_SPEECH,
                new ArgumentOverride(SCORE_INDEX, double.class, 1.0d));
        rewrite(lpp, Targets.S_PASSAGE,
                new ArgumentOverride(SCORE_INDEX, float.class, 1.0f));
        rewrite(lpp, Targets.S_GRADED,
                new ArgumentOverride(GRADED_CORRECT_INDEX, boolean.class, Boolean.TRUE));
        // MatchChallenge 的首参是序列化位掩码，清零会触发缺字段异常。
        hookBooleanMethod(lpp, Targets.S_CHAR_MATCH);
        hookBooleanMethod(lpp, Targets.S_WORD_MATCH);
        hookBooleanMethod(lpp, Targets.S_LISTEN_MATCH);
    }

    private void rewrite(XC_LoadPackage.LoadPackageParam lpp, String key, ArgumentOverride rule) {
        final Class<?> c = Locator.byKey(key, lpp.classLoader);
        if (c == null) {
            throw new IllegalStateException("强制正确：未定位 " + key);
        }
        int count = 0;
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            if (!rule.supports(ctor.getParameterTypes())) {
                continue;
            }
            XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) {
                    param.args = rule.rewrite(param.args);
                }
            });
            count++;
        }
        if (count == 0) {
            throw new IllegalStateException("强制正确：构造参数不匹配 " + c.getName());
        }
        Logx.i("强制正确：hook " + c.getName() + " args[" + rule.index + "]，构造器数=" + count);
    }

    static final class ArgumentOverride {
        final int index;
        private final Class<?> type;
        private final Object value;

        ArgumentOverride(int index, Class<?> type, Object value) {
            this.index = index;
            this.type = type;
            this.value = value;
        }

        boolean supports(Class<?>[] types) {
            return index >= 0 && index < types.length && types[index] == type;
        }

        Object[] rewrite(Object[] args) {
            if (args == null || index < 0 || index >= args.length
                    || !value.getClass().isInstance(args[index])) {
                throw new IllegalArgumentException("判题参数类型不匹配，下标=" + index);
            }
            Object[] result = args.clone();
            result[index] = value;
            return result;
        }
    }

    /**
     * hook 目标类中「返回 boolean/Boolean」的方法（跳过 equals/hashCode/toString），强制返回 true。
     *
     * <p>实测这三个题型模型类里有两个布尔方法：{@code F(byte,Object)Z} 与 {@code G(byte)Z}，
     * 均为宿主内的单字母方法名；带参（byte）为内部标记位，不影响语义。
     */
    private void hookBooleanMethod(XC_LoadPackage.LoadPackageParam lpp, String key) {
        final Class<?> c = Locator.byKey(key, lpp.classLoader);
        if (c == null) {
            Logx.w("强制正确：未定位 " + key, null);
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?> r = m.getReturnType();
            if (r != boolean.class && r != Boolean.class) {
                continue;
            }
            String name = m.getName();
            if ("equals".equals(name) || "hashCode".equals(name) || "toString".equals(name)) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.TRUE);
                }
            });
            n++;
            Logx.i("强制正确：" + c.getName() + "." + name + " -> true");
        }
        Logx.i("强制正确：" + c.getName() + " 共 hook " + n + " 个布尔方法");
    }
}
