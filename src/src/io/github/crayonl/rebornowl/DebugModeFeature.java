package io.github.crayonl.rebornowl;

import android.app.Activity;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 通过取值器放行调试菜单并翻译标题，避免修改枚举构造数据。 */
public class DebugModeFeature extends Feature {

    /**
     * 本次进程里准入 Hook 是否真的装上了。
     *
     * <p>猫头鹰三连击用它判断能不能开调试页：开关关着、或当前版本结构不支持时，
     * 打开只会看到页面自己关掉，不如直接提示用户。
     */
    static volatile boolean installed;

    @Override
    public String key() {
        return Config.K_DEBUG;
    }

    @Override
    public String label() {
        return "调试模式";
    }

    /** 默认开启：入口是猫头鹰连击手势，用户不需要先去打开某个开关。 */
    @Override
    public boolean defaultOn() {
        return true;
    }

    /** 不占设置行：入口只有猫头鹰连击手势。 */
    @Override
    public boolean settingsVisible() {
        return false;
    }

    /** 没有开关可点，所以始终启用。 */
    @Override
    public boolean alwaysOn() {
        return true;
    }

    @Override
    public void check(XC_LoadPackage.LoadPackageParam lpp) throws ReflectiveOperationException {
        Class<?> category = debugCategory(lpp.classLoader);
        if (category.getMethod("getAllowOnReleaseBuilds").getReturnType() != boolean.class
                || category.getMethod("getTitle").getReturnType() != String.class) {
            throw new IllegalStateException(
                    "调试菜单：结构契约不满足（需要 getAllowOnReleaseBuilds()→boolean 与 getTitle()→String）");
        }
        Class.forName(Targets.C_DEBUG_ACTIVITY, false, lpp.classLoader);
        Activity.class.getDeclaredMethod("onResume");
        Activity.class.getMethod("finish");
        Activity.class.getMethod("finishAndRemoveTask");
        // 准入判定点：结构不符时不在这里致命，由 apply 记录降级说明。
        try {
            DebugEligibility.resolve(lpp.classLoader);
        } catch (Throwable error) {
            Logx.w("调试菜单：准入判定点不可用（将以界面拦截兜底）", error);
        }
    }

    /**
     * 调试分类枚举：名字优先，名字变了就按结构找。
     *
     * <p>结构契约：既是枚举，又同时具有 {@code getAllowOnReleaseBuilds()→boolean}
     * 与 {@code getTitle()→String}。这两个方法名是宿主自有语义（不是混淆名），
     * 组合起来足够唯一；命不中或命中多个都按失败处理。
     */
    private static Class<?> debugCategory(ClassLoader loader) {
        Class<?> category = Locator.findByName(Targets.C_DEBUG_CATEGORY, loader);
        if (category == null) { category = Locator.findByName(Targets.C_DEBUG_CATEGORY_OLD, loader); }
        if (category != null) {
            return category;
        }
        Set<String> titled = new LinkedHashSet<>(
                DexIndex.findClassesWithMethod("getTitle", "Ljava/lang/String;", null));
        Class<?> resolved = null;
        for (String descriptor : DexIndex.findClassesWithMethod("getAllowOnReleaseBuilds", "Z", null)) {
            if (!titled.contains(descriptor)) { continue; }
            Class<?> candidate = Locator.findByName(Locator.descToName(descriptor), loader);
            if (candidate == null || !candidate.isEnum() || !hasShape(candidate)) { continue; }
            if (resolved != null) {
                throw new IllegalStateException("调试模式：结构匹配不唯一 " + resolved.getName()
                        + " / " + candidate.getName());
            }
            resolved = candidate;
        }
        if (resolved == null) {
            throw new IllegalStateException("调试模式：DebugCategory 按名字和结构均未命中");
        }
        Logx.i("调试模式：按结构定位 DebugCategory → " + resolved.getName());
        return resolved;
    }

    private static boolean hasShape(Class<?> candidate) {
        try {
            return candidate.getMethod("getAllowOnReleaseBuilds").getReturnType() == boolean.class
                    && candidate.getMethod("getTitle").getReturnType() == String.class;
        } catch (ReflectiveOperationException error) {
            return false;
        }
    }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        applyDebugCategory(lpp);
        DebugEligibility.install(lpp);
        DebugMenuGuard.install(lpp);
        installResumeMarker(lpp);
        installed = true;
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
