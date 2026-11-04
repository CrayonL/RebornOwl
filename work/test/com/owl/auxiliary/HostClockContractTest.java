package com.owl.auxiliary;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** 在 ART 中核对真实 APK 的定位和返回值构造，不安装 Hook 或启动课程。 */
public final class HostClockContractTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) { throw new IllegalArgumentException("需要时钟类名和时间类名"); }
        ClassLoader loader = HostClockContractTest.class.getClassLoader();
        Class<?> zone = Class.forName(Targets.C_TIME_ZONE, false, loader);
        Method resolve = TimezoneFeature.class.getDeclaredMethod("resolveClock", ClassLoader.class, Class.class);
        resolve.setAccessible(true);
        List<?> methods = (List<?>) resolve.invoke(null, loader, zone);
        check(methods.size() == 7, "必须完整匹配七个时钟方法");
        for (Object entry : methods) {
            Method method = (Method) entry;
            check(method.getDeclaringClass().getName().equals(args[0]), "不得命中其他类");
            check(!method.getName().equals("b"), "不得挂接单调计时");
        }
        Class<?> instant = ((Method) methods.get(0)).getReturnType();
        check(instant.getName().equals(args[1]), "Kotlin Instant 返回类型必须匹配");
        verifyInstant(instant);
        Object hostZone = zone.getConstructor(ZoneId.class).newInstance(ZoneId.of("America/New_York"));
        check(hostZone.toString().equals("America/New_York"), "Kotlin 时区构造结果");
        System.out.println("HOST_CLOCK_ART_PASS: " + args[0] + " / " + args[1]);
    }

    private static void verifyInstant(Class<?> type) throws Exception {
        Constructor<?> constructor = type.getConstructor(long.class, int.class);
        Instant expected = Instant.parse("2026-11-02T12:34:56.123456789Z");
        Object value = constructor.newInstance(expected.getEpochSecond(), expected.getNano());
        check(value.toString().equals(expected.toString()), "秒和纳秒必须按原值构造");
    }

    private static void check(boolean valid, String reason) {
        if (!valid) { throw new AssertionError(reason); }
    }
}
