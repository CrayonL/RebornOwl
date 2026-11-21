"""核对手机 6.103.6 APK 的实际 DEX 契约；不执行宿主或网络请求。"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "work" / "tools"))
from dexdump import load, u16, u32, uleb


def methods(dex, base):
    offset = u32(dex.b, base + 24)
    if not offset:
        return
    counts = []
    for _ in range(4):
        count, offset = uleb(dex.b, offset)
        counts.append(count)
    for _ in range(counts[0] + counts[1]):
        _, offset = uleb(dex.b, offset)
        _, offset = uleb(dex.b, offset)
    for count in counts[2:]:
        index = 0
        for _ in range(count):
            delta, offset = uleb(dex.b, offset)
            access, offset = uleb(dex.b, offset)
            code, offset = uleb(dex.b, offset)
            index += delta
            yield dex.method(index), access, code


def require(condition, label):
    if not condition:
        raise AssertionError(label)
    print("PASS:", label)


def constructor(classes, owner, expected):
    dex, base = classes[owner]
    found = [types for (name, _, types), _, _ in methods(dex, base) if name == "<init>"]
    require(expected in found, owner + " constructor")


def grade_contract(classes):
    constructor(classes, "Lget;", ["F", "Lcom/duolingo/core/music/mistakerecycling/MusicMistakeMeasureInfo;"])
    dex, base = classes["Lcom/duolingo/core/challenges/grading/GradingState$Completed$WithGradingRibbon$Graded;"]
    signatures = [ps for (name, _, ps), _, _ in methods(dex, base) if name == "<init>"]
    require(len(signatures) == 1 and len(signatures[0]) == 22 and signatures[0][1] == "Z",
            "Graded.correct is boolean argument 1")
    strings = [text for (name, _, _), _, code in methods(dex, base)
               if name == "toString" for text in dex.code_strings(code)]
    require(", correct=" in strings, "Graded correctness field label")
    constructor(classes, "Lg240;", ["D", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/util/List;",
                                    "Z", "Ljava/lang/String;", "I", "Z", "Z", "I"])
    constructor(classes, "Lep00;", ["Z", "Ljava/util/Set;", "Z", "Ljava/lang/Integer;", "Z", "Z",
                                   "Ljava/util/List;", "Z", "Lcom/duolingo/core/challenges/ChallengeIndicatorType;",
                                   "Z", "Ljava/lang/String;"])


def user_contract(classes):
    dex, base = classes["La690;"]
    names = [text for (name, _, _), _, code in methods(dex, base)
             if name == "<clinit>" for text in dex.code_strings(code)]
    offset = 11  # 该旧版样本独立核对的字段间隔，不从生产代码读取预期。
    require(names.index("hasMax") - names.index("subscriberLevel") == offset, "serialized hasMax offset")
    dex, base = classes["Lcom/duolingo/data/user/User;"]
    signatures = [ps for (name, _, ps), _, _ in methods(dex, base) if name == "<init>" and len(ps) == 109]
    require(len(signatures) == 1 and signatures[0][83].endswith("/SubscriberLevel;")
            and signatures[0][83 + offset] == "Z", "User subscription parameter types")


def method_exists(classes, owner, signature):
    dex, base = classes[owner]
    require(any(value == signature for value, _, _ in methods(dex, base)), owner + " " + signature[0])


def footer_button_contract(classes):
    owner = "Lcom/duolingo/core/design/juicy/ui/JuicyButton;"
    constructor(classes, owner, ["Landroid/content/Context;"])
    method_exists(classes, owner, ("setPressed", "V", ["Z"]))
    while owner in classes:
        dex, base = classes[owner]
        owner = dex.type(u32(dex.b, base + 8))
    require(owner in {"Landroid/widget/Button;", "Landroid/widget/TextView;"},
            "宿主按钮继承 TextView，保留原生文字与按压绘制")


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: python host_contract_test.py <6.103.6-dex-directory>")
    dexes = load(sys.argv[1])
    require(len(dexes) == 10, "expected ten host DEX files")
    classes = {dex.type(u32(dex.b, base)): (dex, base) for dex in dexes for _, base in dex.class_defs()}
    grade_contract(classes)
    user_contract(classes)
    method_exists(classes, "Lcom/duolingo/settings/SettingsActivity;", ("onCreate", "V", ["Landroid/os/Bundle;"]))
    footer_button_contract(classes)
    print("HOST_CONTRACT_PASS: 6.103.6 (2488); offline only")


if __name__ == "__main__":
    main()
