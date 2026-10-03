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

package io.github.zolfrik.amija.input;

/**
 * Where the keys translated into Amiga raw codes end up.
 *
 * <p>It exists for one reason, remote play: {@code AmigaKeyAdapter} contains the translation
 * from AWT {@code KeyEvent}s to the 128 raw codes, 500 lines of maps plus three traps solved at
 * great cost (the missing key-up of the first shift on Windows, the synthetic Ctrl AltGr
 * brings along, the Italian accented letters). On the guest those keys must go not to a
 * {@code Keyboard} (which does not exist there: the guest emulates nothing) but into a network
 * packet.
 *
 * <p>With this interface only <b>where</b> they go changes: the translation stays one, the
 * tested one. Duplicating it for the guest would mean maintaining two copies and discovering
 * the same traps a second time.
 */
public interface AmigaKeyTarget {

    /** @param rawKeyNum 7-bit Amiga raw code (see the {@code KEY_*} constants) */
    void pressKey(int rawKeyNum);

    void releaseKey(int rawKeyNum);

    /**
     * True while CTRL+Amiga+Amiga reads as held and the keyboard keeps the machine in reset.
     *
     * <p>Used by the poller of {@code AmigaKeyAdapter}, which while the combination is held
     * watches the <i>physical</i> state of the three keys: if Windows loses one of the key-ups
     * (and with the two Alts it does), the machine would stay in reset forever.
     *
     * <p>The default is {@code false}, the right answer for the remote play guest: there is no
     * machine there to keep in reset, and whether the reset should happen belongs to the host
     * anyway (plan section 4.3), not to whoever presses the keys.
     */
    default boolean isResetComboHeld() {
        return false;
    }

    /**
     * True if pressing {@code rawKeyNum} <b>would complete</b> CTRL+Amiga+Amiga <b>now</b>, i.e.
     * if the other two keys of the triple already read as held on the machine.
     *
     * <p>Used by the guest filter ({@code input.RemoteInputApplier}) for the case its packet alone
     * cannot see: the triple formed <b>across the two keyboards</b>, with the host holding two
     * and the guest bringing the third.
     *
     * <p>The default is {@code false}, the right answer where there is no machine behind (the
     * remote play guest, the test doubles), because there is no triple to complete there.
     */
    default boolean resetComboPendingOn(int rawKeyNum) {
        return false;
    }
}
