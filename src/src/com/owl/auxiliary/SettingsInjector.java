package com.owl.auxiliary;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Process;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 贴边设置面板；功能参数在开关下方展开。 */
public final class SettingsInjector {
    private static final int WIDTH_DP = 224;
    private static final int PADDING_DP = 12;
    private static final int ROW_DP = 48;
    private static final int TOGGLE_DP = 60;
    private static final int RADIUS_DP = 16;
    private static final int CONTROL_RADIUS_DP = 10;
    private static final int DARK_BG = 0xFF131F24;
    private static final int DARK_FG = 0xFFF1F7FB;
    private static final int LIGHT_FG = 0xFF3C3C3C;
    private static final int BORDER_DP = 2;
    private static final int TITLE_LOGO_DP = 28;
    private static final int FOOTER_BUTTON_DP = 52;
    private static final int FOOTER_GAP_DP = 8;
    private static Toast levelToast;

    private SettingsInjector() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    Activity act = (Activity) param.thisObject;
                    act.getWindow().getDecorView().post(() -> FloatBall.attach(act));
                }
            });
            Logx.i("设置悬浮球：已挂接 Activity.onPostResume（不依赖调试模式）");
        } catch (Throwable t) {
            Logx.w("设置悬浮球：挂接失败", t);
        }
    }

    public static Dialog show(Activity act) {
        LinearLayout body = column(act);
        for (Feature feature : FeatureManager.all()) {
            body.addView(featureRow(act, feature));
        }
        Dialog dialog = showPanel(act, Brand.NAME, body);
        Logx.i("插件设置：已从悬浮球打开 @" + act.getClass().getSimpleName());
        return dialog;
    }

    private static View featureRow(Activity act, Feature feature) {
        LinearLayout group = column(act);
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = action(act, feature.label());
        Switch toggle = featureSwitch(act, feature);
        View options = featureOptions(act, feature.key());
        if (options != null) {
            options.setVisibility(toggle.isChecked() ? View.VISIBLE : View.GONE);
        }
        boolean[] restoring = {false};
        toggle.setOnCheckedChangeListener((button, on) -> {
            if (restoring[0]) { return; }
            try {
                DateSettings.validateToggle(feature.key(), on);
            } catch (RuntimeException error) {
                restoring[0] = true;
                toggle.setChecked(!on);
                restoring[0] = false;
                Toast.makeText(act, "开关未更改：" + error.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            Config.set(feature.key(), on);
            if (options != null) { options.setVisibility(on ? View.VISIBLE : View.GONE); }
            if (Config.K_ZONEID.equals(feature.key())) { DateSettings.refreshDate(group.getRootView()); }
            Logx.i("插件设置：" + feature.key() + " → " + on);
        });
        label.setOnClickListener(v -> toggle.toggle());
        row.addView(label, new LinearLayout.LayoutParams(0, dp(act, ROW_DP), 1f));
        row.addView(toggle, new LinearLayout.LayoutParams(dp(act, TOGGLE_DP), dp(act, ROW_DP)));
        group.addView(row);
        if (options != null) { group.addView(options); }
        return group;
    }

    private static Switch featureSwitch(Activity act, Feature feature) {
        Switch toggle = new Switch(act);
        toggle.setShowText(false);
        toggle.setSwitchMinWidth(dp(act, TOGGLE_DP));
        toggle.setContentDescription(feature.label() + "开关");
        toggle.setBackground(null);
        toggle.setThumbTintList(null);
        toggle.setThumbDrawable(act.getDrawable(resourceId(act, "drawable", "juicy_switch_thumb")));
        toggle.setTrackDrawable(act.getDrawable(resourceId(act, "drawable", "juicy_switch_track")));
        toggle.setTrackTintList(act.getColorStateList(resourceId(act, "color", "juicy_switch_color")));
        toggle.setSplitTrack(false);
        toggle.setChecked(Config.getOr(feature.key(), feature.defaultOn()));
        return toggle;
    }

    private static View featureOptions(Activity act, String key) {
        if (Config.K_DATE.equals(key)) { return DateSettings.createDate(act); }
        if (Config.K_ZONEID.equals(key)) { return DateSettings.createRegion(act); }
        if (Config.K_PREMIUM.equals(key)) { return levelOptions(act); }
        if (Config.K_DEBUG.equals(key)) {
            TextView open = field(act, "打开调试菜单");
            open.setOnClickListener(v -> FloatBall.openDebugMenu(act));
            return open;
        }
        return null;
    }

    private static View levelOptions(Activity act) {
        LinearLayout row = new LinearLayout(act);
        TextView superLevel = field(act, "Super");
        TextView maxLevel = field(act, "Super Max");
        superLevel.setGravity(Gravity.CENTER);
        maxLevel.setGravity(Gravity.CENTER);
        Runnable refresh = () -> {
            superLevel.setTextColor(PremiumFeature.isMaxLevel() ? foreground(act) : accent(act));
            maxLevel.setTextColor(PremiumFeature.isMaxLevel() ? accent(act) : foreground(act));
        };
        superLevel.setOnClickListener(v -> { selectLevel(act.getApplicationContext(), Config.LV_SUPER); refresh.run(); });
        maxLevel.setOnClickListener(v -> { selectLevel(act.getApplicationContext(), Config.LV_MAX); refresh.run(); });
        row.addView(superLevel, new LinearLayout.LayoutParams(0, dp(act, ROW_DP), 1f));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, dp(act, ROW_DP), 1f);
        second.leftMargin = dp(act, 6);
        row.addView(maxLevel, second);
        refresh.run();
        return row;
    }

    private static void selectLevel(Context context, String level) {
        Config.setStr(Config.P_LEVEL, level);
        String message = Config.LV_MAX.equals(level)
                ? "已激活超级会员Max（含AI功能）" : "已激活超级会员";
        if (levelToast != null) { levelToast.cancel(); }
        levelToast = Toast.makeText(context, message, Toast.LENGTH_SHORT);
        levelToast.show();
        Logx.i("会员等级已选择：" + level + "（重启后应用）");
    }

    static Dialog showPanel(Activity act, String title, View content) {
        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = column(act);
        panel.setClipChildren(true);
        int padding = dp(act, PADDING_DP);
        panel.setPadding(padding, padding, padding, 0);
        panel.setBackground(shape(act, night(act) ? DARK_BG : Color.WHITE, RADIUS_DP));
        panel.addView(panelHeading(act, title));
        ScrollView scroll = new ScrollView(act);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        panel.addView(footer(act, dialog));
        dialog.setContentView(panel);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.setCanceledOnTouchOutside(true);
        bindLifetime(act, dialog);
        dialog.show();
        int width = Math.min(dp(act, WIDTH_DP), act.getResources().getDisplayMetrics().widthPixels);
        dialog.getWindow().setGravity(FloatBall.panelSide(act) | Gravity.CENTER_VERTICAL);
        dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        return dialog;
    }

    private static View panelHeading(Activity act, String title) {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = action(act, title);
        heading.setTextSize(17);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setBackground(null);
        heading.setFocusable(false);
        row.addView(heading, new LinearLayout.LayoutParams(0, dp(act, ROW_DP), 1f));
        if (Brand.NAME.equals(title)) {
            ImageView logo = new ImageView(act);
            logo.setImageDrawable(Brand.logo(act));
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(logo, new LinearLayout.LayoutParams(dp(act, TITLE_LOGO_DP), dp(act, TITLE_LOGO_DP)));
        }
        return row;
    }

    private static View footer(Activity act, Dialog dialog) {
        LinearLayout footer = new LinearLayout(act);
        // 原生按钮按压会改变文字基线，不能让兄弟按钮跟随基线重新定位。
        footer.setBaselineAligned(false);
        footer.setPadding(0, dp(act, FOOTER_GAP_DP), 0, dp(act, PADDING_DP));
        TextView close = footerButton(act, "关闭", "OutlineButton");
        TextView restart = footerButton(act, "重启", "SolidBlueButton");
        close.setOnClickListener(v -> dialog.dismiss());
        restart.setOnClickListener(v -> restart(act));
        footer.addView(close, new LinearLayout.LayoutParams(0, dp(act, FOOTER_BUTTON_DP), 1f));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(act, FOOTER_BUTTON_DP), 1f);
        right.leftMargin = dp(act, FOOTER_GAP_DP);
        footer.addView(restart, right);
        return footer;
    }

    private static TextView footerButton(Activity act, String text, String style) {
        Context themed = new ContextThemeWrapper(act, resourceId(act, "style", style));
        Class<?> type = XposedHelpers.findClass(
                "com.duolingo.core.design.juicy.ui.JuicyButton", act.getClassLoader());
        TextView button = (TextView) XposedHelpers.newInstance(type, themed);
        button.setText(text);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        return button;
    }

    private static void restart(Activity act) {
        try {
            Intent launcher = act.getPackageManager().getLaunchIntentForPackage(Targets.HOST_PKG);
            if (launcher == null || launcher.getComponent() == null) {
                throw new IllegalStateException("未找到多邻国启动入口");
            }
            act.startActivity(Intent.makeRestartActivityTask(launcher.getComponent()));
        } catch (RuntimeException error) {
            Logx.w("重启多邻国：无法发起启动", error);
            Toast.makeText(act, "无法重新启动多邻国，请查看模块日志", Toast.LENGTH_LONG).show();
            return;
        }
        Logx.i("重启多邻国：自身启动新任务，退出进程 " + Process.myPid());
        Process.killProcess(Process.myPid());
    }

    static void bindLifetime(Activity act, Dialog dialog) {
        View decor = act.getWindow().getDecorView();
        View.OnAttachStateChangeListener listener = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {}
            @Override
            public void onViewDetachedFromWindow(View view) { dialog.dismiss(); }
        };
        decor.addOnAttachStateChangeListener(listener);
        dialog.setOnDismissListener(ignored -> decor.removeOnAttachStateChangeListener(listener));
    }

    static TextView action(Context context, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(14);
        label.setTextColor(foreground(context));
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setMinHeight(dp(context, ROW_DP));
        label.setPadding(dp(context, 4), 0, dp(context, 4), 0);
        label.setBackground(ripple(context, null));
        label.setFocusable(true);
        return label;
    }

    static TextView field(Context context, String text) {
        TextView field = action(context, text);
        int padding = dp(context, PADDING_DP);
        field.setPadding(padding, 0, padding, 0);
        field.setBackground(ripple(context, shape(context, Color.TRANSPARENT, CONTROL_RADIUS_DP)));
        return field;
    }

    private static RippleDrawable ripple(Context context, GradientDrawable background) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(context, CONTROL_RADIUS_DP));
        int color = (accent(context) & 0x00FFFFFF) | 0x33000000;
        return new RippleDrawable(ColorStateList.valueOf(color), background, mask);
    }

    private static GradientDrawable shape(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        shape.setStroke(dp(context, BORDER_DP), context.getColor(resourceId(context, "color", "juicySwan")));
        return shape;
    }

    private static int resourceId(Context context, String type, String name) {
        int id = context.getResources().getIdentifier(name, type, Targets.HOST_PKG);
        if (id == 0) { throw new IllegalStateException("宿主样式资源不存在：" + type + "/" + name); }
        return id;
    }

    static int accent(Context context) {
        return context.getColor(resourceId(context, "color", "juicyMacaw"));
    }

    static LinearLayout column(Context context) {
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static boolean night(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int foreground(Context context) {
        return night(context) ? DARK_FG : LIGHT_FG;
    }
}
