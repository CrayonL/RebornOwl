#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按类型描述符（如 Ljr2;）查找引用位置：字段类型、方法签名、代码中的 type 常量。

用法：
  python typeref.py Ljr2; [dex-dir] [--code]

--code 时额外扫描指令里出现的 type/method/field 引用（const-class / new-instance / check-cast 等）。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dexdump as D  # noqa: E402
import dexdis as X_dis  # noqa: E402

X_NAMES = {0x1C: 'const-class', 0x1F: 'check-cast', 0x20: 'instance-of',
           0x22: 'new-instance', 0x23: 'new-array', 0x24: 'filled-new-array',
           0x25: 'filled-new-array/range'}


def iter_classes(d):
    b = d.b
    for i, base in d.class_defs():
        cn = d.type(D.u32(b, base))
        cdo = D.u32(b, base + 24)
        if cdo == 0:
            yield cn, None, None, None, None, None
            continue
        o = cdo
        sf, o = D.uleb(b, o)
        inf, o = D.uleb(b, o)
        dm, o = D.uleb(b, o)
        vm, o = D.uleb(b, o)
        fields = []
        for cnt in (sf, inf):
            fidx = 0
            for k in range(cnt):
                dd, o = D.uleb(b, o)
                a, o = D.uleb(b, o)
                fidx += dd
                fields.append(fidx)
        methods = []
        for cnt in (dm, vm):
            midx = 0
            for k in range(cnt):
                dd, o = D.uleb(b, o)
                a, o = D.uleb(b, o)
                co, o = D.uleb(b, o)
                midx += dd
                methods.append((midx, co))
        yield cn, fields, methods, None, None, None


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    want = sys.argv[1]
    dex_dir = sys.argv[2] if len(sys.argv) > 2 and not sys.argv[2].startswith('--') else \
        D.DEFAULT_DEX_DIR
    code = '--code' in sys.argv
    for d in D.load(dex_dir):
        b = d.b
        for cn, fields, methods, _, _, _ in iter_classes(d):
            hits = []
            if fields:
                for fi in fields:
                    t, nm = d.field(fi)
                    if t == want:
                        hits.append('field %s' % nm)
            if not methods:
                if hits:
                    print('%s  %s  %s' % (os.path.basename(d.path), cn, '; '.join(hits)))
                continue
            for mi, co in methods:
                nm, ret, ps = d.method(mi)
                if ret == want or want in ps:
                    hits.append('method %s(%s)%s' % (nm, ','.join(ps), ret))
                if code and co:
                    for p, mi2 in d.code_invokes(co):
                        mo = d.method_o + 8 * mi2
                        if d.type(D.u16(b, mo)) == want:
                            hits.append('call@%x -> %s' % (p, d.string(D.u32(b, mo + 4))))
                    # 类型常量：const-class / check-cast / instance-of / new-instance / new-array
                    for p, op in X_dis.Dis(d).ops(co):
                        if op in (0x1C, 0x1F, 0x20, 0x22, 0x23, 0x24, 0x25):
                            ti = D.u16(b, p + 2)
                            if d.type(ti) == want:
                                hits.append('type@%x %s' % (p, X_NAMES.get(op, hex(op))))
            if hits:
                print('%s  %s' % (os.path.basename(d.path), cn))
                for h in hits:
                    print('    ' + h)


if __name__ == '__main__':
    main()
