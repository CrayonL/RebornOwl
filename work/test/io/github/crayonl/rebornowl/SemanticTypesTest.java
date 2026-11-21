package io.github.crayonl.rebornowl;

import java.util.List;
import java.util.Vector;

/**
 * 结构判据单测：证明「按结构找」只会接受契约成立的形状，并拒绝歧义形状。
 *
 * <p>这些判据就是名字失效后的第二道保险，所以要把「像」和「不像」都钉住：
 * 多一个同类型字段、少一个常量、构造参数对不上，都必须被判据拒绝，而不是靠运气。
 */
public final class SemanticTypesTest {

    private SemanticTypesTest() {}

    public enum WithSupported {
        UNKNOWN, SUPPORTED, HIDDEN
    }

    public enum WithoutSupported {
        UNKNOWN, HIDDEN
    }

    public enum LevelState {
        LOCKED, ACTIVE, UNLOCKED, COMPLETED
    }

    public enum ActiveOnly {
        INACTIVE, ACTIVE
    }

    public static final class Section {
        public final int index;
        public final String identity;
        public final Vector items;
        public Section(int index, String identity, Vector items) {
            this.index = index;
            this.identity = identity;
            this.items = items;
        }
    }

    public static final class DuplicateNumber {
        public final int first;
        public final int second;
        public final String identity;
        public final Vector items;
        public DuplicateNumber(int first, int second, String identity, Vector items) {
            this.first = first;
            this.second = second;
            this.identity = identity;
            this.items = items;
        }
    }

    public static final class WrongCtor {
        public final int index;
        public final String identity;
        public final Vector items;
        public WrongCtor(int index, String identity) {
            this.index = index;
            this.identity = identity;
            this.items = new Vector();
        }
    }

    public static void main(String[] args) {
        accept(SemanticTypes.isDashboardStatus(WithSupported.class), "含 SUPPORTED 的枚举应被接受");
        reject(SemanticTypes.isDashboardStatus(WithoutSupported.class), "缺少 SUPPORTED 的枚举必须拒绝");
        reject(SemanticTypes.isDashboardStatus(String.class), "非枚举必须拒绝");

        accept(SemanticTypes.isLevelState(LevelState.class), "ACTIVE + LOCKED/UNLOCKED 应被接受");
        reject(SemanticTypes.isLevelState(ActiveOnly.class), "只有 ACTIVE 必须拒绝");
        reject(SemanticTypes.isLevelState(WithSupported.class), "无关枚举必须拒绝");

        accept(SemanticTypes.sectionShape(Section.class) != null, "int/String/列表 + 三参构造应被接受");
        accept(SemanticTypes.sectionShape(DuplicateNumber.class) == null, "同类型字段重复必须拒绝");
        accept(SemanticTypes.sectionShape(WrongCtor.class) == null, "构造参数不匹配必须拒绝");
        accept(SemanticTypes.sectionShape(List.class) == null, "无字段的接口必须拒绝");

        System.out.println("SEMANTIC_TYPES_PASS: 结构判据只接受契约成立的形状，歧义形状全部拒绝");
    }

    private static void accept(boolean valid, String message) {
        if (!valid) { throw new AssertionError(message); }
    }

    private static void reject(boolean valid, String message) {
        if (valid) { throw new AssertionError(message); }
    }
}
