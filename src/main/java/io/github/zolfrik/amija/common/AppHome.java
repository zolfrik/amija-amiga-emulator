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

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.nio.file.Paths;
import java.security.CodeSource;

/**
 * AmiJa's <b>home</b>: the folder next to which live all the files the emulator reads and
 * writes at run time ({@code amiga.properties}, {@code shaders/}, {@code blurs/},
 * {@code screenshots/}, {@code savestate/}, {@code floppysounds/}, {@code whdload/},
 * {@code whdload-cache/}, {@code whdload-saves/}).
 *
 * <p>AmiJa is <b>portable by design</b>: it writes next to itself, not in a system data
 * folder. Launched with {@code java -jar} from the unpacked folder the working directory is
 * enough, but with a native launcher produced by {@code jpackage} it is not: on Linux the CWD
 * is the user's home, on macOS it is {@code /}. This class resolves the home once, at
 * initialisation, with this chain:
 *
 * <ol>
 *   <li>the system property {@code amija.home}, if set: what the jpackage launcher passes
 *       through {@code --java-options -Damija.home=$ROOTDIR};</li>
 *   <li>otherwise, if the code runs <b>from a jar</b>, the folder containing the jar (with
 *       the adjustment for the macOS {@code .app} bundle, below);</li>
 *   <li>otherwise the <b>working directory</b>, which is what launches from the IDE, from
 *       {@code mvn exec} or from the tests get.</li>
 * </ol>
 *
 * <p>Step 2 holds only for a real jar: when the code source is a folder of classes
 * ({@code target/classes}) it falls to step 3, because that folder is nobody's home.
 *
 * <p><b>No early initialisation.</b> {@code AmigaConfig.load()} is called by
 * {@code Amija.main} before anything else (AWT included): the resolution here must not depend
 * on other components, and it does not. If something goes wrong it falls back on the working
 * directory without ever throwing.
 */
public final class AppHome {

    /** System property that imposes the home; the native launcher (jpackage) passes it. */
    public static final String PROPERTY = "amija.home";

    /** The home resolved once per JVM. Never {@code null}. */
    private static final Path HOME = discover();

    private AppHome() {}

    /** The AmiJa home folder, absolute and normalised. For diagnostics and logs. */
    public static Path home() {
        return HOME;
    }

    /** Resolves a path relative to the home, like {@code Path.resolve}. */
    public static Path resolve(String first, String... more) {
        return HOME.resolve(Paths.get(first, more));
    }

    /** Like {@link #resolve}, for the APIs that want a {@link File}. */
    public static File file(String first, String... more) {
        return resolve(first, more).toFile();
    }

    // =========================================================================
    // Risoluzione
    // =========================================================================

    private static Path discover() {
        Path fromProperty = fromSystemProperty();
        if (fromProperty != null) {
            return announce(fromProperty, PROPERTY);
        }
        Path fromJar = fromCodeSource();
        if (fromJar != null) {
            return announce(fromJar, "jar");
        }
        return announce(Paths.get(""), "cwd");
    }

    private static Path fromSystemProperty() {
        String value = System.getProperty(PROPERTY);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Paths.get(value.trim());
        } catch (RuntimeException e) {
            System.err.println("[HOME] " + PROPERTY + " non è un percorso valido: " + value);
            return null;
        }
    }

    /**
     * The folder containing the jar this class runs from, or {@code null} if not running from
     * a jar (IDE, {@code mvn exec}, tests) or if the code source cannot be inspected.
     */
    private static Path fromCodeSource() {
        try {
            CodeSource source = AppHome.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            URI uri = source.getLocation().toURI();
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            Path location = Paths.get(uri);
            // Only a real jar identifies a home; a folder of classes does not.
            if (!location.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return null;
            }
            Path dir = location.getParent();
            return dir == null ? null : unwrapMacBundle(dir);
        } catch (Exception e) {
            // Any problem (malformed URI, SecurityManager, non-local path): fall back on the
            // next step of the chain.
            return null;
        }
    }

    /**
     * macOS adjustment: inside a jpackage app image the jar is in {@code AmiJa.app/Contents/app}.
     * The home is not that folder (it would be inside the bundle, where the user does not look
     * and which may be read-only) but the folder that <b>contains</b> {@code AmiJa.app}, where
     * the packaging puts {@code shaders/}, {@code blurs/} and the documents.
     */
    private static Path unwrapMacBundle(Path jarDir) {
        Path contents = jarDir.getParent();
        Path bundle = contents == null ? null : contents.getParent();
        if (bundle == null) {
            return jarDir;
        }
        if (!"app".equals(nameOf(jarDir)) || !"Contents".equals(nameOf(contents))
                || !nameOf(bundle).toLowerCase(Locale.ROOT).endsWith(".app")) {
            return jarDir;
        }
        Path outside = bundle.getParent();
        return outside == null ? jarDir : outside;
    }

    private static String nameOf(Path p) {
        Path name = p.getFileName();
        return name == null ? "" : name.toString();
    }

    /** Makes the home absolute and normalised, and prints it once. */
    private static Path announce(Path candidate, String origin) {
        Path resolved;
        try {
            resolved = candidate.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            resolved = Paths.get("").toAbsolutePath();
        }
        System.out.println("[HOME] " + resolved + " (" + origin + ")");
        return resolved;
    }
}
