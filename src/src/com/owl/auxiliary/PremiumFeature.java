package com.owl.auxiliary;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Parcelable;
import android.view.View;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 会员解锁。
 *
 * <p>四层协作：
 * <ol>
 *   <li><b>视图层</b>：hook {@code PlusViewModel} 中返回类型为
 *       {@code DashboardFeatureDisplayStatus} 的方法，直接 {@code setResult(SUPPORTED)}；</li>
 *   <li><b>数据层</b>：hook {@code User} 构造器，把参数里的
 *       {@code SubscriberLevel}（枚举）改写为目标等级，并同步 {@code hasMax} 标志；
 *       目标等级由 {@link Config#P_LEVEL} 决定（{@code super} / {@code max}），
 *       由设置页的单选项切换；</li>
 *   <li><b>拽姐视频通话</b>（无独立开关，随本功能开启；**受服务端限制、无法解锁**，
 *       仅为入口改道）：Max 功能组注入 + 控制板「开始视频通话」点击改直接开呼
 *       （见 {@link #applyVideoCall}）；</li>
 *   <li><b>计费层</b>：hook Play Billing 相关类（见 {@link #applyBilling}）。</li>
 * </ol>
 */
public class PremiumFeature extends Feature {

    @Override
    public String key() {
        return Config.K_PREMIUM;
    }

    @Override
    public String label() {
        return "会员解锁";
    }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        applyDashboard(lpp);
        applyUser(lpp);
        applyVideoCall(lpp);
        applyBilling(lpp);
    }

    /** 当前配置的会员等级：true = Max，false = Super。 */
    public static boolean isMaxLevel() {
        return Config.LV_MAX.equalsIgnoreCase(
                Config.getStr(Config.P_LEVEL, Config.LV_SUPER));
    }

    /**
     * 目标 SubscriberLevel 常量名。
     *
     * <p><b>实机验证结论（6.99.5，务必别照直觉写反）</b>：Duolingo 的枚举命名与产品名是
     * 「历史遗留错位」的 —— 个人档案页右上角徽章实测：
     * <ul>
     *   <li>{@code PREMIUM} → 显示 <b>SUPER</b> 徽章（Super 的旧名就是 Plus/Premium）；</li>
     *   <li>{@code GOLD} → 显示 <b>MAX</b> 徽章。</li>
     * </ul>
     * 因此 {@code super} 对应 PREMIUM、{@code max} 对应 GOLD。
     */
    private static String targetTierName() {
        return isMaxLevel() ? Targets.F_TIER_GOLD : Targets.F_TIER_PREMIUM;
    }

    // ---------------------------------------------------------------- 视图层

    private void applyDashboard(XC_LoadPackage.LoadPackageParam lpp) {
        Class<?> pvm = Locator.byName(Targets.C_PLUS_VIEW_MODEL, lpp.classLoader);
        Class<?> status = Locator.byName(Targets.C_DASH_STATUS, lpp.classLoader);
        if (pvm == null || status == null) {
            Logx.w("会员解锁(视图)：PlusViewModel/DashboardFeatureDisplayStatus 缺失", null);
            return;
        }
        final Object supported = supportedConstant(status);
        if (supported == null) {
            Logx.w("会员解锁(视图)：找不到 SUPPORTED 常量", null);
            return;
        }
        int n = 0;
        for (Method m : pvm.getDeclaredMethods()) {
            if (!status.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(supported);
                }
            });
            n++;
            Logx.i("会员解锁(视图)：已 hook " + m.getName()
                    + (Modifier.isStatic(m.getModifiers()) ? "(static)" : "")
                    + " -> " + supported);
        }
        Logx.i("会员解锁(视图)：共 hook " + n + " 个方法");
    }

    /** 取 DashboardFeatureDisplayStatus 的 SUPPORTED 常量（枚举或静态字段）。 */
    private static Object supportedConstant(Class<?> status) {
        try {
            return XposedHelpers.getStaticObjectField(status, Targets.F_DASH_SUPPORTED);
        } catch (Throwable ignored) {
        }
        // 回退：枚举常量中名字含 SUPPORTED 者
        try {
            Object[] cs = status.getEnumConstants();
            if (cs != null) {
                for (Object c : cs) {
                    if (String.valueOf(c).toUpperCase().contains("SUPPORT")) {
                        return c;
                    }
                }
                if (cs.length > 0) {
                    return cs[0];
                }
            }
        } catch (Throwable ignored) {
        }
        // 回退：任意名为 SUPPORTED 的静态字段
        try {
            for (java.lang.reflect.Field f : status.getDeclaredFields()) {
                if (f.getName().toUpperCase().contains("SUPPORT") && Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    return f.get(null);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ---------------------------------------------------------------- 数据层

    private void applyUser(XC_LoadPackage.LoadPackageParam lpp) {
        Class<?> user = Locator.byKeyOrName(Targets.S_USER, null, lpp.classLoader);
        if (user == null) {
            Logx.w("会员解锁(数据)：未定位 User 类", null);
            return;
        }
        final String tierName = targetTierName();
        final boolean max = isMaxLevel();
        XposedBridge.hookAllConstructors(user, new XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                Object[] args = param.args;
                if (args == null) {
                    return;
                }
                for (int i = 0; i < args.length; i++) {
                    Object a = args[i];
                    if (!(a instanceof Enum)) {
                        continue;
                    }
                    String cls = a.getClass().getSimpleName();
                    if (!cls.contains("SubscriberLevel") && !cls.contains("Tier")
                            && !cls.contains("Plan")) {
                        continue;
                    }
                    Object want = enumConstant(a.getClass(), tierName);
                    if (want != null && want != a) {
                        args[i] = want;
                    }
                    // 同步 hasMax：SubscriberLevel 实参下标 + 固定偏移（见 Targets 注释）
                    int hi = i + Targets.USER_ARG_HAS_MAX_OFFSET;
                    if (hi >= 0 && hi < args.length && args[hi] instanceof Boolean
                            && ((Boolean) args[hi]) != max) {
                        args[hi] = Boolean.valueOf(max);
                    }
                }
            }
        });
        Logx.i("会员解锁(数据)：已 hook " + user.getName() + " 构造器 → 等级 " + tierName
                + "（" + (max ? "Max" : "Super") + "）");
    }

    private static Object enumConstant(Class<?> enumClass, String name) {
        try {
            Object[] cs = enumClass.getEnumConstants();
            if (cs == null) {
                return null;
            }
            for (Object c : cs) {
                if (name.equals(((Enum<?>) c).name())) {
                    return c;
                }
            }
            for (Object c : cs) {
                if (((Enum<?>) c).name().contains(name)) {
                    return c;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 按名字批量解析枚举常量；名字找不到的跳过并记日志。 */
    private static Object[] enumConstants(Class<?> enumCls, String[] names) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (String name : names) {
            Object c = enumConstant(enumCls, name);
            if (c == null) {
                Logx.w("会员解锁(视频通话)：枚举常量缺失 " + enumCls.getSimpleName() + "." + name, null);
            } else {
                out.add(c);
            }
        }
        return out.toArray();
    }

    // ---------------------------------------------------------------- 拽姐视频通话（随会员解锁）

    /**
     * 拽姐视频通话入口改道（并入会员解锁，无独立开关）。
     *
     * <p><b>结论先行：本功能无法解锁</b> —— 通话会话由多邻国服务端签发，非真实订阅
     * 必被拒绝（{@code 403 Subscription required}）；以下改造仅是客户端交互修正
     * （点击不再退回、可进入通话界面），<b>不构成解锁</b>。
     *
     * <p>6.100.3 实测的「开始视频通话」链路（勿重复推导）：
     * Max 控制板（{@code PlusActivity}）「功能设置」行由 {@code DashboardFeature} 列表渲染；
     * 点「开始视频通话」→ 该行的点击监听器只发事件 {@code hdv(20)}：
     * <pre>
     * k2k.d(TabId.PRACTICE_HUB);  // 切到练习基地页签
     * activity.setResult(-1);
     * activity.finish();          // 关闭控制板
     * </pre>
     * —— 即「切页签 + 关面板」而已；实测落回主界面（表现为"自动退回上一个页面"），
     * 通话界面根本不会被唤起。练习基地/路径上的通话入口又受服务端下发的
     * 「视频通话在路径/练习基地」开关限制，因此这条入口对非真实 Max 用户完全不可达。
     *
     * <p>两步处理：
     * <ol>
     *   <li><b>功能组注入</b>：给 {@code SubscriptionFeatureGroup} 全部构造器里的
     *       {@code PSet} 实参追加 VIDEO_CALL_IN_PATH / VIDEO_CALL_IN_PRACTICE_HUB
     *       —— 控制板 CTA 与练习基地卡片的判定读的就是该功能组；</li>
     *   <li><b>点击改直接开呼</b>：hook 控制板行点击监听器工厂（{@code j3v}），
     *       对 VIDEO_CALL 行包一层：点击时先以 {@code PracticeHub} 为来源启动
     *       {@code VideoCallActivity}（其视图模型强制要求 intent 携带
     *       {@code call_origin} 附加项），再执行原监听器
     *       （保留「切练习基地 + 关控制板」的原始行为）。</li>
     * </ol>
     */
    private void applyVideoCall(XC_LoadPackage.LoadPackageParam lpp) {
        applyVideoCallFeatures(lpp);
        applyVideoCallLauncher(lpp);
    }

    /** 改写日志的条数上限（该构造器可能高频触发，避免刷屏）。 */
    private static final int VC_LOG_LIMIT = 5;

    private static volatile int sVcRewritten = 0;
    private static volatile int sVcSeen = 0;
    private static volatile boolean sVcLaunchWarned = false;

    /** ① 给每个 {@code SubscriptionFeatureGroup} 的功能集加上视频通话两项（并集，不删原值）。 */
    private void applyVideoCallFeatures(XC_LoadPackage.LoadPackageParam lpp) {
        final Class<?> featuresCls = Locator.byName(Targets.C_SUB_FEATURES, lpp.classLoader);
        final Class<?> groupCls = Locator.byName(Targets.C_SUB_FEATURE_GROUP, lpp.classLoader);
        final Class<?> psetCls = Locator.byName(Targets.C_PSET, lpp.classLoader);
        if (featuresCls == null || groupCls == null || psetCls == null) {
            Logx.w("会员解锁(视频通话)：SubscriptionFeatures / FeatureGroup / PSet 定位失败"
                    + "（features=" + featuresCls + " group=" + groupCls + " pset=" + psetCls + "）", null);
            return;
        }
        final Object[] wants = enumConstants(featuresCls, Targets.F_VIDEO_CALL_FEATURES);
        if (wants.length == 0) {
            Logx.w("会员解锁(视频通话)：目标功能枚举一个都没解析到，放弃功能组注入", null);
            return;
        }
        Logx.i("会员解锁(视频通话)：目标功能 = " + java.util.Arrays.toString(wants));

        final Method plus;
        try {
            plus = psetCls.getMethod("plus", Object.class);
            plus.setAccessible(true);
        } catch (Throwable t) {
            Logx.w("会员解锁(视频通话)：PSet.plus 不存在，放弃功能组注入", t);
            return;
        }
        XposedBridge.hookAllConstructors(groupCls, new XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                appendVideoCallFeatures(param.args, plus, wants);
            }
        });
        Logx.i("会员解锁(视频通话)：已 hook " + groupCls.getName() + " 全部构造器（PSet 并集注入）");
    }

    private static void appendVideoCallFeatures(Object[] args, Method plus, Object[] wants) {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            Object original = args[i];
            if (!plus.getDeclaringClass().isInstance(original)) {
                continue;
            }
            int seen = ++sVcSeen;
            if (seen <= VC_LOG_LIMIT) {
                Logx.i("会员解锁(视频通话)：命中 FeatureGroup 构造器（第 " + seen + " 次）");
            }
            Object merged = original;
            try {
                for (Object wanted : wants) {
                    merged = plus.invoke(merged, wanted);
                }
            } catch (Throwable t) {
                Logx.w("会员解锁(视频通话)：PSet.plus 失败（保持原集合）", t);
                return;
            }
            if (merged != original) {
                args[i] = merged;
                int count = ++sVcRewritten;
                if (count <= VC_LOG_LIMIT) {
                    Logx.i("会员解锁(视频通话)：功能集已扩充（第 " + count + " 次）");
                }
            }
        }
    }

    /** ② 控制板「开始视频通话」点击 → 直接启动 {@code VideoCallActivity}（再执行原行为）。 */
    private void applyVideoCallLauncher(XC_LoadPackage.LoadPackageParam lpp) {
        final Class<?> factory = Locator.byName(Targets.C_DASHBOARD_CLICK_FACTORY, lpp.classLoader);
        final Class<?> originCls = Locator.byName(Targets.C_VC_ORIGIN_PRACTICE_HUB, lpp.classLoader);
        if (factory == null || originCls == null) {
            throw new IllegalStateException("会员解锁(视频通话)：点击工厂或通话来源类缺失");
        }
        Method invoke = dashboardInvoke(factory);
        XposedBridge.hookMethod(invoke, new XC_MethodHook() {
            @Override
            public void afterHookedMethod(MethodHookParam param) {
                wrapVideoCallResult(param, originCls);
            }
        });
        Logx.i("会员解锁(视频通话)：已 hook " + factory.getName() + "#invoke（控制板行点击工厂）");
    }

    static Method dashboardInvoke(Class<?> factory) {
        try {
            Method method = factory.getDeclaredMethod("invoke", Object.class);
            if (method.getReturnType() != Object.class || Modifier.isStatic(method.getModifiers())) {
                throw new IllegalStateException("点击工厂签名不匹配：" + factory.getName());
            }
            return method;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("点击工厂缺少 invoke(Object)：" + factory.getName(), e);
        }
    }

    private static void wrapVideoCallResult(XC_MethodHook.MethodHookParam param, Class<?> originCls) {
        try {
            Object[] args = param.args;
            if (args == null || args.length != 1 || !(args[0] instanceof Enum)) {
                return;
            }
            if (!Targets.F_DASHBOARD_FEATURE_VIDEO_CALL.equals(((Enum<?>) args[0]).name())) {
                return;
            }
            Object result = param.getResult();
            if (!(result instanceof View.OnClickListener)) {
                return;
            }
            View.OnClickListener original = (View.OnClickListener) result;
            param.setResult((View.OnClickListener) v -> {
                startVideoCall(v.getContext(), originCls);
                original.onClick(v);
            });
            Logx.i("会员解锁(视频通话)：已包装控制板视频通话点击");
        } catch (Throwable t) {
            Logx.w("会员解锁(视频通话)：包装点击失败（保持原行为）", t);
        }
    }

    /** 启动通话界面；失败仅记日志（点击的原行为不受影响）。 */
    private static void startVideoCall(Context ctx, Class<?> originCls) {
        try {
            Constructor<?> ctor = originCls.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object origin = ctor.newInstance();
            Intent intent = new Intent();
            intent.setClassName(Targets.HOST_PKG, Targets.C_VIDEO_CALL_ACTIVITY);
            intent.putExtra(Targets.E_VIDEO_CALL_CALL_ORIGIN, (Parcelable) origin);
            Context base = ctx;
            while (base instanceof ContextWrapper && !(base instanceof Activity)) {
                base = ((ContextWrapper) base).getBaseContext();
            }
            if (!(base instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            base.startActivity(intent);
            Logx.i("会员解锁(视频通话)：已请求启动 " + Targets.C_VIDEO_CALL_ACTIVITY
                    + "（来源=" + originCls.getSimpleName() + "）");
        } catch (Throwable t) {
            if (!sVcLaunchWarned) {
                sVcLaunchWarned = true;
                Logx.w("会员解锁(视频通话)：启动通话界面失败（本次点击退化为原行为）", t);
            }
        }
    }

    // ---------------------------------------------------------------- 计费层

    /**
     * Play Billing 伪造（10 个 hook 点）。
     *
     * <p><b>TODO</b>：需要在 {@code BillingResult}/{@code ProductDetails}/{@code Purchase}
     * 的构造器与 {@code BillingFlowParams$Builder.build} 上改写 {@code recurrenceMode}、
     * {@code offerToken}、{@code ProductId} 等字段，伪造「已购买」。当前只做探测与日志，
     * 便于在真机确认这些类的实际方法/字段后再补齐改写逻辑。
     */
    private void applyBilling(XC_LoadPackage.LoadPackageParam lpp) {
        String[] candidates = {
                "com.android.billingclient.api.BillingResult",
                "com.android.billingclient.api.Purchase",
                "com.android.billingclient.api.ProductDetails",
                "com.android.billingclient.api.BillingFlowParams$Builder",
                "com.android.billingclient.api.BillingFlowParams$ProductDetailsParams$Builder",
        };
        for (final String n : candidates) {
            final boolean[] logged = {false};
            Class<?> c = Locator.findByName(n, lpp.classLoader);
            if (c == null) {
                continue;
            }
            try {
                XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                    @Override
                    public void afterHookedMethod(MethodHookParam param) {
                        if (!logged[0]) {
                            logged[0] = true;
                            Logx.i("[billing] " + n + " ctor 首次命中");
                        }
                    }
                });
                Logx.i("会员解锁(计费)：已探测 " + n);
            } catch (Throwable t) {
                Logx.w("会员解锁(计费)：hook " + n + " 失败", t);
            }
        }
    }
}
