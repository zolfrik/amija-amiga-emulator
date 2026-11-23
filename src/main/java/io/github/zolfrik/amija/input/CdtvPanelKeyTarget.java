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

import io.github.zolfrik.amija.hardware.custom.CdtvDmac;

/**
 * The CDTV's key codes $72-$77, its front panel buttons, on their way to the keyboard line.
 * While the drive handles the panel a press goes to the drive and its release is dropped;
 * otherwise the code continues to the keyboard like any other key.
 */
public final class CdtvPanelKeyTarget implements AmigaKeyTarget {

    /** First and last key code of the panel buttons. */
    public static final int FIRST_CODE = 0x72, LAST_CODE = 0x77;

    private final AmigaKeyTarget keyboard;
    private final CdtvDmac drive;
    private final boolean[] taken = new boolean[LAST_CODE - FIRST_CODE + 1];

    public CdtvPanelKeyTarget(AmigaKeyTarget keyboard, CdtvDmac drive) {
        this.keyboard = keyboard;
        this.drive = drive;
    }

    @Override
    public synchronized void pressKey(int rawKeyNum) {
        if (rawKeyNum >= FIRST_CODE && rawKeyNum <= LAST_CODE && drive.frontPanelActive()) {
            taken[rawKeyNum - FIRST_CODE] = true;
            drive.queuePanelButton(rawKeyNum - FIRST_CODE);
            return;
        }
        keyboard.pressKey(rawKeyNum);
    }

    @Override
    public synchronized void releaseKey(int rawKeyNum) {
        if (rawKeyNum >= FIRST_CODE && rawKeyNum <= LAST_CODE && taken[rawKeyNum - FIRST_CODE]) {
            taken[rawKeyNum - FIRST_CODE] = false;
            return;
        }
        keyboard.releaseKey(rawKeyNum);
    }

    @Override
    public boolean isResetComboHeld() {
        return keyboard.isResetComboHeld();
    }

    @Override
    public boolean resetComboPendingOn(int rawKeyNum) {
        return keyboard.resetComboPendingOn(rawKeyNum);
    }
}
