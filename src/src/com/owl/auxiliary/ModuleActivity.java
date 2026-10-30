package com.owl.auxiliary;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 模块自身的桌面页；桌面可见性以系统组件状态为准。 */
public final class ModuleActivity extends Activity {
    static final String SOURCE_URL = "https://github.com/CrayonL/RebornOwl";
    private static final String GITHUB_PACKAGE = "com.github.android";
    private static final String LAUNCHER = Targets.MODULE_PKG + ".Launcher";
    private static final int BLUE = 0xFF1CB0F6;
    private static final int DARK_BACKGROUND = 0xFF131F24;
    private static final int LIGHT_FOREGROUND = 0xFF3C3C3C;
    private static final int DARK_FOREGROUND = 0xFFF1F7FB;
    private static final int PADDING_DP = 24;
    private static final int CONTROL_HEIGHT_DP = 56;
    private Switch launcherSwitch;
    private boolean dark;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(dark ? DARK_BACKGROUND : Color.WHITE);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP));
        scroll.addView(body);
        addHeading(body);
        body.addView(launcherControl());
        body.addView(sourceButton());
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(scroll);
    }

    @Override
    public void onResume() {
        super.onResume();
        launcherSwitch.setChecked(launcherVisible());
    }

    private void addHeading(LinearLayout body) {
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setGravity(Gravity.CENTER_HORIZONTAL);
        heading.setPadding(0, dp(28), 0, dp(40));
        ImageView logo = new ImageView(this);
        logo.setImageDrawable(Brand.logo(this));
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(logo, new LinearLayout.LayoutParams(dp(88), dp(88)));
        LinearLayout name = new LinearLayout(this);
        name.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(Brand.NAME, 26);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(16), 0, dp(4));
        name.addView(title, new LinearLayout.LayoutParams(-2, -2));
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            TextView label = text(version, 13);
            label.setGravity(Gravity.END);
            name.addView(label);
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("无法读取模块版本", error);
        }
        heading.addView(name, new LinearLayout.LayoutParams(-2, -2));
        body.addView(heading);
    }

    private View launcherControl() {
        launcherSwitch = new Switch(this);
        launcherSwitch.setText("显示桌面入口");
        launcherSwitch.setTextSize(16);
        launcherSwitch.setPadding(dp(16), 0, dp(16), 0);
        launcherSwitch.setTextColor(foreground());
        launcherSwitch.setMinHeight(dp(CONTROL_HEIGHT_DP));
        launcherSwitch.setChecked(launcherVisible());
        int[][] states = {{android.R.attr.state_checked}, {}};
        launcherSwitch.setThumbTintList(new ColorStateList(states, new int[]{BLUE, 0xFF9BA5AA}));
        launcherSwitch.setTrackTintList(new ColorStateList(states, new int[]{0x661CB0F6, 0x449BA5AA}));
        launcherSwitch.setOnCheckedChangeListener((button, visible) -> {
            if (visible == launcherVisible()) { return; }
            setLauncherVisible(visible);
            button.setChecked(launcherVisible());
        });
        return launcherSwitch;
    }

    private boolean launcherVisible() {
        int state = getPackageManager().getComponentEnabledSetting(new ComponentName(this, LAUNCHER));
        return state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private void setLauncherVisible(boolean visible) {
        int state = visible ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        try {
            getPackageManager().setComponentEnabledSetting(new ComponentName(this, LAUNCHER),
                    state, PackageManager.DONT_KILL_APP);
        } catch (RuntimeException error) {
            Log.e("OwlReborn", "设置桌面入口失败", error);
            Toast.makeText(this, "桌面入口设置失败，请重试", Toast.LENGTH_LONG).show();
        }
    }

    private View sourceButton() {
        LinearLayout button = new LinearLayout(this);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(16), dp(16), dp(16), dp(16));
        button.setMinimumHeight(dp(80));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(dark ? 0xFF1D2C33 : 0xFFF5F7F8);
        shape.setCornerRadius(dp(14));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), shape, shape));
        ImageView icon = new ImageView(this);
        icon.setImageResource(getResources().getIdentifier("ic_github", "drawable", getPackageName()));
        icon.setColorFilter(foreground());
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(16), 0, 0, 0);
        words.addView(text("GitHub", 17));
        words.addView(text("CrayonL / RebornOwl", 13));
        button.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        button.setContentDescription("开源项目，GitHub，CrayonL / RebornOwl");
        button.setFocusable(true);
        button.setOnClickListener(view -> openSource());
        return button;
    }

    private void openSource() {
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL));
        web.addCategory(Intent.CATEGORY_BROWSABLE);
        Intent app = new Intent(web).setPackage(GITHUB_PACKAGE);
        try {
            startActivity(app.resolveActivity(getPackageManager()) != null ? app : web);
        } catch (ActivityNotFoundException error) {
            Log.e("OwlReborn", "无法打开开源地址", error);
            Toast.makeText(this, "未找到可打开 GitHub 链接的应用", Toast.LENGTH_LONG).show();
        }
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(foreground());
        view.setLineSpacing(dp(3), 1f);
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private int foreground() { return dark ? DARK_FOREGROUND : LIGHT_FOREGROUND; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
