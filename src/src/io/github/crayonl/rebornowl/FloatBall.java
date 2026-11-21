package io.github.crayonl.rebornowl;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

/** 各宿主页面共用的设置入口；使用模块图标，无需系统悬浮窗权限。 */
public final class FloatBall extends FrameLayout {
    private static final String TAG_BALL = "owl_float_ball";
    private static final int BALL_DP = 52;
    private static final int DEFAULT_TOP_DP = 120;
    private static final int DEFAULT_RIGHT_DP = 10;
    private static final float IDLE_ALPHA = 0.92f;

    private final Activity activity;
    private final LayoutParams position;
    private final int slop;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::clampPosition;
    private Dialog settings;
    private float downX;
    private float downY;
    private int startRight;
    private int startTop;
    private boolean dragging;

    private FloatBall(Activity act) {
        super(act);
        activity = act;
        position = new LayoutParams(dp(BALL_DP), dp(BALL_DP));
        position.gravity = Gravity.TOP | Gravity.RIGHT;
        slop = ViewConfiguration.get(act).getScaledTouchSlop();
        setTag(TAG_BALL);
        setContentDescription(Brand.NAME + "设置");
        setFocusable(true);
        setAlpha(IDLE_ALPHA);
        ImageView icon = new ImageView(act);
        icon.setImageDrawable(Brand.logo(act));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(icon, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setOnClickListener(v -> {
            if (settings == null || !settings.isShowing()) {
                settings = SettingsInjector.show(activity);
            }
        });
        restorePosition();
    }

    public static void attach(Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) {
            return;
        }
        try {
            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            FloatBall ball = decor.findViewWithTag(TAG_BALL);
            if (ball == null) {
                ball = new FloatBall(act);
                decor.addView(ball, ball.position);
                Logx.i("设置悬浮球：已挂到 " + act.getClass().getSimpleName());
            } else {
                ball.restorePosition();
                ball.bringToFront();
            }
        } catch (Throwable t) {
            Logx.w("设置悬浮球：挂载失败", t);
        }
    }

    static int panelSide(Activity act) {
        ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
        View ball = decor.findViewWithTag(TAG_BALL);
        if (ball == null) { throw new IllegalStateException("设置面板缺少悬浮入口"); }
        return ball.getX() + ball.getWidth() / 2f < decor.getWidth() / 2f
                ? Gravity.LEFT : Gravity.RIGHT;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void restorePosition() {
        position.topMargin = Config.getInt(Config.P_FLOAT_Y, dp(DEFAULT_TOP_DP));
        position.rightMargin = Config.getInt(Config.P_FLOAT_X, dp(DEFAULT_RIGHT_DP));
        setLayoutParams(position);
        clampPosition();
    }

    private void clampPosition() {
        if (!(getParent() instanceof View)) {
            return;
        }
        View parent = (View) getParent();
        if (parent.getWidth() < position.width || parent.getHeight() < position.height) {
            return;
        }
        WindowInsets insets = getRootWindowInsets();
        int left = insets == null ? 0 : insets.getSystemWindowInsetLeft();
        int right = insets == null ? 0 : insets.getSystemWindowInsetRight();
        int top = insets == null ? 0 : insets.getSystemWindowInsetTop();
        int bottom = insets == null ? 0 : insets.getSystemWindowInsetBottom();
        int x = Math.max(right, Math.min(position.rightMargin, parent.getWidth() - position.width - left));
        int y = Math.max(top, Math.min(position.topMargin, parent.getHeight() - position.height - bottom));
        if (x != position.rightMargin || y != position.topMargin) {
            position.rightMargin = x;
            position.topMargin = y;
            setLayoutParams(position);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        if (settings != null) {
            settings.dismiss();
            settings = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                startRight = position.rightMargin;
                startTop = position.topMargin;
                dragging = false;
                setAlpha(1f);
                return true;
            case MotionEvent.ACTION_MOVE:
                moveBall(event);
                return true;
            case MotionEvent.ACTION_UP:
                finishGesture();
                return true;
            case MotionEvent.ACTION_CANCEL:
                setAlpha(IDLE_ALPHA);
                restorePosition();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void moveBall(MotionEvent event) {
        int dx = Math.round(event.getRawX() - downX);
        int dy = Math.round(event.getRawY() - downY);
        dragging |= Math.abs(dx) > slop || Math.abs(dy) > slop;
        if (!dragging) {
            return;
        }
        position.rightMargin = startRight - dx;
        position.topMargin = startTop + dy;
        setLayoutParams(position);
        clampPosition();
    }

    private void finishGesture() {
        setAlpha(IDLE_ALPHA);
        if (dragging) {
            Config.setInt(Config.P_FLOAT_X, position.rightMargin);
            Config.setInt(Config.P_FLOAT_Y, position.topMargin);
        } else {
            performClick();
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    public static void openDebugMenu(Activity act) {
        DebugMenuGuard.markOpening();
        try {
            String name = debugActivity(act);
            Intent intent = new Intent();
            intent.setClassName(Targets.HOST_PKG, name);
            act.startActivity(intent);
            Logx.i("调试模式：已请求打开 " + name);
        } catch (Throwable t) {
            FeatureDiagnostics.failure(Config.K_DEBUG, "调试模式：打开调试菜单失败", t);
            Toast.makeText(act, "打开调试菜单失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 调试界面组件名：已知名字优先，名字变了就按宿主清单回退。
     *
     * <p>清单里的组件名不会被 R8 混淆（运行时按名字解析），所以「以 DebugActivity
     * 结尾、且包段正好是 debug」足以在改名后仍定位到同一个界面；命中多个则显式失败，
     * 不猜。
     */
    private static String debugActivity(Activity act) {
        if (sDebugActivity != null) {
            return sDebugActivity;
        }
        if (Locator.findByName(Targets.C_DEBUG_ACTIVITY, act.getClassLoader()) != null) {
            sDebugActivity = Targets.C_DEBUG_ACTIVITY;
            return sDebugActivity;
        }
        try {
            PackageInfo info = act.getPackageManager()
                    .getPackageInfo(Targets.HOST_PKG, PackageManager.GET_ACTIVITIES);
            ActivityInfo[] activities = info.activities;
            String loose = null;
            if (activities != null) {
                for (ActivityInfo candidate : activities) {
                    String name = candidate == null ? null : candidate.name;
                    if (name == null || name.contains("$")) {
                        continue;
                    }
                    if (name.endsWith(".debug.DebugActivity")) {
                        sDebugActivity = name;
                        Logx.i("调试模式：按清单定位调试界面 " + name);
                        return sDebugActivity;
                    }
                    if (name.endsWith("DebugActivity")) {
                        if (loose != null && !loose.equals(name)) {
                            throw new IllegalStateException("调试界面不唯一：" + loose + " / " + name);
                        }
                        loose = name;
                    }
                }
            }
            if (loose != null) {
                sDebugActivity = loose;
                Logx.i("调试模式：按清单定位调试界面 " + loose);
                return sDebugActivity;
            }
        } catch (Throwable error) {
            Logx.w("调试模式：读取宿主清单失败", error);
        }
        throw new IllegalStateException("找不到宿主调试界面组件");
    }

    private static String sDebugActivity;
}
