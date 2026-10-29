package com.owl.auxiliary;

import android.app.Activity;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 调试模式，分为两部分：
 *
 * <h3>1. 放行宿主调试菜单</h3>
 * 定位 {@code DebugCategory} 后 hook 其全部构造器：
 * <ul>
 *   <li>把标题串用 {@link DebugLabels} 的英→中对照表替换（调试菜单本地化）；</li>
 *   <li>把 {@code allowOnReleaseBuilds} 布尔参数强制为 {@code true} ——
 *       该字段确认为 {@code DebugCategory.getAllowOnReleaseBuilds()}，
 *       release 包下只有它为 true 的条目才会出现在调试菜单里。</li>
 * </ul>
 *
 * 调试菜单入口位于插件设置内；通用设置悬浮球不依赖本功能开关。
 *
 * <p>同时安装 {@link DebugMenuGuard}：宿主在 release 包下会对调试菜单做「准入校验」并立刻
 * 自关闭（详见该类注释），这里拦截该自关闭，否则界面会一闪即退。
 */
public class DebugModeFeature extends Feature {

    /**
     * 是否把 {@code allowOnReleaseBuilds} 强制置 true。
     * 已确认 {@code com.duolingo.data.debug.settings.DebugCategory} 的字段语义
     * （{@code String a=id, String b=title, boolean c=allowOnReleaseBuilds}），故默认开启。
     */
    private static final boolean FORCE_ALLOW_ON_RELEASE = true;

    /** 诊断计数：记录 DebugCategory 构造器被命中的次数。 */
    private static final AtomicInteger HITS = new AtomicInteger();

    @Override
    public String key() {
        return Config.K_DEBUG;
    }

    @Override
    public String label() {
        return "调试模式";
    }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        applyDebugCategory(lpp);
        DebugMenuGuard.install(lpp);
        installResumeMarker(lpp);
    }

    // ------------------------------------------------------------ 调试菜单放行

    private void applyDebugCategory(XC_LoadPackage.LoadPackageParam lpp) {
        Class<?> dc = Locator.byName(Targets.C_DEBUG_CATEGORY, lpp.classLoader);
        if (dc == null) {
            dc = Locator.byName(Targets.C_DEBUG_CATEGORY_OLD, lpp.classLoader);
        }
        if (dc == null) {
            Logx.w("调试模式：未找到 DebugCategory（新旧路径均失败）", null);
            return;
        }
        Logx.i("调试模式：命中 " + dc.getName()
                + " 字段=" + fieldNames(dc) + "；标签表 " + DebugLabels.size() + " 条");

        // ★ 关键：DebugCategory 是 enum，其实例在 <clinit> 里创建，而 <clinit> 早于本模块
        //   装载（宿主启动阶段就被导航图引用），因此 hook 构造器**永远不命中**（实测确认）。
        //   改为 hook 取值器：渲染时才会调用，必然晚于本 hook，稳定生效。
        hookAllowOnRelease(dc);
        hookTitle(dc);
        hookAllCtorsAsFallback(dc);
    }

    /** 让所有调试条目在 release 包可见（{@code getAllowOnReleaseBuilds()} 恒 true）。 */
    private void hookAllowOnRelease(Class<?> dc) {
        try {
            XposedHelpers.findAndHookMethod(dc, "getAllowOnReleaseBuilds", new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.TRUE);
                }
            });
            Logx.i("调试模式：已 hook getAllowOnReleaseBuilds() → true（release 包全量可见）");
        } catch (Throwable t) {
            Logx.w("调试模式：hook getAllowOnReleaseBuilds 失败", t);
        }
    }

    /** 标题英→中（调试菜单本地化）。 */
    private void hookTitle(Class<?> dc) {
        try {
            XposedHelpers.findAndHookMethod(dc, "getTitle", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
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
            Logx.w("调试模式：hook getTitle 失败", t);
        }
    }

    /** 兜底：若本模块在 {@code <clinit>} 之前装载（未来版本可能的时序），构造器 hook 也能生效。 */
    private void hookAllCtorsAsFallback(final Class<?> dc) {
        XposedBridge.hookAllConstructors(dc, new XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                Object[] args = param.args;
                if (args == null) {
                    return;
                }
                HITS.incrementAndGet();
                for (int i = 0; i < args.length; i++) {
                    Object a = args[i];
                    if (a instanceof String) {
                        String t = DebugLabels.translate((String) a);
                        if (t != null) {
                            args[i] = t;
                        }
                    } else if (FORCE_ALLOW_ON_RELEASE && a instanceof Boolean && !((Boolean) a)) {
                        args[i] = Boolean.TRUE;
                    }
                }
            }
        });
    }

    /** 标记已 resume，供调试菜单准入拦截判断。 */
    private void installResumeMarker(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", lpp.classLoader,
                    "onResume", new XC_MethodHook() {
                        @Override
                        public void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Activity)) {
                                return;
                            }
                            Activity act = (Activity) param.thisObject;
                            DebugMenuGuard.markResumed(act);
                        }
                    });
            Logx.i("调试模式：已挂接 Activity.onResume（界面标记）");
        } catch (Throwable t) {
            Logx.w("调试模式：挂接 Activity.onResume 失败", t);
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
