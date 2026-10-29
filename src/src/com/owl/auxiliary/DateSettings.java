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

/** 日期通过日历选择；真实今天按所选时区计算，未来日期不可选。 */
public final class DateSettings {
    private DateSettings() {}

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("uuuu/MM/dd", Locale.ROOT);
    private static final int CALENDAR_WIDTH_DP = 304;

    public static View create(Activity act) {
        LinearLayout body = SettingsInjector.column(act);
        TextView date = SettingsInjector.field(act, dateLabel());
        TextView zone = SettingsInjector.field(act, zoneLabel());
        date.setContentDescription("选择日期");
        zone.setContentDescription("选择时区");
        date.setOnClickListener(v -> showCalendar(act, date));
        zone.setOnClickListener(v -> showZones(act, zone, date));
        addField(body, "日期", date);
        addField(body, "时区", zone);
        body.setPadding(0, 0, 0, SettingsInjector.dp(act, 8));
        return body;
    }

    private static void addField(LinearLayout body, String title, TextView field) {
        TextView heading = SettingsInjector.action(body.getContext(), title);
        heading.setTextSize(12);
        heading.setMinHeight(SettingsInjector.dp(body.getContext(), 28));
        heading.setBackground(null);
        heading.setFocusable(false);
        body.addView(heading);
        body.addView(field);
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
        ZoneId zone = ZoneId.of(Config.getStr(Config.P_TIMEZONE, Config.ZONE_DEFAULT));
        return DateOverride.today(Clock.systemUTC(), zone);
    }

    private static String dateLabel() {
        String saved = Config.getStr(Config.P_DATE, "");
        return displayDate(saved.isEmpty() ? today() : LocalDate.parse(saved));
    }

    private static void showZones(Activity act, TextView zone, TextView date) {
        LinearLayout items = SettingsInjector.column(act);
        Dialog dialog = SettingsInjector.showPanel(act, "选择时区", items);
        for (int i = 0; i < ZoneList.IDS.length; i++) {
            final String id = ZoneList.IDS[i];
            String label = ZoneList.LABELS[i].split(" \\(")[0];
            TextView row = SettingsInjector.action(act, label);
            if (id.equals(Config.getStr(Config.P_TIMEZONE, Config.ZONE_DEFAULT))) {
                row.setTextColor(SettingsInjector.accent(act));
            }
            row.setOnClickListener(v -> {
                LocalDate targetToday = DateOverride.today(Clock.systemUTC(), ZoneId.of(id));
                try {
                    DateOverride.validate(Config.getStr(Config.P_DATE, ""), targetToday);
                } catch (RuntimeException error) {
                    Toast.makeText(act, "请先选择不晚于该时区今天的日期", Toast.LENGTH_LONG).show();
                    return;
                }
                Config.setStr(Config.P_TIMEZONE, id);
                zone.setText(zoneLabel());
                date.setText(dateLabel());
                dialog.dismiss();
                Logx.i("日期设置：时区 → " + id);
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
