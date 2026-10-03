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

package io.github.zolfrik.amija.common;

/**
 * The pins of the MOS 8520.
 *
 * Handles the two 8-bit I/O ports (PA0-PA7, PB0-PB7) and the special pins:
 *   - FLAG : a falling edge raises an interrupt (bit 4 of the ICR)
 *   - TOD  : a rising edge increments the Time-of-Day counter
 *   - CNT  : clock of the serial port (input or output depending on CRA bit 6)
 *   - SP   : serial data (input or output depending on CRA bit 6)
 *   - IRQ  : interrupt line to the CPU (open drain, active LOW)
 *
 * <h2>Port model (PERF)</h2>
 * The 16 lines of ports A/B need neither edge detection nor open-drain logic: they only
 * serve to read/write PRA/PRB. So they are NOT modelled as pin objects (which would cost
 * ~16 virtual calls + a loop at every access) but as three bit masks per port:
 *   - {@code drive} : value the chip emits (PR register), bit i = pin i
 *   - {@code ddr}   : direction (1 = output, 0 = input)
 *   - {@code ext}   : level imposed by the external hardware
 * The physical state of a pin is therefore {@code (drive & ddr) | (ext & ~ddr)} on all 8
 * bits in a single expression, with no loop.
 *
 * The 5 special pins stay {@link CIAPin} objects because they need edge detection
 * (FLAG/CNT/TOD) and open-drain logic (IRQ/SP/CNT).
 *
 * Timer override on PB6/PB7:
 *   When PBON is on, the timer drives the pin. This is handled by MOS8520.updatePB(), which
 *   folds the timer level into the value written with writePortData; the per-pin override
 *   flag ({@code bTimerOvr}) is used only by the reset (always off in steady state).
 *
 * @author alessio.carotenuto
 */
public class CIAPinSet {

    /**
     * A single physical pin of the MOS 8520 (used for the 5 special pins).
     */
    public static class CIAPin {

        // Current logic state and state at the previous cycle (for edge detection)
        private boolean currentLevel = true;
        private boolean lastLevel    = true;

        // Signal sources
        private boolean internalDrive = true; // Value the CIA tries to emit (if output)
        private boolean externalInput = true; // Value imposed by the external hardware

        // Configurazione
        private boolean isOutput      = false; // Set by the DDR
        private boolean timerOverride = false; // If true, the timer drives the pin (PB6/PB7)
        private boolean isOpenDrain   = false; // IRQ, SP, CNT
        private boolean dirty         = true;

        /**
         * Updates the pin's state for the current cycle.
         */
        public void update() {
            lastLevel = currentLevel;

            if (isOutput || timerOverride) {
                if (isOpenDrain) {
                    currentLevel = internalDrive && externalInput;
                } else {
                    currentLevel = internalDrive;
                }
            } else {
                currentLevel = externalInput;
            }
            dirty = false;
        }

        /** Falling edge: HIGH -> LOW. */
        public boolean isFallingEdge() {
            return lastLevel && !currentLevel;
        }

        /** Rising edge: LOW -> HIGH. */
        public boolean isRisingEdge() {
            return !lastLevel && currentLevel;
        }

        /**
         * Real physical state of the pin, used for reading the PRA/PRB registers.
         */
        public boolean getPinState() {
            if (isOutput || timerOverride) {
                if (isOpenDrain) {
                    return internalDrive && externalInput;
                } else {
                    return internalDrive;
                }
            } else {
                return externalInput;
            }
        }

        /** Sets the value the chip tries to emit on the pin. */
        public void setInternalDrive(boolean value) {
            if (this.internalDrive == value) return;
            this.internalDrive = value;
            dirty = true;
        }

        /** Sets the signal coming from the external hardware connected to the pin. */
        public void setExternalInput(boolean value) {
            if (this.externalInput == value) return;
            this.externalInput = value;
            dirty = true;
        }

        /** Configures the pin's direction: true = output, false = input. */
        public void setDirection(boolean output) {
            if (this.isOutput == output) return;
            this.isOutput = output;
            dirty = true;
        }

        /**
         * Switches the timer override on the pin on or off.
         */
        public void setTimerOverride(boolean active) {
            if (this.timerOverride == active) return;
            this.timerOverride = active;
            dirty = true;
        }

        /**
         * Configures the pin as open drain.
         */
        public void setOpenDrain(boolean openDrain) {
            if (this.isOpenDrain == openDrain) return;
            this.isOpenDrain = openDrain;
            dirty = true;
        }

        /** Returns the value the chip is trying to emit. */
        public boolean getInternalDrive() {
            return internalDrive;
        }

        /** Whether the pin is configured as an output. */
        public boolean isOutputMode() {
            return isOutput || timerOverride;
        }

        private boolean isDirty() {
            return dirty;
        }
    }

    // -- 8-bit I/O ports, represented as bit masks ------------------------------------------
    // Defaults: internalDrive=true (drive=0xFF), externalInput=true (ext=0xFF),
    // isOutput=false (ddr=0). The MOS8520 reset overwrites drive/ddr to 0 anyway.
    private int aDrive = 0xFF, aDdr = 0x00, aExt = 0xFF;
    private int bDrive = 0xFF, bDdr = 0x00, bExt = 0xFF;
    private int bTimerOvr = 0x00; // timer override on PB6/PB7 (bits 6/7); 0 in steady state

    // Special pins (need edges / open-drain)
    private final CIAPin flagPin = new CIAPin();
    private final CIAPin todPin  = new CIAPin();
    private final CIAPin cntPin  = new CIAPin();
    private final CIAPin spPin   = new CIAPin();
    private final CIAPin irqPin  = new CIAPin();

    /**
     * Current value of the PRB register, needed to restore the drive bit on PB6/PB7 when the
     * timer override is switched off.
     */
    private int prbValue = 0xFF;

    public CIAPinSet() {
        // IRQ: open drain, active LOW.
        // The CIA forces LOW to signal an interrupt; HIGH at rest.
        irqPin.setOpenDrain(true);
        irqPin.setDirection(true);
        irqPin.setInternalDrive(true); // Rilasciato (HIGH) a riposo
        irqPin.setExternalInput(true);

        // SP and CNT: open drain, as the 8520 specifies.
        spPin.setOpenDrain(true);
        cntPin.setOpenDrain(true);
    }

    // -------------------------------------------------------------------------
    // Aggiornamento ciclico
    // -------------------------------------------------------------------------

    /**
     * Updates the physical state of the special pins (edges). Ports A/B need no update:
     * their state is computed on demand from the masks.
     */
    public boolean updateAll() {
        if (!hasSpecialPinChanges()) return false;
        flagPin.update();
        todPin.update();
        cntPin.update();
        spPin.update();
        irqPin.update();
        return true;
    }

    public boolean hasSpecialPinChanges() {
        return flagPin.isDirty() || todPin.isDirty() || cntPin.isDirty()
                || spPin.isDirty() || irqPin.isDirty();
    }

    // -------------------------------------------------------------------------
    // Interface with the CIA registers
    // -------------------------------------------------------------------------

    /**
     * Writes the DDR (Data Direction Register). Bit = 1 -> output, bit = 0 -> input.
     *
     * @param portIdx 0 = port A, 1 = port B
     * @param value   byte to write to the DDR
     */
    public void writeDDR(int portIdx, byte value) {
        if (portIdx == 0) aDdr = value & 0xFF;
        else              bDdr = value & 0xFF;
    }

    /**
     * Writes the PR (Peripheral Register): sets the value the chip tries to emit on the pins
     * configured as outputs.
     *
     * @param portIdx 0 = port A, 1 = port B
     * @param value   byte to write to the PR
     */
    public void writePortData(int portIdx, byte value) {
        if (portIdx == 0) {
            aDrive = value & 0xFF;
        } else {
            bDrive = value & 0xFF;
            prbValue = value & 0xFF; // copy of PRB to restore the override
        }
    }

    /**
     * Reads the PR register: the real physical state of the pins, i.e. for the bits
     * configured as outputs the value emitted by the chip (drive), for the input bits the
     * external level (ext). What is on the pin is what is read.
     *
     * @param portIdx 0 = port A, 1 = port B
     * @return current state of the port's 8 pins
     */
    /**
     * Level imposed from OUTSIDE on a port's pins, with no direction mask: for CIA-A port A
     * these are the four drive status lines (bits 2-5) and the two fire buttons (bits 6-7).
     *
     * <p>Used by the <b>register read-back</b> ({@code MOS8520.readRegister} reg 0/1), which
     * on the plain 8520 does <b>not</b> follow the DDR on those bits; see the comment of
     * {@code MOS8520.computePA}. The level of the <i>pin</i>, which is another thing, is
     * {@link #readPortData}.
     */
    public int readExternal(int portIdx) {
        return (portIdx == 0 ? aExt : bExt) & 0xFF;
    }

    public byte readPortData(int portIdx) {
        int out, drive, ext;
        if (portIdx == 0) {
            out = aDdr;
            drive = aDrive;
            ext = aExt;
        } else {
            out = bDdr | bTimerOvr; // pins under timer override count as outputs
            drive = bDrive;
            ext = bExt;
        }
        return (byte) (((drive & out) | (ext & ~out)) & 0xFF);
    }

    /**
     * Handles the override of pins PB6 and PB7 by the timers (PBON). Not used in steady state
     * (the override is folded into writePortData by MOS8520.updatePB); the reset switches it
     * off.
     *
     * @param timerIdx 0 = timer A / PB6, 1 = timer B / PB7
     * @param active   true if the timer must drive the pin
     * @param level    logic level emitted by the timer (used only if active = true)
     */
    public void setTimerOutputOverride(int timerIdx, boolean active, boolean level) {
        int mask = (timerIdx == 0) ? 0x40 : 0x80;
        if (active) {
            bTimerOvr |= mask;
            if (level) bDrive |= mask; else bDrive &= ~mask;
        } else {
            bTimerOvr &= ~mask;
            // Restore the bit from the PRB register
            if ((prbValue & mask) != 0) bDrive |= mask; else bDrive &= ~mask;
        }
    }

    // -------------------------------------------------------------------------
    // Interface with the external hardware
    // -------------------------------------------------------------------------

    /**
     * Simulates an input signal from an external peripheral (e.g. floppy drive, joystick,
     * keyboard).
     *
     * @param portIdx 0 = port A, 1 = port B
     * @param bit     pin index (0-7)
     * @param level   logic level of the external signal (false = LOW)
     */
    public void setExternalInput(int portIdx, int bit, boolean level) {
        int mask = 1 << bit;
        if (portIdx == 0) {
            if (level) aExt |= mask; else aExt &= ~mask;
        } else {
            if (level) bExt |= mask; else bExt &= ~mask;
        }
    }

    // -------------------------------------------------------------------------
    // Special pin accessors
    // -------------------------------------------------------------------------

    public CIAPin getFlagPin() { return flagPin; }
    public CIAPin getTodPin()  { return todPin;  }
    public CIAPin getCntPin()  { return cntPin;  }
    public CIAPin getSpPin()   { return spPin;   }
    public CIAPin getIrqPin()  { return irqPin;  }
}
