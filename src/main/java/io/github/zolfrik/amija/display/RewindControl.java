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

package io.github.zolfrik.amija.display;

import java.awt.image.BufferedImage;

/**
 * What the "Rewind" section of the F12 popup sees of the rewind history: the positions on the
 * bar, oldest first, the last one being the moment the popup stopped the machine.
 *
 * <p>The section only chooses: the position picked is applied by the caller when the popup
 * closes with OK ({@link #selected()}).
 */
public interface RewindControl {

    /** Number of positions on the bar, "now" included (at least 1). */
    int positions();

    /** Emulated seconds between position {@code p} and now; 0 for the last one. */
    double secondsBefore(int p);

    /** The screen at position {@code p}. */
    BufferedImage image(int p);

    /** Seconds of history kept, as configured. */
    int seconds();

    /** Seconds of history that fit in the memory the history may use. */
    long maxSeconds();

    /** Records the position chosen on the bar; the last one means "do not rewind". */
    void select(int p);

    /** The position chosen, the last one if none. */
    int selected();
}
