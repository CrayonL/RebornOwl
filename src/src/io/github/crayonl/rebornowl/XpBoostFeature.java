package io.github.crayonl.rebornowl;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 经验加成：账号没有经验道具时，把选定的 2 倍 / 3 倍写进真实课程的结算。
 *
 * <h3>宿主结构（6.106.5-china / 2503，设备实装 DEX 实测）</h3>
 * 结算请求体是 {@code CompletedSessionSurrogate} 的 JSON，里面有
 * {@code xpBoostMultiplier}(double) 与 {@code hasBoost}(bool)。这两个值在打包时
 * **完全来自会话模型的 {@code i:D} / {@code j:Z}**（{@code vd8.serialize}），
 * 而会话模型又来自「提交瞬间按背包算出的加成」：
 *
 * <pre>
 * r8e0.a(User) : double  // 无加成 1.0；有加成取 InventoryItem 的倍率
 * r8e0.d(User) : boolean // 背包里是否存在生效中的 xp_boost_stackable
 *        ↓
 * Session.i / Session.j  →  界面显示 XP（ceil((基础+附加)*倍率)）与 PUT /sessions/%s 的请求体
 * </pre>
 *
 * <p>所以只要在这一对「从背包算加成」的静态方法上改结果，界面显示的 XP、会话模型和
 * 上送服务端的结算载荷就会一致地按所选倍率走 —— 不需要在序列化阶段反射改字段，
 * 也不会碰任何库存/消耗流程（本类不写背包、不触发领取）。
 *
 * <h3>定位方式（不依赖混淆名）</h3>
 * 用背包 id 字符串 {@code xp_boost_stackable} 找到类，再要求同一个类里同时存在
 * 「单参数 → double」与「单参数 → boolean」且参数类型相同的一对静态方法；不满足就
 * 显式失败，绝不猜。
 */
public class XpBoostFeature extends Feature {

    /** 背包里经验加成的 id；属于服务端道具契约，跨版本比类名稳定。 */
    static final String S_BOOST_ITEM = "xp_boost_stackable";

    private volatile Class<?> sOwner;
    private volatile Method sMultiplier;
    private volatile Method sHasBoost;

    @Override
    public String key() {
        return Config.K_XPBOOST;
    }

    @Override
    public String label() {
        return "经验加成";
    }

    @Override
    public void check(XC_LoadPackage.LoadPackageParam lpp) {
        resolve(lpp.classLoader);
    }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) {
        resolve(lpp.classLoader);
        double wanted = Config.xpBoostMultiplier();
        XposedBridge.hookMethod(sMultiplier, new DiagnosticHook(Config.K_XPBOOST) {
            @Override
            public void before(MethodHookParam param) {
                param.setResult(wanted);
                note(wanted);
            }
        });
        XposedBridge.hookMethod(sHasBoost, new DiagnosticHook(Config.K_XPBOOST) {
            @Override
            public void before(MethodHookParam param) {
                param.setResult(Boolean.TRUE);
            }
        });
        Logx.i("经验加成：已挂接 " + sOwner.getName() + " 的倍率与加成判定，结算写入 "
                + wanted + " 倍");
    }

    // ------------------------------------------------------------ 定位

    private void resolve(ClassLoader loader) {
        if (sMultiplier != null) {
            return;
        }
        synchronized (this) {
            if (sMultiplier != null) {
                return;
            }
            // 背包 id 是通用字符串（同包里 40+ 个类都提到它），所以不能取第一个，
            // 必须让「单参数→double 与 单参数→boolean 且参数类型相同」的结构筛选唯一命中。
            List<String> matched = new ArrayList<>();
            List<Method[]> pairs = new ArrayList<>();
            for (String descriptor : DexIndex.findClassesContaining(S_BOOST_ITEM)) {
                Class<?> type = Locator.findByName(Locator.descToName(descriptor), loader);
                if (type == null) {
                    continue;
                }
                Method[] pair = pair(type);
                if (pair == null) {
                    continue;
                }
                matched.add(type.getName());
                pairs.add(pair);
            }
            if (pairs.size() > 1) {
                throw new IllegalStateException("经验加成：结构匹配不唯一 " + matched);
            }
            if (pairs.isEmpty()) {
                throw new IllegalStateException(
                        "经验加成：结构契约不满足（需要含 "
                                + S_BOOST_ITEM + " 的类里成对的 单参数→double / 单参数→boolean）");
            }
            sMultiplier = pairs.get(0)[0];
            sHasBoost = pairs.get(0)[1];
            sOwner = sMultiplier.getDeclaringClass();
            Logx.i("经验加成：命中 " + sOwner.getName() + " → "
                    + sMultiplier.getName() + "()/" + sHasBoost.getName() + "()");
        }
    }

    /** 同一个类里成对的「单参数 → double」与「单参数 → boolean」静态方法。 */
    private static Method[] pair(Class<?> type) {
        Method multiplier = null;
        Method hasBoost = null;
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> param = method.getParameterTypes()[0];
            if (method.getReturnType() == double.class) {
                if (multiplier != null && multiplier.getParameterTypes()[0] != param) {
                    return null;
                }
                multiplier = method;
            } else if (method.getReturnType() == boolean.class) {
                if (hasBoost != null && hasBoost.getParameterTypes()[0] != param) {
                    return null;
                }
                hasBoost = method;
            }
        }
        if (multiplier == null || hasBoost == null) {
            return null;
        }
        if (multiplier.getParameterTypes()[0] != hasBoost.getParameterTypes()[0]) {
            return null;
        }
        return new Method[]{multiplier, hasBoost};
    }

    // ------------------------------------------------------------ 日志

    private static int sWrites;

    private static synchronized void note(double wanted) {
        if (sWrites++ < 2) {
            Logx.i("经验加成：结算按 " + wanted + " 倍计算（第 " + sWrites + " 次）");
        }
    }
}
