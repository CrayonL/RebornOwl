"""验证已适配版本的真实 DEX 时钟契约；版本预期来自独立 APK 核对。"""
import pathlib
import re
import sys
from host_contract_test import methods, method_exists, constructor, require
from dexdump import load, u16, u32

HOSTS = {'6.103.6': ('Lbb7;', 'Lnqk;'), '6.104.4': ('Lcf7;', 'Lhzk;')}


def main():
    version = sys.argv[2] if len(sys.argv) == 3 else '6.103.6'
    clock, instant = HOSTS[version]
    dexes = load(sys.argv[1])
    classes = {d.type(u32(d.b, base)): (d, base) for d in dexes for _, base in d.class_defs()}
    source = (pathlib.Path(__file__).resolve().parents[2] / 'src/src/com/owl/auxiliary/Targets.java').read_text(encoding='utf-8')
    candidates = dict(re.findall(r'clocks\.put\("([^"]+)", "([^"]+)"\)', source))
    require(candidates.get(version) == clock[1:-1], version + ' clock registered')
    returns = {'a': instant, 'b': 'Ljava/time/Duration;', 'c': 'Ljava/time/LocalDateTime;',
               'd': 'Lkotlinx/datetime/TimeZone;', 'e': 'Ljava/util/Calendar;',
               'f': 'Ljava/time/ZoneId;', 'g': 'Ljava/time/Instant;', 'h': 'Ljava/time/LocalDate;'}
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
    print('HOST_DATE_CONTRACT_PASS:', version)


if __name__ == '__main__':
    main()
