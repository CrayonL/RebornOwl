#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按字段描述符+字段名，扫描所有 iget*/iput* 引用（只解析 22c 格式，绕开 dexdis 的 payload 解码问题）。

用法: python fieldscan.py "Lcom/x/Y;" fieldName [dex-dir]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dexdump as D

NAMES = {0x52: 'iget', 0x53: 'iget-wide', 0x54: 'iget-object', 0x55: 'iget-boolean',
         0x56: 'iget-byte', 0x57: 'iget-char', 0x58: 'iget-short',
         0x59: 'iput', 0x5a: 'iput-wide', 0x5b: 'iput-object', 0x5c: 'iput-boolean',
         0x5d: 'iput-byte', 0x5e: 'iput-char', 0x5f: 'iput-short'}


def main():
    want_cls = sys.argv[1]
    want_name = sys.argv[2]
    dex_dir = sys.argv[3] if len(sys.argv) > 3 else D.DEFAULT_DEX_DIR
    for d in D.load(dex_dir):
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
                    if not co:
                        continue
                    nm, ret, ps = d.method(idx)
                    for p, op in d._walk(co):
                        if op not in NAMES:
                            continue
                        fidx = D.u16(b, p + 2)
                        ft, fn = d.field(fidx)
                        if fn != want_name:
                            continue
                        # field_id 第 0/1 个 u16 是 class_idx/type_idx
                        fo = d.field_o + 8 * fidx
                        fcls = d.type(D.u16(b, fo))
                        if fcls != want_cls:
                            continue
                        print('%s  %s::%s  %#x  %s v%d' % (
                            os.path.basename(d.path), cn, nm, p, NAMES[op], b[p + 1]))


if __name__ == '__main__':
    main()
