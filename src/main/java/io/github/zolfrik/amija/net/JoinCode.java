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

package io.github.zolfrik.amija.net;

import java.security.SecureRandom;

/**
 * The <i>join code</i>: 8 characters, ~40 bits, generated at every server start and shown in
 * the popup ({@code docs/remote-play-plan.md} sections 8.1, 9).
 *
 * <p>The alphabet is Crockford's base32: 32 symbols from which the letters that can be confused
 * with digits have been <b>removed</b>. The code is read aloud over the phone or pasted into a
 * chat: I/L/O/U are out because 1/1/0/V are already in, and {@link #normalise} maps them back to
 * the right digit instead of refusing them. A code failing because the user typed O instead of
 * 0 is a design bug, not a user error.
 *
 * <p>The dash in the middle (`K7M2-Q4XZ`) is only presentation: {@link #normalise} ignores it, as
 * it ignores spaces and case.
 */
public final class JoinCode {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 8;

    private JoinCode() {
    }

    /** New random code, already formatted with the dash. */
    public static String generate() {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(LENGTH + 1);
        for (int i = 0; i < LENGTH; i++) {
            if (i == LENGTH / 2) {
                sb.append('-');
            }
            sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * Reduces a hand-written code to its canonical form: upper case, no separators, with the
     * typographic confusions mapped back to the digit ({@code I,L -> 1}, {@code O -> 0}).
     *
     * <p>It is the function the whole key derivation uses as the password, so it <b>must</b> be
     * the same on both sides: normalising on one side only means failing authentication
     * without understanding why.
     */
    public static String normalise(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = Character.toUpperCase(code.charAt(i));
            switch (c) {
                case '-', ' ', '_', '\t' -> { }
                case 'I', 'L' -> sb.append('1');
                case 'O' -> sb.append('0');
                case 'U' -> sb.append('V');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** True if the code, normalised, has the right form. It does not say whether it is the right one. */
    public static boolean isWellFormed(String code) {
        String n = normalise(code);
        if (n.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < n.length(); i++) {
            if (indexOf(n.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(char c) {
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == c) {
                return i;
            }
        }
        return -1;
    }
}
