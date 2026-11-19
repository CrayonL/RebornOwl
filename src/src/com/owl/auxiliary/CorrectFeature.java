package com.owl.auxiliary;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 按数据模型字段语义修改判题值。 */
public class CorrectFeature extends Feature {
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

    private static java.util.Map<Constructor<?>, ConstructorValue.Argument> constructorRules(XC_LoadPackage.LoadPackageParam lpp) {
        java.util.Map<Constructor<?>, ConstructorValue.Argument> result = new java.util.LinkedHashMap<>();
        result.putAll(new ConstructorValue("alwaysGradeCorrect", boolean.class, true).resolve(lpp.classLoader, Targets.S_SESSION_DEBUG));
        result.putAll(new ConstructorValue("score", double.class, 1.0d).resolve(lpp.classLoader, Targets.S_SPEECH));
        result.putAll(new ConstructorValue("percentCorrect", float.class, 1.0f).resolve(lpp.classLoader, Targets.S_PASSAGE));
        result.putAll(new ConstructorValue("correct", boolean.class, true).resolve(lpp.classLoader, Targets.S_GRADED));
        return result;
    }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        java.util.Map<Constructor<?>, ConstructorValue.Argument> constructors = constructorRules(lpp);
        java.util.List<Method> methods = booleanTargets(lpp);
        ConstructorValue.install(key(), constructors);
        for (Method method : methods) {
            XposedBridge.hookMethod(method, new DiagnosticHook(Config.K_CORRECT) {
                @Override
                public void before(MethodHookParam param) { param.setResult(Boolean.TRUE); }
            });
        }
    }

    /** 仅匹配字符串配对和语言相关的配对判断，排除其他布尔状态。 */
    private static java.util.List<Method> booleanTargets(XC_LoadPackage.LoadPackageParam lpp) {
        java.util.List<Method> result = new java.util.ArrayList<>();
        for (String key : new String[]{Targets.S_CHAR_MATCH, Targets.S_WORD_MATCH, Targets.S_LISTEN_MATCH}) {
            result.addAll(booleanMethods(Locator.byKeyContract(lpp.classLoader, key, type -> booleanMethods(type))));
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
            Class<?>[] parameters = m.getParameterTypes();
            boolean grading = java.util.Arrays.equals(parameters, new Class<?>[]{String.class, String.class})
                    || parameters.length == 2 && java.util.Arrays.asList(parameters).contains(String.class)
                    && java.util.Arrays.stream(parameters).anyMatch(type ->
                            "com.duolingo.core.language.Language".equals(type.getName()));
            if (!grading) { continue; }
            result.add(m);
        }
        long pairCount = result.stream().filter(m -> java.util.Arrays.equals(m.getParameterTypes(),
                new Class<?>[]{String.class, String.class})).count();
        if (result.size() != 2 || pairCount != 1) { throw new IllegalStateException("强制正确：判题方法缺失或有歧义 " + c.getName()); }
        return result;
    }
}
