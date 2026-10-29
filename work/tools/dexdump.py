#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""纯 Python DEX 类结构 / 字符串引用分析器。

用法：
  python dexdump.py cls  <descriptor|simple-name> [dex-dir]   # 转储类结构
  python dexdump.py str  <子串>                [dex-dir]      # 查哪些类引用了该字符串
  python dexdump.py clsname <正则>             [dex-dir]      # 按正则列出类名
  python dexdump.py strs <正则>                               # 列出匹配的字符串常量

dex-dir 默认 ./dl/dex（多邻国 base 的 10 个 dex）。

设计要点（踩过的坑）：
 - DEX 头部偏移：string_ids 0x38/0x3C，type_ids 0x40/0x44，proto_ids 0x48/0x4C，
   field_ids 0x50/0x54，method_ids 0x58/0x5C，class_defs 0x60/0x64。
 - class_data 中字段/方法索引是**差分编码**，必须 idx += diff。
 - proto 的 parameters_off 指向 type_list（u32 size + size*u16 type_idx）。
 - code_item 的字符串引用需要遍历 instructions（本工具仅处理 const-string 的
   变体：0x1A const-string / 0x1B const-string/jumbo），已足够定位特征串。
"""

import sys
import os
import re
import struct
import glob

DEFAULT_DEX_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                               '..', 'dl', 'dex')

ACC_STATIC = 0x8
ACC_PRIVATE = 0x2
ACC_PUBLIC = 0x1
ACC_PROTECTED = 0x4
ACC_NATIVE = 0x100
ACC_ABSTRACT = 0x400


def u16(b, o):
    return struct.unpack_from('<H', b, o)[0]


def u32(b, o):
    return struct.unpack_from('<I', b, o)[0]


def uleb(b, o):
    r = 0
    s = 0
    while True:
        x = b[o]
        o += 1
        r |= (x & 0x7f) << s
        if not (x & 0x80):
            break
        s += 7
    return r, o


class Dex(object):
    def __init__(self, path):
        self.path = path
        with open(path, 'rb') as f:
            self.b = f.read()
        b = self.b
        self.string_n = u32(b, 0x38)
        self.string_o = u32(b, 0x3c)
        self.type_n = u32(b, 0x40)
        self.type_o = u32(b, 0x44)
        self.proto_n = u32(b, 0x48)
        self.proto_o = u32(b, 0x4c)
        self.field_n = u32(b, 0x50)
        self.field_o = u32(b, 0x54)
        self.method_n = u32(b, 0x58)
        self.method_o = u32(b, 0x5c)
        self.class_n = u32(b, 0x60)
        self.class_o = u32(b, 0x64)
        self._str_cache = {}

    # ---------------- 基础表 ----------------
    def string(self, si):
        v = self._str_cache.get(si)
        if v is not None:
            return v
        off = u32(self.b, self.string_o + 4 * si)
        n, o = uleb(self.b, off)
        v = self.b[o:o + n].decode('utf-8', 'replace')
        self._str_cache[si] = v
        return v

    def type(self, ti):
        try:
            return self.string(u32(self.b, self.type_o + 4 * ti))
        except Exception:
            return '?'

    def field(self, fi):
        o = self.field_o + 8 * fi
        return (self.type(u16(self.b, o + 2)),     # type
                self.string(u32(self.b, o + 4)))   # name

    def proto(self, pi):
        o = self.proto_o + 12 * pi
        ret = self.type(u32(self.b, o + 4))
        par = u32(self.b, o + 8)
        if par == 0:
            return ret, []
        # 注意：type_list 的 size 是**定长 uint32**（不是 uleb128），
        # 早期版本用 uleb 解析导致参数表整体错位 3 字节 —— 已修正。
        n = u32(self.b, par)
        base = par + 4
        return ret, [self.type(u16(self.b, base + 2 * k)) for k in range(n)]

    def method(self, mi):
        o = self.method_o + 8 * mi
        proto = u16(self.b, o + 2)
        name = self.string(u32(self.b, o + 4))
        ret, ps = self.proto(proto)
        return name, ret, ps

    def class_defs(self):
        for i in range(self.class_n):
            base = self.class_o + 32 * i
            yield i, base

    def find_class(self, want):
        """want: 描述符 Lcom/x/Y; 或简单名 Y / Y$Z。返回 (dex, index, base) 列表。"""
        out = []
        simple = want.replace('.', '/')
        for i, base in self.class_defs():
            d = self.type(u32(self.b, base))
            if d == want or d == 'L' + simple + ';' or d.endswith('/' + simple + ';'):
                out.append((self, i, base, d))
        return out

    # ---------------- 类结构 ----------------
    def dump_class(self, want, show_code_strings=True):
        hits = self.find_class(want)
        for dex, i, base, desc in hits:
            dex._dump_one(base, desc, show_code_strings)

    def _dump_one(self, base, desc, show_code_strings):
        b = self.b
        acc = u32(b, base + 4)
        sup = self.type(u32(b, base + 8))
        ifs_off = u32(b, base + 12)
        src = u32(b, base + 16)
        cdo = u32(b, base + 24)
        print('=' * 78)
        print('CLASS %s   (%s)' % (desc, os.path.basename(self.path)))
        print('  access=%s super=%s source=%s' % (hex(acc), sup,
              self.string(src) if src != 0xffffffff else '-'))
        if ifs_off:
            n, o = uleb(b, ifs_off)
            print('  interfaces: ' + ', '.join(self.type(u16(b, o + 2 * k)) for k in range(n)) if n else '  interfaces: -')
        if cdo == 0:
            print('  <no class_data>')
            return
        o = cdo
        sf, o = uleb(b, o)
        inf, o = uleb(b, o)
        dm, o = uleb(b, o)
        vm, o = uleb(b, o)
        print('  static=%d instance=%d direct=%d virtual=%d' % (sf, inf, dm, vm))

        idx = 0
        print('  -- static fields --')
        for k in range(sf):
            d, o = uleb(b, o)
            a, o = uleb(b, o)
            idx += d
            t, nm = self.field(idx)
            print('     %s %s  acc=%s' % (t, nm, hex(a)))
        idx = 0
        print('  -- instance fields --')
        for k in range(inf):
            d, o = uleb(b, o)
            a, o = uleb(b, o)
            idx += d
            t, nm = self.field(idx)
            print('     %s %s  acc=%s' % (t, nm, hex(a)))

        for label, cnt in (('direct', dm), ('virtual', vm)):
            idx = 0
            print('  -- %s methods --' % label)
            for k in range(cnt):
                d, o = uleb(b, o)
                a, o = uleb(b, o)
                co, o = uleb(b, o)
                idx += d
                nm, ret, ps = self.method(idx)
                flags = []
                if a & ACC_STATIC:
                    flags.append('static')
                if a & ACC_NATIVE:
                    flags.append('native')
                if a & ACC_ABSTRACT:
                    flags.append('abstract')
                if a & ACC_PRIVATE:
                    flags.append('private')
                if a & ACC_PUBLIC:
                    flags.append('public')
                print('     %s %s(%s) %s' % (ret, nm, ','.join(ps), ' '.join(flags)))
                if show_code_strings and co:
                    ss = self.code_strings(co)
                    if ss:
                        for s in ss:
                            print('        [str] %r' % s)

    def code_strings(self, code_off):
        out = []
        for p, op in self._walk(code_off):
            if op == 0x1A:
                out.append(self.string(u16(self.b, p + 2)))
            elif op == 0x1B:
                out.append(self.string(u32(self.b, p + 2)))
        return out

    def code_invokes(self, code_off):
        """产出 (offset, target_method_idx)。"""
        for p, op in self._walk(code_off):
            if 0x6E <= op <= 0x72 or 0x74 <= op <= 0x78:
                yield p, u16(self.b, p + 2)

    def _walk(self, code_off):
        """遍历 code_item 指令，产出 (ptr, opcode)。会正确跳过 inline payload。"""
        b = self.b
        try:
            insns_size = u32(b, code_off + 12)
        except Exception:
            return
        p = code_off + 16
        end = p + insns_size * 2
        while p < end - 1:
            op = b[p]
            yield p, op
            if op == 0x00:
                # payload 的 ident 为小端 uint16：0x0100/0x0200/0x0300
                ident = b[p] | (b[p + 1] << 8)
                if ident == 0:
                    p += 2                     # nop
                elif ident == 0x0100:          # packed-switch-payload
                    size = u16(b, p + 2)
                    p += 8 + size * 4          # ident+size+first_key 共 8B，元素各 4B
                elif ident == 0x0200:          # sparse-switch-payload
                    size = u16(b, p + 2)
                    p += 4 + size * 8          # ident+size 共 4B，元素各 (key,target)=8B
                elif ident == 0x0300:          # fill-array-data-payload
                    ew = u16(b, p + 2)
                    size = u32(b, p + 4)
                    p += 8 + ((size * ew + 1) // 2) * 2
                else:
                    p += 2
                continue
            p += LENGTHS[op] * 2 if op < len(LENGTHS) else 2


# 官方指令长度表（单位：16-bit code units）。按 Dalvik 字节码规范逐段构造。
LENGTHS = [1] * 256
for _lo, _hi, _n in [
    (0x00, 0x01, 1), (0x02, 0x02, 2), (0x03, 0x03, 3), (0x04, 0x04, 1),
    (0x05, 0x05, 2), (0x06, 0x06, 3), (0x07, 0x07, 1), (0x08, 0x08, 2),
    (0x09, 0x09, 3), (0x0a, 0x12, 1), (0x13, 0x13, 2), (0x14, 0x14, 3),
    (0x15, 0x16, 2), (0x17, 0x17, 3), (0x18, 0x18, 5), (0x19, 0x1a, 2),
    (0x1b, 0x1b, 3), (0x1c, 0x1c, 2), (0x1d, 0x1e, 1), (0x1f, 0x20, 2),
    (0x21, 0x21, 1), (0x22, 0x23, 2), (0x24, 0x26, 3), (0x27, 0x28, 1),
    (0x29, 0x29, 2), (0x2a, 0x2c, 3), (0x2d, 0x3d, 2), (0x3e, 0x43, 1),
    (0x44, 0x6d, 2), (0x6e, 0x72, 3), (0x73, 0x73, 1), (0x74, 0x78, 3),
    (0x79, 0x7a, 1), (0x7b, 0x8f, 1), (0x90, 0xaf, 2), (0xb0, 0xcf, 1),
    (0xd0, 0xd7, 2), (0xd8, 0xe2, 2), (0xe3, 0xf9, 1), (0xfa, 0xfb, 4),
    (0xfc, 0xfd, 3), (0xfe, 0xff, 2),
]:
    for _op in range(_lo, _hi + 1):
        LENGTHS[_op] = _n


def load(dex_dir):
    out = []
    for p in sorted(glob.glob(os.path.join(dex_dir, 'classes*.dex'))):
        out.append(Dex(p))
    return out


def cmd_cls(argv):
    want = argv[0]
    dex_dir = argv[1] if len(argv) > 1 else DEFAULT_DEX_DIR
    for d in load(dex_dir):
        d.dump_class(want)


def cmd_clsname(argv):
    pat = re.compile(argv[0])
    dex_dir = argv[1] if len(argv) > 1 else DEFAULT_DEX_DIR
    seen = set()
    for d in load(dex_dir):
        for i, base in d.class_defs():
            cn = d.type(u32(d.b, base))
            if pat.search(cn) and cn not in seen:
                seen.add(cn)
                print('%s  %s' % (os.path.basename(d.path), cn))


def cmd_str(argv):
    needle = argv[0]
    dex_dir = argv[1] if len(argv) > 1 else DEFAULT_DEX_DIR
    for d in load(dex_dir):
        for i, base in d.class_defs():
            cn = d.type(u32(d.b, base))
            cdo = u32(d.b, base + 24)
            if cdo == 0:
                continue
            o = cdo
            sf, o = uleb(d.b, o)
            inf, o = uleb(d.b, o)
            dm, o = uleb(d.b, o)
            vm, o = uleb(d.b, o)
            for _ in range(sf + inf):
                _, o = uleb(d.b, o)
                _, o = uleb(d.b, o)
            idx = 0
            for cnt in (dm, vm):
                idx = 0
                for _ in range(cnt):
                    dd, o = uleb(d.b, o)
                    a, o = uleb(d.b, o)
                    co, o = uleb(d.b, o)
                    idx += dd
                    if not co:
                        continue
                    for s in d.code_strings(co):
                        if needle in s:
                            nm, _, _ = d.method(idx)
                            print('%s  %s::%s  -> %r' % (os.path.basename(d.path), cn, nm, s))


def cmd_strs(argv):
    pat = re.compile(argv[0])
    dex_dir = argv[1] if len(argv) > 1 else DEFAULT_DEX_DIR
    seen = set()
    for d in load(dex_dir):
        for i in range(d.string_n):
            s = d.string(i)
            if pat.search(s) and s not in seen:
                seen.add(s)
                print(repr(s))


def cmd_refs(argv):
    """列出调用了指定方法名（或 类::方法名）的位置。"""
    want_cls = None
    want = argv[0]
    if '::' in want:
        want_cls, want = want.split('::', 1)
    dex_dir = argv[1] if len(argv) > 1 else DEFAULT_DEX_DIR
    for d in load(dex_dir):
        for i, base in d.class_defs():
            cn = d.type(u32(d.b, base))
            cdo = u32(d.b, base + 24)
            if cdo == 0:
                continue
            o = cdo
            sf, o = uleb(d.b, o)
            inf, o = uleb(d.b, o)
            dm, o = uleb(d.b, o)
            vm, o = uleb(d.b, o)
            for _ in range(sf + inf):
                _, o = uleb(d.b, o)
                _, o = uleb(d.b, o)
            idx = 0
            for cnt in (dm, vm):
                idx = 0
                for _ in range(cnt):
                    dd, o = uleb(d.b, o)
                    a, o = uleb(d.b, o)
                    co, o = uleb(d.b, o)
                    idx += dd
                    if not co:
                        continue
                    for _p, mi in d.code_invokes(co):
                        mo = d.method_o + 8 * mi
                        tgt_cls = d.type(u16(d.b, mo))
                        name = d.string(u32(d.b, mo + 4))
                        if name != want:
                            continue
                        if want_cls and want_cls not in tgt_cls:
                            continue
                        nm, _, _ = d.method(idx)
                        print('%s  %s::%s  ->  %s::%s' % (
                            os.path.basename(d.path), cn, nm, tgt_cls, name))


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return
    cmd = sys.argv[1]
    {'cls': cmd_cls, 'clsname': cmd_clsname, 'str': cmd_str,
     'strs': cmd_strs, 'refs': cmd_refs}[cmd](sys.argv[2:])


if __name__ == '__main__':
    main()
