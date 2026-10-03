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

package io.github.zolfrik.amija.cpu;

/**
 * Abstract base class for all M68k instructions.
 * Provides helper methods for common instruction tasks like EA calculation,
 * pipeline management, and timing gaps.
 */
public abstract class AbstractM68kInstruction {

    // --- Step Management ---

    protected int getStep(Motorola68000 cpu) {
        return cpu.getInstructionStep();
    }

    protected void setStep(Motorola68000 cpu, int step) {
        cpu.setInstructionStep(step);
    }

    protected void nextStep(Motorola68000 cpu) {
        cpu.incrementInstructionStep();
    }

    // --- TempReg Management ---

    protected int getTemp(Motorola68000 cpu, int index) {
        return cpu.getTempReg(index);
    }

    protected void setTemp(Motorola68000 cpu, int index, int value) {
        cpu.setTempReg(index, value);
    }

    // --- Timing Gaps ---

    /**
     * Standard Yacht setup gap (4 clock cycles).
     */
    protected void setupGap4(Motorola68000 cpu) {
        cpu.idleCycles(4);
    }

    /**
     * Indexed addressing mode gap (2 clock cycles).
     */
    protected void indexedGap2(Motorola68000 cpu) {
        cpu.idleCycles(2);
    }

    // --- Pipeline Management ---

    public boolean shouldInhibitPrefetch() {
        return false;
    }

    // WHERE THE IPL PIN IS SAMPLED: if this instruction has a point of its own, it declares
    // it by calling cpu.markIplAnchor(...) IN THE RIGHT STEP; otherwise the default applies.
    // The model is written once, in Motorola68000.markIplAnchor/pollIpl.

    public void prepareInstruction(Motorola68000 cpu) {
        cpu.consumeIRC();
        if (!shouldInhibitPrefetch()) {
            cpu.startPrefetchCycle();
        }
    }

    /**
     * Helper to fetch an extension word from the pipeline.
     * Consumes IRC, moves it to IRD, and optionally starts a new prefetch.
     * @return true if successful, false if IRD is not valid (waiting for bus).
     */
    protected boolean fetchExtension(Motorola68000 cpu, int tempRegIndex) {
        if (!cpu.isIRDValid()) {
            if (shouldInhibitPrefetch()) {
                cpu.startPrefetchCycle();
            }
            return false;
        }
        cpu.setTempReg(tempRegIndex, cpu.getIRD() & 0xFFFF);
        cpu.consumeIRC();
        // If the instruction inhibits prefetch, it might need to start it manually
        // if it wants to overlap with internal cycles.
        if (shouldInhibitPrefetch()) {
            cpu.startPrefetchCycle();
        }
        return true;
    }

    /**
     * Helper for final pipeline refill (np np sequence in Yacht).
     */
    protected void refillPipeline(Motorola68000 cpu) {
        cpu.startPrefetchCycle();
    }

    // --- Effective Address Calculation ---

    /**
     * Calculates the Effective Address for a given mode and register.
     * This version assumes extensions are already fetched and passed as parameters.
     *
     * @param cpu The CPU instance.
     * @param mode Addressing mode.
     * @param reg Register number.
     * @param size Size of the operation (1, 2, or 4).
     * @param basePC The PC to use for PC-relative addressing.
     * @param ext1 First extension word (if applicable).
     * @param ext2 Second extension word (if applicable).
     * @param updateAn Whether to update An for pre-decrement/post-increment modes.
     * @return The calculated Effective Address.
     */
    protected int calculateEA(Motorola68000 cpu, int mode, int reg, int size, int basePC, int ext1, int ext2, boolean updateAn) {
        return calculateEA(cpu, mode, reg, size, basePC, ext1, ext2, updateAn, true);
    }

    protected int calculateEA(Motorola68000 cpu, int mode, int reg, int size, int basePC, int ext1, int ext2, boolean updateAn, boolean includeGap) {
        // The EA just computed is the one on which a possible address error will fire, and its
        // mode decides the PC offset in the group 0 frame
        // (Motorola68000.group0DataFaultPcOffset). The instructions that fail on the STACK
        // instead of the EA (PEA, JSR, LINK, BSR) declare it themselves.
        cpu.noteGroup0Ea(mode, reg, size);
        int inc = (reg == 7 && size == 1) ? 2 : size;
        return switch (mode) {
            case 2 -> cpu.getA(reg, 4);
            case 3 -> {
                int addr = cpu.getA(reg, 4);
                if (updateAn) cpu.setA(reg, addr + inc);
                yield addr;
            }
            case 4 -> {
                int addr = cpu.getA(reg, 4) - inc;
                // 68000 Quirk: -(An) IS updated even if Address Error occurs
                if (updateAn) cpu.setA(reg, addr);
                yield addr;
            }
            case 5 -> cpu.getA(reg, 4) + (short) ext1;
            case 6 -> cpu.getA(reg, 4) + calculateIndexedAddress(cpu, ext1, includeGap);
            case 7 -> switch (reg) {
                case 0 -> (short) ext1;
                case 1 -> (ext1 << 16) | (ext2 & 0xFFFF);
                case 2 -> basePC + (short) ext1;
                case 3 -> basePC + calculateIndexedAddress(cpu, ext1, includeGap);
                default -> 0;
            };
            default -> 0;
        };
    }

    protected int calculateIndexedAddress(Motorola68000 cpu, int ext) {
        return calculateIndexedAddress(cpu, ext, true);
    }

    protected int calculateIndexedAddress(Motorola68000 cpu, int ext, boolean includeGap) {
        int isA = (ext >> 15) & 1, regIdx = (ext >> 12) & 7, isLong = (ext >> 11) & 1, disp8 = (byte) (ext & 0xFF);
        int index = (isA == 1) ? cpu.getA(regIdx, 4) : cpu.getD(regIdx, 4);
        if (isLong == 0) index = (short) (index & 0xFFFF);
        if (includeGap) indexedGap2(cpu);
        return index + disp8;
    }

    // --- Arithmetic Helpers ---

    protected int calculateAdd(Motorola68000 cpu, int src, int dst, int size) {
        long s, d, r, msbMask, sizeMask;
        if (size == 1) {
            s = src & 0xFFL; d = dst & 0xFFL; msbMask = 0x80L; sizeMask = 0xFFL;
        } else if (size == 2) {
            s = src & 0xFFFFL; d = dst & 0xFFFFL; msbMask = 0x8000L; sizeMask = 0xFFFFL;
        } else {
            s = src & 0xFFFFFFFFL; d = dst & 0xFFFFFFFFL; msbMask = 0x80000000L; sizeMask = 0xFFFFFFFFL;
        }
        r = s + d;
        int res = (int) (r & sizeMask);
        boolean sm = (s & msbMask) != 0, dm = (d & msbMask) != 0, rm = (res & msbMask) != 0;
        boolean carry = r > sizeMask;
        boolean overflow = (sm && dm && !rm) || (!sm && !dm && rm);
        int newSR = cpu.getSR() & 0xFFE0;
        if (carry) newSR |= 0x11;
        if (overflow) newSR |= 0x02;
        if (rm) newSR |= 0x08;
        if (res == 0) newSR |= 0x04;
        cpu.writeSR(newSR, false);
        return res;
    }

    protected int calculateSub(Motorola68000 cpu, int src, int dst, int size) {
        long s, d, r, msbMask, sizeMask;
        if (size == 1) {
            s = src & 0xFFL; d = dst & 0xFFL; msbMask = 0x80L; sizeMask = 0xFFL;
        } else if (size == 2) {
            s = src & 0xFFFFL; d = dst & 0xFFFFL; msbMask = 0x8000L; sizeMask = 0xFFFFL;
        } else {
            s = src & 0xFFFFFFFFL; d = dst & 0xFFFFFFFFL; msbMask = 0x80000000L; sizeMask = 0xFFFFFFFFL;
        }
        r = d - s;
        int res = (int) (r & sizeMask);
        boolean sm = (s & msbMask) != 0, dm = (d & msbMask) != 0, rm = (res & msbMask) != 0;
        boolean carry = (d < s);
        boolean overflow = (!sm && dm && !rm) || (sm && !dm && rm);
        int newSR = cpu.getSR() & 0xFFE0;
        if (carry) newSR |= 0x11;
        if (overflow) newSR |= 0x02;
        if (rm) newSR |= 0x08;
        if (res == 0) newSR |= 0x04;
        cpu.writeSR(newSR, false);
        return res;
    }

    protected int calculateAddx(Motorola68000 cpu, int src, int dst, int size) {
        long s, d, r, msbMask, sizeMask;
        int x = cpu.x ? 1 : 0;
        if (size == 1) {
            s = src & 0xFFL; d = dst & 0xFFL; msbMask = 0x80L; sizeMask = 0xFFL;
        } else if (size == 2) {
            s = src & 0xFFFFL; d = dst & 0xFFFFL; msbMask = 0x8000L; sizeMask = 0xFFFFL;
        } else {
            s = src & 0xFFFFFFFFL; d = dst & 0xFFFFFFFFL; msbMask = 0x80000000L; sizeMask = 0xFFFFFFFFL;
        }
        r = s + d + x;
        int res = (int) (r & sizeMask);
        boolean sm = (s & msbMask) != 0, dm = (d & msbMask) != 0, rm = (res & msbMask) != 0;
        boolean carry = r > sizeMask;
        boolean overflow = (sm && dm && !rm) || (!sm && !dm && rm);
        int newSR = cpu.getSR() & 0xFFE0;
        if (carry) newSR |= 0x11;
        if (overflow) newSR |= 0x02;
        if (rm) newSR |= 0x08;
        // Z is persistent
        if (res != 0) newSR &= ~0x04; else newSR |= (cpu.z ? 0x04 : 0);
        cpu.writeSR(newSR, false);
        return res;
    }

    protected int calculateSubx(Motorola68000 cpu, int src, int dst, int size) {
        long s, d, r, msbMask, sizeMask;
        int x = cpu.x ? 1 : 0;
        if (size == 1) {
            s = src & 0xFFL; d = dst & 0xFFL; msbMask = 0x80L; sizeMask = 0xFFL;
        } else if (size == 2) {
            s = src & 0xFFFFL; d = dst & 0xFFFFL; msbMask = 0x8000L; sizeMask = 0xFFFFL;
        } else {
            s = src & 0xFFFFFFFFL; d = dst & 0xFFFFFFFFL; msbMask = 0x80000000L; sizeMask = 0xFFFFFFFFL;
        }
        r = d - s - x;
        int res = (int) (r & sizeMask);
        boolean sm = (s & msbMask) != 0, dm = (d & msbMask) != 0, rm = (res & msbMask) != 0;
        // Carry for SUB: s + x > d (unsigned)
        boolean carry = ((d - s - x) < 0);
        // Overflow: signs are opposite and result sign is different from destination sign
        boolean overflow = (!sm && dm && !rm) || (sm && !dm && rm);
        int newSR = cpu.getSR() & 0xFFE0;
        if (carry) newSR |= 0x11;
        if (overflow) newSR |= 0x02;
        if (rm) newSR |= 0x08;
        // Z is persistent
        if (res != 0) newSR &= ~0x04; else newSR |= (cpu.z ? 0x04 : 0);
        cpu.writeSR(newSR, false);
        return res;
    }

    // --- Logical Helpers ---

    protected int calculateAnd(Motorola68000 cpu, int src, int dst, int size) {
        int res = src & dst;
        int msbMask = (size == 1) ? 0x80 : (size == 2) ? 0x8000 : 0x80000000;
        int sizeMask = (size == 1) ? 0xFF : (size == 2) ? 0xFFFF : 0xFFFFFFFF;
        res &= sizeMask;
        int newSR = cpu.getSR() & 0xFFF0; // Clear N,Z,V,C. Keep X!
        if ((res & msbMask) != 0) newSR |= 0x08;
        if (res == 0) newSR |= 0x04;
        cpu.writeSR(newSR, false);
        return res;
    }

    protected int calculateOr(Motorola68000 cpu, int src, int dst, int size) {
        int res = src | dst;
        int msbMask = (size == 1) ? 0x80 : (size == 2) ? 0x8000 : 0x80000000;
        int sizeMask = (size == 1) ? 0xFF : (size == 2) ? 0xFFFF : 0xFFFFFFFF;
        res &= sizeMask;
        int newSR = cpu.getSR() & 0xFFF0; // Clear N,Z,V,C. Keep X!
        if ((res & msbMask) != 0) newSR |= 0x08;
        if (res == 0) newSR |= 0x04;
        cpu.writeSR(newSR, false);
        return res;
    }

    protected int calculateEor(Motorola68000 cpu, int src, int dst, int size) {
        int res = src ^ dst;
        int msbMask = (size == 1) ? 0x80 : (size == 2) ? 0x8000 : 0x80000000;
        int sizeMask = (size == 1) ? 0xFF : (size == 2) ? 0xFFFF : 0xFFFFFFFF;
        res &= sizeMask;
        int newSR = cpu.getSR() & 0xFFF0; // Clear N,Z,V,C. Keep X!
        if ((res & msbMask) != 0) newSR |= 0x08;
        if (res == 0) newSR |= 0x04;
        cpu.writeSR(newSR, false);
        return res;
    }

    protected int calculateCmp(Motorola68000 cpu, int src, int dst, int size) {
        long s, d, r, msbMask, sizeMask;
        if (size == 1) {
            s = src & 0xFFL; d = dst & 0xFFL; msbMask = 0x80L; sizeMask = 0xFFL;
        } else if (size == 2) {
            s = src & 0xFFFFL; d = dst & 0xFFFFL; msbMask = 0x8000L; sizeMask = 0xFFFFL;
        } else {
            s = (long)src & 0xFFFFFFFFL; d = (long)dst & 0xFFFFFFFFL; msbMask = 0x80000000L; sizeMask = 0xFFFFFFFFL;
        }
        r = (d - s) & sizeMask;
        boolean sm = (s & msbMask) != 0;
        boolean dm = (d & msbMask) != 0;
        boolean rm = (r & msbMask) != 0;

        // Carry (C): set if s > d (unsigned)
        boolean carry = (s > d);
        // Overflow (V): set if operands have different signs AND result sign differs from destination
        boolean overflow = (sm != dm) && (rm != dm);
        // Negative (N): set if result is negative (MSB set)
        boolean negative = rm;
        // Zero (Z): set if result is 0
        boolean zero = (r == 0);

        int newSR = cpu.getSR() & 0xFFF0; // CMP affects N, Z, V, C. Keep X!
        if (negative) newSR |= 0x08;
        if (zero)     newSR |= 0x04;
        if (overflow) newSR |= 0x02;
        if (carry)    newSR |= 0x01;
        cpu.writeSR(newSR, false);
        return (int)r;
    }

    // --- Condition Testing ---

    protected boolean testCondition(Motorola68000 cpu, int cond) {
        return switch (cond) {
            case 0 -> true; // T / BRA
            case 1 -> false; // F / BSR uses condition 1 but handled specially
            case 2 -> !cpu.c && !cpu.z; // HI
            case 3 -> cpu.c || cpu.z; // LS
            case 4 -> !cpu.c; // CC
            case 5 -> cpu.c; // CS
            case 6 -> !cpu.z; // NE
            case 7 -> cpu.z; // EQ
            case 8 -> !cpu.v; // VC
            case 9 -> cpu.v; // VS
            case 10 -> !cpu.n; // PL
            case 11 -> cpu.n; // MI
            case 12 -> (cpu.n && cpu.v) || (!cpu.n && !cpu.v); // GE
            case 13 -> (cpu.n && !cpu.v) || (!cpu.n && cpu.v); // LT
            case 14 -> ((cpu.n && cpu.v) || (!cpu.n && !cpu.v)) && !cpu.z; // GT
            case 15 -> cpu.z || (cpu.n && !cpu.v) || (!cpu.n && cpu.v); // LE
            default -> false;
        };
    }

    // --- Instruction Finalization ---

    /**
     * Standard instruction finalization.
     * Sets the instruction finished flag immediately.
     * If there are pending internal cycles, the core will process them before starting the next instruction.
     * @return true.
     */
    protected boolean finalizeInstruction(Motorola68000 cpu) {
        cpu.setInstructionFinished(true);
        return true;
    }

    /**
     * Finalization that prevents the next instruction from starting in the same cycle.
     * Use this for instructions that have issues with back-to-back execution in tests.
     * @return false.
     */
    protected boolean finalizeInstructionSafe(Motorola68000 cpu) {
        cpu.setInstructionFinished(true);
        cpu.setRisingEdgeConsumed(true);
        return false;
    }

    // --- Helpers ---

    protected boolean hasExt1(int mode, int reg) {
        return mode == 5 || mode == 6 || (mode == 7 && reg <= 4);
    }

    protected boolean hasExt2(int mode, int reg, int size) {
        return (mode == 7 && reg == 1) || (mode == 7 && (reg == 4 && size == 4));
    }
    
    public abstract boolean executeStep(Motorola68000 cpu);
    
    public abstract String getDisassembly(Motorola68000 cpu);
}

