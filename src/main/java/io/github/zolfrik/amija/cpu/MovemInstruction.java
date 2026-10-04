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

import io.github.zolfrik.amija.cpu.AbstractMotorola.OpPurpose;

public class MovemInstruction extends AbstractM68kInstruction {
    public static final MovemInstruction INSTANCE = new MovemInstruction();
    private MovemInstruction() {}

    private static final int MASK_VAL = 0;
    private static final int CURRENT_ADDR = 1;
    private static final int BIT_IDX = 2;
    private static final int WORD_IN_REG = 3;
    private static final int BASE_REG_VAL = 4;
    private static final int EA_EXT1 = 5;
    private static final int EA_EXT2 = 6;

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int dr = (op >> 10) & 1, sz = (op >> 6) & 1, mode = (op >> 3) & 7, reg = op & 7;

        switch (getStep(cpu)) {
            case 0: // Fetch Mask
                if (!cpu.isIRDValid()) { cpu.startPrefetchCycle(); return false; }
                setTemp(cpu, MASK_VAL, cpu.getIRD());
                cpu.consumeIRD();
                cpu.startPrefetchCycle();
                nextStep(cpu);
                return executeStep(cpu);

            case 1: // Fetch Ext 1
                if (hasEAExt1(mode, reg)) {
                    if (!cpu.isIRDValid()) return false;
                    if (mode == 6 || (mode == 7 && reg == 3)) {
                        indexedGap2(cpu);
                        setStep(cpu, 10); return false;
                    }
                    setTemp(cpu, EA_EXT1, cpu.getIRD());
                    cpu.consumeIRD();
                    cpu.startPrefetchCycle();
                    nextStep(cpu);
                    return executeStep(cpu);
                }
                nextStep(cpu);
                return executeStep(cpu);

            case 2: // Fetch Ext 2
                if (hasEAExt2(mode, reg)) {
                    if (!cpu.isIRDValid()) return false;
                    setTemp(cpu, EA_EXT2, cpu.getIRD());
                    cpu.consumeIRD();
                    cpu.startPrefetchCycle();
                    nextStep(cpu);
                    return executeStep(cpu);
                }
                nextStep(cpu);
                return executeStep(cpu);

            case 3: // EA Setup
                if (!cpu.isBusCycleIdle()) return false;
                int addr = calculateMovemEA(cpu, mode, reg);
                setTemp(cpu, BASE_REG_VAL, cpu.getA(reg, 4));

                // On 68000, Address Error for MOVEM pre-decrement happens on the decremented address.
                int firstAddr = (mode == 4) ? (addr - 2) : addr;

                // Register Update Quirk during EA setup for MOVEM:
                // 1. (An)+ mode: The register is incremented by 2 BEFORE the Address Error check.
                //    Verified by official JSON test "4cd8 [MOVEM.l (A0)+, #] 15" where A0 is updated.
                //    A7 is special and is NOT updated to maintain stack alignment for the exception frame.
                if (mode == 3 && reg < 7) {
                    cpu.setA(reg, addr + 2);
                }

                // 2. -(An) mode: The register is NOT updated if an Address Error occurs during EA calculation.
                //    Verified by official JSON test "48e5 [MOVEM.l #, -(A5)] 21" where A5 stays at initial value.
                //    The reported fault address is the decremented address (firstAddr).

                if ((firstAddr & 1) != 0) {
                    // MOVEM declares its own group 0 PC offset: the general rule
                    // (AbstractMotorola.group0DataFaultPcOffset) does not apply here. The fault
                    // branch always carries two bytes more than the mask word already counted,
                    // except MOVEM to registers with an INDEXED mode, where it is two less
                    // ($4CB0/$4CBB: +4, against +8 for $4CA8 (d16,An)).
                    boolean indexedLoad = dr == 1 && (mode == 6 || (mode == 7 && reg == 3));
                    cpu.noteGroup0Ea(mode, reg, sz == 1 ? 4 : 2);
                    cpu.markGroup0EaDelta(indexedLoad && !cpu.isM68010() ? -2 : 2);
                    if (dr == 0) noteAbortedStore(cpu, getTemp(cpu, MASK_VAL) & 0xFFFF, mode, sz == 1);
                    cpu.triggerAddressError(firstAddr, dr == 0, cpu.currentFCForData(), false);
                    return finalizeInstructionSafe(cpu);
                }
                setTemp(cpu, CURRENT_ADDR, addr);
                setTemp(cpu, BIT_IDX, 0); setTemp(cpu, WORD_IN_REG, 0);
                nextStep(cpu);
                return executeStep(cpu);

            case 4: // Transfer Loop
                if (!cpu.isBusCycleIdle()) return false;
                int mask = getTemp(cpu, MASK_VAL) & 0xFFFF;
                int bitIdx = getTemp(cpu, BIT_IDX);
                while (bitIdx < 16 && (mask & (1 << bitIdx)) == 0) bitIdx++;

                if (bitIdx < 16) {
                    setTemp(cpu, BIT_IDX, bitIdx);
                    int curAddr = getTemp(cpu, CURRENT_ADDR);
                    if (dr == 1) { // Load
                        cpu.scheduleReadWord(curAddr, OpPurpose.DATA_READ);
                        setStep(cpu, 5);
                        return false;
                    } else { // Store
                        int rIdx = getRegisterIndex(bitIdx, mode);
                        int rVal = getReg(cpu, rIdx);
                        if (sz == 0) {
                            int val = rVal & 0xFFFF;
                            if (mode == 4) curAddr -= 2;
                            cpu.scheduleWriteWord(curAddr, val);
                            if (mode != 4) curAddr += 2;
                            setTemp(cpu, BIT_IDX, bitIdx + 1);
                        } else {
                            if (mode == 4) {
                                if (getTemp(cpu, WORD_IN_REG) == 0) {
                                    cpu.scheduleWriteWord(curAddr - 2, rVal & 0xFFFF);
                                    curAddr -= 2; setTemp(cpu, WORD_IN_REG, 1);
                                } else {
                                    cpu.scheduleWriteWord(curAddr - 2, (rVal >>> 16) & 0xFFFF);
                                    curAddr -= 2; setTemp(cpu, WORD_IN_REG, 0); setTemp(cpu, BIT_IDX, bitIdx + 1);
                                }
                            } else {
                                if (getTemp(cpu, WORD_IN_REG) == 0) {
                                    cpu.scheduleWriteWord(curAddr, (rVal >>> 16) & 0xFFFF);
                                    curAddr += 2; setTemp(cpu, WORD_IN_REG, 1);
                                } else {
                                    cpu.scheduleWriteWord(curAddr, rVal & 0xFFFF);
                                    curAddr += 2; setTemp(cpu, WORD_IN_REG, 0); setTemp(cpu, BIT_IDX, bitIdx + 1);
                                }
                            }
                        }
                        setTemp(cpu, CURRENT_ADDR, curAddr);
                        return false;
                    }
                } else {
                    setStep(cpu, (dr == 1) ? 8 : 9); // Overshoot for both .w and .l Load
                    return executeStep(cpu);
                }

            case 5: // Finish Read
                if (!cpu.isBusCycleIdle()) return false;
                int val = cpu.getLatchedBusData() & 0xFFFF;
                int bIdx = getTemp(cpu, BIT_IDX), rIdxL = getRegisterIndex(bIdx, mode);
                if (sz == 0) {
                    setReg(cpu, rIdxL, (int)(short)val);
                    setTemp(cpu, BIT_IDX, bIdx + 1);
                    setTemp(cpu, CURRENT_ADDR, getTemp(cpu, CURRENT_ADDR) + 2);
                } else {
                    if (getTemp(cpu, WORD_IN_REG) == 0) {
                        setReg(cpu, rIdxL, val << 16);
                        setTemp(cpu, WORD_IN_REG, 1);
                        setTemp(cpu, CURRENT_ADDR, getTemp(cpu, CURRENT_ADDR) + 2);
                    } else {
                        setReg(cpu, rIdxL, (getReg(cpu, rIdxL) & 0xFFFF0000) | val);
                        setTemp(cpu, WORD_IN_REG, 0);
                        setTemp(cpu, BIT_IDX, bIdx + 1);
                        setTemp(cpu, CURRENT_ADDR, getTemp(cpu, CURRENT_ADDR) + 2);
                    }
                }
                setStep(cpu, 4);
                return executeStep(cpu);

            case 8: // Overshoot (Load only)
                if (!cpu.isBusCycleIdle()) return false;
                cpu.scheduleReadWord(getTemp(cpu, CURRENT_ADDR), OpPurpose.DATA_READ);
                // Extra read does not increment An on successful MOVEM completion
                nextStep(cpu);
                return false;

            case 9: // Final refill
                if (!cpu.isBusCycleIdle()) return false;
                if (mode == 3 || mode == 4) cpu.setA(reg, getTemp(cpu, CURRENT_ADDR));
                // The anchor is placed right before the final prefetch, so the transfers do not
                // move it. Except memory -> register with (An)+, which declared it at the start
                // of the instruction (prepareInstruction) and must not be overwritten here.
                if (!(dr == 1 && mode == 3)) {
                    cpu.markIplAnchor(AbstractMotorola.IplAnchor.NEXT);
                }
                cpu.startPrefetchCycle(); // Refill IRC
                return finalizeInstructionSafe(cpu);

            case 10: // Indexed Gap handler
                if (!cpu.isIRDValid()) return false;
                setTemp(cpu, EA_EXT1, cpu.getIRD());
                cpu.consumeIRD();
                cpu.startPrefetchCycle();
                setStep(cpu, 2);
                return executeStep(cpu);

            default:
                return finalizeInstruction(cpu);
        }
    }

    private int calculateMovemEA(AbstractMotorola cpu, int mode, int reg) {
        int ext1 = getTemp(cpu, EA_EXT1), pc = cpu.getOpcodeAddress();
        return switch (mode) {
            case 2, 3, 4 -> cpu.getA(reg, 4);
            case 5 -> cpu.getA(reg, 4) + (short) ext1;
            case 6 -> cpu.getA(reg, 4) + calculateIndexedMovem(cpu, ext1);
            case 7 -> switch (reg) {
                case 0 -> (short) ext1;
                case 1 -> (ext1 << 16) | (getTemp(cpu, EA_EXT2) & 0xFFFF);
                case 2 -> (cpu.getOpcodeAddress() + 4) + (short) ext1;
                case 3 -> (cpu.getOpcodeAddress() + 4) + calculateIndexedMovem(cpu, ext1);
                default -> 0;
            };
            default -> 0;
        };
    }

    private int calculateIndexedMovem(AbstractMotorola cpu, int ext) {
        int isA = (ext >> 15) & 1, rIdx = (ext >> 12) & 7, isL = (ext >> 11) & 1, d8 = (byte) (ext & 0xFF);
        int idx = (isA == 1) ? cpu.getA(rIdx, 4) : cpu.getD(rIdx, 4);
        if (isL == 0) idx = (short) (idx & 0xFFFF);
        return idx + d8;
    }

    private int getRegisterIndex(int bit, int mode) { return (mode == 4) ? 15 - bit : bit; }
    private int getReg(AbstractMotorola cpu, int r) { return (r < 8) ? cpu.getD(r, 4) : cpu.getA(r - 8, 4); }
    private void setReg(AbstractMotorola cpu, int r, int v) { if (r < 8) cpu.setD(r, v, 4); else cpu.setA(r - 8, v); }
    private boolean hasEAExt1(int m, int r) { return m == 5 || m == 6 || (m == 7 && r <= 3); }
    private boolean hasEAExt2(int m, int r) { return m == 7 && r == 1; }
    @Override public boolean shouldInhibitPrefetch() { return true; }

    /**
     * Only <b>memory -> register with {@code (An)+}</b> anchors at the <b>start of the
     * instruction</b> ($4C98/$4CD8: a {@code now} sample before even the prefetch of the mask),
     * while every other form ({@code (An)}, {@code (d16,An)}, and every register -> memory) puts
     * a {@code next} sample before the final prefetch (case 9). The condition is exactly that,
     * not a generalisation: $4C90 ({@code (An)}) anchors at the final prefetch like the others.
     */
    @Override
    public void prepareInstruction(AbstractMotorola cpu) {
        int op = cpu.getIr();
        if (((op >> 10) & 1) == 1 && ((op >> 3) & 7) == 3) {
            cpu.markIplAnchor(AbstractMotorola.IplAnchor.NOW);
        }
        super.prepareInstruction(cpu);
    }

    /**
     * A store aborted by an odd address leaves the first register of the transfer in the
     * data output buffer (its high word for a long store, the low word in predecrement
     * mode, which walks the registers backwards) and, for the register-indirect modes, the
     * register mask in the data input buffer. Both are stacked by the 68010 fault frame.
     */
    private static void noteAbortedStore(AbstractMotorola cpu, int mask, int mode, boolean isLong) {
        if (mask == 0) return;
        int b = Integer.numberOfTrailingZeros(mask);
        int value;
        if (mode == 4) {
            value = b < 8 ? cpu.getA(7 - b, 4) : cpu.getD(15 - b, 4);
        } else {
            value = b < 8 ? cpu.getD(b, 4) : cpu.getA(b - 8, 4);
            if (isLong) value >>>= 16;
        }
        cpu.writeBuffer = value & 0xFFFF;
        if (mode == 2 || mode == 3 || mode == 4) cpu.readBuffer = mask;
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int dr = (op >> 10) & 1, sz = (op >> 6) & 1, mode = (op >> 3) & 7, reg = op & 7;
        int pc = cpu.getOpcodeAddress();
        int mask = cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), ext1 = cpu.extensionWordAt(cpu.getOpcodeAddress() + 4), ext2 = cpu.extensionWordAt(cpu.getOpcodeAddress() + 6);
        String suffix = (sz == 1) ? ".l" : ".w", list = (mode == 4) ? Disassembly.formatMovemListPreDec(mask) : Disassembly.formatMovemList(mask);
        String ea = Disassembly.formatEA(mode, reg, (sz == 1 ? 4 : 2), ext1, ext2);
        return "MOVEM" + suffix + " " + (dr == 1 ? ea + ", " + list : list + ", " + ea);
    }
}

