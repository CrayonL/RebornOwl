#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""列出类的每个方法的 code_off 与指令数（用于定位 xxxx 偏移属于哪个方法）。

用法：python methods.py <描述符|简单名> [dex-dir]
"""
import os
import sys
import struct

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dexdump as D


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    want = sys.argv[1]
    dex_dir = sys.argv[2] if len(sys.argv) > 2 else D.DEFAULT_DEX_DIR
    for d in D.load(dex_dir):
        for dex, i, base, desc in d.find_class(want):
            b = dex.b
            cdo = D.u32(b, base + 24)
            if cdo == 0:
                continue
            o = cdo
            sf, o = D.uleb(b, o)
            inf, o = D.uleb(b, o)
            dm, o = D.uleb(b, o)
            vm, o = D.uleb(b, o)
            for _ in range(sf + inf):
                _, o = D.uleb(b, o)
                _, o = D.uleb(b, o)
            print('CLASS %s  (%s)' % (desc, os.path.basename(dex.path)))
            idx = 0
            for label, cnt in (('direct', dm), ('virtual', vm)):
                idx = 0
                for _ in range(cnt):
                    dd, o = D.uleb(b, o)
                    a, o = D.uleb(b, o)
                    co, o = D.uleb(b, o)
                    idx += dd
                    nm, ret, ps = dex.method(idx)
                    if co:
                        n = D.u32(b, co + 12)
                        print('  %s  %s %s(%s)  code=%#x insns=%d  end=%#x' % (
                            label, ret, nm, ','.join(ps), co, n, co + 16 + 2 * n))
                    else:
                        print('  %s  %s %s(%s)  code=-' % (label, ret, nm, ','.join(ps)))


if __name__ == '__main__':
    main()
