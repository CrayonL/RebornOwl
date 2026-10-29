package com.owl.auxiliary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class DateOverrideTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    public static void main(String[] args) {
        Clock real = Clock.fixed(Instant.parse("2026-10-29T15:30:40Z"), ZoneOffset.UTC);
        check(DateOverride.shifted(real, "", NEW_YORK).instant().equals(real.instant()), "默认今天不改时间");
        check(DateOverride.shifted(real, "2026-10-29", NEW_YORK).instant().equals(real.instant()), "允许选择今天");
        check(DateOverride.shifted(real, "2026-10-28", NEW_YORK).instant()
                .equals(Instant.parse("2026-10-28T15:30:40Z")), "过去日期保留本地时分秒");
        check(DateOverride.shifted(real, "2024-02-29", NEW_YORK).instant()
                .equals(Instant.parse("2024-02-29T16:30:40Z")), "闰年与冬夏令时换算");
        Clock utcMidnight = Clock.fixed(Instant.parse("2026-10-29T01:00:00Z"), ZoneOffset.UTC);
        check(DateOverride.today(utcMidnight, NEW_YORK).equals(LocalDate.parse("2026-10-28")), "今天按纽约日期判断");
        rejected("2026-10-30", real);
        rejected("2026-10-29", utcMidnight);
        rejected("2025-02-29", real);
        rejected("not-a-date", real);
        verifyContinuousTime();
        check("Asia/Shanghai".equals(Config.ZONE_DEFAULT), "默认时区为中国上海");
        check(DateSettings.displayDate(LocalDate.of(2026, 10, 29)).equals("2026/10/29"), "日期框使用斜线格式");
        check(DateSettings.displayDate(LocalDate.of(2026, 1, 2)).equals("2026/01/02"), "月日不足两位补零");
        check(ZoneList.LABELS[ZoneList.indexOf(Config.ZONE_DEFAULT)].startsWith("中国 · 上海"), "默认时区显示上海");
        check(DateOverride.today(Clock.fixed(Instant.parse("2026-10-29T17:00:00Z"), ZoneOffset.UTC),
                ZoneId.of(Config.ZONE_DEFAULT)).equals(LocalDate.of(2026, 10, 30)), "上海今天按当地日期判断");
        verifyCalendarDates();
        System.out.println("PASS DateOverride: today, past, future rejection, zone boundary, leap day, DST, continuous clock");
    }

    private static void verifyCalendarDates() {
        for (String value : new String[]{"2026-10-29", "2026-01-02", "2024-02-29"}) {
            LocalDate date = LocalDate.parse(value);
            LocalDate roundTrip = Instant.ofEpochMilli(DateSettings.calendarMillis(date))
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            check(roundTrip.equals(date), "日历毫秒值往返保持年月日：" + value);
        }
    }

    private static void verifyContinuousTime() {
        MutableClock real = new MutableClock(Instant.parse("2026-10-29T15:30:40Z"));
        Clock shifted = DateOverride.shifted(real, "2026-10-28", NEW_YORK);
        Instant start = shifted.instant();
        real.now = real.now.plusSeconds(65);
        check(shifted.instant().equals(start.plusSeconds(65)), "偏移后时间持续前进");
        check(real.instant().equals(Instant.parse("2026-10-29T15:31:45Z")), "不修改原时钟");
    }

    private static void rejected(String value, Clock clock) {
        try {
            DateOverride.shifted(clock, value, NEW_YORK);
        } catch (java.time.DateTimeException | IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("不应接受日期 " + value);
    }

    private static void check(boolean valid, String message) {
        if (!valid) { throw new AssertionError(message); }
    }

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant value) { now = value; }
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) {
            MutableClock source = this;
            return new Clock() {
                public Instant instant() { return source.instant(); }
                public ZoneId getZone() { return zone; }
                public Clock withZone(ZoneId next) { return source.withZone(next); }
            };
        }
    }
}
