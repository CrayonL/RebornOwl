package com.owl.auxiliary;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 调试菜单准入拦截（调试模式的第 2 部分）。
 *
 * <h3>背景（6.99.5 实测）</h3>
 * 宿主的调试菜单界面有「准入校验」，未通过时会打印
 * {@code Ineligible user accessed the debug menu} 并立刻 {@code finish()}，
 * 表现为「点进去一闪就退回主界面」。校验逻辑如下：
 *
 * <pre>
 * // defpackage.s7#invokeSuspend (case 1)  ← combine(两个 Flow) { z4, z5 -> ... }
 * kr3 k = BuildConfigProvider;                 // a = isDebugBuild, b = buildType 相关
 * boolean eligible = true;
 * if (!k.a &amp;&amp; !k.b &amp;&amp; !z4 &amp;&amp; !z5) eligible = false;
 * // 6.99.5 release 包：k.a = false，k.b = "release".contains("benchmark") = false，
 * // z4 / z5 来自服务端的「内部员工 / 白名单」标记 ⇒ 永远 false ⇒ 永远不合格
 * </pre>
 *
 * <p>而且 {@code kr3.b} 的写法本身就与构建类型无关（恒 false），说明该分支在 release 上
 * 本就不可能通过 —— 调试菜单只有内部构建才能进。
 *
 * <h3>本类做法</h3>
 * 不去逐个伪造那些 Flow（它们的上游随版本漂移且依赖服务端），而是<b>只在「调试界面刚打开、
 * 还没真正显示出来」的时间窗内吞掉 {@code finish()} 调用</b>：
 * <ul>
 *   <li>开关打开后：界面留在前台，正常渲染；</li>
 *   <li>窗口期过后 / 界面已 resume：不拦截，返回键可以正常关闭。</li>
 * </ul>
 * 对宿主无副作用——只作用于 {@code com.duolingo.debug.*} / {@code *.debug.*} 的界面。
 */
public final class DebugMenuGuard {

    /** 「刚打开」窗口：此期间内的 finish() 一律拦截（毫秒）。 */
    private static final long GRACE_MS = 3000L;

    /** 已真正 resume 过的 Activity（一旦 resume 说明界面显示成功）。 */
    private static final Map<Activity, Boolean> RESUMED =
            Collections.synchronizedMap(new WeakHashMap<Activity, Boolean>());

    private static volatile long sOpenedAt = 0L;

    private DebugMenuGuard() {}

    /** 由 {@link FloatBall} 在点击时打点，作为时间锚点。 */
    public static void markOpening() {
        sOpenedAt = SystemClock.uptimeMillis();
    }

    /** 由统一的 Activity.onResume 钩子回调。 */
    public static void markResumed(Activity act) {
        if (act != null) {
            RESUMED.put(act, Boolean.TRUE);
        }
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpp) {
        hookDebugOnCreate(lpp, Targets.C_BASE_DEBUG_ACTIVITY);
        hookDebugOnCreate(lpp, Targets.C_BASE_DEBUG_COMPOSE_ACTIVITY);
        hookFinish(lpp, "finish");
        hookFinish(lpp, "finishAndRemoveTask");
    }

    /** 调试界面 onCreate 也作为时间锚点（不依赖悬浮球入口）。 */
    private static void hookDebugOnCreate(XC_LoadPackage.LoadPackageParam lpp, String cls) {
        try {
            XposedHelpers.findAndHookMethod(cls, lpp.classLoader, "onCreate",
                    Bundle.class, new XC_MethodHook() {
                        @Override
                        public void afterHookedMethod(MethodHookParam param) {
                            sOpenedAt = SystemClock.uptimeMillis();
                            Logx.i("调试模式：调试界面 onCreate（准入拦截已就绪）");
                        }
                    });
        } catch (Throwable t) {
            Logx.i("调试模式：未挂到 " + cls + "（非致命）");
        }
    }

    private static void hookFinish(XC_LoadPackage.LoadPackageParam lpp, String method) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", lpp.classLoader,
                    method, new XC_MethodHook() {
                        @Override
                        public void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.thisObject instanceof Activity)) {
                                    return;
                                }
                                Activity act = (Activity) param.thisObject;
                                String name = act.getClass().getName();
                                if (!isDebugScreen(name)) {
                                    return;
                                }
                                boolean tooEarly =
                                        SystemClock.uptimeMillis() - sOpenedAt < GRACE_MS;
                                boolean notResumedYet = !RESUMED.containsKey(act);
                                if (tooEarly || notResumedYet) {
                                    param.setResult(null);
                                    Logx.i("调试模式：已拦截 " + name + "." + method
                                            + "（准入校验触发的自关闭）");
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            Logx.i("调试模式：已挂接 Activity." + method + "（拦截调试界面自关闭）");
        } catch (Throwable t) {
            Logx.w("调试模式：挂接 Activity." + method + " 失败", t);
        }
    }

    private static boolean isDebugScreen(String name) {
        if (name == null) {
            return false;
        }
        return name.startsWith("com.duolingo.debug.")
                || name.contains(".debug.")
                || name.contains("DebugActivity");
    }
}
