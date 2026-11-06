package com.owl.auxiliary;

import de.robv.android.xposed.XC_MethodHook;

/** 记录模块回调异常后原样抛出，保留框架原有的异常处理语义。 */
abstract class DiagnosticHook extends XC_MethodHook {
    private final String key;

    DiagnosticHook(String key) { this.key = key; }

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
        try {
            before(param);
        } catch (Throwable error) {
            report(param, error);
            throw error;
        }
    }

    @Override
    protected final void afterHookedMethod(MethodHookParam param) throws Throwable {
        try {
            after(param);
        } catch (Throwable error) {
            report(param, error);
            throw error;
        }
    }

    protected void before(MethodHookParam param) throws Throwable {}
    protected void after(MethodHookParam param) throws Throwable {}

    private void report(MethodHookParam param, Throwable error) {
        FeatureDiagnostics.failure(key, "运行回调异常：" + param.method.getDeclaringClass().getName()
                + "." + param.method.getName() + "（" + error.getClass().getSimpleName() + "）", error);
    }
}
