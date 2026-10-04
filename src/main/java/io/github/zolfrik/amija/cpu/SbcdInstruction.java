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

public abstract class SbcdInstruction extends AbstractM68kInstruction {
    public static final SbcdInstruction INSTANCE = new RegisterSbcd();
    public static final SbcdInstruction MEMORY_INSTANCE = new MemorySbcd();

    @Override
    public abstract boolean executeStep(AbstractMotorola cpu);

    protected void calculateSbcd(AbstractMotorola cpu, int src, int dst) {
        int x = cpu.x ? 1 : 0;
        int binaryDiff = (dst - src - x) & 0xFF;
        int t = (dst & 0x0F) - (src & 0x0F) - x;
        int res = dst - src - x;
        boolean highBorrow = (res < 0);

        if (t < 0) res -= 6;
        boolean carry = (res < 0);
        if (highBorrow) res -= 0x60;

        int resultByte = res & 0xFF;

        // V flag quirk: (binaryDiff & (~resultByte) & 0x80) != 0
        cpu.v = (binaryDiff & (~resultByte) & 0x80) != 0;
        cpu.x = cpu.c = carry;

        // Z flag is persistent
        if (resultByte != 0) cpu.z = false;

        // N is bit 7 of result
        cpu.n = (resultByte & 0x80) != 0;

        setTemp(cpu, 2, resultByte);
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        if ((cpu.getIr() & 0x8) == 0) {
            return String.format("SBCD D%d, D%d", cpu.getIr() & 0x7, (cpu.getIr() >> 9) & 0x7);
        } else {
            return String.format("SBCD -(A%d), -(A%d)", cpu.getIr() & 0x7, (cpu.getIr() >> 9) & 0x7);
        }
    }

    public static class RegisterSbcd extends SbcdInstruction {
        @Override
        public boolean executeStep(AbstractMotorola cpu) {
            while (true) {
                if (cpu.isRisingEdgeConsumed()) return false;
                switch (getStep(cpu)) {
                    case 0:
                        // Yacht np n
                        refillPipeline(cpu);
                        nextStep(cpu);
                        return false;
                    case 1:
                        if (!cpu.isBusCycleIdle()) return false;
                        calculateSbcd(cpu, cpu.getD(cpu.getIr() & 0x7, 1), cpu.getD((cpu.getIr() >> 9) & 0x7, 1));
                        cpu.setD((cpu.getIr() >> 9) & 0x7, getTemp(cpu, 2), 1);
                        // NOTE (Phase 4): terminal internal-idle cycles; finalizing here breaks
                        // the JSON 'n' count, so the +1 seam is left as a documented residual.
                        cpu.idleCycles(2);
                        nextStep(cpu);
                        return false;
                    case 2:
                        return finalizeInstructionSafe(cpu);
                }
            }
        }

        @Override
        public boolean shouldInhibitPrefetch() {
            return true;
        }
    }

    public static class MemorySbcd extends SbcdInstruction {
        @Override
        public boolean executeStep(AbstractMotorola cpu) {
            int regX = (cpu.getIr() >> 9) & 0x7;
            int regY = cpu.getIr() & 0x7;

            while (true) {
                if (cpu.isRisingEdgeConsumed()) return false;
                switch (getStep(cpu)) {
                    case 0:
                        // Yacht n nr nr np nw
                        // n - execution start idle (2 cycles)
                        indexedGap2(cpu);
                        nextStep(cpu);
                        continue;
                    case 1:
                        // nr - read source
                        int decY = (regY == 7) ? 2 : 1;
                        cpu.setA(regY, cpu.getA(regY, 4) - decY);
                        cpu.scheduleRead(cpu.getA(regY, 4), 1);
                        nextStep(cpu);
                        return false;
                    case 2:
                        // nr - read destination
                        setTemp(cpu, 1, cpu.getLatchedByte());
                        int decX = (regX == 7) ? 2 : 1;
                        cpu.setA(regX, cpu.getA(regX, 4) - decX);
                        // ($8108/$C108): the `next` sample sits here, before the destination
                        // read. The position is the default's, the branch is not: `next` instead
                        // of `now`.
                        cpu.markIplAnchor(AbstractMotorola.IplAnchor.NEXT);
                        cpu.scheduleRead(cpu.getA(regX, 4), 1);
                        nextStep(cpu);
                        return false;
                    case 3:
                        // np - prefetch next word and ALU
                        setTemp(cpu, 0, cpu.getLatchedByte());
                        calculateSbcd(cpu, getTemp(cpu, 1), getTemp(cpu, 0));
                        refillPipeline(cpu);
                        nextStep(cpu);
                        return false;
                    case 4:
                        // nw - write result
                        if (!cpu.isBusCycleIdle()) return false;
                        cpu.scheduleWrite(cpu.getA(regX, 4), getTemp(cpu, 2), 1);
                        return finalizeInstructionSafe(cpu);
                }
            }
        }

        @Override
        public boolean shouldInhibitPrefetch() {
            return true;
        }
    }
}
