#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""查找对某字段（读/写）或某类的构造器调用位置。

用法：
  python fieldrefs.py <字段所属类描述符> <字段名> [dex-dir]
  python fieldrefs.py --type <类型描述符> [dex-dir]      # 代码中引用该类型的指令位置
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dexdump as D  # noqa: E402
import dexdis as X  # noqa: E402

GET_OPS = set(range(0x44, 0x4b)) | set(range(0x52, 0x59)) | set(range(0x60, 0x67))
PUT_OPS = set(range(0x4b, 0x52)) | set(range(0x59, 0x60)) | set(range(0x67, 0x6e))


def methods_of(d):
    b = d.b
    for i, base in d.class_defs():
        cn = d.type(D.u32(b, base))
        cdo = D.u32(b, base + 24)
        if cdo == 0:
            continue
        o = cdo
        sf, o = D.uleb(b, o)
        inf, o = D.uleb(b, o)
        dm, o = D.uleb(b, o)
        vm, o = D.uleb(b, o)
        for cnt in (sf, inf):
            for _ in range(cnt):
                _, o = D.uleb(b, o)
                _, o = D.uleb(b, o)
        for cnt in (dm, vm):
            midx = 0
            for _ in range(cnt):
                dd, o = D.uleb(b, o)
                a, o = D.uleb(b, o)
                co, o = D.uleb(b, o)
                midx += dd
                yield cn, midx, co


def main():
    argv = [a for a in sys.argv[1:]]
    if not argv:
        print(__doc__)
        return
    if argv[0] == '--type':
        want = argv[1]
        dex_dir = argv[2] if len(argv) > 2 else D.DEFAULT_DEX_DIR
        for d in D.load(dex_dir):
            b = d.b
            for cn, mi, co in methods_of(d):
                if not co:
                    continue
                hits = []
                for p, ln, text, tg in X.Dis(d).instructions(co):
                    if want in text and ('-instance' in text or 'check-cast' in text
                                         or 'const-class' in text or 'new-array' in text):
                        hits.append(text)
                if hits:
                    nm, ret, ps = d.method(mi)
                    print('%s  %s->%s(%s)%s' % (os.path.basename(d.path), cn, nm,
                                                ','.join(ps), ret))
                    for h in hits[:8]:
                        print('    ' + h)
        return
    cls, fname = argv[0], argv[1]
    dex_dir = argv[2] if len(argv) > 2 else D.DEFAULT_DEX_DIR
    for d in D.load(dex_dir):
        b = d.b
        dis = X.Dis(d)
        for cn, mi, co in methods_of(d):
            if not co:
                continue
            hits = []
            for p, ln, text, tg in dis.instructions(co):
                op = b[p]
                if op not in GET_OPS and op not in PUT_OPS:
                    continue
                if cls not in text or ('->%s:' % fname) not in text:
                    continue
                hits.append(('read ' if op in GET_OPS else 'write ') + text)
            if hits:
                nm, ret, ps = d.method(mi)
                print('%s  %s->%s(%s)%s' % (os.path.basename(d.path), cn, nm, ','.join(ps), ret))
                for h in hits:
                    print('    ' + h)


if __name__ == '__main__':
    main()
