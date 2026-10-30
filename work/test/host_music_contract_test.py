"""核对 6.103.6 真实音乐权限构造入口、写入字段与实际限制判断。"""
import re
import sys
from host_contract_test import ROOT, methods, require
from dexdump import load, u16, u32

ACCESS = 'Lcom/duolingo/core/music/licensed/LicensedMusicAccess;'
# (参数数量, 权限参数下标)：来自当前 APK，不以模块的 Hook 数量代替宿主契约。
EXPECTED = {
    'Lnt8;': {(8, 6)}, 'Lfk4;': {(21, 17)}, 'Lok4;': {(9, 8)}, 'Lpk4;': {(9, 8)},
    'Lzmq;': {(50, 13)},
    'Lcom/duolingo/core/music/licensed/LicensedMusicPathData;': {(2, 1)},
    'Lcom/duolingo/data/home/music/LicensedMusicDetails;': {(3, 1), (2, 0)},
    'Lcom/duolingo/data/home/music/LicensedSessionData;': {(3, 0)},
    'Lcom/duolingo/data/home/music/SongLandingPathData$LicensedSongLandingData;': {(9, 7)},
}


def field_ref(dex, index):
    return dex.type(u16(dex.b, dex.field_o + index * 8)), *dex.field(index)


def verify_models(classes, source):
    names = re.search(r'C_MUSIC_ACCESS_MODELS\s*=\s*\{(.*?)\};', source, re.S).group(1)
    declared = {'L' + name.replace('.', '/') + ';' for name in re.findall(r'"([^"]+)"', names)}
    require(declared == set(EXPECTED) - {'Lnt8;'}, '新增目标恰好覆盖已核对的音乐模型')
    for owner, expected in EXPECTED.items():
        dex, base = classes[owner]
        constructors = [(params, code) for (name, _, params), _, code in methods(dex, base)
                        if name == '<init>' and ACCESS in params]
        actual = {(len(params), params.index(ACCESS)) for params, _ in constructors}
        require(actual == expected, owner + ' 音乐权限构造签名')
        require(all(params.count(ACCESS) == 1 for params, _ in constructors), owner + ' 权限参数唯一')
        stores = {field_ref(dex, u16(dex.b, pos + 2)) for _, code in constructors
                  for pos, op in dex._walk(code) if op == 0x5b}
        require(any(field[:2] == (owner, ACCESS) for field in stores), owner + ' 构造器写入实际权限字段')


def verify_gates(classes):
    gates = [
        ('Lzmq;', 'K0', ('Lzmq;', ACCESS, 'o')),
        ('Lpr30;', 'get', ('Lcom/duolingo/data/home/music/SongLandingPathData$LicensedSongLandingData;', ACCESS, 'k')),
        ('Lcom/duolingo/session/a0;', 'accept', ('Lcom/duolingo/core/music/licensed/LicensedMusicPathData;', ACCESS, 'b')),
    ]
    for owner, name, field in gates:
        dex, base = classes[owner]
        code = next(code for (method, _, _), _, code in methods(dex, base) if method == name)
        ops = list(dex._walk(code))
        reads = {field_ref(dex, u16(dex.b, pos + 2)) for pos, op in ops if op in (0x54, 0x62)}
        require(field in reads and (ACCESS, ACCESS, 'TASTER') in reads
                and any(op in (0x32, 0x33) for _, op in ops), owner + '.' + name + ' 实际读取并比较 TASTER')


def main():
    dexes = load(sys.argv[1])
    classes = {d.type(u32(d.b, base)): (d, base) for d in dexes for _, base in d.class_defs()}
    dex, base = classes[ACCESS]
    require(dex.type(u32(dex.b, base + 8)) == 'Ljava/lang/Enum;', '音乐权限是枚举，不是携带权限参数的数据模型')
    enum_names = {text for (name, _, _), _, code in methods(dex, base)
                  if name == '<clinit>' for text in dex.code_strings(code)}
    require({'NONE', 'TASTER', 'FULL'} <= enum_names and 'LOCKED' not in enum_names, '权限状态是 NONE/TASTER/FULL')
    require(all(ACCESS not in params for (name, _, params), _, _ in methods(dex, base) if name == '<init>'),
            '旧枚举构造 Hook 没有可替换的权限参数')
    source = (ROOT / 'src/src/com/owl/auxiliary/Targets.java').read_text(encoding='utf-8')
    marker = re.search(r'S_MUSIC\s*=\s*"([^"]+)"', source).group(1)
    matches = [owner for owner, (d, item) in classes.items()
               if any(name == 'toString' and marker in d.code_strings(code)
                      for (name, _, _), _, code in methods(d, item))]
    require(matches == ['Lnt8;'], 'Music 特征串唯一命中 nt8')
    verify_models(classes, source)
    verify_gates(classes)
    print('HOST_MUSIC_CONTRACT_PASS: 6.103.6 / 2488; 未验证未解锁账号或服务端授权')


if __name__ == '__main__':
    main()
