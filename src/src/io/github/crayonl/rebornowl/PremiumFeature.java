package io.github.crayonl.rebornowl;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 会员解锁。
 *
 * <p>两层协作：
 * <ol>
 *   <li><b>视图层</b>：hook {@code PlusViewModel} 中返回类型为
 *       {@code DashboardFeatureDisplayStatus} 的方法，视频通话由宿主判断，其余返回 {@code SUPPORTED}；</li>
 *   <li><b>数据层</b>：hook {@code User} 构造器，把参数里的
 *       {@code SubscriberLevel}（枚举）改写为目标等级，并同步 {@code hasMax} 标志；
 *       目标等级由 {@link Config#P_LEVEL} 决定（{@code super} / {@code max}），
 *       由设置页的单选项切换；</li>
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

    /** 默认开启：模块只默认启用「会员解锁」和「无限时间」两项。 */
    @Override
    public boolean defaultOn() {
        return true;
    }

    @Override
    public void check(XC_LoadPackage.LoadPackageParam lpp) throws ReflectiveOperationException {
        Class<?> model = dashboardModel(lpp.classLoader);
        Class<?> status = SemanticTypes.dashboardStatus(lpp.classLoader);
        if (status.getField(Targets.F_DASH_SUPPORTED).getType() != status
                || java.util.Arrays.stream(model.getDeclaredMethods())
                .noneMatch(method -> status.isAssignableFrom(method.getReturnType()))) {
            throw new IllegalStateException("会员入口：显示状态方法或 SUPPORTED 常量缺失");
        }
        checkUser(lpp);
    }

    private static Class<?> user(ClassLoader loader) {
        return Locator.byKeyContract(loader, Targets.S_USER, type -> {
            Class<?> tier = Class.forName("com.duolingo.data.user.SubscriberLevel", false, loader);
            UserContract.parameters(type, tier);
        });
    }

    private static void checkUser(XC_LoadPackage.LoadPackageParam lpp) throws ReflectiveOperationException {
        Class<?> user = user(lpp.classLoader);
        if (user == null) { throw new IllegalStateException("会员数据：User 类缺失"); }
        Class<?> tier = Class.forName("com.duolingo.data.user.SubscriberLevel", false, lpp.classLoader);
        tier.getField(Targets.F_TIER_GOLD);
        tier.getField(Targets.F_TIER_PREMIUM);
        UserContract.parameters(user, tier);
    }

    static Class<?> dashboardModel(ClassLoader loader) {
        Class<?> status = SemanticTypes.dashboardStatus(loader);
        String descriptor = DexCode.descriptor(status);
        String[] names = DexIndex.findClassesWithMethod(null, descriptor, "Ljava/util/Set;")
                .stream().map(Locator::descToName).toArray(String[]::new);
        return Locator.byContract(loader, names, type -> {
            if (type.isEnum() || java.util.Arrays.stream(type.getDeclaredMethods())
                    .noneMatch(m -> m.getReturnType() == status)) {
                throw new IllegalStateException("会员视图状态方法缺失");
            }
        });
    }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        applyDashboard(lpp);
        applyUser(lpp);
    }

    /** 当前配置的会员等级：true = Max，false = Super。 */
    public static boolean isMaxLevel() {
        return Config.LV_MAX.equalsIgnoreCase(
                Config.getStr(Config.P_LEVEL, Config.LV_MAX));
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
        Class<?> pvm = dashboardModel(lpp.classLoader);
        Class<?> status = SemanticTypes.dashboardStatus(lpp.classLoader);
        if (pvm == null || status == null) {
            FeatureDiagnostics.failure(Config.K_PREMIUM, "会员解锁(视图)：PlusViewModel/DashboardFeatureDisplayStatus 缺失", null);
            return;
        }
        final Object supported = supportedConstant(status);
        if (supported == null) {
            FeatureDiagnostics.failure(Config.K_PREMIUM, "会员解锁(视图)：找不到 SUPPORTED 常量", null);
            return;
        }
        int n = 0;
        for (Method m : pvm.getDeclaredMethods()) {
            if (!status.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            XposedBridge.hookMethod(m, new DiagnosticHook(Config.K_PREMIUM) {
                @Override
                public void before(MethodHookParam param) {
                    if (usesNativeDashboardStatus(param.args)) { return; }
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

    static boolean usesNativeDashboardStatus(Object[] args) {
        for (Object arg : args) {
            if (!(arg instanceof Enum<?>)) { continue; }
            String feature = ((Enum<?>) arg).name();
            if ("VIDEO_CALL_IN_PATH".equals(feature) || "VIDEO_CALL_IN_PRACTICE_HUB".equals(feature)) {
                return true;
            }
        }
        return false;
    }

    /** 取 DashboardFeatureDisplayStatus 的 SUPPORTED 常量（枚举或静态字段）。 */
    private static Object supportedConstant(Class<?> status) {
        return enumConstant(status, Targets.F_DASH_SUPPORTED);
    }

    private void applyUser(XC_LoadPackage.LoadPackageParam lpp) throws ReflectiveOperationException {
        Class<?> user = user(lpp.classLoader);
        Class<?> tier = Class.forName("com.duolingo.data.user.SubscriberLevel", false, lpp.classLoader);
        Object wanted = enumConstant(tier, targetTierName());
        boolean max = isMaxLevel();
        for (java.util.Map.Entry<Constructor<?>, int[]> entry : UserContract.parameters(user, tier).entrySet()) {
            int[] indices = entry.getValue();
            XposedBridge.hookMethod(entry.getKey(), new DiagnosticHook(Config.K_PREMIUM) {
                @Override
                public void before(MethodHookParam param) {
                    Object[] rewritten = param.args.clone();
                    rewritten[indices[0]] = wanted;
                    if (indices[1] >= 0) { rewritten[indices[1]] = max; }
                    param.args = rewritten;
                }
            });
        }
        Logx.i("会员解锁(数据)：已按序列化字段定位等级和 hasMax → " + targetTierName());
    }

    private static Object enumConstant(Class<?> enumClass, String name) {
        if (!enumClass.isEnum()) { throw new IllegalStateException("会员目标不是枚举：" + enumClass.getName()); }
        for (Object value : enumClass.getEnumConstants()) {
            if (name.equals(((Enum<?>) value).name())) { return value; }
        }
        throw new IllegalStateException("会员枚举缺失：" + enumClass.getName() + "." + name);
    }
}
