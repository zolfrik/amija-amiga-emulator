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

package io.github.zolfrik.amija.display;

import com.formdev.flatlaf.util.UIScale;

import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.border.Border;

/**
 * The sizes written by hand in the dialogs, multiplied by the interface scale.
 *
 * <p><b>Why it is needed.</b> FlatLaf's <em>user scaling</em> ({@code ui.scale} ->
 * {@code flatlaf.uiScale}) reaches the fonts and the {@code UIDefaults} values: borders,
 * spacing, icons of the standard components. It does not (cannot) reach the sizes our code
 * writes by itself, like {@code new Dimension(580, 460)}. Without this mediation,
 * {@code ui.scale=2} gives large text inside boxes that stayed small: measured before this
 * class, the F12 popup came out 1.86 times wider but only 1.09 times taller, with the doubled
 * content squeezed into a scrolling pane that stayed the same.
 *
 * <p><b>Rule for new code:</b> every pixel size of an interface component goes through here.
 * The only sizes that must <em>not</em> are those drawn inside the emulated window (LEDs, FPS
 * counter, drag overlay in {@code AmigaDisplay}), which belong to the Amiga frame and scale
 * with {@code video.integerScaling}, not with the interface scale.
 *
 * <p>Meant for static import:
 * {@code import static io.github.zolfrik.amija.display.UiScaling.*;}
 */
final class UiScaling {

    private UiScaling() {}

    /** A pixel measure, scaled. */
    static int px(int value) {
        return UIScale.scale(value);
    }

    /** A font size, scaled. */
    static float px(float value) {
        return UIScale.scale(value);
    }

    /** A component size, scaled on both axes. */
    static Dimension dim(int width, int height) {
        return new Dimension(px(width), px(height));
    }

    /** An empty margin, scaled on all four sides. */
    static Border pad(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(px(top), px(left), px(bottom), px(right));
    }

    /**
     * Derives a font changing its style and size, with the size scaled.
     *
     * <p>To be used instead of {@code font.deriveFont(style, size)}: that one sets an
     * <em>absolute</em> size, throwing away the scaling FlatLaf had already applied to the
     * starting font.
     */
    static Font derive(Font base, int style, float size) {
        return base.deriveFont(style, px(size));
    }
}
