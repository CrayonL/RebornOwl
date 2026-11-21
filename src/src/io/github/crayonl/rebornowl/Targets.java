package io.github.crayonl.rebornowl;

/**
 * 版本敏感层：所有可能随 Duolingo 版本变化的目标集中于此。
 *
 * <p>时钟和路径目标使用 DEX 签名或特征串，再校验完整结构。
 * 保留有语义的类型与枚举名称；缺失或歧义不能当作适配成功。
 * 四版离线契约见 {@code work/test/host_auto_adapt_contract_test.py}；真机效果需单独验收。
 */
public final class Targets {

    private Targets() {}

    public static final String HOST_PKG = "com.duolingo";
    public static final String MODULE_PKG = "io.github.crayonl.rebornowl";

    // ---------- 有语义的类型名 ----------
    public static final String C_DASH_STATUS =
            "com.duolingo.plus.dashboard.PlusViewModel$DashboardFeatureDisplayStatus";
    public static final String C_DEBUG_CATEGORY = "com.duolingo.data.debug.settings.DebugCategory";
    public static final String C_DEBUG_CATEGORY_OLD = "com.duolingo.debug.DebugCategory";
    public static final String C_SETTINGS_ACTIVITY = "com.duolingo.settings.SettingsActivity";
    public static final String C_LAUNCH_ACTIVITY = "com.duolingo.splash.LaunchActivity";
    public static final String C_TIME_ZONE = "kotlinx.datetime.TimeZone";

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
    /** 宿主 release 包拒绝进入调试菜单时的日志文案；同时是本模块定位准入判定分支的锚点。 */
    public static final String S_DEBUG_INELIGIBLE = "Ineligible user accessed the debug menu";
    /** 会话结算请求里的经验加成倍数；本模块按此定位加成写入点。 */
    public static final String S_XP_BOOST_MULTIPLIER = ", xpBoostMultiplier=";
    /** 会话模型（`Session(...)`）里的当前加成倍数，读用户状态得到。 */
    public static final String S_XP_BOOST_CURRENT = ", currentXpBoostMultiplier=";
    // 会员
    public static final String S_USER = "User(adsConfig=";
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
    public static final String S_PATH_CONVERTER = "migration_review_unit_review";
    public static final String S_PATH_POPUP = "No popup copy for path level client data ";
    public static final String S_PATH_UNIT = "GenericPathUnit(index=";
    public static final String S_PATH_TREATMENT = "HideFuturePathAndUnitsTreatmentRecords(hideFuturePathTreatmentRecords=";
    public static final String C_PATH_SECTION = "com.duolingo.data.path.common.CoursePathSection";
    public static final String C_PATH_VECTOR = "com.duolingo.core.pcollections.migration.PVector";
    public static final String C_PATH_LEVEL_STATE = "com.duolingo.data.path.common.PathLevelState";

    // ---------- 关键字段/常量名 ----------
    public static final String F_DASH_SUPPORTED = "SUPPORTED";
    /** Max 等级；6.103.6 的 hasMax 默认计算检查 GOLD。 */
    public static final String F_TIER_GOLD = "GOLD";
    /** Super 等级（沿用宿主的历史命名 PREMIUM）。 */
    public static final String F_TIER_PREMIUM = "PREMIUM";
    /** Super Lite 等级。 */
    public static final String F_TIER_LITE = "LITE";
    /** {@code com.duolingo.data.user.User} 上的「Max 品牌」布尔字段（序列化名 hasMax）。 */
    public static final String F_USER_HAS_MAX = "hasMax";
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
