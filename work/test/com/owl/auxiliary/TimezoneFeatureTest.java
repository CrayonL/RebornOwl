package com.owl.auxiliary;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** 配置迁移与独立开关的可观察行为；不代表真机 Hook 验收。 */
public final class TimezoneFeatureTest {
    public static void main(String[] args) throws Exception {
        Logx.setMirror(false);
        check(FeatureManager.all().stream().filter(f -> "日期".equals(f.label())).count() == 1,
                "日期必须有独立开关");
        check(FeatureManager.all().stream().filter(f -> "地区".equals(f.label())).count() == 1,
                "地区必须有独立开关");
        verifyMigration(false);
        verifyMigration(true);
        System.out.println("PASS: 日期/地区独立入口，旧开关迁移，选择保留，四种开关组合与重载");
    }

    private static void verifyMigration(boolean oldEnabled) throws Exception {
        Path folder = Files.createTempDirectory("owl-date-config-");
        Path configDir = Files.createDirectory(folder.resolve("app_owl-auxiliary"));
        Path file = configDir.resolve("config.properties");
        try {
            Properties old = new Properties();
            old.setProperty("zoneid", Boolean.toString(oldEnabled));
            old.setProperty("time_zone", "America/New_York");
            old.setProperty("selected_date", "2026-10-28");
            try (OutputStream out = Files.newOutputStream(file)) { old.store(out, "legacy"); }
            reload(folder);
            check(Config.getOr("date", true) == oldEnabled, "日期继承旧开关状态");
            check(Config.getOr("zoneid", true) == oldEnabled, "地区继承旧开关状态");
            for (boolean date : new boolean[]{false, true}) {
                for (boolean region : new boolean[]{false, true}) {
                    verifyCombination(folder, date, region);
                }
            }
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(configDir);
            Files.deleteIfExists(folder);
        }
    }

    private static void verifyCombination(Path folder, boolean date, boolean region) throws Exception {
        Config.set("date", date);
        Config.set("zoneid", region);
        reload(folder);
        check(Config.getOr("date", true) == date, "地区开关不得改变日期开关");
        check(Config.getOr("zoneid", true) == region, "日期开关不得改变地区开关");
        check("2026-10-28".equals(Config.getStr(Config.P_DATE, "")), "关闭不清空日期选择");
        check("America/New_York".equals(Config.getStr(Config.P_TIMEZONE, "")), "关闭不清空地区选择");
        Clock real = Clock.fixed(Instant.parse("2026-10-29T15:30:40Z"), ZoneId.of("Asia/Shanghai"));
        Clock result = DateOverride.configured(real, Config.activeDate(), Config.activeRegion());
        check(result.getZone().getId().equals(region ? "America/New_York" : "Asia/Shanghai"),
                "地区开关独立决定时区");
        check(result.instant().equals(date ? Instant.parse("2026-10-28T15:30:40Z") : real.instant()),
                "日期开关独立决定日期偏移");
        Config.set("date", false);
        Config.set("zoneid", false);
        Config.setStr(Config.P_DATE, "invalid-date");
        Config.setStr(Config.P_TIMEZONE, "invalid-region");
        check(DateOverride.configured(real, Config.activeDate(), Config.activeRegion()) == real,
                "已关闭选项的旧值不得参与解析或影响时钟");
        Config.setStr(Config.P_DATE, "2026-10-28");
        Config.setStr(Config.P_TIMEZONE, "America/New_York");
    }

    private static void reload(Path folder) throws Exception {
        Field loaded = Config.class.getDeclaredField("sLoaded");
        loaded.setAccessible(true);
        loaded.set(null, false);
        Field values = Config.class.getDeclaredField("P");
        values.setAccessible(true);
        ((Properties) values.get(null)).clear();
        Config.init(folder.toString());
    }

    private static void check(boolean valid, String message) {
        if (!valid) { throw new AssertionError(message); }
    }
}
