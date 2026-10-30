package com.owl.auxiliary;

/**
 * 版本敏感层：所有可能随 Duolingo 版本变化的目标集中于此。
 *
 * <p>6.103.6（2488）安装包离线核对：14 条特征串仍有代码引用。
 * 方法和参数契约见 {@code work/test/host_contract_test.py}；真机效果需单独验收。
 *
 * <p>定位策略固定为三级回退：
 * <ol>
 *   <li>字符串特征定位（{@link Locator}，经 {@link DexIndex}）——版本自适应，首选；</li>
 *   <li>硬编码类名（本类常量）——特征串失效时的兜底；</li>
 *   <li>都失败则记 WARN，绝不静默吞异常。</li>
 * </ol>
 */
public final class Targets {

    private Targets() {}

    public static final String HOST_PKG = "com.duolingo";
    public static final String MODULE_PKG = "io.github.crayonl.rebornowl";

    // ---------- 硬编码类名（兜底） ----------
    public static final String C_PLUS_VIEW_MODEL = "com.duolingo.plus.dashboard.PlusViewModel";
    public static final String C_DASH_STATUS =
            "com.duolingo.plus.dashboard.PlusViewModel$DashboardFeatureDisplayStatus";
    public static final String C_MUSIC_ACCESS = "com.duolingo.core.music.licensed.LicensedMusicAccess";
    /** 6.103.6 中独立携带音乐权限的歌曲入口、会话与题目模型；真实 DEX 契约逐一核对。 */
    public static final String[] C_MUSIC_ACCESS_MODELS = {
            "com.duolingo.core.music.licensed.LicensedMusicPathData",
            "com.duolingo.data.home.music.SongLandingPathData$LicensedSongLandingData",
            "com.duolingo.data.home.music.LicensedMusicDetails",
            "com.duolingo.data.home.music.LicensedSessionData",
            "fk4", "ok4", "pk4", "zmq",
    };
    public static final String C_DEBUG_CATEGORY = "com.duolingo.data.debug.settings.DebugCategory";
    public static final String C_DEBUG_CATEGORY_OLD = "com.duolingo.debug.DebugCategory";
    public static final String C_SETTINGS_ACTIVITY = "com.duolingo.settings.SettingsActivity";
    public static final String C_LAUNCH_ACTIVITY = "com.duolingo.splash.LaunchActivity";
    public static final String C_TIME_ZONE = "kotlinx.datetime.TimeZone";
    /** 6.103.6 统一业务时钟及 Kotlin Instant；由 DEX 契约测试核对。 */
    public static final String C_HOST_CLOCK = "bb7";
    public static final String C_HOST_INSTANT = "nqk";
    public static final String C_SUBSCRIPTION_PREFIX = "com.duolingo.data.user.Subscrib";

    // ---------- 拽姐视频通话（无法解锁——服务端限制；入口改道并入会员解锁） ----------
    /** Max 功能枚举：{@code SubscriptionFeatures}（含 VIDEO_CALL_IN_PATH 等 19 项）。 */
    public static final String C_SUB_FEATURES =
            "com.duolingo.core.subscription.models.SubscriptionFeatures";
    /**
     * 功能组数据类：字段 {@code a} 为 {@code PSet<SubscriptionFeatures>}，
     * 由服务端 JSON（{@code deserialize}）构造；宿主各处用
     * {@code group.a.contains(feature)} 判断某 Max 功能是否可用。
     */
    public static final String C_SUB_FEATURE_GROUP =
            "com.duolingo.data.subscription.max.SubscriptionFeatureGroup";
    /** 不可变集合接口（pcollections 迁移层），字段 {@code a} 的类型，提供 {@code plus(Object)}。 */
    public static final String C_PSET = "com.duolingo.core.pcollections.migration.PSet";
    /** 视频通话界面（其 onCreate 要求 intent 携带 call_origin 附加项，见 6.100.3 实测）。 */
    public static final String C_VIDEO_CALL_ACTIVITY = "com.duolingo.ai.videocall.VideoCallActivity";
    /** 通话来源：练习基地（无参构造，默认 accessMethod=SUBSCRIPTION、serializedName=practice_hub）。 */
    public static final String C_VC_ORIGIN_PRACTICE_HUB =
            "com.duolingo.data.video.call.VideoCallCallOrigin$PracticeHub";
    /**
     * 6.103.6 控制板点击工厂。PlusActivity 与 dashboard.b 均构造此类，
     * invoke(Object) 的对应分支将 DashboardFeature 转成 View.OnClickListener。
     *
     * <p>该类在默认包，查找时使用裸名。旧 j3v 在当前版本已经属于购买页面，
     * 不能继续作为回退目标。
     *
     * <p>实测 6.100.3：{@code invoke(DashboardFeature)} 返回该行的 OnClickListener；
     * 其中 {@code VIDEO_CALL} 行返回的监听器点击后仅「切到练习基地页签 + 关闭控制板」
     * （事件 {@code hdv(20)}），并不直接开呼 —— 本模块在此处将点击改为
     * 「直接启动 VideoCallActivity」（注：该功能受服务端限制、无法解锁，此项仅为入口交互修正）。仅控制板内使用（含
     * {@code dashboard.b.e} 与 {@code PlusActivity.onCreate} 两处构造点），无其它调用点。
     */
    public static final String C_DASHBOARD_CLICK_FACTORY = "tuu";
    /** 视频通话 intent 的附加项名（VideoCallActivity 视图模型强制要求）。 */
    public static final String E_VIDEO_CALL_CALL_ORIGIN = "call_origin";
    /** 控制板功能枚举中「视频通话」行的常量名（DashboardFeature.VIDEO_CALL）。 */
    public static final String F_DASHBOARD_FEATURE_VIDEO_CALL = "VIDEO_CALL";

    // ---------- 主界面 / 调试界面（调试模式的悬浮球与入口） ----------
    /**
     * 宿主「主界面」Activity。单 Activity 架构：登录后首页/学习路径一直挂在该 Activity 上。
     *
     * <p>实测 6.100.3：启动器为若干 activity-alias（{@code com.duolingo.app.LoginActivity} /
     * {@code StreakSocietyLauncher} / {@code SeasonalLauncher1-4}），
     * targetActivity 全部指向 {@code com.duolingo.splash.LaunchActivity} —— 运行时真实类名
     * 即后者（别名名不会出现在 {@code getClass().getName()} 上）。
     */
    public static final String C_MAIN_ACTIVITY = "com.duolingo.app.LoginActivity";
    /** 真实主界面候选（activity-alias 的目标）。 */
    public static final String C_MAIN_ACTIVITY_ALT = "com.duolingo.splash.LaunchActivity";
    /** 宿主自带的调试菜单界面（未 exported，但同进程内可直接 startActivity）。 */
    public static final String C_DEBUG_ACTIVITY = "com.duolingo.debug.DebugActivity";
    public static final String C_BASE_DEBUG_ACTIVITY =
            "com.duolingo.core.debug.settings.BaseDebugActivity";
    public static final String C_BASE_DEBUG_COMPOSE_ACTIVITY =
            "com.duolingo.core.debug.settings.BaseDebugComposeActivity";

    // ---------- 字符串特征 ----------
    // 会员
    public static final String S_USER = "User(adsConfig=";
    public static final String S_PLUS_VIEW_MODEL = "PlusViewModel";
    // 音乐
    public static final String S_MUSIC = "Music(courseSummary=";
    // 强制正确
    public static final String S_GRADED = "Graded(blameType=";
    public static final String S_SPEECH = "SpeechRecognitionResult(score=";
    public static final String S_CHAR_MATCH = "CharacterMatch(base=";
    public static final String S_WORD_MATCH = "WordMatch(base=";
    public static final String S_LISTEN_MATCH = "ListenMatch(base=";
    public static final String S_PASSAGE = "PassageCorrectnessInfo(percentCorrect=";
    public static final String S_MATCH_CHALLENGE = "MatchChallenge(instruction=";
    // 无限时间
    public static final String S_TIMED_MULTI = "TimedMultiSessionPractice(sessionXp=";
    public static final String S_TIMED_MATCH = "TimedMatchPractice(initialSessionTime=";
    public static final String S_SESSION_DEBUG = "SessionDebugSettings(allowSessionOverride=";
    public static final String S_MAX_SESSION_LEN = ", maxSessionLength=";
    // 解锁关卡
    public static final String S_PATH_LEVEL = "GenericPathLevel(id=";
    public static final String S_PATH_SESSION_STATE = "PathLevelSessionState(level=";
    public static final String C_NEW_PATH_CONVERTER = "cur";
    public static final String C_NEW_PATH_POPUP = "qvr";
    public static final String C_PATH_SECTION_FILTER = "cao";
    public static final String C_PATH_SECTION = "com.duolingo.data.path.common.CoursePathSection";
    public static final String C_PATH_UNIT = "wzh";
    public static final String C_PATH_VECTOR = "com.duolingo.core.pcollections.migration.PVector";
    public static final String C_PATH_LEVEL_STATE = "com.duolingo.data.path.common.PathLevelState";

    // ---------- 拽姐视频通话 ----------
    /**
     * 要追加进每个 {@code SubscriptionFeatureGroup} 的功能名。
     *
     * <p>只加「视频通话」相关两项，不动互斥项（如 LICENSED_SONGS / NO_LICENSED_SONGS）
     * 与运营项（NO_SUPER_PROMOS），避免连锁副作用；后续要解锁 Roleplay /
     * Explain My Answer 等其它 Max 功能时往这里加枚举名即可。
     */
    public static final String[] F_VIDEO_CALL_FEATURES = {
            "VIDEO_CALL_IN_PATH",           // 学习路径上的视频通话入口（拽姐弹层/节点）
            "VIDEO_CALL_IN_PRACTICE_HUB",   // 练习中心里的视频通话
    };

    // ---------- 关键字段/常量名 ----------
    public static final String F_DASH_SUPPORTED = "SUPPORTED";
    public static final String F_MUSIC_FULL = "FULL";
    public static final String F_MUSIC_TASTER = "TASTER";
    public static final String F_MUSIC_NONE = "NONE";
    /** Max 等级；6.103.6 的 hasMax 默认计算检查 GOLD。 */
    public static final String F_TIER_GOLD = "GOLD";
    /** Super 等级（沿用宿主的历史命名 PREMIUM）。 */
    public static final String F_TIER_PREMIUM = "PREMIUM";
    /** Super Lite 等级。 */
    public static final String F_TIER_LITE = "LITE";
    /** {@code com.duolingo.data.user.User} 上的「Max 品牌」布尔字段（序列化名 hasMax）。 */
    public static final String F_USER_HAS_MAX = "hasMax";
    /**
     * {@code User} 序列化字段表里 {@code hasMax} 相对 {@code subscriberLevel} 的下标偏移。
     *
     * <p>实测 6.99.5：字段序号 79/90（差 11），全参构造器（107 参）内 83/94；
     * 6.100.3 复测：偏移仍为 11（全参构造器已增至 109 参）。两个版本一致，
     * 故用「SubscriberLevel 实参下标 + 11」定位 hasMax，避免依赖字段名。
     */
    public static final int USER_ARG_HAS_MAX_OFFSET = 11;

    /**
     * 宿主版本号：{@code ApplicationInfo} 不含 versionName，这里返回安装路径后缀
     * 以便日志中区分宿主包。
     */
    public static String hostVersion(Object appInfo) {
        try {
            if (appInfo instanceof android.content.pm.ApplicationInfo) {
                return ((android.content.pm.ApplicationInfo) appInfo).sourceDir;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
