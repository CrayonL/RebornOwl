package com.owl.auxiliary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** 只偏移宿主业务时钟；系统时间和单调计时保持原样。 */
public final class DateOverride {
    private DateOverride() {}

    /** null 表示该项关闭；关闭地区时保留输入时钟的实际时区。 */
    public static Clock configured(Clock realClock, String date, String region) {
        Clock clock = region == null ? realClock : realClock.withZone(ZoneId.of(region));
        return date == null || date.isEmpty() ? clock : shifted(clock, date, clock.getZone());
    }

    public static LocalDate today(Clock realClock, ZoneId zone) {
        return realClock.instant().atZone(zone).toLocalDate();
    }

    public static LocalDate validate(String value, LocalDate today) {
        LocalDate selected = value == null || value.isEmpty() ? today : LocalDate.parse(value);
        if (selected.isAfter(today)) {
            throw new IllegalArgumentException("不能选择未来日期：" + selected);
        }
        return selected;
    }

    public static Clock shifted(Clock realClock, String value, ZoneId zone) {
        Instant now = realClock.instant();
        ZonedDateTime local = now.atZone(zone);
        LocalDate selected = validate(value, local.toLocalDate());
        Instant target = selected.atTime(local.toLocalTime()).atZone(zone).toInstant();
        // 固定偏移后继续正常走时，避免每次取日期重算导致计时跳变。
        return Clock.offset(realClock.withZone(zone), Duration.between(now, target));
    }
}
