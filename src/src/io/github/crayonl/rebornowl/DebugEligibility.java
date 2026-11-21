package io.github.crayonl.rebornowl;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 调试菜单准入判定（调试模式的第 1 部分）。
 *
 * <h3>背景</h3>
 * 宿主调试界面真正的闸门不是 {@code DebugCategory} 的枚举数据，而是一段
 * 「本机是否 debug 构建 + 服务端内部员工/白名单标记」的合并结果。release 包上该结果为
 * false，于是宿主打印
 * {@code Ineligible user accessed the debug menu}（DuoLog/平台稳定性），
 * 并向界面发一个「立即退出」事件。
 *
 * <h3>6.106.5 实测结构</h3>
 * 判定与副作用都写在 R8 合并后的 lambda 类里（当前 {@code Lj8}），
 * 该类 {@code accept(Object)} 的不同分支由构造参数里的一个 byte 选路：
 *
 * <pre>
 * // 只有一个分支接收裸 Boolean，其余分支接收元组/Throwable 等
 * if (!((Boolean) value).booleanValue()) {
 *     logger.a(PLATFORM_STABILITY_PERFORMANCE, "Ineligible user accessed the debug menu");
 *     debugComponent.e.onNext(Unit);   // 界面随后 finish()
 * }
 * </pre>
 *
 * <h3>做法</h3>
 * 不伪造上游 flow（其上游随版本漂移且依赖服务端），而是把这一次判定**当作通过**：
 * 只在「参数是 {@link Boolean#FALSE}」时吞掉该回调，等价于宿主认为本账号有资格。
 * 判定为 true 时宿主本来就不做任何事，因此不会影响别的合并分支 —— 那些分支收到的
 * 是元组、字符串或异常，不是裸 Boolean。
 *
 * <p>与 {@link DebugMenuGuard}（拦截随后的自关闭）互为兜底：本类失效时界面仍可显示，
 * 但会留下 Ineligible 记录；本类生效时连记录都不会出现。
 */
final class DebugEligibility {

    private static volatile int sSwallowed;

    private DebugEligibility() {}

    /** 解析并校验目标；结构不符时抛出，由调用方决定是致命还是降级。 */
    static Method resolve(ClassLoader loader) {
        String descriptor = DexIndex.findClassContaining(Targets.S_DEBUG_INELIGIBLE);
        if (descriptor == null) {
            throw new IllegalStateException("调试准入：特征串未命中");
        }
        Class<?> owner = Locator.findByName(Locator.descToName(descriptor), loader);
        if (owner == null) {
            throw new IllegalStateException("调试准入：类加载失败 " + descriptor);
        }
        return accept(owner);
    }

    private static Method accept(Class<?> owner) {
        Method found = null;
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!"accept".equals(method.getName())
                        || method.getParameterTypes().length != 1
                        || method.getParameterTypes()[0] != Object.class
                        || method.getReturnType() != void.class) {
                    continue;
                }
                if (found != null) {
                    throw new IllegalStateException("调试准入：accept 不唯一 " + owner.getName());
                }
                found = method;
            }
        }
        if (found == null) {
            throw new IllegalStateException("调试准入：accept(Object) 缺失 " + owner.getName());
        }
        return found;
    }

    static void install(XC_LoadPackage.LoadPackageParam lpp) {
        Method accept;
        try {
            accept = resolve(lpp.classLoader);
        } catch (Throwable error) {
            FeatureDiagnostics.failure(Config.K_DEBUG, "调试模式：准入判定点缺失，仍以界面拦截兜底", error);
            return;
        }
        XposedBridge.hookMethod(accept, new DiagnosticHook(Config.K_DEBUG) {
            @Override
            public void before(MethodHookParam param) {
                if (param.args.length != 1 || !Boolean.FALSE.equals(param.args[0])) {
                    return;
                }
                param.setResult(null);
                note(param);
            }
        });
        Logx.i("调试模式：已挂接准入判定 " + accept.getDeclaringClass().getName()
                + ".accept(Object)，判定为不合格时按合格处理");
    }

    private static synchronized void note(XC_MethodHook.MethodHookParam param) {        sSwallowed++;
        if (sSwallowed > 3) {
            return;
        }
        Logx.i("调试模式：已放行准入判定（第 " + sSwallowed + " 次）"
                + " @" + param.method.getDeclaringClass().getSimpleName());
    }

    /** 供测试与日志使用：本进程已放行次数。 */
    static int swallowed() {
        return sSwallowed;
    }
}
