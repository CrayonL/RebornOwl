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
    public void check(XC_LoadPackage.LoadPackageParam lpp) {
        constructorRules(lpp);
        booleanTargets(lpp);
    }

    private static java.util.Map<Constructor<?>, ArgumentOverride> constructorRules(XC_LoadPackage.LoadPackageParam lpp) {
        java.util.Map<String, ArgumentOverride> rules = new java.util.LinkedHashMap<>();
        rules.put(Targets.S_SESSION_DEBUG, new ArgumentOverride(SESSION_CORRECT_INDEX, boolean.class, Boolean.TRUE));
        rules.put(Targets.S_SPEECH, new ArgumentOverride(SCORE_INDEX, double.class, 1.0d));
        rules.put(Targets.S_PASSAGE, new ArgumentOverride(SCORE_INDEX, float.class, 1.0f));
        rules.put(Targets.S_GRADED, new ArgumentOverride(GRADED_CORRECT_INDEX, boolean.class, Boolean.TRUE));
        java.util.Map<Constructor<?>, ArgumentOverride> result = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, ArgumentOverride> entry : rules.entrySet()) {
            for (Constructor<?> ctor : constructors(lpp.classLoader, entry.getKey(), entry.getValue())) {
                result.put(ctor, entry.getValue());
            }
        }
        return result;
    }

    private static java.util.List<Constructor<?>> constructors(ClassLoader loader, String key, ArgumentOverride rule) {
        Class<?> c = Locator.byKey(key, loader);
        if (c == null) {
            throw new IllegalStateException("强制正确：未定位 " + key);
        }
        java.util.List<Constructor<?>> result = new java.util.ArrayList<>();
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            if (rule.supports(ctor.getParameterTypes())) { result.add(ctor); }
        }
        if (result.isEmpty()) { throw new IllegalStateException("强制正确：构造参数不匹配 " + c.getName()); }
        return result;
    }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        for (java.util.Map.Entry<Constructor<?>, ArgumentOverride> entry : constructorRules(lpp).entrySet()) {
            Constructor<?> ctor = entry.getKey();
            ArgumentOverride rule = entry.getValue();
            XposedBridge.hookMethod(ctor, new DiagnosticHook(Config.K_CORRECT) {
                @Override
                public void before(MethodHookParam param) {
                    param.args = rule.rewrite(param.args);
                }
            });
        }
        for (Method method : booleanTargets(lpp)) {
            XposedBridge.hookMethod(method, new DiagnosticHook(Config.K_CORRECT) {
                @Override
                public void before(MethodHookParam param) { param.setResult(Boolean.TRUE); }
            });
        }
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
    private static java.util.List<Method> booleanTargets(XC_LoadPackage.LoadPackageParam lpp) {
        java.util.List<Method> result = new java.util.ArrayList<>();
        for (String key : new String[]{Targets.S_CHAR_MATCH, Targets.S_WORD_MATCH, Targets.S_LISTEN_MATCH}) {
            result.addAll(booleanMethods(Locator.byKey(key, lpp.classLoader)));
        }
        return result;
    }

    private static java.util.List<Method> booleanMethods(Class<?> c) {
        if (c == null) { throw new IllegalStateException("强制正确：判题模型缺失"); }
        java.util.List<Method> result = new java.util.ArrayList<>();
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
            result.add(m);
        }
        if (result.isEmpty()) { throw new IllegalStateException("强制正确：判题方法缺失 " + c.getName()); }
        return result;
    }
}
