"""6.104.5-china 的实际调用与字段契约；结构定位另由 ART 全功能检查覆盖。"""
import sys
from host_contract_test import methods, require, constructor, factory_contract, footer_button_contract
from host_music_contract_test import ACCESS, EXPECTED, field_ref
from dexdump import load, u16, u32


def music_contract(classes):
    renamed = dict(zip(('Lnt8;', 'Lfk4;', 'Lok4;', 'Lpk4;', 'Lzmq;'),
                       ('Lnf8;', 'Ls34;', 'Lb44;', 'Lc44;', 'Lpop;')))
    for old, expected in EXPECTED.items():
        owner = renamed.get(old, old)
        dex, base = classes[owner]
        constructors = [(ps, code) for (name, _, ps), _, code in methods(dex, base)
                        if name == '<init>' and ACCESS in ps]
        require({(len(ps), ps.index(ACCESS)) for ps, _ in constructors} == expected,
                owner + ' 音乐权限参数位置未改变')
        require(all(ps.count(ACCESS) == 1 for ps, _ in constructors), owner + ' 权限参数唯一')
        stores = {field_ref(dex, u16(dex.b, pos + 2)) for _, code in constructors
                  for pos, op in dex._walk(code) if op == 0x5b}
        require(any(ref[:2] == (owner, ACCESS) for ref in stores), owner + ' 实际写入权限字段')


def popup_contract(classes):
    section = 'Lcom/duolingo/data/path/common/CoursePathSection;'
    vector = 'Lcom/duolingo/core/pcollections/migration/PVector;'
    constructor(classes, section, ['I', vector, 'Ljava/lang/String;'])
    dex, base = classes[section]
    code = next(c for (n, _, _), _, c in methods(dex, base) if n == '<init>')
    first = u16(dex.b, code) - u16(dex.b, code + 2)
    stores = {(dex.b[pos + 1] & 15, field_ref(dex, u16(dex.b, pos + 2)))
              for pos, op in dex._walk(code) if op in (0x59, 0x5b)}
    require({(first + 1, (section, 'I', 'b')), (first + 2, (section, vector, 'c')),
             (first + 3, (section, 'Ljava/lang/String;', 'a'))} <= stores,
            '新构造顺序对应原来的阶段标识、序号和单元字段')
    dex, base = classes['Ljvq;']
    code = next(c for (n, _, _), _, c in methods(dex, base) if n == 'invokeSuspend')
    calls = [(dex.type(u16(dex.b, dex.method_o + i * 8)), dex.method(i)[0])
             for _, i in dex.code_invokes(code)]
    require(calls.count(('Lbxc;', 'J')) == 1 and (section, 'b') in calls,
            '弹层只过滤一次路径，当前位置仍读取原路径')
    reads = {field_ref(dex, u16(dex.b, pos + 2)) for pos, op in dex._walk(code) if op == 0x62}
    require(not any('CoroutineSingletons' in owner for owner, _, _ in reads),
            '弹层无协程挂起，副本作用域不会跨线程遗留')
    dex, base = classes['Le7h;']
    code = next(c for (n, ret, ps), _, c in methods(dex, base)
                if n == 'a' and ret == 'Le7h;' and len(ps) == 4)
    require({dex.b[pos + 3] for pos, op in dex._walk(code) if op == 0xdd} == {1, 2},
            '单元副本掩码仍保留原编号、替换关卡列表')
    require(not any(0x59 <= op <= 0x5f for _, op in dex._walk(code)), '单元副本不写原对象')


def main():
    classes = {dex.type(u32(dex.b, base)): (dex, base)
               for dex in load(sys.argv[1]) for _, base in dex.class_defs()}
    factory_contract(classes, {'C_DASHBOARD_CLICK_FACTORY': 'eot'})
    footer_button_contract(classes)
    music_contract(classes)
    popup_contract(classes)
    print('HOST_1045_CONTRACT_PASS: 6.104.5-china (2494); static DEX evidence only')


if __name__ == '__main__':
    main()
