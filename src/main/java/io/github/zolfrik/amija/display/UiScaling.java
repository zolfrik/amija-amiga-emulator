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
 * Le misure scritte a mano nei dialoghi, moltiplicate per la scala dell'interfaccia.
 *
 * <p><b>Perché serve.</b> Lo <em>user scaling</em> di FlatLaf ({@code ui.scale} →
 * {@code flatlaf.uiScale}) arriva ai font e ai valori di {@code UIDefaults}: bordi,
 * spaziature, icone dei componenti standard. Non arriva — non può — alle dimensioni che
 * il nostro codice scrive di suo pugno, tipo {@code new Dimension(580, 460)}. Senza
 * questa mediazione, a {@code ui.scale=2} si ottiene testo grande dentro caselle rimaste
 * piccole: misurato prima di questa classe, il popup F12 risultava 1,86 volte più largo
 * ma solo 1,09 volte più alto, col contenuto raddoppiato compresso in un riquadro
 * scorrevole rimasto uguale.
 *
 * <p><b>Regola per il codice nuovo:</b> ogni misura in pixel di un componente
 * dell'interfaccia passa di qui. Le uniche misure che <em>non</em> devono passarci sono
 * quelle disegnate dentro la finestra emulata — LED, contatore FPS, overlay di drag in
 * {@code AmigaDisplay} — che appartengono al frame Amiga e scalano con
 * {@code video.integerScaling}, non con la scala dell'interfaccia.
 *
 * <p>Pensata per l'import statico:
 * {@code import static io.github.zolfrik.amija.display.UiScaling.*;}
 */
final class UiScaling {

    private UiScaling() {}

    /** Una misura in pixel, scalata. */
    static int px(int value) {
        return UIScale.scale(value);
    }

    /** Un corpo del carattere, scalato. */
    static float px(float value) {
        return UIScale.scale(value);
    }

    /** Una dimensione di componente, scalata su entrambi gli assi. */
    static Dimension dim(int width, int height) {
        return new Dimension(px(width), px(height));
    }

    /** Un margine vuoto, scalato su tutti e quattro i lati. */
    static Border pad(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(px(top), px(left), px(bottom), px(right));
    }

    /**
     * Deriva un font cambiandone stile e corpo, con il corpo scalato.
     *
     * <p>Va usata al posto di {@code font.deriveFont(stile, corpo)}: quella imposta un
     * corpo <em>assoluto</em>, e quindi butta via lo scaling che FlatLaf aveva già
     * applicato al font di partenza.
     */
    static Font derive(Font base, int style, float size) {
        return base.deriveFont(style, px(size));
    }
}
