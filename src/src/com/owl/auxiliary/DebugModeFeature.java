package com.owl.auxiliary;

import android.app.Activity;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 通过取值器放行调试菜单并翻译标题，避免修改枚举构造数据。 */
public class DebugModeFeature extends Feature {

    @Override
    public String key() {
        return Config.K_DEBUG;
    }

    @Override
    public String label() {
        return "调试模式";
    }

    @Override
    public void check(XC_LoadPackage.LoadPackageParam lpp) throws ReflectiveOperationException {
        Class<?> category = debugCategory(lpp.classLoader);
        if (category.getMethod("getAllowOnReleaseBuilds").getReturnType() != boolean.class
                || category.getMethod("getTitle").getReturnType() != String.class) {
            throw new IllegalStateException("调试菜单：准入或标题方法签名不匹配");
        }
        Class.forName(Targets.C_DEBUG_ACTIVITY, false, lpp.classLoader);
        Activity.class.getDeclaredMethod("onResume");
        Activity.class.getMethod("finish");
        Activity.class.getMethod("finishAndRemoveTask");
    }

    private static Class<?> debugCategory(ClassLoader loader) {
        Class<?> category = Locator.findByName(Targets.C_DEBUG_CATEGORY, loader);
        if (category == null) { category = Locator.findByName(Targets.C_DEBUG_CATEGORY_OLD, loader); }
        if (category == null) { throw new IllegalStateException("调试模式：DebugCategory 缺失"); }
        return category;
    }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        applyDebugCategory(lpp);
        DebugMenuGuard.install(lpp);
        installResumeMarker(lpp);
    }

    // ------------------------------------------------------------ 调试菜单放行

    private void applyDebugCategory(XC_LoadPackage.LoadPackageParam lpp) {
        Class<?> dc = debugCategory(lpp.classLoader);
        Logx.i("调试模式：命中 " + dc.getName()
                + " 字段=" + fieldNames(dc) + "；标签表 " + DebugLabels.size() + " 条");

        // ★ 关键：DebugCategory 是 enum，其实例在 <clinit> 里创建，而 <clinit> 早于本模块
        //   装载（宿主启动阶段就被导航图引用），因此 hook 构造器**永远不命中**（实测确认）。
        //   改为 hook 取值器：渲染时才会调用，必然晚于本 hook，稳定生效。
        hookAllowOnRelease(dc);
        hookTitle(dc);
    }

    /** 让所有调试条目在 release 包可见（{@code getAllowOnReleaseBuilds()} 恒 true）。 */
    private void hookAllowOnRelease(Class<?> dc) {
        try {
            XposedHelpers.findAndHookMethod(dc, "getAllowOnReleaseBuilds", new DiagnosticHook(Config.K_DEBUG) {
                @Override
                public void before(MethodHookParam param) {
                    param.setResult(Boolean.TRUE);
                }
            });
            Logx.i("调试模式：已 hook getAllowOnReleaseBuilds() → true（release 包全量可见）");
        } catch (Throwable t) {
            FeatureDiagnostics.failure(Config.K_DEBUG, "调试模式：hook getAllowOnReleaseBuilds 失败", t);
        }
    }

    /** 标题英→中（调试菜单本地化）。 */
    private void hookTitle(Class<?> dc) {
        try {
            XposedHelpers.findAndHookMethod(dc, "getTitle", new DiagnosticHook(Config.K_DEBUG) {
                @Override
                public void after(MethodHookParam param) {
                    Object r = param.getResult();
                    if (r instanceof String) {
                        String t = DebugLabels.translate((String) r);
                        if (t != null) {
                            param.setResult(t);
                        }
                    }
                }
            });
            Logx.i("调试模式：已 hook getTitle() → 中文标签");
        } catch (Throwable t) {
            FeatureDiagnostics.failure(Config.K_DEBUG, "调试模式：hook getTitle 失败", t);
        }
    }

    /** 标记已 resume，供调试菜单准入拦截判断。 */
    private void installResumeMarker(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", lpp.classLoader,
                    "onResume", new DiagnosticHook(Config.K_DEBUG) {
                        @Override
                        public void after(MethodHookParam param) {
                            if (!(param.thisObject instanceof Activity)) {
                                return;
                            }
                            Activity act = (Activity) param.thisObject;
                            DebugMenuGuard.markResumed(act);
                        }
                    });
            Logx.i("调试模式：已挂接 Activity.onResume（界面标记）");
        } catch (Throwable t) {
            FeatureDiagnostics.failure(Config.K_DEBUG, "调试模式：挂接 Activity.onResume 失败", t);
        }
    }

    private static String fieldNames(Class<?> c) {
        try {
            Field[] fs = c.getDeclaredFields();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Field f : fs) {
                if (f.isEnumConstant()) {
                    continue;
                }
                if (n++ > 0) {
                    sb.append(", ");
                }
                sb.append(f.getType().getSimpleName()).append(' ').append(f.getName());
            }
            int total = 0;
            if (c.isEnum()) {
                Object[] cs = c.getEnumConstants();
                total = cs == null ? 0 : cs.length;
            }
            return "(枚举常量 " + total + " 个；实例字段=[" + sb + "])";
        } catch (Throwable t) {
            return "?";
        }
    }
}
