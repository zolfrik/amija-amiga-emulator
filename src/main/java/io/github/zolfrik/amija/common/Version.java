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

package io.github.zolfrik.amija.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Version and build date of the emulator, read from {@code /version.properties}.
 *
 * <p>The file is a template in {@code src/main/resources}: Maven filters it during the build,
 * replacing {@code ${project.version}} and {@code ${maven.build.timestamp}}, so the version
 * shown at run time is always that of the pom the jar was produced with. If the file is
 * missing or was not filtered (e.g. classes run without going through Maven) the values fall
 * back on {@code "dev"} / an empty string.
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
            // unreadable resource: keep the defaults
        }
        VERSION = clean(p.getProperty("version"), UNKNOWN_VERSION);
        BUILD_DATE = clean(p.getProperty("build.date"), "");
    }

    private Version() {}

    /** Emulator version (e.g. {@code "1.0.0"}), or {@code "dev"} if unknown. */
    public static String version() {
        return VERSION;
    }

    /** Build date/time (format {@code yyyy-MM-dd HH:mm}), or an empty string if unknown. */
    public static String buildDate() {
        return BUILD_DATE;
    }

    /** An unreplaced placeholder (build outside Maven) is as good as a missing value. */
    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String v = value.trim();
        return (v.isEmpty() || v.startsWith("${")) ? fallback : v;
    }
}
