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

package io.github.zolfrik.amija.input;

/**
 * A keyboard that can be switched off: while closed, presses do not reach the Amiga. Closing
 * it releases what it let through, and a release reaches the Amiga only for a key whose
 * press did, so nothing stays held and nothing is released twice.
 *
 * <p>It keeps the PC's keyboard away from the machine while another keyboard drives it.
 */
public final class GatedKeyTarget implements AmigaKeyTarget {

    private final AmigaKeyTarget target;
    private final boolean[] down = new boolean[128];
    private boolean open = true;

    public GatedKeyTarget(AmigaKeyTarget target) {
        this.target = target;
    }

    /** Opens or closes the gate; closing releases every key let through. */
    public synchronized void setOpen(boolean open) {
        if (this.open == open) {
            return;
        }
        this.open = open;
        if (!open) {
            for (int k = 0; k < down.length; k++) {
                if (down[k]) {
                    down[k] = false;
                    target.releaseKey(k);
                }
            }
        }
    }

    public synchronized boolean isOpen() {
        return open;
    }

    @Override
    public synchronized void pressKey(int rawKeyNum) {
        if (!open || rawKeyNum < 0 || rawKeyNum >= down.length) {
            return;
        }
        down[rawKeyNum] = true;
        target.pressKey(rawKeyNum);
    }

    @Override
    public synchronized void releaseKey(int rawKeyNum) {
        if (rawKeyNum < 0 || rawKeyNum >= down.length || !down[rawKeyNum]) {
            return;
        }
        down[rawKeyNum] = false;
        target.releaseKey(rawKeyNum);
    }

    @Override
    public synchronized boolean isResetComboHeld() {
        return open && target.isResetComboHeld();
    }

    @Override
    public synchronized boolean resetComboPendingOn(int rawKeyNum) {
        return open && target.resetComboPendingOn(rawKeyNum);
    }
}
