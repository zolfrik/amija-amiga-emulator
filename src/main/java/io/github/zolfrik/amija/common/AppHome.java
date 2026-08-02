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

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;

/**
 * La <b>home</b> di AmiJa: la cartella accanto a cui vivono tutti i file che
 * l'emulatore legge e scrive a runtime — {@code amiga.properties}, {@code shaders/},
 * {@code blurs/}, {@code screenshots/}, {@code savestate/}, {@code floppysounds/},
 * {@code whdload/}, {@code whdload-cache/}, {@code whdload-saves/}.
 *
 * <p>AmiJa è <b>portable by design</b>: scrive accanto a sé, non in una cartella dati
 * di sistema. Finché lo si lancia con {@code java -jar} dalla cartella scompattata la
 * working directory basta, ma con un launcher nativo prodotto da {@code jpackage} no:
 * su Linux la CWD è la home dell'utente, su macOS è {@code /}. Questa classe risolve
 * la home una volta sola, all'inizializzazione, con questa catena:
 *
 * <ol>
 *   <li>la system property {@code amija.home}, se valorizzata — è ciò che passa il
 *       launcher jpackage tramite {@code --java-options -Damija.home=$ROOTDIR};</li>
 *   <li>altrimenti, se il codice gira <b>da un jar</b>, la cartella che contiene il
 *       jar (con l'aggiustamento per il bundle {@code .app} di macOS, sotto);</li>
 *   <li>altrimenti la <b>working directory</b> — il comportamento storico, che resta
 *       invariato quando si lancia dall'IDE, da {@code mvn exec} o dai test.</li>
 * </ol>
 *
 * <p>Il gradino 2 vale solo per un jar vero: quando il code source è una cartella di
 * classi ({@code target/classes}) si scende al gradino 3, perché quella cartella non è
 * la home di nessuno.
 *
 * <p><b>Nessuna inizializzazione preventiva.</b> {@code AmigaConfig.load()} viene
 * invocata da {@code Amija.main} prima di qualunque altra cosa (AWT compreso): la
 * risoluzione qui dentro non deve dipendere da altri componenti, e infatti non lo fa.
 * Se qualcosa va storto si ripiega sulla working directory senza mai lanciare.
 */
public final class AppHome {

    /** System property che impone la home; la passa il launcher nativo (jpackage). */
    public static final String PROPERTY = "amija.home";

    /** La home risolta una volta per JVM. Mai {@code null}. */
    private static final Path HOME = discover();

    private AppHome() {}

    /** La cartella home di AmiJa, assoluta e normalizzata. Per diagnostica e log. */
    public static Path home() {
        return HOME;
    }

    /** Risolve un percorso relativo alla home, come {@code Path.resolve}. */
    public static Path resolve(String first, String... more) {
        return HOME.resolve(Paths.get(first, more));
    }

    /** Come {@link #resolve}, per le API che vogliono un {@link File}. */
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
     * La cartella che contiene il jar da cui gira questa classe, oppure {@code null}
     * se non si sta girando da un jar (IDE, {@code mvn exec}, test) o se il code source
     * non è ispezionabile.
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
            // Solo un jar vero identifica una home; una cartella di classi no.
            if (!location.getFileName().toString().toLowerCase().endsWith(".jar")) {
                return null;
            }
            Path dir = location.getParent();
            return dir == null ? null : unwrapMacBundle(dir);
        } catch (Exception e) {
            // Qualsiasi problema (URI malformato, SecurityManager, path non locale):
            // si ripiega sul gradino successivo della catena.
            return null;
        }
    }

    /**
     * Aggiustamento macOS: dentro un'app-image jpackage il jar sta in
     * {@code AmiJa.app/Contents/app}. La home non è quella cartella — sarebbe dentro
     * il bundle, dove l'utente non guarda e che può essere di sola lettura — ma la
     * cartella che <b>contiene</b> {@code AmiJa.app}, dove il packaging deposita
     * {@code shaders/}, {@code blurs/} e i documenti.
     */
    private static Path unwrapMacBundle(Path jarDir) {
        Path contents = jarDir.getParent();
        Path bundle = contents == null ? null : contents.getParent();
        if (bundle == null) {
            return jarDir;
        }
        if (!"app".equals(nameOf(jarDir)) || !"Contents".equals(nameOf(contents))
                || !nameOf(bundle).toLowerCase().endsWith(".app")) {
            return jarDir;
        }
        Path outside = bundle.getParent();
        return outside == null ? jarDir : outside;
    }

    private static String nameOf(Path p) {
        Path name = p.getFileName();
        return name == null ? "" : name.toString();
    }

    /** Rende la home assoluta e normalizzata, e la stampa una volta sola. */
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
