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

package io.github.zolfrik.amija.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Versione e data di build dell'emulatore, lette da {@code /version.properties}.
 *
 * <p>Il file è un template in {@code src/main/resources}: Maven lo filtra durante la
 * build sostituendo {@code ${project.version}} e {@code ${maven.build.timestamp}}, così
 * la versione mostrata a runtime è sempre quella del pom con cui il jar è stato prodotto.
 * Se il file manca o non è stato filtrato (p.es. classi eseguite senza passare da Maven)
 * i valori ricadono su {@code "dev"} / stringa vuota.
 */
public final class Version {

    private static final String UNKNOWN_VERSION = "dev";

    private static final String VERSION;
    private static final String BUILD_DATE;

    static {
        Properties p = new Properties();
        try (InputStream in = Version.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            // risorsa illeggibile: si resta sui default
        }
        VERSION = clean(p.getProperty("version"), UNKNOWN_VERSION);
        BUILD_DATE = clean(p.getProperty("build.date"), "");
    }

    private Version() {}

    /** Versione dell'emulatore (p.es. {@code "1.0.0"}), o {@code "dev"} se ignota. */
    public static String version() {
        return VERSION;
    }

    /** Data/ora di build (formato {@code yyyy-MM-dd HH:mm}), o stringa vuota se ignota. */
    public static String buildDate() {
        return BUILD_DATE;
    }

    /** Un placeholder non sostituito (build fuori da Maven) vale quanto un valore assente. */
    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String v = value.trim();
        return (v.isEmpty() || v.startsWith("${")) ? fallback : v;
    }
}
