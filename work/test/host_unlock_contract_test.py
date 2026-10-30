"""核对真实 6.103.6 DEX 的关卡构造、状态读写和学习入口；不运行宿主。"""
from pathlib import Path
import re
import sys

from host_contract_test import methods, constructor, require
from dexdump import load, u16, u32

STATE = 'Lcom/duolingo/data/path/common/PathLevelState;'
LEVEL = 'Luzh;'
EXPECTED_PARAMETERS = [
    'Ljava/lang/String;', STATE, 'I', 'I', 'Lgst;',
    'Lcom/duolingo/data/home/path/PathLevelMetadata;',
    'Lcom/duolingo/data/path/common/PathLevelSessionMetadata;',
    'Lcom/duolingo/data/path/common/DailyRefreshInfo;', 'Z', 'Ljava/lang/String;', 'Z',
    'Lcom/duolingo/data/path/common/PathLevelType;',
    'Lcom/duolingo/data/path/common/PathLevelSubtype;', 'Ljava/lang/Integer;',
    'Ljava/lang/Object;', 'Lcom/duolingo/data/path/common/PathLevelScoreInfo;', LEVEL,
]


def field_ref(dex, index):
    return dex.type(u16(dex.b, dex.field_o + index * 8)), *dex.field(index)


def signature_contract(classes, source):
    marker = re.search(r'S_PATH_LEVEL\s*=\s*"([^"]+)"', source).group(1)
    matches = [owner for owner, (dex, base) in classes.items()
               if any(name == 'toString' and marker in dex.code_strings(code)
                      for (name, _, _), _, code in methods(dex, base))]
    require(matches == [LEVEL], '关卡特征串唯一命中实际 GenericPathLevel')
    constructor(classes, LEVEL, EXPECTED_PARAMETERS)
    dex, base = classes[LEVEL]
    ctors = [code for (name, _, _), _, code in methods(dex, base) if name == '<init>']
    require(len(ctors) == 1, '关卡只有一个构造入口，无遗漏的序列化构造器')
    code = ctors[0]
    state_register = u16(dex.b, code) - u16(dex.b, code + 2) + 2
    stores = [(dex.b[pos + 1] & 15, field_ref(dex, u16(dex.b, pos + 2)))
              for pos, op in dex._walk(code) if op == 0x5b]
    require((state_register, (LEVEL, STATE, 'b')) in stores,
            '构造参数 1 确实写入关卡状态字段 b')


def read_write_contract(dexes):
    writes, callers, readers = set(), set(), set()
    for dex in dexes:
        state_fields = {i for i in range(dex.field_n) if field_ref(dex, i) == (LEVEL, STATE, 'b')}
        constructors = {i for i in range(dex.method_n)
                        if dex.type(u16(dex.b, dex.method_o + i * 8)) == LEVEL
                        and dex.method(i)[0] == '<init>'}
        operations = {0x54: (state_fields, readers), 0x5b: (state_fields, writes),
                      0x70: (constructors, callers), 0x76: (constructors, callers)}
        for _, base in dex.class_defs():
            collect_references(dex, base, operations)
    require(writes == {(LEVEL, '<init>')}, '状态只有构造器写入，不存在稍后覆盖状态的直接写路径')
    require(callers == {
        ('Lcom/duolingo/data/path/common/v;', 'deserialize'), ('Li8o;', 'g'),
        ('Li8o;', 'h'), (LEVEL, 'a'), ('Lvnj;', 'H0'),
    }, '原始关卡五个创建调用点保持不变，包含反序列化和复制')
    require({('Lcao;', 'g'), ('Lrft;', 'a'), ('Lcom/duolingo/feature/home/b;', 'a')} <= readers,
            '可用性判断、学习提示和路径显示共用同一状态字段')


def collect_references(dex, base, operations):
    owner = dex.type(u32(dex.b, base))
    for (name, _, _), _, code in methods(dex, base):
        if not code:
            continue
        for pos, op in dex._walk(code):
            if op not in operations:
                continue
            targets, results = operations[op]
            index = u16(dex.b, pos + 2)
            if index in targets: results.add((owner, name))


def gate_contract(classes):
    dex, base = classes['Lcao;']
    code = next(code for (name, _, _), _, code in methods(dex, base) if name == 'h')
    instructions = list(dex._walk(code))
    require([op for _, op in instructions[:7]] == [0x6e, 0x54, 0x62, 0x32, 0x62, 0x33, 0x28],
            '学习入口先读取关卡状态并判断 LOCKED / UNLOCKED')
    states = [field_ref(dex, u16(dex.b, instructions[index][0] + 2)) for index in (2, 4)]
    require(states == [(STATE, STATE, 'LOCKED'), (STATE, STATE, 'UNLOCKED')],
            'UNLOCKED 仍属于入口排除状态，不能简单把 LOCKED 改成 UNLOCKED')
    locked_jump = instructions[3][0]
    unlocked_jump = instructions[6][0]
    locked_target = locked_jump + 2 * int.from_bytes(dex.b[locked_jump + 2:locked_jump + 4], 'little', signed=True)
    unlocked_target = unlocked_jump + 2 * int.from_bytes(dex.b[unlocked_jump + 1:unlocked_jump + 2], 'little', signed=True)
    require(locked_target == unlocked_target and dex.b[locked_target] == 0x12
            and dex.b[locked_target + 1] >> 4 == 0 and dex.b[locked_target + 2] == 0x0f,
            'LOCKED 和 UNLOCKED 两条真实分支都返回 false')


def copy_and_anchor_contract(classes):
    dex, base = classes[LEVEL]
    copies = [code for (name, ret, ps), access, code in methods(dex, base)
              if access & 8 and ret == LEVEL and ps == [LEVEL, STATE, 'I', 'Lgst;', 'I']]
    require(len(copies) == 1, '关卡副本方法签名唯一')
    ops = list(dex._walk(copies[0]))
    masks = {dex.b[pos + 3] for pos, op in ops if op == 0xdd}
    require(masks == {2, 4, 16}, 'copy 默认参数位：状态 2、完成次数 4、课程数据 16')
    require(not any(0x59 <= op <= 0x5f for _, op in ops), '副本方法不写原对象字段')
    dex, base = classes['Lw9o;']
    code = next(code for (name, _, _), _, code in methods(dex, base) if name == 'c')
    reads = {field_ref(dex, u16(dex.b, pos + 2)) for pos, op in dex._walk(code) if op == 0x62}
    calls = {dex.method(index)[0] for _, index in dex.code_invokes(code)}
    require((STATE, STATE, 'ACTIVE') in reads and {'listIterator', 'previous'} <= calls,
            '真实定位函数从后向前找 ACTIVE，必须保留路径原始状态')


def new_node_contract(classes, source):
    owner = re.search(r'C_NEW_PATH_CONVERTER\s*=\s*"([^"]+)"', source).group(1)
    owner = 'L' + owner.replace('.', '/') + ';'
    dex, base = classes[owner]
    candidates = [(sig, code) for sig, access, code in methods(dex, base)
                  if access & 8 and len(sig[2]) == 23 and sig[2][:4] == [owner, 'I', LEVEL, LEVEL]]
    require(len(candidates) == 1, '新版路径节点转换入口签名唯一')
    (name, ret, ps), code = candidates[0]
    require(name == 'a' and ret == 'Lhur;' and ps[6:8] == [
        'Lcom/duolingo/data/path/common/CoursePathSection;', 'Lwzh;'],
        '参数 2 是当前显示节点，参数 3/6/7 的前节点和路径数据保持原值')
    calls = {(dex.type(u16(dex.b, dex.method_o + i * 8)), dex.method(i)[0])
             for _, i in dex.code_invokes(code)}
    require({('Lcao;', 'h'), ('Lmao;', 'l'), ('Lw9o;', 'c'), ('Lhur;', '<init>')} <= calls,
            '新版转换共用副本生成显示和点击操作，定位继续读取原始路径')
    node_dex, node_base = classes[ret]
    node_text = [text for (n, _, _), _, c in methods(node_dex, node_base) if n == 'toString'
                 for text in node_dex.code_strings(c)]
    require({'Node(key=', ', tap=', ', state=', ', isActiveNode='} <= set(node_text),
            '返回新版路径 Node，同时包含显示状态和点击操作')


def click_session_contract(classes, source):
    marker = re.search(r'S_PATH_SESSION_STATE\s*=\s*"([^"]+)"', source).group(1)
    matches = [owner for owner, (dex, base) in classes.items()
               if any(name == 'toString' and marker in dex.code_strings(code)
                      for (name, _, _), _, code in methods(dex, base))]
    require(matches == ['Lvst;'], '点击会话特征串唯一命中 PathLevelSessionState')
    dex, base = classes['Lvst;']
    constructors = [ps for (name, _, ps), _, _ in methods(dex, base) if name == '<init>']
    require(sorted(len(ps) for ps in constructors) == [9, 11]
            and all(ps[0] == LEVEL for ps in constructors), '两条会话构造入口的首参均为关卡')
    dex, base = classes['Lbnj;']
    code = next(c for (name, _, ps), _, c in methods(dex, base)
                if name == 'l' and ps == ['Ljava/lang/String;', 'Ljava/lang/String;', 'Ltl8;'])
    calls = {(dex.type(u16(dex.b, dex.method_o + i * 8)), dex.method(i)[0])
             for _, i in dex.code_invokes(code)}
    require({('Lvst;', '<init>'), ('Llqx;', '<init>'),
             ('Lcom/duolingo/data/path/common/CoursePathSection;', 'b')} <= calls,
            '实际点击解析器单独创建会话，当前位置仍从原路径读取')


def popup_contract(classes, source):
    name = re.search(r'C_NEW_PATH_POPUP\s*=\s*"([^"]+)"', source).group(1)
    dex, base = classes['L' + name + ';']
    code = next(c for (n, _, ps), _, c in methods(dex, base)
                if n == 'invokeSuspend' and ps == ['Ljava/lang/Object;'])
    calls = [(dex.type(u16(dex.b, dex.method_o + i * 8)), dex.method(i)[0])
             for _, i in dex.code_invokes(code)]
    require(calls.count(('Lcao;', 'b')) == 1 and
            ('Lcom/duolingo/data/path/common/CoursePathSection;', 'b') in calls,
            '新版弹层只过滤一次路径，当前关卡通过原有 b 方法获取')
    reads = {field_ref(dex, u16(dex.b, pos + 2)) for pos, op in dex._walk(code) if op == 0x62}
    require(not any('CoroutineSingletons' in owner for owner, _, _ in reads),
            '弹层计算没有协程挂起分支，同步作用域不会跨线程遗留')
    vector = 'Lcom/duolingo/core/pcollections/migration/PVector;'
    constructor(classes, 'Lcom/duolingo/data/path/common/CoursePathSection;',
                ['I', 'Ljava/lang/String;', vector])
    dex, base = classes['Lwzh;']
    copies = [c for (n, ret, ps), _, c in methods(dex, base) if n == 'a'
              and ret == 'Lwzh;' and ps == ['Lwzh;',
                  'Lcom/duolingo/data/path/common/PathUnitIndex;', vector, 'I']]
    require(len(copies) == 1, '单元副本方法签名唯一')
    masks = {dex.b[pos + 3] for pos, op in dex._walk(copies[0]) if op == 0xdd}
    require(masks == {1, 2}, '单元 copy 掩码 1 保留原单元编号，2 保留原关卡列表')
    require(not any(0x59 <= op <= 0x5f for _, op in dex._walk(copies[0])),
            '单元副本方法不改写原对象')


def main():
    dexes = load(sys.argv[1])
    classes = {dex.type(u32(dex.b, base)): (dex, base)
               for dex in dexes for _, base in dex.class_defs()}
    root = Path(__file__).resolve().parents[2]
    source = (root / 'src/src/com/owl/auxiliary/Targets.java').read_text(encoding='utf-8')
    signature_contract(classes, source)
    read_write_contract(dexes)
    gate_contract(classes)
    copy_and_anchor_contract(classes)
    new_node_contract(classes, source)
    click_session_contract(classes, source)
    popup_contract(classes, source)
    print('HOST_UNLOCK_CONTRACT_PASS: 6.103.6 (2488); static DEX evidence only')


if __name__ == '__main__':
    main()
