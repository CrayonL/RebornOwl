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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 6.103.6 的宿主时钟适配；日期与时区在重启宿主时统一应用。 */
public class TimezoneFeature extends Feature {
    @Override
    public String key() { return Config.K_ZONEID; }

    @Override
    public String label() { return "日期时区"; }

    @Override
    public boolean defaultOn() { return true; }

    @Override
    public void apply(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        ZoneId zone = ZoneId.of(Config.getStr(Config.P_TIMEZONE, Config.ZONE_DEFAULT));
        Clock clock = DateOverride.shifted(Clock.systemUTC(), Config.getStr(Config.P_DATE, ""), zone);
        Class<?> host = XposedHelpers.findClass(Targets.C_HOST_CLOCK, lpp.classLoader);
        Class<?> instant = XposedHelpers.findClass(Targets.C_HOST_INSTANT, lpp.classLoader);
        Class<?> kotlinZone = XposedHelpers.findClass(Targets.C_TIME_ZONE, lpp.classLoader);
        Constructor<?> instantConstructor = instant.getConstructor(long.class, int.class);
        Object zoneValue = kotlinZone.getConstructor(ZoneId.class).newInstance(zone);
        Map<String, Class<?>> signatures = new LinkedHashMap<>();
        signatures.put("a", instant);
        signatures.put("c", LocalDateTime.class);
        signatures.put("d", kotlinZone);
        signatures.put("e", Calendar.class);
        signatures.put("f", ZoneId.class);
        signatures.put("g", Instant.class);
        signatures.put("h", LocalDate.class);
        List<Method> methods = resolveMethods(host, signatures);
        HostClockHook hook = new HostClockHook(clock, instantConstructor, zoneValue);
        for (Method method : methods) {
            XposedBridge.hookMethod(method, hook);
        }
        Logx.i("日期时区：已适配宿主时钟 " + host.getName() + "，时区=" + zone
                + "，日期=" + LocalDate.now(clock) + "；单调计时不变");
    }

    private static List<Method> resolveMethods(Class<?> host, Map<String, Class<?>> signatures)
            throws ReflectiveOperationException {
        List<Method> methods = new ArrayList<>();
        for (Map.Entry<String, Class<?>> entry : signatures.entrySet()) {
            Class<?>[] parameters = "e".equals(entry.getKey())
                    ? new Class<?>[]{TimeZone.class} : new Class<?>[0];
            Method method = host.getDeclaredMethod(entry.getKey(), parameters);
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != entry.getValue()) {
                throw new IllegalStateException("宿主时钟签名不匹配：" + method);
            }
            methods.add(method);
        }
        return methods;
    }

    private static final class HostClockHook extends XC_MethodHook {
        private final Clock clock;
        private final Constructor<?> instantConstructor;
        private final Object kotlinZone;
        private String reportedDate;

        HostClockHook(Clock clock, Constructor<?> instantConstructor, Object kotlinZone) {
            this.clock = clock;
            this.instantConstructor = instantConstructor;
            this.kotlinZone = kotlinZone;
        }

        @Override
        public void beforeHookedMethod(MethodHookParam param) {
            String method = param.method.getName();
            final Object result;
            try {
                result = valueFor(method, param.args);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("宿主时钟返回值构造失败：" + method, error);
            }
            param.setResult(result);
            if ("h".equals(method) && !result.toString().equals(reportedDate)) {
                reportedDate = result.toString();
                Logx.i("宿主时钟：实际读取日期 h() → " + reportedDate + "，时区=" + clock.getZone());
            }
        }

        private Object valueFor(String method, Object[] args) throws ReflectiveOperationException {
            switch (method) {
                case "a":
                    Instant instant = clock.instant();
                    return instantConstructor.newInstance(instant.getEpochSecond(), instant.getNano());
                case "c":
                    return LocalDateTime.now(clock);
                case "d":
                    return kotlinZone;
                case "e":
                    TimeZone zone = args[0] == null ? TimeZone.getTimeZone(clock.getZone()) : (TimeZone) args[0];
                    Calendar calendar = Calendar.getInstance(zone);
                    calendar.setTimeInMillis(clock.millis());
                    return calendar;
                case "f":
                    return clock.getZone();
                case "g":
                    return clock.instant();
                case "h":
                    return LocalDate.now(clock);
                default:
                    throw new IllegalArgumentException("未适配的时钟方法：" + method);
            }
        }
    }
}
