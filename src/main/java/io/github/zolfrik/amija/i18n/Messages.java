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
 * Central access to the interface's localised strings, based on the standard
 * {@link ResourceBundle} mechanism.
 *
 * <p>The bundles live in {@code src/main/resources} with base name {@code messages}:
 * {@code messages.properties} (English, default/root) and {@code messages_it.properties}
 * (Italian). The language is selected with {@link #setLanguage(String)} (usually from the
 * {@code ui.language} value of {@code amiga.properties}).
 */
public final class Messages {

    private static final String BASE_NAME = "messages";

    // No fallback: asking for English never falls back on the JVM's default locale (which
    // could be Italian), but on the root bundle (English).
    private static final ResourceBundle.Control CONTROL =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private static volatile ResourceBundle bundle =
            ResourceBundle.getBundle(BASE_NAME, Locale.ENGLISH, CONTROL);

    /**
     * Chosen language, kept apart from the bundle: English's is the <i>root</i> bundle and
     * {@code getLocale()} would answer {@link Locale#ROOT}, which as a formatting locale is not
     * English but a neutral one with ISO dates.
     */
    private static volatile Locale locale = Locale.ENGLISH;

    private Messages() {}

    /** Sets the interface language ({@code "it"} = Italian, otherwise English). */
    public static void setLanguage(String lang) {
        locale = (lang != null && lang.equalsIgnoreCase("it")) ? Locale.ITALIAN : Locale.ENGLISH;
        bundle = ResourceBundle.getBundle(BASE_NAME, locale, CONTROL);
    }

    /** Current interface language, for dates, numbers and sorting. */
    public static Locale locale() {
        return locale;
    }

    /** Localised string for the key; returns the key itself if missing. */
    public static String t(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    /**
     * Like {@link #t(String)}, but returns {@code null} if the key is not in the bundle. Useful
     * when the localised string is a preferred alternative to a value computed otherwise
     * (e.g. the key names provided by AWT).
     */
    public static String tOrNull(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return null;
        }
    }

    /**
     * Localised string with the placeholders {@code {0}}, {@code {1}}, ... replaced (plain
     * textual replacement, so apostrophes in the strings stay literal).
     *
     * <p>A single scan, and the replaced values are <b>not</b> re-read. A loop of
     * {@code String.replace}, one per argument, would rescan what it had just written: if
     * {@code args[0]} contained the sequence {@code {1}} (a file path, or an error message
     * bouncing from another component) the next round would replace it, and an argument would
     * appear in place of another in the final text. Here the placeholder is looked for in the
     * <i>template</i>, and what is emitted is not looked at again.
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
                // Not one of our placeholders (or the argument is missing): stays as it is.
                out.append(s, i, open + 1);
                i = open + 1;
                continue;
            }
            out.append(s, i, open).append(args[index]);
            i = close + 1;
        }
        return out.toString();
    }

    /** The integer between {@code from} included and {@code to} excluded, or {@code -1} if it is not one. */
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
