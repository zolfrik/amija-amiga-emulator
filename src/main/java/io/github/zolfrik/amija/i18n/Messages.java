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

    /**
     * Lingua scelta, tenuta a parte dal bundle: quello dell'inglese è il bundle
     * <i>root</i> e {@code getLocale()} risponderebbe {@link Locale#ROOT}, che come
     * locale di formattazione non è l'inglese ma un neutro con date in forma ISO.
     */
    private static volatile Locale locale = Locale.ENGLISH;

    private Messages() {}

    /** Imposta la lingua dell'interfaccia ({@code "it"} = italiano, altrimenti inglese). */
    public static void setLanguage(String lang) {
        locale = (lang != null && lang.equalsIgnoreCase("it")) ? Locale.ITALIAN : Locale.ENGLISH;
        bundle = ResourceBundle.getBundle(BASE_NAME, locale, CONTROL);
    }

    /** Lingua corrente dell'interfaccia, per date, numeri e ordinamenti. */
    public static Locale locale() {
        return locale;
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
     *
     * <p>Una scansione sola, e i valori sostituiti <b>non</b> vengono riletti. Un ciclo di
     * {@code String.replace}, uno per argomento, riscandiva ogni volta anche quel che
     * aveva appena scritto: se {@code args[0]} conteneva la sequenza {@code {1}} — un
     * percorso di file, o un messaggio d'errore che rimbalza da un altro componente — il
     * giro successivo la sostituiva, e nel testo finale compariva un argomento al posto
     * di un altro. Qui il segnaposto lo si cerca nel <i>modello</i>, e quel che si emette
     * non viene piu' guardato.
     */
    public static String t(String key, Object... args) {
        String s = t(key);
        StringBuilder out = new StringBuilder(s.length() + 16 * args.length);
        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf('{', i);
            if (open < 0) {
                out.append(s, i, s.length());
                break;
            }
            int close = s.indexOf('}', open + 1);
            int index = close < 0 ? -1 : parseIndex(s, open + 1, close);
            if (index < 0 || index >= args.length) {
                // Non e' un segnaposto nostro (o non c'e' l'argomento): resta com'e'.
                out.append(s, i, open + 1);
                i = open + 1;
                continue;
            }
            out.append(s, i, open).append(args[index]);
            i = close + 1;
        }
        return out.toString();
    }

    /** L'intero fra {@code from} incluso e {@code to} escluso, o {@code -1} se non lo e'. */
    private static int parseIndex(String s, int from, int to) {
        if (from >= to || to - from > 9) {
            return -1;
        }
        int n = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            n = n * 10 + (c - '0');
        }
        return n;
    }
}
