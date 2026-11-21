#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把某类构造器里的 iput-object 源寄存器换算成参数下标（p0=this）。

用法: python ctor_map.py <类描述符> <dex-dir> <字段名1> [字段名2 ...]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dexdump as D
import dexdis as X


def main():
    want = sys.argv[1]
    dex_dir = sys.argv[2]
    fields = set(sys.argv[3:])
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
            idx = 0
            for cnt in (dm, vm):
                idx = 0
                for _ in range(cnt):
                    dd, o = D.uleb(b, o)
                    a, o = D.uleb(b, o)
                    co, o = D.uleb(b, o)
                    idx += dd
                    nm, ret, ps = dex.method(idx)
                    if nm != '<init>' or co == 0:
                        continue
                    R = D.u16(b, co)
                    I = D.u16(b, co + 2)
                    base_reg = R - I
                    print('== %s params=%d regs=%d ins=%d p0=v%d code=%#x' % (
                        desc, len(ps), R, I, base_reg, co))
                    dec = X.Decoder(dex) if hasattr(X, 'Decoder') else None
                    for ins in X._insns(dex, co) if hasattr(X, '_insns') else []:
                        pass
                    # 直接扫描指令流: iput-object 0x5b (22c)
                    for p, op in dex._walk(co):
                        if op == 0x5b:
                            reg = b[p + 1]
                            fidx = D.u16(b, p + 2)
                            ft, fn = dex.field(fidx)
                            if fn in fields:
                                print('   %#x iput-object v%d -> %s  param=%d' % (
                                    p, reg, fn, reg - base_reg))


if __name__ == '__main__':
    main()
