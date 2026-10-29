package com.owl.auxiliary;

/**
 * 常用国家 / 地区的时区表（设置页下拉菜单用）。
 *
 * <p>每项为 {@code {显示名, IANA 时区 ID}}。下拉选中后直接写入
 * {@link Config#P_TIMEZONE}，选择后直接确认，并在设置框中显示名称。
 */
public final class ZoneList {

    /** 显示名 → 时区 ID。 */
    public static final String[][] ENTRIES = {
            // 亚洲
            {"中国 · 上海 (Asia/Shanghai)", "Asia/Shanghai"},
            {"中国香港 (Asia/Hong_Kong)", "Asia/Hong_Kong"},
            {"中国澳门 (Asia/Macau)", "Asia/Macau"},
            {"中国台湾 · 台北 (Asia/Taipei)", "Asia/Taipei"},
            {"日本 · 东京 (Asia/Tokyo)", "Asia/Tokyo"},
            {"韩国 · 首尔 (Asia/Seoul)", "Asia/Seoul"},
            {"新加坡 (Asia/Singapore)", "Asia/Singapore"},
            {"马来西亚 · 吉隆坡 (Asia/Kuala_Lumpur)", "Asia/Kuala_Lumpur"},
            {"泰国 · 曼谷 (Asia/Bangkok)", "Asia/Bangkok"},
            {"越南 · 河内 (Asia/Ho_Chi_Minh)", "Asia/Ho_Chi_Minh"},
            {"印度尼西亚 · 雅加达 (Asia/Jakarta)", "Asia/Jakarta"},
            {"菲律宾 · 马尼拉 (Asia/Manila)", "Asia/Manila"},
            {"印度 · 新德里 (Asia/Kolkata)", "Asia/Kolkata"},
            {"阿联酋 · 迪拜 (Asia/Dubai)", "Asia/Dubai"},
            {"以色列 · 耶路撒冷 (Asia/Jerusalem)", "Asia/Jerusalem"},
            {"沙特 · 利雅得 (Asia/Riyadh)", "Asia/Riyadh"},
            {"土耳其 · 伊斯坦布尔 (Europe/Istanbul)", "Europe/Istanbul"},
            // 欧洲
            {"英国 · 伦敦 (Europe/London)", "Europe/London"},
            {"爱尔兰 · 都柏林 (Europe/Dublin)", "Europe/Dublin"},
            {"法国 · 巴黎 (Europe/Paris)", "Europe/Paris"},
            {"德国 · 柏林 (Europe/Berlin)", "Europe/Berlin"},
            {"荷兰 · 阿姆斯特丹 (Europe/Amsterdam)", "Europe/Amsterdam"},
            {"西班牙 · 马德里 (Europe/Madrid)", "Europe/Madrid"},
            {"意大利 · 罗马 (Europe/Rome)", "Europe/Rome"},
            {"瑞士 · 苏黎世 (Europe/Zurich)", "Europe/Zurich"},
            {"瑞典 · 斯德哥尔摩 (Europe/Stockholm)", "Europe/Stockholm"},
            {"挪威 · 奥斯陆 (Europe/Oslo)", "Europe/Oslo"},
            {"波兰 · 华沙 (Europe/Warsaw)", "Europe/Warsaw"},
            {"俄罗斯 · 莫斯科 (Europe/Moscow)", "Europe/Moscow"},
            {"乌克兰 · 基辅 (Europe/Kyiv)", "Europe/Kyiv"},
            // 美洲
            {"美国东部 · 纽约 (America/New_York)", "America/New_York"},
            {"美国中部 · 芝加哥 (America/Chicago)", "America/Chicago"},
            {"美国山地 · 丹佛 (America/Denver)", "America/Denver"},
            {"美国太平洋 · 洛杉矶 (America/Los_Angeles)", "America/Los_Angeles"},
            {"美国阿拉斯加 (America/Anchorage)", "America/Anchorage"},
            {"美国夏威夷 (Pacific/Honolulu)", "Pacific/Honolulu"},
            {"加拿大 · 多伦多 (America/Toronto)", "America/Toronto"},
            {"加拿大 · 温哥华 (America/Vancouver)", "America/Vancouver"},
            {"墨西哥 · 墨西哥城 (America/Mexico_City)", "America/Mexico_City"},
            {"巴西 · 圣保罗 (America/Sao_Paulo)", "America/Sao_Paulo"},
            {"阿根廷 · 布宜诺斯艾利斯 (America/Argentina/Buenos_Aires)",
                    "America/Argentina/Buenos_Aires"},
            {"智利 · 圣地亚哥 (America/Santiago)", "America/Santiago"},
            {"秘鲁 · 利马 (America/Lima)", "America/Lima"},
            // 大洋洲 / 非洲
            {"澳大利亚 · 悉尼 (Australia/Sydney)", "Australia/Sydney"},
            {"澳大利亚 · 珀斯 (Australia/Perth)", "Australia/Perth"},
            {"新西兰 · 奥克兰 (Pacific/Auckland)", "Pacific/Auckland"},
            {"埃及 · 开罗 (Africa/Cairo)", "Africa/Cairo"},
            {"南非 · 约翰内斯堡 (Africa/Johannesburg)", "Africa/Johannesburg"},
            {"尼日利亚 · 拉各斯 (Africa/Lagos)", "Africa/Lagos"},
            {"肯尼亚 · 内罗毕 (Africa/Nairobi)", "Africa/Nairobi"},
            {"摩洛哥 · 卡萨布兰卡 (Africa/Casablanca)", "Africa/Casablanca"},
            // 通用
            {"UTC (Etc/UTC)", "Etc/UTC"},
    };

    public static final String[] LABELS;
    public static final String[] IDS;

    static {
        LABELS = new String[ENTRIES.length];
        IDS = new String[ENTRIES.length];
        for (int i = 0; i < ENTRIES.length; i++) {
            LABELS[i] = ENTRIES[i][0];
            IDS[i] = ENTRIES[i][1];
        }
    }

    private ZoneList() {}

    /** 找到 zoneId 对应的下拉下标；未找到返回 -1。 */
    public static int indexOf(String zoneId) {
        if (zoneId == null) {
            return -1;
        }
        String z = zoneId.trim();
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equalsIgnoreCase(z)) {
                return i;
            }
        }
        return -1;
    }

    /** 追加「自定义」项后的下标（用于「未匹配」时定位）。 */
    public static final int CUSTOM_INDEX = ENTRIES.length;
}
