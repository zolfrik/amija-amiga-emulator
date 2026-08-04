package io.github.zolfrik.amija.romgen;

/**
 * Versione della ROM AmiJa.
 *
 * E' l'unica definizione che esista: da qui escono sia la scritta dello splash
 * sia il tag {@code $VER} incorporato nell'immagine, che {@link RomBuilder}
 * genera in {@code rom/version.i} prima di assemblare. Per pubblicare una ROM
 * nuova si cambiano {@link #MAJOR}, {@link #MINOR} e {@link #DATE} qui dentro,
 * si rilancia {@code RomBuilder}, e il resto segue da solo.
 *
 * Tenerla in un solo posto non e' pedanteria: una ROM che dice "1.0" nello
 * splash e "1.1" nel tag e' peggio di una senza versione, perche' non si sa
 * piu' quale delle due sia il numero di cui parla chi segnala un problema.
 *
 * Il formato {@code "$VER: nome versione (gg.mm.aaaa)"} e' la convenzione con
 * cui l'Amiga marca i propri binari — quella che il comando {@code version}
 * cerca dentro il file. La data e' quella di rilascio della versione, non
 * quella della build: cosi' due build della stessa versione producono
 * un'immagine identica byte per byte, e il {@code .rom} versionato nel
 * repository non cambia a ogni ricompilazione.
 */
public final class RomVersion {

    public static final int MAJOR = 1;
    public static final int MINOR = 0;

    /** Data di rilascio di questa versione, nel formato del tag {@code $VER}. */
    public static final String DATE = "04.08.2026";

    /** Nome con cui la ROM si presenta. */
    public static final String NAME = "AmiJa ROM";

    /** {@code "1.0"} */
    public static final String VERSION = MAJOR + "." + MINOR;

    /** {@code "AmiJa ROM 1.0"}: quello che si legge nello splash. */
    public static final String TITLE = NAME + " " + VERSION;

    /** Il tag come finisce nell'immagine, senza il terminatore. */
    public static final String TAG = "$VER: " + TITLE + " (" + DATE + ")";

    private RomVersion() {}

    /** Il sorgente di {@code rom/version.i}, che {@code amija.asm} include. */
    public static String includeSource() {
        return """
                ;   AmiJa ROM - tag di versione
                ;   SPDX-License-Identifier: GPL-3.0-or-later
                ;
                ;   File GENERATO da romgen/RomVersion, riscritto a ogni build:
                ;   non modificarlo a mano, la versione si cambia in RomVersion.java.

                romVersionTag:
                            dc.b    '%s',0
                            even
                """.formatted(TAG);
    }
}
