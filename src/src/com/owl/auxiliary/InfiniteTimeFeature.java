package com.owl.auxiliary;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 无限时间。
 *
 * <p>做法：
 * <ul>
 *   <li>定位两个「限时练习」模型类并 hook 构造器，把时长参数改成 99 分钟（5940 秒）；</li>
 *   <li>定位「会话调试设置」类，在构造完成后把 maxSessionLength 字段写成 0（视为不限）。</li>
 * </ul>
 *
 * <p>时长参数下标：{@code TimedMultiSessionPractice} 为第 1 参、
 * {@code TimedMatchPractice} 为第 0 参。若宿主版本参数顺序变动，请只调整本类。
 */
public class InfiniteTimeFeature extends Feature {

    /**
     * 目标时长：99 分钟（单位＝秒）。
     *
     * <p>依据 6.100.3 逆向：该参数在 {@code po10#accept} 中被直接传入
     * {@code RampupTimerState$Paused(remainingSeconds=…)}，字段语义即「剩余秒数」。
     * 原先的 999999999 秒≈31 年（显示畸长）；99 分钟 = 5940 秒，足够覆盖单次限时练习。
     */
    private static final int GAME_TIME_SEC = 99 * 60;

    @Override
    public String key() {
        return Config.K_GAMETIME;
    }

    @Override
    public String label() {
        return "无限时间";
    }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        hookIndex(lpp, Targets.S_TIMED_MULTI, 1);
        hookIndex(lpp, Targets.S_TIMED_MATCH, 0);
        hookSessionDebug(lpp);
    }

    private void hookIndex(XC_LoadPackage.LoadPackageParam lpp, String key, final int idx) {
        Class<?> c = Locator.byKey(key, lpp.classLoader);
        if (c == null) {
            Logx.w("无限时间：未定位 " + key, null);
            return;
        }
        XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                Object[] args = param.args;
                if (args == null || idx >= args.length) {
                    return;
                }
                if (args[idx] instanceof Integer) {
                    Logx.i("无限时间：" + param.method.getName() + " " + idx
                            + " 由 " + args[idx] + " → " + GAME_TIME_SEC);
                    args[idx] = GAME_TIME_SEC;
                }
            }
        });
        Logx.i("无限时间：已 hook " + c.getName() + " args[" + idx + "]");
    }

    private void hookSessionDebug(XC_LoadPackage.LoadPackageParam lpp) {
        Class<?> c = Locator.byKey(Targets.S_SESSION_DEBUG, lpp.classLoader);
        if (c == null) {
            Logx.w("无限时间：未定位 " + Targets.S_SESSION_DEBUG, null);
            return;
        }
        XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                Object[] args = param.args;
                if (args == null) {
                    return;
                }
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof Integer) {
                        args[i] = 0; // 0 = 不限
                    }
                }
            }
        });
        Logx.i("无限时间：已 hook 会话调试设置 " + c.getName());
    }
}
