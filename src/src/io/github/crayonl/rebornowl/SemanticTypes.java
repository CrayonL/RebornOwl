package io.github.crayonl.rebornowl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 语义类型定位：**名字只是线索，结构才是契约**。
 *
 * <p>这几类目标在宿主里是「语义名」（R8 通常不改写），但只认名字就没有第二道保险：
 * 一旦哪天被改写或挪包，功能会以「类缺失」失败，而不是按结构继续工作。这里统一成
 * 「先按名字，未命中或类型不符就按结构找，结构命中多个即失败」。
 *
 * <p>各目标的判据（都在真实 6.106.5 包上量过候选数）：
 * <ul>
 *   <li>会员视图状态枚举：枚举 + 常量 {@code SUPPORTED} + 被「参数含 {@code Set} 的方法」
 *       当作返回类型（实测唯一）；</li>
 *   <li>路径关卡状态枚举：名字命中时直接用；否则从关卡类自己的字段里找「唯一一个带
 *       {@code ACTIVE} 且带 {@code LOCKED}/{@code UNLOCKED} 的枚举字段类型」
 *       （单独按常量找有 8 个候选，必须借关卡类收敛）；</li>
 *   <li>路径分段/向量：名字命中时直接用；否则按「名字含 PathSection + 唯一 int/String/
 *       列表 字段 + 三参构造」找分段类，再由它的列表字段反推向量类型（按名字找 Vector
 *       有 5 个候选）。</li>
 * </ul>
 */
final class SemanticTypes {

    private SemanticTypes() {}

    private static volatile Class<?> sStatus;
    private static volatile Class<?> sVector;
    private static volatile Class<?> sSection;

    // ---------------------------------------------------------------- 会员视图状态

    static Class<?> dashboardStatus(ClassLoader loader) {
        Class<?> cached = sStatus;
        if (cached != null) {
            return cached;
        }
        Class<?> named = Locator.findByName(Targets.C_DASH_STATUS, loader);
        if (named != null && isDashboardStatus(named)) {
            sStatus = named;
            return named;
        }
        List<Class<?>> found = new ArrayList<>();
        for (String descriptor : DexIndex.findClassesContaining(Targets.F_DASH_SUPPORTED)) {
            Class<?> candidate = Locator.findByName(Locator.descToName(descriptor), loader);
            if (candidate == null || !isDashboardStatus(candidate)) {
                continue;
            }
            if (DexIndex.findClassesWithMethod(null, descriptor, "Ljava/util/Set;").isEmpty()) {
                continue;   // 会员面板的视图模型一定带 Set 参数
            }
            found.add(candidate);
        }
        if (found.size() != 1) {
            throw new IllegalStateException("会员视图状态枚举：名字与结构均未唯一命中 " + names(found));
        }
        Logx.i("会员解锁：按结构定位状态枚举 → " + found.get(0).getName());
        sStatus = found.get(0);
        return found.get(0);
    }

    /** 判据：枚举 + 自身类型的 SUPPORTED 常量（供结构回退与单测共用）。 */
    static boolean isDashboardStatus(Class<?> type) {
        if (!type.isEnum()) {
            return false;
        }
        try {
            return type.getField(Targets.F_DASH_SUPPORTED).getType() == type;
        } catch (NoSuchFieldException error) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 路径关卡状态

    /** 关卡状态枚举：名字优先；名字失效时从关卡类自己的枚举字段里收敛。 */
    static Class<?> pathLevelState(ClassLoader loader, Class<?> level) {
        Class<?> named = Locator.findByName(Targets.C_PATH_LEVEL_STATE, loader);
        if (named != null && isLevelState(named)) {
            return named;
        }
        List<Class<?>> found = new ArrayList<>();
        for (Field field : level.getDeclaredFields()) {
            Class<?> type = field.getType();
            if (Modifier.isStatic(field.getModifiers()) || !isLevelState(type)) {
                continue;
            }
            found.add(type);
        }
        if (found.size() != 1) {
            throw new IllegalStateException("路径关卡状态枚举：结构未唯一命中 " + names(found));
        }
        Logx.i("解锁关卡：按结构定位状态枚举 → " + found.get(0).getName());
        return found.get(0);
    }

    /** 枚举且同时有 ACTIVE 与 LOCKED/UNLOCKED —— 本模块只会读这几个常量。 */
    static boolean isLevelState(Class<?> type) {
        if (!type.isEnum()) {
            return false;
        }
        boolean active = false;
        boolean locked = false;
        for (Object constant : type.getEnumConstants()) {
            String name = ((Enum<?>) constant).name();
            active |= "ACTIVE".equals(name);
            locked |= "LOCKED".equals(name) || "UNLOCKED".equals(name);
        }
        return active && locked;
    }

    // ---------------------------------------------------------------- 路径分段 / 向量

    static Class<?> coursePathSection(ClassLoader loader) {
        Class<?> cached = sSection;
        if (cached != null) {
            return cached;
        }
        Class<?> named = Locator.findByName(Targets.C_PATH_SECTION, loader);
        if (named != null && sectionShape(named) != null) {
            sSection = named;
            return named;
        }
        List<Class<?>> found = new ArrayList<>();
        for (String descriptor : DexIndex.findClassesByName("PathSection")) {
            Class<?> candidate = Locator.findByName(Locator.descToName(descriptor), loader);
            if (candidate != null && sectionShape(candidate) != null) {
                found.add(candidate);
            }
        }
        if (found.size() != 1) {
            throw new IllegalStateException("路径分段：名字与结构均未唯一命中 " + names(found));
        }
        Logx.i("解锁关卡：按结构定位路径分段 → " + found.get(0).getName());
        sSection = found.get(0);
        return found.get(0);
    }

    /** 分段契约：唯一 int / String / 列表 字段，且有一个正好接收这三者的构造器。 */
    static Field[] sectionShape(Class<?> type) {
        Field number = null;
        Field identity = null;
        Field items = null;
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> fieldType = field.getType();
            if (fieldType == int.class) {
                if (number != null) { return null; }
                number = field;
            } else if (fieldType == String.class) {
                if (identity != null) { return null; }
                identity = field;
            } else if (List.class.isAssignableFrom(fieldType)) {
                if (items != null) { return null; }
                items = field;
            }
        }
        if (number == null || identity == null || items == null) {
            return null;
        }
        Set<Class<?>> wanted = new LinkedHashSet<>();
        wanted.add(int.class);
        wanted.add(String.class);
        wanted.add(items.getType());
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Set<Class<?>> actual = new LinkedHashSet<>(java.util.Arrays.asList(constructor.getParameterTypes()));
            if (constructor.getParameterTypes().length == 3 && actual.equals(wanted)) {
                return new Field[]{number, identity, items};
            }
        }
        return null;
    }

    static Class<?> pathVector(ClassLoader loader, Class<?> section) {
        Class<?> cached = sVector;
        if (cached != null) {
            return cached;
        }
        Class<?> named = Locator.findByName(Targets.C_PATH_VECTOR, loader);
        if (named != null && List.class.isAssignableFrom(named)) {
            sVector = named;
            return named;
        }
        Field[] shape = sectionShape(section);
        if (shape == null) {
            throw new IllegalStateException("路径向量：分段结构不成立，无法反推");
        }
        Logx.i("解锁关卡：按结构定位路径向量 → " + shape[2].getType().getName());
        sVector = shape[2].getType();
        return sVector;
    }

    private static String names(List<Class<?>> types) {
        StringBuilder text = new StringBuilder("[");
        for (Class<?> type : types) {
            if (text.length() > 1) { text.append(", "); }
            text.append(type.getName());
        }
        return text.append(']').toString();
    }
}
