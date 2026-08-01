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

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.Window;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Icone dell'applicazione (logo AmiJa), caricate da {@code /icons/amija-<N>.png}.
 *
 * <p>Il set viene passato per intero a {@link Window#setIconImages(List)}: il window
 * manager sceglie da solo la risoluzione piu' adatta a ogni contesto (barra del titolo,
 * taskbar, Alt+Tab, anteprime). Le immagini sono caricate una volta sola e condivise.
 */
public final class AppIcons {

    /** Risoluzioni disponibili sotto {@code src/main/resources/icons}. */
    private static final int[] SIZES = { 16, 20, 24, 32, 40, 48, 64, 128, 256, 512 };

    private static List<Image> icons;

    private AppIcons() {
    }

    /** Le icone disponibili, dalla piu' piccola alla piu' grande (vuota se mancanti). */
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
                    // Icona assente o illeggibile: non e' un errore fatale, si prosegue
                    // con le risoluzioni rimaste.
                    System.err.println("[ICON] Impossibile caricare " + path + ": " + e);
                }
            }
            icons = Collections.unmodifiableList(loaded);
        }
        return icons;
    }

    /** Applica il set di icone alla finestra (no-op se il set e' vuoto). */
    public static void apply(Window window) {
        List<Image> images = getIcons();
        if (window != null && !images.isEmpty()) {
            window.setIconImages(images);
        }
    }

    /**
     * Imposta l'icona a livello di applicazione (dock macOS, launcher di alcuni
     * desktop Linux). Su Windows l'icona in barra viene dall'icona della finestra,
     * quindi qui il supporto manca ed e' corretto ignorare la richiesta.
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
            // Piattaforma senza icona di applicazione: irrilevante.
        }
    }
}
