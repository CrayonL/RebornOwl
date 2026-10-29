"""验证 6.103.6 宿主时钟的实际 DEX 契约。"""
import pathlib
import sys
from host_contract_test import methods, method_exists, constructor, require
from dexdump import load, u16, u32


def main():
    dexes = load(sys.argv[1])
    classes = {d.type(u32(d.b, base)): (d, base) for d in dexes for _, base in d.class_defs()}
    returns = {'a': 'Lnqk;', 'b': 'Ljava/time/Duration;', 'c': 'Ljava/time/LocalDateTime;',
               'd': 'Lkotlinx/datetime/TimeZone;', 'e': 'Ljava/util/Calendar;',
               'f': 'Ljava/time/ZoneId;', 'g': 'Ljava/time/Instant;', 'h': 'Ljava/time/LocalDate;'}
    for name, returned in returns.items():
        method_exists(classes, 'Lbb7;', (name, returned, ['Ljava/util/TimeZone;'] if name == 'e' else []))
    constructor(classes, 'Lnqk;', ['J', 'I'])
    constructor(classes, 'Lkotlinx/datetime/TimeZone;', ['Ljava/time/ZoneId;'])
    d, base = classes['Lbb7;']
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
        require(expected[name] in calls, f'bb7.{name} actual time source')
    print('HOST_DATE_CONTRACT_PASS: 6.103.6 (2488)')


if __name__ == '__main__':
    main()
