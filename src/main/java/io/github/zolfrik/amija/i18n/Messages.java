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

package io.github.zolfrik.amija.i18n;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Accesso centralizzato alle stringhe localizzate dell'interfaccia, basato sul
 * meccanismo standard {@link ResourceBundle}.
 *
 * <p>I bundle risiedono in {@code src/main/resources} con base name {@code messages}:
 * {@code messages.properties} (inglese, default/root) e {@code messages_it.properties}
 * (italiano). La lingua si seleziona con {@link #setLanguage(String)} (tipicamente dal
 * valore {@code ui.language} di {@code amiga.properties}).
 */
public final class Messages {

    private static final String BASE_NAME = "messages";

    // No-fallback: richiedendo l'inglese non si ricade mai sul locale di default della
    // JVM (che potrebbe essere italiano), ma sul bundle root (inglese).
    private static final ResourceBundle.Control CONTROL =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private static volatile ResourceBundle bundle =
            ResourceBundle.getBundle(BASE_NAME, Locale.ENGLISH, CONTROL);

    private Messages() {}

    /** Imposta la lingua dell'interfaccia ({@code "it"} = italiano, altrimenti inglese). */
    public static void setLanguage(String lang) {
        Locale locale = (lang != null && lang.equalsIgnoreCase("it")) ? Locale.ITALIAN : Locale.ENGLISH;
        bundle = ResourceBundle.getBundle(BASE_NAME, locale, CONTROL);
    }

    /** Stringa localizzata per la chiave; ritorna la chiave stessa se mancante. */
    public static String t(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    /**
     * Come {@link #t(String)}, ma ritorna {@code null} se la chiave non è nel bundle.
     * Utile quando la stringa localizzata è un'alternativa preferenziale a un valore
     * calcolato altrimenti (es. il nome dei tasti fornito da AWT).
     */
    public static String tOrNull(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return null;
        }
    }

    /**
     * Stringa localizzata con sostituzione dei segnaposto {@code {0}}, {@code {1}}, ...
     * (sostituzione testuale semplice, così gli apostrofi nelle stringhe restano letterali).
     */
    public static String t(String key, Object... args) {
        String s = t(key);
        for (int i = 0; i < args.length; i++) {
            s = s.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return s;
    }
}
