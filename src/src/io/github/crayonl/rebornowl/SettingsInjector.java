package io.github.crayonl.rebornowl;

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
import android.os.SystemClock;
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
    private static final int NOTICE_WIDTH_DP = 320;
    private static final int NOTICE_TEXT_SP = 16;

    private SettingsInjector() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    Activity act = (Activity) param.thisObject;
                    act.getWindow().getDecorView().post(() -> {
                        FloatBall.attach(act);
                        CompatibilityNotice.onResume(act);
                    });
                }
            });
            Logx.i("设置悬浮球：已挂接 Activity.onPostResume（不依赖调试模式）");
        } catch (Throwable t) {
            Logx.w("设置悬浮球：挂接失败", t);
        }
    }

    public static Dialog show(Activity act) {
        LinearLayout body = column(act);
        for (Feature feature : FeatureManager.settings()) {
            body.addView(featureRow(act, feature));
        }
        // 反馈入口只在「第一次检测到异常」的弹窗里出现；平时能用就不打扰。
        Dialog dialog = showPanel(act, Brand.NAME, body);
        Logx.i("插件设置：已从悬浮球打开 @" + act.getClass().getSimpleName());
        return dialog;
    }

    static Dialog showCompatibilityNotice(Activity act, String message, String fingerprint) {
        LinearLayout panel = noticeContent(act, "部分功能暂时不可用",
                "当前多邻国版本上，以下功能没能正常工作：\n\n" + message
                        + "\n\n其他功能不受影响。多邻国更新后出现这种情况比较常见，通常等模块更新即可；"
                        + "如果一直失效，可以点击下方按钮反馈（详细原因已记录在模块日志里）。");
        Dialog dialog = new Dialog(act);
        LinearLayout buttons = noticeButtons(act);
        TextView feedback = footerButton(act, "功能失效，点击反馈", "OutlineButton");
        feedback.setOnClickListener(view -> {
            if (ModuleActivity.openIssues(act)) { dialog.dismiss(); }
        });
        TextView dismiss = footerButton(act, "知道了", "SolidBlueButton");
        dismiss.setOnClickListener(view -> {
            dialog.dismiss();
            if (!act.isFinishing() && !act.isDestroyed()) { showNoticePreference(act, fingerprint); }
        });
        addNoticeButton(buttons, feedback, 2f);
        addNoticeButton(buttons, dismiss, 1f);
        panel.addView(buttons);
        return showNotice(act, dialog, panel);
    }

    private static void showNoticePreference(Activity act, String fingerprint) {
        LinearLayout panel = noticeContent(act, "是否不再提示此问题？",
                "仅关闭当前这组异常的提醒，不影响功能开关。更新多邻国、更新模块或出现新异常时会再次提醒。可随时在设置中查看异常并反馈。");
        Dialog dialog = new Dialog(act);
        LinearLayout buttons = noticeButtons(act);
        TextView remind = footerButton(act, "下次仍提示", "OutlineButton");
        remind.setOnClickListener(view -> dialog.dismiss());
        TextView ignore = footerButton(act, "不再提示", "SolidBlueButton");
        ignore.setOnClickListener(view -> {
            if (Config.suppressProblem(fingerprint)) { dialog.dismiss(); }
            else { Toast.makeText(act, "提醒设置保存失败，请重试", Toast.LENGTH_LONG).show(); }
        });
        addNoticeButton(buttons, remind, 1f);
        addNoticeButton(buttons, ignore, 1f);
        panel.addView(buttons);
        showNotice(act, dialog, panel);
    }

    private static LinearLayout noticeContent(Activity act, String title, String message) {
        LinearLayout panel = column(act);
        int padding = dp(act, PADDING_DP);
        panel.setPadding(padding, padding, padding, padding);
        panel.setBackground(shape(act, night(act) ? DARK_BG : Color.WHITE, RADIUS_DP));
        panel.addView(panelHeading(act, title));
        TextView body = action(act, message);
        body.setTextSize(NOTICE_TEXT_SP);
        body.setBackground(null);
        body.setFocusable(false);
        body.setPadding(dp(act, 4), padding, dp(act, 4), padding);
        ScrollView scroll = new ScrollView(act);
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return panel;
    }

    private static LinearLayout noticeButtons(Activity act) {
        LinearLayout row = new LinearLayout(act);
        row.setBaselineAligned(false);
        return row;
    }

    private static void addNoticeButton(LinearLayout row, TextView button, float weight) {
        button.setSingleLine(false);
        button.setMinHeight(dp(row.getContext(), FOOTER_BUTTON_DP));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        if (row.getChildCount() > 0) { params.leftMargin = dp(row.getContext(), FOOTER_GAP_DP); }
        row.addView(button, params);
    }

    private static Dialog showNotice(Activity act, Dialog dialog, LinearLayout panel) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.setCanceledOnTouchOutside(true);
        bindLifetime(act, dialog);
        dialog.show();
        int width = Math.min(dp(act, NOTICE_WIDTH_DP), act.getResources().getDisplayMetrics().widthPixels);
        dialog.getWindow().setGravity(Gravity.CENTER);
        dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
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
            if (on) {
                // 没有二级选择：打开即写最强档，顺带覆盖旧配置里残留的「Super / 2 倍」。
                if (Config.K_PREMIUM.equals(feature.key())) { Config.setStr(Config.P_LEVEL, Config.LV_MAX); }
                if (Config.K_XPBOOST.equals(feature.key())) { Config.setStr(Config.P_XPBOOST, Config.XP_3X); }
            }
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
        // 会员档位固定 Super Max、经验加成固定 3 倍：没有二级选择，打开开关即按最强档生效。
        return null;
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
            // 标题右侧猫头鹰：连点三次打开宿主官方调试菜单（隐藏入口，不占设置行）。
            logo.setContentDescription(Brand.NAME + "图标");
            logo.setOnClickListener(new TripleTap(act));
            row.addView(logo, new LinearLayout.LayoutParams(dp(act, TITLE_LOGO_DP), dp(act, TITLE_LOGO_DP)));
        }
        return row;
    }

    /**
     * 标题右侧猫头鹰：**连点三次以上**直接打开宿主官方调试菜单（隐藏入口，不占设置行）。
     *
     * <p>相邻两次间隔超过 {@link #WINDOW_MS} 就重新计数，避免把平时乱点当成手势；
     * 触发后进入 {@link #COOLDOWN_MS} 冷却，连点第四、第五下不会重复打开，
     * 从调试界面返回后再点也需要重新数满三次。
     */
    private static final class TripleTap implements View.OnClickListener {
        private static final long WINDOW_MS = 900L;
        private static final long COOLDOWN_MS = 1200L;
        private final Activity activity;
        private int taps;
        private long last;
        private long muted;

        TripleTap(Activity activity) {
            this.activity = activity;
        }

        @Override
        public void onClick(View view) {
            long now = SystemClock.uptimeMillis();
            if (now < muted) {
                return;
            }
            if (now - last > WINDOW_MS) {
                taps = 0;
            }
            last = now;
            if (++taps < 3) {
                return;
            }
            taps = 0;
            muted = now + COOLDOWN_MS;
            // 调试模式没有开关、也没有配置项：手势是唯一入口，唯一的前置条件是
            // 本进程真的装上了准入 Hook（否则调试页会立刻自我关闭）。
            if (!DebugModeFeature.installed) {
                Logx.i("插件设置：猫头鹰手势命中，但本进程未装上调试准入 Hook");
                Toast.makeText(activity.getApplicationContext(),
                        "调试菜单暂不可用：当前多邻国版本结构不支持，详见模块日志",
                        Toast.LENGTH_LONG).show();
                return;
            }
            Logx.i("插件设置：猫头鹰连点三次以上 → 打开宿主调试菜单");
            FloatBall.openDebugMenu(activity);
        }
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
