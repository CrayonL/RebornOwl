package com.owl.auxiliary;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 两个独立开关共用一个宿主时钟，重启时应用各自的配置。 */
public class TimezoneFeature extends Feature {
    private final boolean date;

    public TimezoneFeature(boolean date) { this.date = date; }

    @Override
    public String key() { return date ? Config.K_DATE : Config.K_ZONEID; }

    @Override
    public String label() { return date ? "日期" : "地区"; }

    @Override
    public boolean defaultOn() { return true; }

    @Override
    public void check(XC_LoadPackage.LoadPackageParam lpp) throws ReflectiveOperationException {
        Class<?> zone = Class.forName(Targets.C_TIME_ZONE, false, lpp.classLoader);
        zone.getConstructor(ZoneId.class);
        resolveClock(lpp.classLoader, zone);
    }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        // 两项均开启时由日期项安装一次，避免重复 Hook 同一组方法。
        if (!date && Config.getOr(Config.K_DATE, true)) { return; }
        Clock clock = DateOverride.configured(Clock.systemDefaultZone(),
                Config.activeDate(), Config.activeRegion());
        ZoneId zone = clock.getZone();
        Class<?> kotlinZone = XposedHelpers.findClass(Targets.C_TIME_ZONE, lpp.classLoader);
        List<Method> methods = resolveClock(lpp.classLoader, kotlinZone);
        Method instantMethod = methods.get(0);
        Class<?> instant = instantMethod.getReturnType();
        Constructor<?> instantConstructor = instant.getConstructor(long.class, int.class);
        Object zoneValue = kotlinZone.getConstructor(ZoneId.class).newInstance(zone);
        HostClockHook hook = new HostClockHook(clock, instantConstructor, zoneValue);
        for (Method method : methods) {
            XposedBridge.hookMethod(method, hook);
        }
        Logx.i("日期/地区：已适配宿主时钟 " + instantMethod.getDeclaringClass().getName() + "，时区=" + zone
                + "，日期=" + LocalDate.now(clock) + "；单调计时不变");
    }

    private static List<Method> resolveClock(ClassLoader loader, Class<?> kotlinZone) {
        String[] names = DexIndex.findClassesWithMethod(null, "Ljava/util/Calendar;", "Ljava/util/TimeZone;")
                .stream().map(Locator::descToName).toArray(String[]::new);
        Class<?> host = Locator.byContract(loader, names, type -> resolveMethods(type, kotlinZone));
        try { return resolveMethods(host, kotlinZone); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("宿主时钟结构不匹配", error); }
    }

    private static List<Method> resolveMethods(Class<?> host, Class<?> kotlinZone)
            throws ReflectiveOperationException {
        List<Method> methods = new ArrayList<>();
        methods.add(instantMethod(host));
        for (Class<?> type : new Class<?>[]{LocalDateTime.class, kotlinZone, Calendar.class,
                ZoneId.class, Instant.class, LocalDate.class}) {
            Class<?>[] parameters = type == Calendar.class ? new Class<?>[]{TimeZone.class} : new Class<?>[0];
            methods.add(methodReturning(host, type, parameters));
        }
        return methods;
    }

    private static Method instantMethod(Class<?> host) throws NoSuchMethodException {
        Method result = null;
        for (Method method : host.getDeclaredMethods()) {
            if (method.getParameterCount() != 0 || !concreteInstance(method)) { continue; }
            try { method.getReturnType().getConstructor(long.class, int.class); }
            catch (NoSuchMethodException ignored) { continue; }
            if (result != null) { throw new NoSuchMethodException("宿主时间戳方法不唯一：" + host); }
            result = method;
        }
        if (result == null) { throw new NoSuchMethodException("宿主时间戳方法缺失：" + host); }
        return result;
    }

    private static Method methodReturning(Class<?> host, Class<?> returned, Class<?>[] parameters)
            throws NoSuchMethodException {
        Method result = null;
        for (Method method : host.getDeclaredMethods()) {
            if (!concreteInstance(method) || method.getReturnType() != returned
                    || !java.util.Arrays.equals(method.getParameterTypes(), parameters)) { continue; }
            if (result != null) { throw new NoSuchMethodException("宿主时钟方法不唯一：" + returned); }
            result = method;
        }
        if (result == null) { throw new NoSuchMethodException("宿主时钟方法缺失：" + returned); }
        return result;
    }

    private static boolean concreteInstance(Method method) {
        return !Modifier.isStatic(method.getModifiers()) && !Modifier.isAbstract(method.getModifiers());
    }

    private static final class HostClockHook extends DiagnosticHook {
        private final Clock clock;
        private final Constructor<?> instantConstructor;
        private final Object kotlinZone;
        private String reportedDate;

        HostClockHook(Clock clock, Constructor<?> instantConstructor, Object kotlinZone) {
            super(Config.K_DATE);
            this.clock = clock;
            this.instantConstructor = instantConstructor;
            this.kotlinZone = kotlinZone;
        }

        @Override
        public void before(MethodHookParam param) {
            Method method = (Method) param.method;
            final Object result;
            try {
                result = valueFor(method, param.args);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("宿主时钟返回值构造失败：" + method, error);
            }
            param.setResult(result);
            if (method.getReturnType() == LocalDate.class && !result.toString().equals(reportedDate)) {
                reportedDate = result.toString();
                Logx.i("宿主时钟：实际读取日期 " + method.getName() + "() → " + reportedDate + "，时区=" + clock.getZone());
            }
        }

        private Object valueFor(Method method, Object[] args) throws ReflectiveOperationException {
            Class<?> returned = method.getReturnType();
            if (returned == instantConstructor.getDeclaringClass()) {
                Instant instant = clock.instant();
                return instantConstructor.newInstance(instant.getEpochSecond(), instant.getNano());
            }
            if (returned == LocalDateTime.class) { return LocalDateTime.now(clock); }
            if (returned == kotlinZone.getClass()) { return kotlinZone; }
            if (returned == ZoneId.class) { return clock.getZone(); }
            if (returned == Instant.class) { return clock.instant(); }
            if (returned == LocalDate.class) { return LocalDate.now(clock); }
            if (returned != Calendar.class) { throw new IllegalArgumentException("未适配的时钟方法：" + method); }
            TimeZone zone = args[0] == null ? TimeZone.getTimeZone(clock.getZone()) : (TimeZone) args[0];
            Calendar calendar = Calendar.getInstance(zone);
            calendar.setTimeInMillis(clock.millis());
            return calendar;
        }
    }
}
