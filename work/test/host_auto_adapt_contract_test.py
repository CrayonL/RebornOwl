"""生产 DEX 定位器 + 独立真实字节码契约；不代替 ART 或业务验收。"""
import argparse
import subprocess
from host_contract_test import methods, require, factory_contract
from host_music_contract_test import ACCESS, EXPECTED, field_ref
from dexdump import load, u16, u32, uleb

SECTION = 'Lcom/duolingo/data/path/common/CoursePathSection;'
VECTOR = 'Lcom/duolingo/core/pcollections/migration/PVector;'
CLOCKS = {'6.103.6': 'Lbb7;', '6.104.4': 'Lcf7;', '6.104.5-china': 'La07;', '6.106.5-china': 'Ljh7;'}
MUSIC_NAMES = {'6.103.6': ['nt8', 'fk4', 'ok4', 'pk4', 'zmq'],
               '6.104.4': ['ix8', 'pj4', 'yj4', 'zj4', 'dwq'],
               '6.104.5-china': ['nf8', 's34', 'b44', 'c44', 'pop'],
               '6.106.5-china': ['kz8', 'be4', 'ke4', 'le4', 'paq']}


def instance_fields(item):
    dex, base = item
    offset = u32(dex.b, base + 24)
    if not offset:
        return []
    static, offset = uleb(dex.b, offset)
    count, offset = uleb(dex.b, offset)
    for _ in range(2 + static * 2):
        _, offset = uleb(dex.b, offset)
    index = 0
    result = []
    for _ in range(count):
        delta, offset = uleb(dex.b, offset)
        _, offset = uleb(dex.b, offset)
        index += delta
        result.append(dex.field(index))
    return result


def unique_field(classes, owner, kind):
    found = [name for field_type, name in instance_fields(classes[owner]) if field_type == kind]
    if len(found) != 1:
        raise AssertionError(f'{owner}: {kind} 字段不唯一: {found}')
    return found[0]


def calls(dex, code):
    return [(dex.type(u16(dex.b, dex.method_o + index * 8)), dex.method(index)[0])
            for _, index in dex.code_invokes(code)]


def concrete(entries, returned, parameters):
    return [(sig, code) for sig, flags, code in entries
            if not flags & (8 | 0x400) and sig[1:] == (returned, parameters)]


def verify_clock(classes, entries, candidates, version):
    required = {'Ljava/time/LocalDateTime;': [], 'Lkotlinx/datetime/TimeZone;': [],
                'Ljava/util/Calendar;': ['Ljava/util/TimeZone;'], 'Ljava/time/ZoneId;': [],
                'Ljava/time/Instant;': [], 'Ljava/time/LocalDate;': []}
    resolved = []
    for owner in candidates['CLOCK']:
        declared = entries[owner]
        if not all(len(concrete(declared, ret, ps)) == 1 for ret, ps in required.items()):
            continue
        instant = [ret for (_, ret, ps), flags, _ in declared if not ps and not flags & (8 | 0x400)
                   and any(sig == ('<init>', 'V', ['J', 'I']) for sig, _, _ in entries.get(ret, []))]
        if len(instant) == 1:
            resolved.append(owner)
    require(resolved == [CLOCKS[version]], version + ' 时钟按类型唯一定位，排除接口与测试时钟')
    owner = resolved[0]
    dex, _ = classes[owner]
    for returned, source in [('Ljava/time/Instant;', ('Ljava/time/Instant;', 'now')),
                             ('Ljava/time/LocalDate;', ('Ljava/time/LocalDate;', 'now')),
                             ('Ljava/time/Duration;', ('Landroid/os/SystemClock;', 'elapsedRealtimeNanos'))]:
        found = concrete(entries[owner], returned, [])
        require(len(found) == 1 and source in calls(dex, found[0][1]), returned + ' 实际时间来源与单调计时边界')


def verify_music(classes, entries, candidates, version):
    originals = ['nt8', 'fk4', 'ok4', 'pk4', 'zmq']
    renamed = dict(zip(('L' + name + ';' for name in originals),
                       ('L' + name + ';' for name in MUSIC_NAMES[version])))
    expected = {renamed.get(owner, owner): signature for owner, signature in EXPECTED.items()}
    require(set(candidates['MUSIC']) == set(expected), version + ' 生产扫描完整命中九种音乐模型，无额外目标')
    for owner, signatures in expected.items():
        field = unique_field(classes, owner, ACCESS)
        ctors = [(ps, code) for (name, _, ps), _, code in entries[owner] if name == '<init>' and ACCESS in ps]
        require({(len(ps), ps.index(ACCESS)) for ps, _ in ctors} == signatures
                and all(ps.count(ACCESS) == 1 for ps, _ in ctors), owner + ' 权限参数唯一且位置符合独立样本')
        dex, _ = classes[owner]
        stores = {field_ref(dex, u16(dex.b, pos + 2)) for _, code in ctors
                  for pos, op in dex._walk(code) if op == 0x5b}
        require((owner, ACCESS, field) in stores, owner + ' 构造器写入唯一权限字段')


def verify_section(classes, entries):
    fields = {kind: unique_field(classes, SECTION, kind) for kind in ('I', 'Ljava/lang/String;', VECTOR)}
    ctors = [(ps, code) for (name, _, ps), _, code in entries[SECTION]
             if name == '<init>' and len(ps) == 3 and set(ps) == set(fields)]
    require(len(ctors) == 1, '阶段构造器按类型唯一匹配')
    parameters, code = ctors[0]
    dex, _ = classes[SECTION]
    first = u16(dex.b, code) - u16(dex.b, code + 2)
    stores = {(dex.b[pos + 1] & 15, field_ref(dex, u16(dex.b, pos + 2)))
              for pos, op in dex._walk(code) if op in (0x59, 0x5b)}
    require({(first + index + 1, (SECTION, kind, fields[kind]))
             for index, kind in enumerate(parameters)} <= stores, '换序后构造参数仍写入对应类型字段')


def verify_unit(classes, entries, candidates):
    require(len(candidates['UNIT']) == 1, '路径单元特征唯一')
    unit = candidates['UNIT'][0]
    unique_field(classes, unit, VECTOR)
    copies = [(ps, code) for (_, ret, ps), flags, code in entries[unit]
              if flags & 8 and ret == unit and len(ps) == 4 and ps[0] == unit
              and ps[2:] == [VECTOR, 'I'] and ps[1].startswith('L')]
    require(len(copies) == 1, '改名后的单元副本方法签名唯一')
    ps, code = copies[0]
    unique_field(classes, unit, ps[1])
    dex, _ = classes[unit]
    ops = list(dex._walk(code))
    require({dex.b[pos + 3] for pos, op in ops if op == 0xdd} == {1, 2}
            and not any(0x59 <= op <= 0x5f for _, op in ops), '副本掩码保留编号，不写回原对象')


def verify_path(classes, entries, candidates, version):
    verify_section(classes, entries)
    verify_unit(classes, entries, candidates)
    require(len(candidates['LEVEL']) == len(candidates['POPUP']) == 1, '关卡和弹层特征唯一')
    if version == '6.106.5-china':
        require(candidates['TREATMENT'] == [], '新版已移除旧过滤配置，必须走直接关卡参数链路')
        verify_direct_popup(classes, entries, candidates)
        return
    require(len(candidates['TREATMENT']) == 1, '旧版过滤配置特征唯一')
    level, treatment, popup = (candidates[label][0] for label in ('LEVEL', 'TREATMENT', 'POPUP'))
    require(any(sig == ('<init>', 'V', ['Ljava/util/ArrayList;'] * 2)
                for sig, _, _ in entries[treatment]), '路径过滤配置构造契约')
    filters = [(owner, name) for owner in candidates['FILTER'] for (name, ret, ps), flags, _ in entries[owner]
               if flags & 8 and ret == SECTION and ps == [SECTION, 'Lcom/duolingo/data/course/Subject;', treatment]]
    require(len(filters) == 1, '阶段过滤方法动态定位唯一')
    anchors = concrete(entries[SECTION], level, [])
    require(len(anchors) == 1, '当前关卡按返回类型唯一定位')
    dex, _ = classes[popup]
    code = next(code for (name, _, ps), _, code in entries[popup]
                if name == 'invokeSuspend' and ps == ['Ljava/lang/Object;'])
    invoked = calls(dex, code)
    require(invoked.count(filters[0]) == 1 and (SECTION, anchors[0][0][0]) in invoked,
            '弹层调用唯一过滤入口，当前位置仍读取原路径')
    reads = {field_ref(dex, u16(dex.b, pos + 2)) for pos, op in dex._walk(code) if op == 0x62}
    require(not any('CoroutineSingletons' in owner for owner, _, _ in reads), '弹层未跨协程挂起，副本作用域有效')
    converters = [owner for owner in candidates['CONVERTER'] for (_, ret, ps), flags, _ in entries[owner]
                  if flags & 8 and ret != 'V' and len(ps) == 23 and ps[0] == owner and ps[2:4] == [level, level]]
    require(len(converters) == 1, '新版节点转换器按特征和完整参数契约唯一定位')


def verify_direct_popup(classes, entries, candidates):
    require(candidates['LEVEL'] == ['Leeh;'] and candidates['POPUP'] == ['Lser;'], '新版弹层及关卡独立样本')
    nodes = [(sig, flags, code) for sig, flags, code in entries['Lkan;'] if sig[0] == 'b']
    require(len(nodes) == 1 and nodes[0][0][1] == 'Llir;' and len(nodes[0][0][2]) == 17
            and nodes[0][0][2][0] == 'Leeh;' and not nodes[0][1] & 8, '新版节点改为实例方法，关卡参数为零')
    signature, _, code = next(item for item in entries['Lser;'] if item[0][0] == 'b')
    require(signature[2][0] == signature[2][8] == 'Leeh;' and len(signature[2]) == 25,
            '弹层同时接收点击关卡和真实当前关卡，不能批量改写')
    dex, _ = classes['Lser;']
    reads = {field_ref(dex, u16(dex.b, pos + 2)) for pos, op in dex._walk(code) if 0x52 <= op <= 0x58}
    require(('Leeh;', 'Lcom/duolingo/data/path/common/PathLevelState;', 'b') in reads,
            '新版弹层实际依据输入关卡状态生成操作')


def verify_factory(classes, entries, candidates, version):
    model = 'Lkxu;' if version == '6.106.5-china' else 'Lcom/duolingo/plus/dashboard/PlusViewModel;'
    discriminator = 'B' if version == '6.106.5-china' else 'I'
    require(candidates['MODEL'] == [model], '会员视图按状态返回类型定位唯一')
    constructor = ('<init>', 'V', [model, discriminator])
    found = [owner for owner in candidates['FACTORY'] if any(sig == constructor for sig, _, _ in entries[owner])
             and len(concrete(entries[owner], 'Ljava/lang/Object;', ['Ljava/lang/Object;'])) == 1]
    require(len(found) == 1, '会员点击工厂唯一定位')
    factory_contract(classes, {'C_DASHBOARD_CLICK_FACTORY': found[0][1:-1], 'MODEL': model,
                               'DISCRIMINATOR': discriminator})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory')
    parser.add_argument('version', choices=CLOCKS)
    parser.add_argument('--java', required=True)
    parser.add_argument('--classpath', required=True)
    args = parser.parse_args()
    output = subprocess.run([args.java, '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', args.classpath,
                             'com.owl.auxiliary.AutoAdaptDexTest', args.directory],
                            check=True, timeout=60, capture_output=True, text=True, encoding='utf-8').stdout
    candidates = {parts[1]: [value for value in parts[2:] if value] for line in output.splitlines()
                  if (parts := line.split('\t'))[0] == 'RESULT'}
    classes = {dex.type(u32(dex.b, base)): (dex, base)
               for dex in load(args.directory) for _, base in dex.class_defs()}
    entries = {owner: list(methods(*item)) for owner, item in classes.items()}
    verify_clock(classes, entries, candidates, args.version)
    verify_music(classes, entries, candidates, args.version)
    verify_path(classes, entries, candidates, args.version)
    verify_factory(classes, entries, candidates, args.version)
    print('HOST_AUTO_ADAPT_PASS:', args.version, '(真实 DEX；未运行 ART 或实际业务)')


if __name__ == '__main__':
    main()
