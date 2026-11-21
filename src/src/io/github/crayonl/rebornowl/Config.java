package io.github.crayonl.rebornowl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * 配置存储（无 Context 依赖）。
 *
 * <p>不占用宿主 SharedPreferences，而是在宿主 data 目录下自建 properties 文件：
 * {@code <dataDir>/app_owl-auxiliary/config.properties}。
 * 模块运行在宿主进程内、与宿主同 uid，因此可自由读写该目录，且随宿主卸载/清数据一并清除。
 */
public final class Config {

    // ---- 开关键 ----
    public static final String K_PREMIUM  = "premium";   // 会员解锁
    public static final String K_SUPER    = "super";     // Super/Max 模式
    public static final String K_UNLOCK   = "unlock";    // 解锁关卡
    public static final String K_GAMETIME = "gametime";  // 无限时间
    public static final String K_DATE     = "date";      // 日期
    public static final String K_ZONEID   = "zoneid";    // 地区（沿用原时区开关键）
    public static final String K_CORRECT  = "correct";   // 强制正确
    public static final String K_DEBUG    = "debug";     // 调试模式
    public static final String K_SKIP     = "skip";      // 直接通关
    public static final String K_XPBOOST  = "xpboost";   // 经验加成倍率（账号没有道具时也写入结算）

    // ---- 附加参数 ----
    public static final String P_TIMEZONE = "time_zone";   // 时区 ID
    /** 默认中国上海时区；用户选定其他时区后保留其选择。 */
    public static final String ZONE_DEFAULT = "Asia/Shanghai";
    public static final String P_DATE     = "selected_date"; // 选定日期 yyyy-MM-dd
    public static final String P_FLOAT_X  = "float_pos_x";
    public static final String P_FLOAT_Y  = "float_pos_y";
    /** 会员等级：super / max。 */
    public static final String P_LEVEL    = "premium_level";
    public static final String P_IGNORED_PROBLEM = "ignored_problem";
    /** 经验加成倍率：2 / 3。 */
    public static final String P_XPBOOST  = "xpboost_multiplier";

    /** 会员等级取值。 */
    public static final String LV_SUPER = "super";
    public static final String LV_MAX   = "max";

    /** 经验加成倍率取值。 */
    public static final String XP_2X = "2";
    public static final String XP_3X = "3";

    private static final Properties P = new Properties();
    private static volatile boolean sLoaded = false;
    private static File sFile;
    /** PC 可写的覆盖配置（宿主外部私有目录，adb shell 可直接读写，无需 root/run-as）。 */
    private static File sAltFile;

    private Config() {}

    /** 由 {@link XposedEntry} 在 handleLoadPackage 早期调用，传入宿主 ApplicationInfo.dataDir。 */
    public static synchronized void init(String dataDir) {
        if (dataDir == null) {
            return;
        }
        sFile = new File(new File(dataDir, "app_owl-auxiliary"), "config.properties");
        sAltFile = new File("/sdcard/Android/data/" + Targets.HOST_PKG
                + "/files/owl-auxiliary.properties");
        load0();
    }

    /** 兜底：若 init 未调用，则在已知文件路径时懒加载。 */
    public static synchronized void load() {
        load0();
    }

    private static void load0() {
        if (sLoaded || sFile == null) {
            return;
        }
        try {
            if (sFile.isFile()) {
                try (FileInputStream in = new FileInputStream(sFile)) {
                    P.load(in);
                }
            }
            // 外部覆盖文件（便于 PC 端 adb 写入开关做调试）。
            // 只有当它比内部配置**不旧**时才应用：否则一份过期的镜像会在下次启动时把
            // 用户刚改的开关回滚，表现为「开关记不住」。
            if (sAltFile != null && sAltFile.isFile()
                    && (!sFile.isFile() || sAltFile.lastModified() >= sFile.lastModified())) {
                String ignored = P.getProperty(P_IGNORED_PROBLEM);
                try (FileInputStream in = new FileInputStream(sAltFile)) {
                    P.load(in);
                }
                // 提醒偏好以内部配置为准，外部调试文件不能覆盖用户的新选择。
                if (ignored == null) { P.remove(P_IGNORED_PROBLEM); }
                else { P.setProperty(P_IGNORED_PROBLEM, ignored); }
                Logx.i("已应用外部覆盖配置: " + sAltFile);
            }
            if (!P.containsKey(K_DATE)) {
                // 只迁移一次，之后切换地区不再影响日期开关。
                P.setProperty(K_DATE, P.getProperty(K_ZONEID, "true"));
                save();
            }
        } catch (Throwable t) {
            Logx.w("Config 读取失败", t);
        }
        sLoaded = true;
        Logx.i("Config 已加载: " + P);
    }

    public static boolean get(String key) {
        load();
        return "true".equalsIgnoreCase(P.getProperty(key, "false"));
    }

    /**
     * 带默认值的开关读取：配置里<b>显式写过的值</b>优先（含用户手动写下的 false）；
     * 完全没有该键时用 {@code def}（来自 {@link Feature#defaultOn()}）。
     *
     * <p>这样「默认开启」的功能在全新安装时即生效，而用户手动关闭
     * （写入 false）后仍能真正关闭 —— 不会出现"关不掉"。
     */
    public static boolean getOr(String key, boolean def) {
        load();
        String v = P.getProperty(key);
        return v == null ? def : "true".equalsIgnoreCase(v);
    }

    public static String getStr(String key, String def) {
        load();
        String v = P.getProperty(key);
        return v == null ? def : v;
    }

    public static String activeDate() {
        return getOr(K_DATE, true) ? getStr(P_DATE, "") : null;
    }

    public static String activeRegion() {
        return getOr(K_ZONEID, true) ? getStr(P_TIMEZONE, ZONE_DEFAULT) : null;
    }

    /** 选定的经验加成倍率（2 或 3）。 */
    public static String xpBoostLevel() {
        String value = getStr(P_XPBOOST, XP_3X);
        return XP_3X.equals(value) ? XP_3X : XP_2X;
    }

    /** 选定的经验加成倍数（2.0 / 3.0），用于写入宿主结算。 */
    public static double xpBoostMultiplier() {
        return XP_3X.equals(xpBoostLevel()) ? 3.0d : 2.0d;
    }

    public static int getInt(String key, int def) {
        load();
        String v = P.getProperty(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static synchronized void set(String key, boolean value) {
        load();
        P.setProperty(key, value ? "true" : "false");
        save();
    }

    public static synchronized void setStr(String key, String value) {
        load();
        if (value == null) {
            P.remove(key);
        } else {
            P.setProperty(key, value);
        }
        save();
    }

    public static synchronized void setInt(String key, int value) {
        setStr(key, Integer.toString(value));
    }

    static synchronized boolean suppressProblem(String fingerprint) {
        load();
        String previous = P.getProperty(P_IGNORED_PROBLEM);
        P.setProperty(P_IGNORED_PROBLEM, fingerprint);
        if (!store(sFile, false)) {
            if (previous == null) { P.remove(P_IGNORED_PROBLEM); }
            else { P.setProperty(P_IGNORED_PROBLEM, previous); }
            return false;
        }
        return true;
    }

    private static void save() {
        store(sFile, false);
        if (sAltFile != null && sAltFile.isFile() && !store(sAltFile, true)) {
            // 外部镜像写不进去（例如被 root 改过权限）时，它会在下次启动覆盖用户选择，
            // 表现为「开关记不住」。这种镜像宁可直接删掉，让内部配置说了算。
            if (sAltFile.delete()) {
                Logx.w("Config 外部镜像不可写，已删除以免覆盖用户开关: " + sAltFile, null);
            } else {
                Logx.w("Config 外部镜像不可写且无法删除，下次启动可能回滚开关: " + sAltFile, null);
            }
        }
    }

    private static boolean store(File f, boolean quiet) {
        if (f == null) {
            return false;
        }
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                P.store(out, "Owl Auxiliary config");
            }
            return true;
        } catch (Throwable t) {
            if (!quiet) {
                Logx.w("Config 保存失败: " + f, t);
            }
            return false;
        }
    }
}
