/*
 * AmiJa - a cycle-exact Amiga 500 emulator in Java.
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

import static io.github.zolfrik.amija.cpu.m68k.Motorola68000Core.IPL_NONE;

/**
 * Program flow and system control of the 68000/68010: Bcc, BSR, DBcc, JMP, JSR, RTS, RTR,
 * RTE, TRAP, TRAPV, CHK, LINK, UNLK, STOP, RESET, NOP, the moves to and from SR, CCR and
 * USP, the immediate operations on SR and CCR, the illegal opcodes.
 *
 * <p>A change of flow refills the prefetch at the target with two fetches. The displacement
 * of a branch and the extension word of a jump are taken from the prefetch register itself,
 * so a jump fetches one word fewer than its extension words.
 */
final class Ops000Flow {
    private Ops000Flow() {}

    /** Refills the prefetch at the new {@code pc}: the opcode, then the word after it. */
    private static void refill(Motorola68000Core c) {
        c.prefetch(0);
        c.ir = c.irc;
        c.iplNext();
        c.prefetch(2);
    }

    // ---------------------------------------------------------------------------------------
    // Branches
    // ---------------------------------------------------------------------------------------

    static void bcc(Motorola68000Core c, int op) {
        final int cc = (op >> 8) & 15;
        final int d8 = (byte) op;
        final int disp = d8 == 0 ? (short) c.irc : d8;
        c.internal(2);
        if (c.cond(cc)) {
            if ((disp & 1) != 0) {
                if (c.m68010 && d8 != -1) {
                    // The 68010 fetches the word the odd target falls in, keeping its input buffer.
                    final int rb = c.irc;
                    c.prefetch((disp + 2) & ~1);
                    c.readBuffer = rb;
                    c.pc += 2;
                }
                c.exception3ReadPrefetch(c.ir, c.pc + disp + 2);
                throw Motorola68000Core.Abort.INSTANCE;
            }
            c.pc += disp + 2;
            refill(c);
            return;
        }
        if (d8 == 0) {
            c.pc += 4;
            c.internal(2);
            refill(c);
        } else {
            c.pc += 2;
            if (!c.m68010 || d8 == -1) c.internal(2);
            c.ir = c.irc;
            c.iplNext();
            c.prefetch(2);
        }
    }

    static void bsr(Motorola68000Core c, int op) {
        final int d8 = (byte) op;
        final int disp = d8 == 0 ? (short) c.irc : d8;
        c.internal(2);
        final int oldpc = c.pc;
        final int nextpc = oldpc + (d8 == 0 ? 4 : 2);
        if ((c.r[15] & 1) != 0) {
            c.r[15] -= 4;
            c.pc += 2;
            c.exception3WriteAccess(c.ir, c.r[15], 1, oldpc, 1);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        if (c.m68010 && (disp & 1) != 0) {
            // The 68010 checks the target before it pushes the return address.
            c.pc += 2;
            c.exception3ReadPrefetchOnly(c.ir, oldpc + disp + 2);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        c.r[15] -= 4;
        final int sp = c.r[15];
        c.writeWord(sp, nextpc >>> 16);
        c.writeWord(sp + 2, nextpc);
        c.pc += disp + 2;
        if ((c.pc & 1) != 0) {
            final int addr = c.pc;
            c.pc -= 2;
            c.exception3ReadPrefetch(c.ir, addr);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        c.prefetch(0);
        c.ir = c.irc;
        c.iplNext();
        c.iplNext();
        c.prefetch(2);
    }

    static void dbcc(Motorola68000Core c, int op) {
        if (c.m68010) {
            dbcc010(c, op);
            return;
        }
        final int cc = (op >> 8) & 15;
        final int reg = op & 7;
        final int counter = (short) c.r[reg];
        final int offs = (short) c.irc;
        final int oldpc = c.pc;
        c.internal(2);
        final boolean condition = c.cond(cc);
        if (!condition) {
            c.pc += offs + 2;
            if ((offs & 1) != 0) {
                c.setD(reg, counter - 1, 2);
                c.exception3ReadPrefetch(c.ir, c.pc);
                throw Motorola68000Core.Abort.INSTANCE;
            }
            c.prefetch(0);
            if (counter != 0) {
                c.setD(reg, counter - 1, 2);
                c.ir = c.irc;
                c.iplNext();
                c.prefetch(2);
                return;
            }
        } else {
            c.internal(2);
        }
        c.pc = oldpc + 4;
        c.prefetch(0);
        c.ir = c.irc;
        if (!condition) c.setD(reg, counter - 1, 2);
        c.iplNext();
        c.prefetch(2);
    }

    /**
     * The 68010 DBcc: a taken branch back over a loopable one-word instruction (displacement
     * -4, no trace) enters the loop mode. The first pass refetches the instruction and the
     * DBcc, then each iteration runs the instruction in IRD without fetching it.
     */
    private static void dbcc010(Motorola68000Core c, int op) {
        final int cc = (op >> 8) & 15;
        final int reg = op & 7;
        final int counter = (short) c.r[reg];
        final int offs = (short) c.irc;
        final int wasLoop = c.loopMode;
        c.loopMode = 0;
        final int oldpc = c.pc;
        c.internal(2);
        final boolean condition = c.cond(cc);
        if (!condition) {
            c.pc += offs + 2;
            if ((offs & 1) != 0) {
                // The bus has no A0: the fetch of the odd target reads the word it falls in.
                c.irc = c.fetch((c.pc - 1) & ~1);
                c.setD(reg, counter - 1, 2);
                c.exception3ReadPrefetch(c.ir, c.pc);
                throw Motorola68000Core.Abort.INSTANCE;
            }
            if (offs == -4 && c.t1 == 0 && c.loopTable[c.ird] != 0) {
                loop(c, cc, reg, counter, oldpc, wasLoop);
                return;
            }
            c.prefetch(0);
            if (counter != 0) {
                c.setD(reg, counter - 1, 2);
                c.ir = c.irc;
                c.iplNext();
                c.prefetch(2);
                return;
            }
            // Unlike the 68000, the 68010 spends its two clocks when the count runs out and
            // none when the condition is true.
            c.internal(2);
        }
        c.pc = oldpc + 4;
        c.prefetch(0);
        c.ir = c.irc;
        if (!condition) c.setD(reg, counter - 1, 2);
        c.iplNext();
        c.prefetch(2);
    }

    /** One iteration of the loop mode: the counter, the instruction in IRD, continue or exit. */
    private static void loop(Motorola68000Core c, int cc, int reg, int counter, int oldpc, int wasLoop) {
        if (wasLoop == 0) {
            final int irc = c.irc;
            c.prefetch(0);
            c.prefetch(2);
            c.irc = irc;
        } else {
            c.internal(2);
        }
        c.loopMode = 1;
        c.setD(reg, counter - 1, 2);
        if (counter != 0) {
            final int ir = c.ir;
            final int ird = c.ird;
            c.ir = ird;
            c.po = 4;
            c.ops[ird].run(c, ird);
            c.ir = ir;
            c.loopEnd();
            if (!c.cond(cc) && (c.r[reg] & 0xFFFF) == 0) {
                // The condition is false and the count is spent: the loop ends here.
                c.r[reg] |= 0xFFFF;
                final int exit = c.loopMode >>> 16;
                if (exit > 0) c.internal(exit);
                c.loopMode = 0;
                refillAt(c, oldpc + 4);
                return;
            }
            if ((c.loopMode & 0xFFFE) != 0) c.internal(c.loopMode & 0xFFFE);
            c.pc = oldpc;
            c.iplNow();
            return;
        }
        c.loopMode = 0;
        refillAt(c, oldpc + 4);
    }

    private static void refillAt(Motorola68000Core c, int pc) {
        c.pc = pc;
        c.prefetch(0);
        c.ir = c.irc;
        c.iplNext();
        c.prefetch(2);
    }

    // ---------------------------------------------------------------------------------------
    // JMP, JSR
    // ---------------------------------------------------------------------------------------

    /** Target of JMP/JSR: the extension word is the one in the prefetch register. */
    private static int jumpTarget(Motorola68000Core c, int mode, int reg) {
        switch (mode) {
            case 2: return c.r[8 + reg];
            case 5: return c.r[8 + reg] + (short) c.irc;
            case 6: return c.index(c.r[8 + reg], c.irc);
            default:
                switch (reg) {
                    case 0: return (short) c.irc;
                    case 1: {
                        final int hi = c.prefetch(4);
                        return (hi << 16) | c.irc;
                    }
                    case 2: return c.pc + 2 + (short) c.irc;
                    default: return c.index(c.pc + 2, c.irc);
                }
        }
    }

    /** Internal clocks of the JMP/JSR address calculation. */
    private static int jumpClocks(int mode, int reg) {
        if (mode == 2 || (mode == 7 && reg == 1)) return 0;
        if (mode == 6 || (mode == 7 && reg == 3)) return 6;
        return 2;
    }

    private static int jumpLength(int mode, int reg) {
        if (mode == 2) return 2;
        if (mode == 7 && reg == 1) return 6;
        return 4;
    }

    static void jmp(Motorola68000Core c, int op) {
        final int mode = (op >> 3) & 7, reg = op & 7;
        final int target = jumpTarget(c, mode, reg);
        c.iplNow();
        final int clocks = jumpClocks(mode, reg);
        if (clocks > 0) c.internal(clocks);
        if ((target & 1) != 0) {
            c.pc += 2;
            c.exception3ReadPrefetchOnly(c.ir, target);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        c.pc = target;
        c.prefetch(0);
        c.ir = c.irc;
        c.prefetch(2);
    }

    static void jsr(Motorola68000Core c, int op) {
        final int mode = (op >> 3) & 7, reg = op & 7;
        final int target = jumpTarget(c, mode, reg);
        c.iplNow();
        final int oldpc = c.pc;
        final int nextpc = oldpc + jumpLength(mode, reg);
        final int clocks = jumpClocks(mode, reg);
        if (clocks > 0) c.internal(clocks);
        if ((target & 1) != 0) {
            c.pc += mode == 7 && reg == 1 ? 6 : 2;
            c.exception3ReadPrefetchOnly(c.ir, target);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        c.pc = target;
        c.prefetch(0);
        c.r[15] -= 4;
        if ((c.r[15] & 1) != 0) {
            c.pc = oldpc + jumpLength(mode, reg);
            c.exception3WriteAccess(c.ir, c.r[15], 1, c.r[15] >>> 16, 1);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        final int sp = c.r[15];
        c.writeWord(sp, nextpc >>> 16);
        c.writeWord(sp + 2, nextpc);
        c.ir = c.irc;
        c.prefetch(2);
    }

    // ---------------------------------------------------------------------------------------
    // Returns
    // ---------------------------------------------------------------------------------------

    static void rts(Motorola68000Core c, int op) {
        final int oldpc = c.pc;
        final int sp = c.r[15];
        if ((sp & 1) != 0) {
            c.pc += 2;
            c.exception3ReadAccess(c.ir, sp, 1, 1);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        int newpc = c.readWord(sp) << 16;
        newpc |= c.readWord(sp + 2);
        c.r[15] = sp + 4;
        if ((newpc & 1) != 0) {
            c.pc = oldpc + 2;
            c.exception3ReadPrefetchOnly(c.ir, newpc);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        c.pc = newpc;
        refill(c);
    }

    static void rtr(Motorola68000Core c, int op) {
        final int oldpc = c.pc;
        final int sp = c.r[15];
        if ((sp & 1) != 0) {
            c.pc += 2;
            c.exception3ReadAccess(c.ir, sp, 1, 1);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        int newpc = c.readWord(sp + 2) << 16;
        final int sr = c.readWord(sp);
        newpc |= c.readWord(sp + 4);
        c.r[15] = sp + 6;
        c.setCCR(sr);
        if ((newpc & 1) != 0) {
            c.pc = oldpc + 2;
            c.exception3ReadPrefetchOnly(c.ir, newpc);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        c.pc = newpc;
        refill(c);
    }

    static void rte(Motorola68000Core c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        final int sp = c.r[15];
        if ((sp & 1) != 0) {
            c.exception3ReadAccess(c.ir, sp, 1, 1);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        int newpc = c.readWord(sp + 2) << 16;
        final int sr = c.readWord(sp);
        newpc |= c.readWord(sp + 4);
        c.r[15] = sp + 6;
        c.setSR(sr);
        if ((newpc & 1) != 0) {
            c.pc += 2;
            c.exception3ReadAccess(c.ir | 0x20000, newpc, 1, 2);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        c.pc = newpc;
        refill(c);
    }

    // ---------------------------------------------------------------------------------------
    // Traps
    // ---------------------------------------------------------------------------------------

    static void trap(Motorola68000Core c, int op) {
        c.pc += 2;
        c.exception(32 + (op & 15));
    }

    static void trapv(Motorola68000Core c, int op) {
        if (c.m68010) {
            if (c.fv) {
                c.pc += 2;
                c.internal(2);
                c.exception(7);
                return;
            }
            c.finishNext();
            return;
        }
        c.pc += 2;
        final int ipl0 = c.ipl0, ipl1 = c.ipl1;
        c.ir = c.irc;
        c.iplNext();
        c.prefetch(2);
        if (c.fv) {
            c.ipl0 = ipl0;
            c.ipl1 = ipl1;
            c.ir = op;
            c.exception(7);
        }
    }

    static void chk(Motorola68000Core c, int op) {
        final int dn = (op >> 9) & 7, mode = (op >> 3) & 7, reg = op & 7;
        final int src = (short) c.readEa(mode, reg, 2, IPL_NONE);
        final int dst = (short) c.r[dn];
        chkFlags(c, src, dst);
        if (c.vectorOrder) {
            c.ir = c.irc;
            c.iplNext();
            c.prefetch(c.po);
            c.advance();
            if (dst > src) {
                c.exception(6);
                return;
            }
            c.internal(2);
            if (dst < 0) {
                c.exception(6);
                return;
            }
            c.internal(4);
            return;
        }
        c.internal(4);
        if (dst > src) {
            c.advance();
            c.exception(6);
            return;
        }
        c.internal(2);
        if (dst < 0) {
            c.advance();
            c.exception(6);
            return;
        }
        c.finishNext();
    }

    /** C and V cleared, Z from the register; N set below zero, cleared above the bound, else kept. */
    private static void chkFlags(Motorola68000Core c, int src, int dst) {
        c.fc = false;
        c.fv = false;
        c.fz = dst == 0;
        if (dst < 0) c.fn = true;
        else if (dst > src) c.fn = false;
    }

    // ---------------------------------------------------------------------------------------
    // LINK, UNLK
    // ---------------------------------------------------------------------------------------

    static void link(Motorola68000Core c, int op) {
        final int an = 8 + (op & 7);
        c.iplNow();
        final int olda = c.r[15] - 4;
        c.r[15] = olda;
        final int src = c.r[an];
        final int offs = (short) c.ext();
        if ((olda & 1) != 0) {
            c.r[15] += 4;
            c.r[an] = olda;
            c.pc += 6;
            c.exception3WriteAccess(c.ir, olda, 1, src >>> 16, 1);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        c.writeWord(olda, src >>> 16);
        c.writeWord(olda + 2, src);
        c.r[an] = c.r[15];
        c.r[15] += offs;
        c.prefetchLast();
        c.advance();
    }

    static void unlk(Motorola68000Core c, int op) {
        final int an = 8 + (op & 7);
        c.iplNow();
        final int src = c.r[an];
        if ((src & 1) != 0) {
            c.pc += 4;
            c.exception3ReadAccess(c.ir, src, 2, 1);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        int old = c.readWord(src) << 16;
        old |= c.readWord(src + 2);
        c.r[15] = src + 4;
        c.r[an] = old;
        c.prefetchLast();
        c.advance();
    }

    // ---------------------------------------------------------------------------------------
    // System control
    // ---------------------------------------------------------------------------------------

    static void nop(Motorola68000Core c, int op) {
        c.finishNext();
    }

    static void illegal(Motorola68000Core c, int op) {
        c.illegal(op);
    }

    static void stop(Motorola68000Core c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        final int sr = c.irc;
        c.iplNext();
        c.setSR(sr, true);
        c.internal(4);
        if (c.probe != null) c.probe.event(Motorola68000Core.Probe.STOP, 0);
        c.stopped = 1;
    }

    static void reset(Motorola68000Core c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        if (c.probe != null) c.probe.event(Motorola68000Core.Probe.RESET, 0);
        c.bus.resetInstruction();
        c.internal(128);
        c.finishNext();
    }

    static void moveToUsp(Motorola68000Core c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        c.usp = c.r[8 + (op & 7)];
        if (c.m68010) c.internal(2);
        c.finishNext();
    }

    static void moveFromUsp(Motorola68000Core c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        c.r[8 + (op & 7)] = c.usp;
        if (c.m68010) c.internal(2);
        c.finishNext();
    }

    /** ORI/ANDI/EORI to CCR (word {@code 0x003C}, {@code 0x023C}, {@code 0x0A3C}) and to SR. */
    static void immediateToSr(Motorola68000Core c, int op) {
        final boolean toSr = (op & 0x40) != 0;
        if (toSr && c.s == 0) {
            c.privilege();
            return;
        }
        // The 68010 takes the immediate from IRC and leaves the prefetch to the refill.
        int src;
        if (c.m68010) {
            src = c.irc;
            c.po += 2;
        } else {
            src = c.ext();
        }
        if (!toSr) src &= 0xFF;
        c.internal(8);
        final int sr = c.getSR();
        final int v;
        switch ((op >> 9) & 7) {
            case 0: v = sr | src; break;
            case 1: v = toSr ? sr & src : sr & (src | 0xFF00); break;
            default: v = sr ^ src; break;
        }
        c.setSR(v);
        sameInstructionRefill(c);
    }

    /** The end of an instruction that changed SR: the prefetch is refilled after it. */
    private static void sameInstructionRefill(Motorola68000Core c) {
        c.advance();
        c.po = 4;
        refill(c);
    }

    static void moveToCcr(Motorola68000Core c, int op) {
        final int src = c.readEa((op >> 3) & 7, op & 7, 2, IPL_NONE);
        c.internal(4);
        c.setCCR(src);
        sameInstructionRefill(c);
    }

    static void moveToSr(Motorola68000Core c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        final int src = c.readEa((op >> 3) & 7, op & 7, 2, IPL_NONE);
        c.internal(4);
        c.setSR(src);
        sameInstructionRefill(c);
    }

    static void moveFromSr(Motorola68000Core c, int op) {
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (c.m68010) {
            moveFromSr010(c, mode, reg);
            return;
        }
        if (mode == 0) {
            c.iplNextPre();
            c.ir = c.irc;
            c.prefetch(c.po);
            c.internal(2);
            c.setD(reg, c.getSR(), 2);
            c.advance();
            return;
        }
        final int a = c.eaAddr(mode, reg, 2);
        if ((a & 1) != 0) c.aeRead(mode, reg, a, 2, 0);
        c.iplNow();
        c.readWord(a);
        c.eaUpdate(mode, reg, 2, a);
        c.prefetchLast();
        c.writeWord(a, c.getSR());
        c.advance();
    }

    /** The 68010 MOVE from SR: privileged, and the operand is written without a read. */
    private static void moveFromSr010(Motorola68000Core c, int mode, int reg) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        if (mode == 0) {
            c.setD(reg, c.getSR(), 2);
            c.finishNext();
            return;
        }
        final int a = c.eaAddrNoRead(mode, reg, 2, 2);
        c.iplNow();
        c.eaUpdate(mode, reg, 2, a);
        c.prefetchLast();
        if ((a & 1) != 0) {
            c.pc += c.po;
            c.exception3Write(c.ir, a, 1, c.getSR(), 1);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        c.writeWord(a, c.getSR());
        c.advance();
    }
}
