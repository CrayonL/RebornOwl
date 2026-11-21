#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""基于 dexdump.Dex 的 Dalvik 反汇编器（smali 风格输出）。

用法：
  python dexdis.py m <类描述符|简单名> <方法名> [dex-dir]   # 反汇编一个方法（所有重载）
  python dexdis.py c <类描述符|简单名> [dex-dir]           # 列出类结构 + 每方法指令数
  python dexdis.py s <类描述符> <方法名> [dex-dir]         # 只列方法里的 const-string 与 invoke

说明：仅做静态解析，不执行任何代码。寄存器用 vXX 表示（不推断类型/名称）。
"""

import os
import sys
import struct

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dexdump as D  # noqa: E402

u16 = D.u16
u32 = D.u32


def s8(v):
    return v - 0x100 if v & 0x80 else v


def s16(v):
    return v - 0x10000 if v & 0x8000 else v


def s32(v):
    return v - 0x100000000 if v & 0x80000000 else v


OPNAMES = {
    0x00: 'nop', 0x01: 'move', 0x02: 'move/from16', 0x03: 'move/16',
    0x04: 'move-wide', 0x05: 'move-wide/from16', 0x06: 'move-wide/16',
    0x07: 'move-object', 0x08: 'move-object/from16', 0x09: 'move-object/16',
    0x0a: 'move-result', 0x0b: 'move-result-wide', 0x0c: 'move-result-object',
    0x0d: 'move-exception', 0x0e: 'return-void', 0x0f: 'return',
    0x10: 'return-wide', 0x11: 'return-object', 0x12: 'const/4', 0x13: 'const/16',
    0x14: 'const', 0x15: 'const/high16', 0x16: 'const-wide/16', 0x17: 'const-wide/32',
    0x18: 'const-wide', 0x19: 'const-wide/high16', 0x1a: 'const-string',
    0x1b: 'const-string/jumbo', 0x1c: 'const-class', 0x1d: 'monitor-enter',
    0x1e: 'monitor-exit', 0x1f: 'check-cast', 0x20: 'instance-of', 0x21: 'array-length',
    0x22: 'new-instance', 0x23: 'new-array', 0x24: 'filled-new-array',
    0x25: 'filled-new-array/range', 0x26: 'fill-array-data', 0x27: 'throw',
    0x28: 'goto', 0x29: 'goto/16', 0x2a: 'goto/32', 0x2b: 'packed-switch',
    0x2c: 'sparse-switch', 0x2d: 'cmpl-float', 0x2e: 'cmpg-float',
    0x2f: 'cmpl-double', 0x30: 'cmpg-double', 0x31: 'cmp-long',
    0x32: 'if-eq', 0x33: 'if-ne', 0x34: 'if-lt', 0x35: 'if-ge', 0x36: 'if-gt',
    0x37: 'if-le', 0x38: 'if-eqz', 0x39: 'if-nez', 0x3a: 'if-ltz', 0x3b: 'if-gez',
    0x3c: 'if-gtz', 0x3d: 'if-lez',
    0x44: 'aget', 0x45: 'aget-wide', 0x46: 'aget-object', 0x47: 'aget-boolean',
    0x48: 'aget-byte', 0x49: 'aget-char', 0x4a: 'aget-short', 0x4b: 'aput',
    0x4c: 'aput-wide', 0x4d: 'aput-object', 0x4e: 'aput-boolean', 0x4f: 'aput-byte',
    0x50: 'aput-char', 0x51: 'aput-short',
    0x52: 'iget', 0x53: 'iget-wide', 0x54: 'iget-object', 0x55: 'iget-boolean',
    0x56: 'iget-byte', 0x57: 'iget-char', 0x58: 'iget-short', 0x59: 'iput',
    0x5a: 'iput-wide', 0x5b: 'iput-object', 0x5c: 'iput-boolean', 0x5d: 'iput-byte',
    0x5e: 'iput-char', 0x5f: 'iput-short',
    0x60: 'sget', 0x61: 'sget-wide', 0x62: 'sget-object', 0x63: 'sget-boolean',
    0x64: 'sget-byte', 0x65: 'sget-char', 0x66: 'sget-short', 0x67: 'sput',
    0x68: 'sput-wide', 0x69: 'sput-object', 0x6a: 'sput-boolean', 0x6b: 'sput-byte',
    0x6c: 'sput-char', 0x6d: 'sput-short',
    0x6e: 'invoke-virtual', 0x6f: 'invoke-super', 0x70: 'invoke-direct',
    0x71: 'invoke-static', 0x72: 'invoke-interface', 0x73: 'unused-73',
    0x74: 'invoke-virtual/range', 0x75: 'invoke-super/range',
    0x76: 'invoke-direct/range', 0x77: 'invoke-static/range',
    0x78: 'invoke-interface/range',
    0x7b: 'neg-int', 0x7c: 'not-int', 0x7d: 'neg-long', 0x7e: 'not-long',
    0x7f: 'neg-float', 0x80: 'neg-double', 0x81: 'int-to-long', 0x82: 'int-to-float',
    0x83: 'int-to-double', 0x84: 'long-to-int', 0x85: 'long-to-float',
    0x86: 'long-to-double', 0x87: 'float-to-int', 0x88: 'float-to-long',
    0x89: 'float-to-double', 0x8a: 'double-to-int', 0x8b: 'double-to-long',
    0x8c: 'double-to-float', 0x8d: 'int-to-byte', 0x8e: 'int-to-char',
    0x8f: 'int-to-short',
    0xfa: 'invoke-polymorphic', 0xfb: 'invoke-polymorphic/range',
    0xfc: 'invoke-custom', 0xfd: 'invoke-custom/range',
    0xfe: 'const-method-handle', 0xff: 'const-method-type',
}

BIN2ADDR = ['add', 'sub', 'mul', 'div', 'rem', 'and', 'or', 'xor', 'shl', 'shr', 'ushr']
for _i, _n in enumerate(['add', 'sub', 'mul', 'div', 'rem', 'and', 'or', 'xor']):
    OPNAMES[0x90 + _i] = _n + '-int'
    OPNAMES[0x9b + _i] = _n + '-long'
    OPNAMES[0xa6 + _i] = _n + '-float'
    OPNAMES[0xab + _i] = _n + '-double'
for _i, _n in enumerate(['add', 'sub', 'mul', 'div', 'rem']):
    OPNAMES[0x9a + _i] = _n + '-int/2addr' if _i == 0 else _n + '-int/2addr'
OPNAMES.update({
    0x90: 'add-int', 0x91: 'sub-int', 0x92: 'mul-int', 0x93: 'div-int',
    0x94: 'rem-int', 0x95: 'and-int', 0x96: 'or-int', 0x97: 'xor-int',
    0x98: 'shl-int', 0x99: 'shr-int', 0x9a: 'ushr-int',
    0x9b: 'add-long', 0x9c: 'sub-long', 0x9d: 'mul-long', 0x9e: 'div-long',
    0x9f: 'rem-long', 0xa0: 'and-long', 0xa1: 'or-long', 0xa2: 'xor-long',
    0xa3: 'shl-long', 0xa4: 'shr-long', 0xa5: 'ushr-long',
    0xa6: 'add-float', 0xa7: 'sub-float', 0xa8: 'mul-float', 0xa9: 'div-float',
    0xaa: 'rem-float',
    0xab: 'add-double', 0xac: 'sub-double', 0xad: 'mul-double', 0xae: 'div-double',
    0xaf: 'rem-double',
    0xb0: 'add-int/2addr', 0xb1: 'sub-int/2addr', 0xb2: 'mul-int/2addr',
    0xb3: 'div-int/2addr', 0xb4: 'rem-int/2addr', 0xb5: 'and-int/2addr',
    0xb6: 'or-int/2addr', 0xb7: 'xor-int/2addr', 0xb8: 'shl-int/2addr',
    0xb9: 'shr-int/2addr', 0xba: 'ushr-int/2addr',
    0xbb: 'add-long/2addr', 0xbc: 'sub-long/2addr', 0xbd: 'mul-long/2addr',
    0xbe: 'div-long/2addr', 0xbf: 'rem-long/2addr', 0xc0: 'and-long/2addr',
    0xc1: 'or-long/2addr', 0xc2: 'xor-long/2addr', 0xc3: 'shl-long/2addr',
    0xc4: 'shr-long/2addr', 0xc5: 'ushr-long/2addr',
    0xc6: 'add-float/2addr', 0xc7: 'sub-float/2addr', 0xc8: 'mul-float/2addr',
    0xc9: 'div-float/2addr', 0xca: 'rem-float/2addr',
    0xcb: 'add-double/2addr', 0xcc: 'sub-double/2addr', 0xcd: 'mul-double/2addr',
    0xce: 'div-double/2addr', 0xcf: 'rem-double/2addr',
    0xd0: 'add-int/lit16', 0xd1: 'rsub-int', 0xd2: 'mul-int/lit16',
    0xd3: 'div-int/lit16', 0xd4: 'rem-int/lit16', 0xd5: 'and-int/lit16',
    0xd6: 'or-int/lit16', 0xd7: 'xor-int/lit16',
    0xd8: 'add-int/lit8', 0xd9: 'rsub-int/lit8', 0xda: 'mul-int/lit8',
    0xdb: 'div-int/lit8', 0xdc: 'rem-int/lit8', 0xdd: 'and-int/lit8',
    0xde: 'or-int/lit8', 0xdf: 'shl-int/lit8', 0xe0: 'shr-int/lit8',
    0xe1: 'ushr-int/lit8',
})
for _n in range(0x0f, 0x12):
    pass


class Dis(object):
    def __init__(self, dex):
        self.d = dex
        # (type, index) 缓冲：method index -> 所在 dex
        self._resolve = {}

    # ---------- 引用解析 ----------
    def _mref(self, mi):
        d = self.d
        try:
            o = d.method_o + 8 * mi
            cls = d.type(u16(d.b, o))
            proto = u16(d.b, o + 2)
            name = d.string(u32(d.b, o + 4))
            ret, ps = d.proto(proto)
            return '%s->%s(%s)%s' % (cls, name, ','.join(ps), ret)
        except Exception:
            return 'method@%d?' % mi

    def _fref(self, fi):
        d = self.d
        try:
            o = d.field_o + 8 * fi
            cls = d.type(u16(d.b, o))
            typ = d.type(u16(d.b, o + 2))
            name = d.string(u32(d.b, o + 4))
            return '%s->%s:%s' % (cls, name, typ)
        except Exception:
            return 'field@%d?' % fi

    def _tref(self, ti):
        try:
            return self.d.type(ti)
        except Exception:
            return 'type@%d?' % ti

    def _sref(self, si):
        try:
            return repr(self.d.string(si))
        except Exception:
            return 'str@%d?' % si

    # ---------- 指令解码 ----------
    def decode(self, p):
        """返回 (len_units, text, targets[])；targets 为绝对 code-unit 偏移。"""
        b = self.d.b
        unit = u16(b, p)
        op = unit & 0xFF
        name = OPNAMES.get(op, 'op-%02x' % op)
        hi = unit >> 8
        hi4 = unit >> 12
        targets = []

        def A12():
            return (unit >> 8) & 0xF, hi4

        if op == 0x00:
            return 1, 'nop', targets
        if op in (0x01, 0x04, 0x07):
            a, bb = A12()
            return 1, '%s v%d, v%d' % (name, a, bb), targets
        if op in (0x02, 0x05, 0x08):
            return 2, '%s v%d, v%d' % (name, hi, u16(b, p + 2)), targets
        if op in (0x03, 0x06, 0x09):
            return 3, '%s v%d, v%d' % (name, u16(b, p + 2), u16(b, p + 4)), targets
        if op in (0x0a, 0x0b, 0x0c, 0x0d, 0x0f, 0x10, 0x11, 0x1d, 0x1e, 0x21, 0x27):
            return 1, '%s v%d' % (name, hi), targets
        if op == 0x0e:
            return 1, 'return-void', targets
        if op == 0x12:
            lit = hi4 - 16 if hi4 & 0x8 else hi4
            return 1, 'const/4 v%d, #%d' % ((unit >> 8) & 0xF, lit), targets
        if op in (0x13, 0x16):
            return 2, '%s v%d, #%d' % (name, hi, s16(u16(b, p + 2))), targets
        if op == 0x14:
            return 3, 'const v%d, #%d' % (hi, s32(u32(b, p + 2))), targets
        if op == 0x17:
            return 3, 'const-wide/32 v%d, #%d' % (hi, s32(u32(b, p + 2))), targets
        if op == 0x15:
            return 2, 'const/high16 v%d, #%d' % (hi, s16(u16(b, p + 2)) << 16), targets
        if op == 0x19:
            return 2, 'const-wide/high16 v%d, #%d' % (hi, s16(u16(b, p + 2)) << 48), targets
        if op == 0x18:
            lo = u32(b, p + 2)
            hw = u32(b, p + 4)
            v = (hw << 32) | lo
            if v >= 1 << 63:
                v -= 1 << 64
            return 5, 'const-wide v%d, #%d' % (hi, v), targets
        if op == 0x1a:
            return 2, 'const-string v%d, %s' % (hi, self._sref(u16(b, p + 2))), targets
        if op == 0x1b:
            return 3, 'const-string/jumbo v%d, %s' % (hi, self._sref(u32(b, p + 2))), targets
        if op in (0x1c, 0x1f, 0xfe, 0xff):
            return 2, '%s v%d, %s' % (name, hi, self._tref(u16(b, p + 2))), targets
        if op == 0x20:
            return 2, 'instance-of v%d, v%d, %s' % ((unit >> 8) & 0xF, hi4, self._tref(u16(b, p + 2))), targets
        if op == 0x22:
            return 2, 'new-instance v%d, %s' % (hi, self._tref(u16(b, p + 2))), targets
        if op == 0x23:
            return 2, 'new-array v%d, v%d, %s' % ((unit >> 8) & 0xF, hi4, self._tref(u16(b, p + 2))), targets
        if op == 0x24:
            n = hi4
            w = u16(b, p + 4)
            regs = [w & 0xF, (w >> 4) & 0xF, (w >> 8) & 0xF, (w >> 12) & 0xF, hi & 0xF]
            return 3, 'filled-new-array {%s}, %s' % (', '.join('v%d' % r for r in regs[:n]), self._tref(u16(b, p + 2))), targets
        if op == 0x25:
            first = u16(b, p + 4)
            return 3, 'filled-new-array/range {v%d..v%d}, %s' % (first, first + hi - 1, self._tref(u16(b, p + 2))), targets
        if op == 0x26:
            return 3, 'fill-array-data v%d, :%d' % (hi, p + s32(u32(b, p + 2))), targets
        if op == 0x28:
            t = p + s8(hi)
            return 1, 'goto :L%04x' % t, [t]
        if op == 0x29:
            t = p + s16(u16(b, p + 2))
            return 2, 'goto/16 :L%04x' % t, [t]
        if op == 0x2a:
            t = p + s32(u32(b, p + 2))
            return 3, 'goto/32 :L%04x' % t, [t]
        if op in (0x2b, 0x2c):
            return 3, '%s v%d, :%d' % (name, hi, p + s32(u32(b, p + 2))), targets
        if op in (0x2d, 0x2e, 0x2f, 0x30, 0x31):
            return 2, '%s v%d, v%d, v%d' % (name, hi, (u16(b, p + 2) >> 8) & 0xF, u16(b, p + 2) & 0xF), targets
        if 0x32 <= op <= 0x37:
            t = p + s16(u16(b, p + 2))
            return 2, '%s v%d, v%d, :L%04x' % (name, (unit >> 8) & 0xF, hi4, t), [t]
        if 0x38 <= op <= 0x3d:
            t = p + s16(u16(b, p + 2))
            return 2, '%s v%d, :L%04x' % (name, hi, t), [t]
        if 0x44 <= op <= 0x51:
            return 2, '%s v%d, v%d, v%d' % (name, hi, (u16(b, p + 2) >> 8) & 0xF, u16(b, p + 2) & 0xF), targets
        if 0x52 <= op <= 0x5f:
            return 2, '%s v%d, v%d, %s' % (name, (unit >> 8) & 0xF, hi4, self._fref(u16(b, p + 2))), targets
        if 0x60 <= op <= 0x6d:
            return 2, '%s v%d, %s' % (name, hi, self._fref(u16(b, p + 2))), targets
        if 0x6e <= op <= 0x72:
            n = hi4
            w = u16(b, p + 4)
            regs = [w & 0xF, (w >> 4) & 0xF, (w >> 8) & 0xF, (w >> 12) & 0xF, hi & 0xF]
            return 3, '%s {%s}, %s' % (name, ', '.join('v%d' % r for r in regs[:n]), self._mref(u16(b, p + 2))), targets
        if 0x74 <= op <= 0x78:
            first = u16(b, p + 4)
            return 3, '%s {v%d..v%d}, %s' % (name, first, first + hi - 1, self._mref(u16(b, p + 2))), targets
        if 0x7b <= op <= 0x8f:
            a, bb = A12()
            return 1, '%s v%d, v%d' % (name, a, bb), targets
        if 0x90 <= op <= 0xaf:
            return 2, '%s v%d, v%d, v%d' % (name, hi, (u16(b, p + 2) >> 8) & 0xF, u16(b, p + 2) & 0xF), targets
        if 0xb0 <= op <= 0xcf:
            a, bb = A12()
            return 1, '%s v%d, v%d' % (name, a, bb), targets
        if 0xd0 <= op <= 0xd7:
            return 2, '%s v%d, v%d, #%d' % (name, (unit >> 8) & 0xF, hi4, s16(u16(b, p + 2))), targets
        if 0xd8 <= op <= 0xe1:
            return 2, '%s v%d, v%d, #%d' % (name, hi, (u16(b, p + 2) >> 8) & 0xFF, s8(u16(b, p + 2) & 0xFF)), targets
        if op == 0xfa:
            n = hi4
            w = u16(b, p + 4)
            regs = [w & 0xF, (w >> 4) & 0xF, (w >> 8) & 0xF, (w >> 12) & 0xF, hi & 0xF]
            return 4, 'invoke-polymorphic {%s}, %s, %s' % (', '.join('v%d' % r for r in regs[:n]),
                                                          self._mref(u16(b, p + 2)), self._mref(u16(b, p + 6))), targets
        if op == 0xfb:
            first = u16(b, p + 4)
            return 4, 'invoke-polymorphic/range {v%d..v%d}, %s, %s' % (
                first, first + hi - 1, self._mref(u16(b, p + 2)), self._mref(u16(b, p + 6))), targets
        if op == 0xfc:
            n = hi4
            w = u16(b, p + 4)
            regs = [w & 0xF, (w >> 4) & 0xF, (w >> 8) & 0xF, (w >> 12) & 0xF, hi & 0xF]
            return 3, 'invoke-custom {%s}, call_site@%d' % (', '.join('v%d' % r for r in regs[:n]), u16(b, p + 2)), targets
        if op == 0xfd:
            first = u16(b, p + 4)
            return 3, 'invoke-custom/range {v%d..v%d}, call_site@%d' % (first, first + hi - 1, u16(b, p + 2)), targets
        return 1, '%s ?' % name, targets

    # ---------- 遍历 ----------
    def instructions(self, code_off):
        """产出 (pc, len, text, targets)，跳过 payload。

        有些类（如 protobuf/R8 产物）在不可达位置留有畸形字节，看起来像 payload 头；
        只有当 payload 整体落在 insns 范围内时才按 payload 跳过，否则按 nop 继续，
        避免整段错位。
        """
        b = self.d.b
        insns_size = u32(b, code_off + 12)
        p = code_off + 16
        end = p + insns_size * 2
        while p < end:
            op = b[p]
            if op == 0:
                ident = u16(b, p)
                payload = 0
                label = None
                if ident == 0x0100:
                    size = u16(b, p + 2)
                    payload = 8 + size * 4
                    label = 'packed-switch-payload size=%d first=%d' % (size, u16(b, p + 4))
                elif ident == 0x0200:
                    size = u16(b, p + 2)
                    payload = 4 + size * 8
                    label = 'sparse-switch-payload size=%d' % size
                elif ident == 0x0300:
                    ew = u16(b, p + 2)
                    size = u32(b, p + 4)
                    payload = 8 + ((size * ew + 1) // 2) * 2
                    label = 'fill-array-data-payload width=%d size=%d' % (ew, size)
                if payload and p + payload <= end:
                    yield p, payload // 2, label, []
                    p += payload
                    continue
            ln, text, tg = self.decode(p)
            yield p, ln, text, tg
            p += ln * 2

    def ops(self, code_off):
        """产出 (指令偏移, opcode)，已跳过 payload。"""
        for p, ln, text, tg in self.instructions(code_off):
            yield p, self.d.b[p]

    def switch_targets(self, payload_off):
        """解析 switch payload，返回 [(key, abs_target)]。"""
        b = self.d.b
        ident = u16(b, payload_off)
        if ident == 0x0100:
            size = u16(b, payload_off + 2)
            first = s32(u32(b, payload_off + 4))
            out = []
            for i in range(size):
                rel = s32(u32(b, payload_off + 8 + 4 * i))
                out.append((first + i, payload_off + rel))
            return out
        if ident == 0x0200:
            size = u16(b, payload_off + 2)
            out = []
            for i in range(size):
                key = s32(u32(b, payload_off + 4 + 8 * i))
                rel = s32(u32(b, payload_off + 8 + 8 * i))
                out.append((key, payload_off + rel))
            return out
        return []


def dexes(dex_dir):
    return D.load(dex_dir)


def find_methods(dex_dir, cls, name=None, with_code_only=True):
    out = []
    for d in dexes(dex_dir):
        for i, base in d.class_defs():
            cn = d.type(u32(d.b, base))
            if not _cls_match(cn, cls):
                continue
            cdo = u32(d.b, base + 24)
            if cdo == 0:
                continue
            o = cdo
            sf, o = D.uleb(d.b, o)
            inf, o = D.uleb(d.b, o)
            dm, o = D.uleb(d.b, o)
            vm, o = D.uleb(d.b, o)
            for _ in range(sf + inf):
                _, o = D.uleb(d.b, o)
                _, o = D.uleb(d.b, o)
            idx = 0
            for cnt in (dm, vm):
                idx = 0
                for _ in range(cnt):
                    dd, o = D.uleb(d.b, o)
                    a, o = D.uleb(d.b, o)
                    co, o = D.uleb(d.b, o)
                    idx += dd
                    nm, ret, ps = d.method(idx)
                    if name is not None and nm != name:
                        continue
                    if with_code_only and not co:
                        continue
                    out.append((d, cn, nm, ret, ps, a, co))
    return out


def _cls_match(cn, want):
    if cn == want:
        return True
    if want.startswith('L') and want.endswith(';'):
        return cn == want
    simple = want.replace('.', '/')
    return cn == 'L' + simple + ';' or cn.endswith('/' + simple + ';')


def dis_method(dex, cls, name, ret, ps, acc, code_off, out=None):
    dis = Dis(dex)
    lines = []
    lines.append('---- %s->%s(%s)%s  (code_off=0x%x, %s)' % (
        cls, name, ','.join(ps), ret, code_off, os.path.basename(dex.path)))
    # 先收集标签目标
    insns = list(dis.instructions(code_off))
    labels = set()
    for pc, ln, text, tg in insns:
        for t in tg:
            labels.add(t)
    for pc, ln, text, tg in insns:
        if pc in labels:
            lines.append('    :L%04x' % pc)
        lines.append('    %04x: %s' % (pc, text))
        # switch 展开
        if ('packed-switch' in text or 'sparse-switch' in text) and 'payload' not in text:
            # 目标是 payload 偏移
            try:
                tgt = int(text.split(':')[-1])
                for k, tp in dis.switch_targets(tgt):
                    lines.append('        case %d -> :L%04x' % (k, tp))
                    labels.add(tp)
            except Exception:
                pass
    text = '\n'.join(lines)
    if out is None:
        print(text)
    else:
        out.write(text + '\n')


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return
    cmd = sys.argv[1]
    if cmd == 'm':
        cls, name = sys.argv[2], sys.argv[3]
        dex_dir = sys.argv[4] if len(sys.argv) > 4 else D.DEFAULT_DEX_DIR
        hits = find_methods(dex_dir, cls, name)
        if not hits:
            print('未找到 %s->%s' % (cls, name))
        for d, cn, nm, ret, ps, a, co in hits:
            dis_method(d, cn, nm, ret, ps, a, co)
    elif cmd == 'c':
        cls = sys.argv[2]
        dex_dir = sys.argv[3] if len(sys.argv) > 3 else D.DEFAULT_DEX_DIR
        for d in dexes(dex_dir):
            for i, base in d.class_defs():
                cn = d.type(u32(d.b, base))
                if _cls_match(cn, cls):
                    d._dump_one(base, cn, False)
    elif cmd == 's':
        cls, name = sys.argv[2], sys.argv[3]
        dex_dir = sys.argv[4] if len(sys.argv) > 4 else D.DEFAULT_DEX_DIR
        for d, cn, nm, ret, ps, a, co in find_methods(dex_dir, cls, name):
            print('==== %s->%s(%s)%s' % (cn, nm, ','.join(ps), ret))
            for pc, ln, text, tg in Dis(d).instructions(co):
                if 'const-string' in text or 'invoke-' in text or 'sget' in text or 'sput' in text:
                    print('  %04x: %s' % (pc, text))
    else:
        print(__doc__)


if __name__ == '__main__':
    main()
