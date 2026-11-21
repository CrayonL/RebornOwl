"""验证真实 DEX 的时钟契约 —— **按结构定位，不按类名**。

宿主每次发版都可能重命名时钟类（6.103.6 的 `Lbb7;`、6.106.5 的 `Ljh7;` 就是同一个
契约的不同混淆名）。测试如果写死类名，换个版本就会「假失败」，这正是要避免的：
这里改为在 DEX 里找**同时具备 a..h 八个返回类型**的那个类，要求唯一命中，再验证
构造器与实际时间来源。

用法：host_date_contract_test.py <dex 目录> [版本标签]
"""
import sys
from host_contract_test import methods, method_exists, constructor, require
from dexdump import load, u16, u32

# b..h 的返回类型是 JDK/kotlinx 类型，跨版本稳定；a() 返回的「时间快照」类由结构派生。
EXPECTED = {
    'b': 'Ljava/time/Duration;',
    'c': 'Ljava/time/LocalDateTime;',
    'd': 'Lkotlinx/datetime/TimeZone;',
    'e': 'Ljava/util/Calendar;',
    'f': 'Ljava/time/ZoneId;',
    'g': 'Ljava/time/Instant;',
    'h': 'Ljava/time/LocalDate;',
}


def signatures(d, base):
    """name -> [返回类型]，只取实例方法。"""
    out = {}
    for (name, returned, params), access, _ in methods(d, base):
        if access & (8 | 0x400):
            continue
        out.setdefault(name, []).append(returned)
    return out


def constructors(classes, desc):
    """类的构造器参数签名列表。"""
    d, base = classes[desc]
    return [params for (name, _ret, params), _access, _code in methods(d, base)
            if name == '<init>']


def discover(classes):
    """找到唯一满足 a..h 契约的时钟类；a() 的返回类型必须是一个有 (J,I) 构造器的类。"""
    candidates = []
    for desc, (d, base) in classes.items():
        sig = signatures(d, base)
        if any(name not in sig for name in list(EXPECTED) + ['a']):
            continue
        if not all(EXPECTED[name] in sig[name] for name in EXPECTED):
            continue
        for snapshot in sig['a']:
            if snapshot not in classes:
                continue
            if ['J', 'I'] not in constructors(classes, snapshot):
                continue
            if EXPECTED['b'] in sig['b'] and snapshot:
                candidates.append((desc, snapshot))
    unique = sorted(set(candidates))
    if len(unique) != 1:
        raise AssertionError('时钟结构契约命中 %d 个类：%s' % (len(unique), unique))
    return unique[0]


def main():
    version = sys.argv[2] if len(sys.argv) == 3 else 'unknown'
    dexes = load(sys.argv[1])
    classes = {d.type(u32(d.b, base)): (d, base) for d in dexes for _, base in d.class_defs()}
    clock, instant = discover(classes)
    print('时钟结构定位：', clock, '（时间快照 ', instant, '）')

    returns = dict(EXPECTED)
    returns['a'] = instant
    for name, returned in returns.items():
        signature = (name, returned, ['Ljava/util/TimeZone;'] if name == 'e' else [])
        method_exists(classes, clock, signature)
        d, base = classes[clock]
        require(any(sig == signature and not access & (8 | 0x400) for sig, access, _ in methods(d, base)),
                clock + '.' + name + ' concrete instance method')
    constructor(classes, instant, ['J', 'I'])
    constructor(classes, 'Lkotlinx/datetime/TimeZone;', ['Ljava/time/ZoneId;'])
    d, base = classes[clock]
    expected = {'g': ('Ljava/time/Instant;', 'now'), 'h': ('Ljava/time/LocalDate;', 'now'),
                'b': ('Landroid/os/SystemClock;', 'elapsedRealtimeNanos')}
    for (name, _, _), _, code in methods(d, base):
        if name not in expected:
            continue
        calls = set()
        for pos, op in d._walk(code):
            if op not in range(0x6e, 0x73):
                continue
            index = u16(d.b, pos + 2)
            calls.add((d.type(u16(d.b, d.method_o + index * 8)), d.method(index)[0]))
        require(expected[name] in calls, f'{clock}.{name} actual time source')
    print('HOST_DATE_CONTRACT_PASS:', version, clock)


if __name__ == '__main__':
    main()
