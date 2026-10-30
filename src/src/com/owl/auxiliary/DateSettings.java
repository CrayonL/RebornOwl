package com.owl.auxiliary;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CalendarView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Calendar;

/** 日期与地区分别设置；今天按启用的地区或手机实际时区计算。 */
public final class DateSettings {
    private DateSettings() {}

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("uuuu/MM/dd", Locale.ROOT);
    private static final int CALENDAR_WIDTH_DP = 304;

    public static View createDate(Activity act) {
        TextView date = SettingsInjector.field(act, dateLabel());
        date.setTag(DateSettings.class);
        date.setContentDescription("选择日期");
        date.setOnClickListener(v -> showCalendar(act, date));
        return fieldBody(act, date);
    }

    public static View createRegion(Activity act) {
        TextView region = SettingsInjector.field(act, zoneLabel());
        region.setContentDescription("选择地区");
        region.setOnClickListener(v -> showZones(act, region));
        return fieldBody(act, region);
    }

    private static View fieldBody(Activity act, TextView field) {
        LinearLayout body = SettingsInjector.column(act);
        body.addView(field);
        body.setPadding(0, 0, 0, SettingsInjector.dp(act, 8));
        return body;
    }

    static void refreshDate(View root) {
        TextView date = root.findViewWithTag(DateSettings.class);
        if (date != null) { date.setText(dateLabel()); }
    }

    static void validateToggle(String key, boolean on) {
        if (!Config.K_DATE.equals(key) && !Config.K_ZONEID.equals(key)) { return; }
        boolean dateOn = Config.K_DATE.equals(key) ? on : Config.getOr(Config.K_DATE, true);
        boolean regionOn = Config.K_ZONEID.equals(key) ? on : Config.getOr(Config.K_ZONEID, true);
        DateOverride.configured(Clock.systemDefaultZone(),
                dateOn ? Config.getStr(Config.P_DATE, "") : null,
                regionOn ? Config.getStr(Config.P_TIMEZONE, Config.ZONE_DEFAULT) : null);
    }

    static String displayDate(LocalDate date) {
        return DISPLAY_DATE.format(date);
    }

    private static String zoneLabel() {
        String zone = Config.getStr(Config.P_TIMEZONE, Config.ZONE_DEFAULT);
        int index = ZoneList.indexOf(zone);
        String label = index < 0 ? zone : ZoneList.LABELS[index];
        int bracket = label.indexOf('(');
        return bracket < 0 ? label : label.substring(0, bracket).trim();
    }

    private static LocalDate today() {
        return LocalDate.now(DateOverride.configured(Clock.systemDefaultZone(), null, Config.activeRegion()));
    }

    private static String dateLabel() {
        String saved = Config.getStr(Config.P_DATE, "");
        return displayDate(saved.isEmpty() ? today() : LocalDate.parse(saved));
    }

    private static void showZones(Activity act, TextView zone) {
        LinearLayout items = SettingsInjector.column(act);
        Dialog dialog = SettingsInjector.showPanel(act, "选择地区", items);
        for (int i = 0; i < ZoneList.IDS.length; i++) {
            final String id = ZoneList.IDS[i];
            String label = ZoneList.LABELS[i].split(" \\(")[0];
            TextView row = SettingsInjector.action(act, label);
            if (id.equals(Config.getStr(Config.P_TIMEZONE, Config.ZONE_DEFAULT))) {
                row.setTextColor(SettingsInjector.accent(act));
            }
            row.setOnClickListener(v -> {
                try {
                    DateOverride.configured(Clock.systemDefaultZone(), Config.activeDate(), id);
                } catch (RuntimeException error) {
                    Toast.makeText(act, "地区未更改：" + error.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
                Config.setStr(Config.P_TIMEZONE, id);
                zone.setText(zoneLabel());
                refreshDate(zone.getRootView());
                dialog.dismiss();
                Logx.i("地区设置：地区 → " + id);
            });
            items.addView(row);
        }
    }

    private static void showCalendar(Activity act, TextView label) {
        LocalDate today = today();
        LocalDate selected = DateOverride.validate(Config.getStr(Config.P_DATE, ""), today);
        int theme = SettingsInjector.night(act)
                ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
        AlertDialog calendar = new AlertDialog.Builder(act, theme)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", null).create();
        CalendarView days = new CalendarView(calendar.getContext());
        days.setContentDescription("选择日期");
        days.setMaxDate(calendarMillis(today));
        days.setDate(calendarMillis(selected), false, true);
        // 原生日历点击只更新绘制选中态；同步其日期值，避免确定时读回初始日期。
        days.setOnDateChangeListener((view, year, month, day) ->
                view.setDate(calendarMillis(LocalDate.of(year, month + 1, day)), false, true));
        calendar.setView(days, 0, 0, 0, 0);
        SettingsInjector.bindLifetime(act, calendar);
        calendar.show();
        int width = Math.min(SettingsInjector.dp(act, CALENDAR_WIDTH_DP),
                act.getResources().getDisplayMetrics().widthPixels);
        calendar.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        calendar.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(SettingsInjector.accent(act));
        calendar.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(SettingsInjector.accent(act));
        calendar.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            LocalDate date = Instant.ofEpochMilli(days.getDate()).atZone(ZoneId.systemDefault()).toLocalDate();
            saveDate(act, date, label);
            calendar.dismiss();
        });
    }

    static long calendarMillis(LocalDate date) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
        return calendar.getTimeInMillis();
    }

    private static void saveDate(Activity act, LocalDate selected, TextView label) {
        LocalDate today = today();
        DateOverride.validate(selected.toString(), today);
        Config.setStr(Config.P_DATE, selected.equals(today) ? null : selected.toString());
        label.setText(dateLabel());
        Logx.i("日期设置：日期 → " + selected);
    }
}
