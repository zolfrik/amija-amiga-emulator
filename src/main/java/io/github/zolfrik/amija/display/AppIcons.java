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

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.Window;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Application icons (the AmiJa logo), loaded from {@code /icons/amija-<N>.png}.
 *
 * <p>The whole set is passed to {@link Window#setIconImages(List)}: the window manager picks
 * the best resolution for each context (title bar, taskbar, Alt+Tab, previews). The images
 * are loaded once and shared.
 */
public final class AppIcons {

    /** Resolutions available under {@code src/main/resources/icons}. */
    private static final int[] SIZES = { 16, 20, 24, 32, 40, 48, 64, 128, 256, 512 };

    private static List<Image> icons;

    private AppIcons() {
    }

    /** The icons available, from the smallest to the largest (empty if missing). */
    public static synchronized List<Image> getIcons() {
        if (icons == null) {
            List<Image> loaded = new ArrayList<>(SIZES.length);
            for (int size : SIZES) {
                String path = "/icons/amija-" + size + ".png";
                try (InputStream in = AppIcons.class.getResourceAsStream(path)) {
                    if (in != null) {
                        loaded.add(ImageIO.read(in));
                    }
                } catch (Exception e) {
                    // Icon missing or unreadable: not a fatal error, carry on with the
                    // remaining resolutions.
                    System.err.println("[ICON] Impossibile caricare " + path + ": " + e);
                }
            }
            icons = Collections.unmodifiableList(loaded);
        }
        return icons;
    }

    /** Applies the icon set to the window (no-op if the set is empty). */
    public static void apply(Window window) {
        List<Image> images = getIcons();
        if (window != null && !images.isEmpty()) {
            window.setIconImages(images);
        }
    }

    /**
     * Sets the application-level icon (macOS dock, launcher of some Linux desktops). On Windows
     * the taskbar icon comes from the window's icon, so support is missing here and ignoring
     * the request is right.
     */
    public static void applyToTaskbar() {
        List<Image> images = getIcons();
        if (images.isEmpty() || !Taskbar.isTaskbarSupported()) {
            return;
        }
        try {
            Taskbar taskbar = Taskbar.getTaskbar();
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                taskbar.setIconImage(images.get(images.size() - 1));
            }
        } catch (UnsupportedOperationException | SecurityException e) {
            // Platform without an application icon: irrelevant.
        }
    }
}
