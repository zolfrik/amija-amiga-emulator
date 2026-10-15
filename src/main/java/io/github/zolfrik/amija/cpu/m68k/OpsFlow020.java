/*
 * AmiJa - a cycle-exact Amiga emulator in Java.
 * Copyright (C) 2026 zolfrik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.zolfrik.amija.cpu.m68k;

/** Program flow, exceptions and system control. */
final class OpsFlow020 {
    private OpsFlow020() {}

    // =======================================================================================
    // Branches
    // =======================================================================================

    static void bcc(Motorola68020 c, int op) {
        int disp = (byte) op;
        if (disp == 0) disp = (short) c.nextWord();
        else if (disp == -1) disp = c.nextLong();
        if (c.cond((op >> 8) & 15)) {
            if ((disp & 1) != 0) {
                c.addressError(c.pc + disp + 2);
                return;
            }
            c.pc += disp + 2;
            c.fillPrefetch();
            return;
        }
        c.advance();
        c.po = 0;
        c.prefetchNext();
    }

    static void bsr(Motorola68020 c, int op) {
        int disp = (byte) op;
        if (disp == 0) disp = (short) c.nextWord();
        else if (disp == -1) disp = c.nextLong();
        final int s = disp + 2;
        final int nextpc = c.pc + c.po;
        if ((s & 1) != 0) {
            c.r[15] -= 4;
            c.addressError(c.pc + s);
            return;
        }
        c.r[15] -= 4;
        c.writeLong(c.r[15], nextpc);
        c.pc += s;
        c.fillPrefetch();
    }

    static void dbcc(Motorola68020 c, int op) {
        final int reg = op & 7;
        final int src = (short) c.r[reg];
        final int offs = (short) c.nextWord();
        final int oldpc = c.pc;
        if (!c.cond((op >> 8) & 15)) {
            c.pc += offs + 2;
            c.r[reg] = (c.r[reg] & 0xFFFF0000) | ((src - 1) & 0xFFFF);
            if ((offs & 1) != 0) {
                c.addressError(c.pc);
                return;
            }
            if (src != 0) {
                c.fillPrefetch();
                return;
            }
        }
        c.pc = oldpc + 4;
        c.po = 0;
        c.prefetchNext();
    }

    static void jmp(Motorola68020 c, int op) {
        final int mode = (op >> 3) & 7, reg = op & 7;
        final int a = c.ea(mode, reg, 4);
        if ((a & 1) != 0) {
            // The indexed modes have already consumed their extension words: the stacked
            // PC is past them.
            if (mode == 6 || (mode == 7 && reg == 3)) c.pc += c.po;
            c.pc += 2;
            c.addressError(a);
            return;
        }
        c.pc = a;
        c.fillPrefetch();
    }

    static void jsr(Motorola68020 c, int op) {
        final int a = c.ea((op >> 3) & 7, op & 7, 4);
        final int nextpc = c.pc + c.po;
        c.pc = a;
        c.r[15] -= 4;
        if ((a & 1) != 0) {
            c.addressError(a);
            return;
        }
        c.writeLong(c.r[15], nextpc);
        c.fillPrefetch();
    }

    static void rts(Motorola68020 c, int op) {
        final int oldpc = c.pc;
        final int npc = c.readLong(c.r[15]);
        c.r[15] += 4;
        if ((npc & 1) != 0) {
            c.pc = oldpc;
            c.addressError(npc);
            return;
        }
        c.pc = npc;
        c.fillPrefetch();
    }

    static void rtr(Motorola68020 c, int op) {
        final int oldpc = c.pc;
        final int ccr = c.readWord(c.r[15]);
        c.r[15] += 2;
        final int npc = c.readLong(c.r[15]);
        c.r[15] += 4;
        c.setSR((c.getSR() & 0xFF00) | (ccr & 0xFF), 0);
        c.interruptCheck();
        if ((npc & 1) != 0) {
            c.pc = oldpc + 2;
            c.addressError(npc);
            return;
        }
        c.pc = npc;
        c.fillPrefetch();
    }

    static void rtd(Motorola68020 c, int op) {
        final int npc = c.readLong(c.r[15]);
        c.r[15] += 4;
        final int offs = (short) c.nextWord();
        c.r[15] += offs;
        if ((npc & 1) != 0) {
            c.addressError(npc);
            return;
        }
        c.pc = npc;
        c.fillPrefetch();
    }

    static void rte(Motorola68020 c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        int newsr, newpc;
        for (;;) {
            final int a = c.r[15];
            final int sr = c.readWord(a) & 0xFFFF;
            final int pcv = c.readLong(a + 2);
            final int format = c.readWord(a + 6) & 0xFFFF;
            final int frame = format >> 12;
            newsr = sr;
            newpc = pcv;
            if (frame == 0x0) { c.r[15] += 8; break; }
            else if (frame == 0x1) { c.r[15] += 8; }
            else if (frame == 0x2) { c.r[15] += 12; break; }
            else if (frame == 0x9) { c.r[15] += 20; break; }
            else if (frame == 0xA) { c.r[15] += 32; break; }
            else if (frame == 0xB) { c.r[15] += 92; break; }
            else {
                c.exceptionCpu(14);
                return;
            }
            c.interruptCheck();
            c.setSR(newsr, 1);
        }
        c.interruptCheck();
        c.setSR(newsr, 1);
        if ((newpc & 1) != 0) {
            c.addressError(newpc);
            return;
        }
        c.pc = newpc;
        c.fillPrefetchNoTrace();
    }

    // =======================================================================================
    // Traps
    // =======================================================================================

    static void trap(Motorola68020 c, int op) {
        c.advance();
        c.exceptionCpu(32 + (op & 15));
    }

    static void trapv(Motorola68020 c, int op) {
        c.advance();
        if (c.fv) {
            c.exceptionCpu(7);
            return;
        }
        c.po = 0;
        c.prefetchNext();
    }

    static void trapcc(Motorola68020 c, int op) {
        final int mode = op & 7;
        if (mode == 2) c.nextWord();
        else if (mode == 3) c.nextLong();
        c.advance();
        if (c.cond((op >> 8) & 15)) {
            c.exceptionCpu(7);
            return;
        }
        c.po = 0;
        c.prefetchNext();
    }

    static void illegal(Motorola68020 c, int op) {
        c.illegal(op);
    }

    /**
     * A floating-point instruction with no coprocessor: its extension words are fetched,
     * then the line F exception is taken at the instruction.
     */
    static void fpuAbsent(Motorola68020 c, int op) {
        switch (op & 0xFFC0) {
            case 0xF200: c.nextWord(); break;
            case 0xF240:
                switch (op & 0x3F) {
                    case 0x3A: c.nextWord(); c.nextWord(); break;
                    case 0x3B: c.nextWord(); c.nextLong(); break;
                    default: c.nextWord(); break;
                }
                break;
            case 0xF280: c.nextWord(); break;
            case 0xF2C0: c.nextLong(); break;
            default:
                // cpSAVE, cpRESTORE (FSAVE, FRESTORE among them): privileged.
                if (c.s == 0) {
                    c.privilege();
                    return;
                }
                break;
        }
        c.exception(11);
    }

    /** CALLM and RTM: not executed by this processor, illegal one word on. */
    static void callm(Motorola68020 c, int op) {
        c.pc += 2;
        c.exception(4);
    }

    // =======================================================================================
    // Stack frames
    // =======================================================================================

    static void link(Motorola68020 c, int op) {
        final int reg = 8 + (op & 7);
        final int src = c.r[reg];
        final int sp = c.r[15] - 4;
        c.r[15] = sp;
        final int offs = (short) c.nextWord();
        c.prefetchNext();
        c.writeLong(sp, src);
        c.r[reg] = c.r[15];
        c.r[15] += offs;
        c.advance();
    }

    static void linkLong(Motorola68020 c, int op) {
        final int reg = 8 + (op & 7);
        final int src = c.r[reg];
        final int sp = c.r[15] - 4;
        c.r[15] = sp;
        final int offs = c.nextLong();
        c.prefetchNext();
        c.writeLong(sp, src);
        c.r[reg] = c.r[15];
        c.r[15] += offs;
        c.advance();
    }

    static void unlk(Motorola68020 c, int op) {
        final int reg = 8 + (op & 7);
        final int src = c.r[reg];
        final int old = c.readLong(src);
        c.r[15] = src + 4;
        c.prefetchNext();
        c.r[reg] = old;
        c.advance();
    }

    // =======================================================================================
    // Status register
    // =======================================================================================

    /** ORI/ANDI/EORI to CCR. */
    static void ccrImm(Motorola68020 c, int op) {
        int sr = c.getSR();
        int src = c.nextWord() & 0xFF;
        if (c.t0 != 0) c.checkT0Trace();
        switch ((op >> 9) & 7) {
            case 0: sr |= src; break;
            case 1: sr &= src | 0xFF00; break;
            default: sr ^= src; break;
        }
        c.setSR(sr, 0);
        c.interruptCheck();
        c.advance();
        c.po = 0;
        c.prefetchNext();
    }

    /** ORI/ANDI/EORI to SR. */
    static void srImm(Motorola68020 c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        int sr = c.getSR();
        final int src = c.nextWord();
        if (c.t0 != 0) c.checkT0Trace();
        switch ((op >> 9) & 7) {
            case 0: sr |= src; break;
            case 1: sr &= src; break;
            default: sr ^= src; break;
        }
        c.interruptCheck();
        c.setSR(sr & 0xFFFF, 1);
        c.advance();
        c.fillPrefetchNoTrace();
    }

    static void moveToSr(Motorola68020 c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        final int src = c.readEa((op >> 3) & 7, op & 7, 2);
        c.prefetchNext();
        if (c.t0 != 0) c.checkT0Trace();
        c.interruptCheck();
        c.setSR(src & 0xFFFF, 1);
        c.advance();
        c.fillPrefetchNoTrace();
    }

    static void moveToCcr(Motorola68020 c, int op) {
        final int mode = (op >> 3) & 7;
        final int src = c.readEa(mode, op & 7, 2);
        if (mode != 0) c.prefetchNext();
        c.setSR((c.getSR() & 0xFF00) | (src & 0xFF), 0);
        c.interruptCheck();
        if (mode == 0) c.prefetchNext();
        c.advance();
    }

    static void moveFromSr(Motorola68020 c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        moveFrom(c, op, c.getSR());
    }

    static void moveFromCcr(Motorola68020 c, int op) {
        moveFrom(c, op, c.getSR() & 0xFF);
    }

    private static void moveFrom(Motorola68020 c, int op, int v) {
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            c.prefetchNext();
            c.setD(reg, v, 2);
        } else {
            final int a = c.ea(mode, reg, 2);
            c.prefetchNext();
            c.writeWord(a, v);
        }
        c.advance();
    }

    static void moveUsp(Motorola68020 c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        final int reg = 8 + (op & 7);
        if ((op & 8) == 0) {
            c.usp = c.r[reg];
            c.prefetchNext();
        } else {
            c.prefetchNext();
            c.r[reg] = c.usp;
        }
        c.advance();
    }

    // =======================================================================================
    // System control
    // =======================================================================================

    static void nop(Motorola68020 c, int op) {
        c.prefetchNext();
        c.advance();
    }

    static void reset(Motorola68020 c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        c.bus.resetInstruction();
        c.prefetchNext();
        c.advance();
    }

    static void stop(Motorola68020 c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        if (c.stopped == 0) c.ir = c.nextWord();
        final int sr = c.ir;
        c.checkInterrupt();
        c.setSR(sr, -1);
        c.internalFull(4);
        c.stopped = 1;
    }

    static void movec(Motorola68020 c, int op) {
        final int ext = c.nextWord();
        final int regno = ext & 0xFFF;
        final int rn = (ext >> 12) & 15;
        final boolean valid = (regno & 0x7FF) < 4 && regno != 3 || regno == 0x804;
        if (!valid) {
            if (c.s == 0) c.privilege();
            else c.illegal(op);
            return;
        }
        if (c.s == 0) {
            c.privilege();
            return;
        }
        if ((op & 1) != 0) {
            final int v = c.r[rn];
            switch (regno) {
                case 0: c.sfc = v & 7; break;
                case 1: c.dfc = v & 7; break;
                case 2: c.cacr = v & 0xF; c.setCaches(); break;
                case 0x800: c.usp = v; break;
                case 0x801: c.vbr = v; break;
                case 0x802: c.caar = v; break;
                case 0x803: c.msp = v; if (c.m == 1) c.r[15] = v; break;
                default: c.isp = v; if (c.m == 0) c.r[15] = v; break;
            }
        } else {
            final int v;
            switch (regno) {
                case 0: v = c.sfc; break;
                case 1: v = c.dfc; break;
                case 2: v = c.cacr & 3; break;
                case 0x800: v = c.usp; break;
                case 0x801: v = c.vbr; break;
                case 0x802: v = c.caar; break;
                case 0x803: v = c.m == 1 ? c.r[15] : c.msp; break;
                default: v = c.m == 0 ? c.r[15] : c.isp; break;
            }
            c.r[rn] = v;
        }
        c.prefetchNext();
        c.advance();
    }

    /** MOVES: the function codes select nothing on this machine; plain accesses. */
    static void moves(Motorola68020 c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        final int size = OpsAlu020.size(op);
        final int ext = c.nextWord();
        final int rn = (ext >> 12) & 15;
        final int a = c.ea((op >> 3) & 7, op & 7, size);
        if ((ext & 0x800) != 0) {
            final int v = c.r[rn];
            c.prefetchNext();
            c.write(a, v, size);
            c.advance();
        } else {
            final int v = c.read(a, size);
            if (rn >= 8) c.r[rn] = Motorola68020.sext(v, size);
            else c.setD(rn, v, size);
            c.advance();
            c.po = 0;
            c.prefetchNext();
        }
    }
}
