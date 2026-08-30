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

package io.github.zolfrik.amija.memory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * La ROM di avvio AmiJa, inclusa nel jar.
 *
 * E' un firmware originale scritto per questo progetto (sorgente in
 * {@code rom/}), non un Kickstart Commodore: si puo' quindi ridistribuire con
 * l'emulatore. Serve a far partire l'A500 anche a chi non possiede un
 * Kickstart, e avvia i giochi su floppy con bootblock autonomo.
 *
 * Non e' un sostituto completo del Kickstart: non implementa AmigaDOS, quindi
 * i dischi che si appoggiano a {@code dos.library} continuano a richiedere una
 * ROM Commodore.
 */
public final class BuiltinRom {

    /** Percorso nel classpath dell'immagine prodotta da {@code romgen/RomBuilder}. */
    public static final String RESOURCE = "/rom/amija.rom";

    /**
     * Inizio del tag di versione incorporato nell'immagine, nella forma
     * {@code "$VER: AmiJa ROM 1.0 (gg.mm.aaaa)"} con cui l'Amiga marca i propri
     * binari. Lo scrive {@code romgen/RomVersion} subito dopo i vettori di reset.
     */
    private static final String VERSION_TAG = "$VER: ";

    /** Come si chiama la ROM quando il tag non c'e' (immagine anteriore alla 1.0). */
    private static final String UNVERSIONED = "ROM AmiJa";

    private static byte[] cached;
    private static String version;

    private BuiltinRom() {}

    /** Vero se l'immagine e' presente nel jar. */
    public static boolean isAvailable() {
        try (InputStream in = BuiltinRom.class.getResourceAsStream(RESOURCE)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Carica l'immagine della ROM. Il risultato viene tenuto in cache: un cold
     * restart la rilegge e non ha senso ripagare l'estrazione dal jar.
     *
     * @throws IOException se l'immagine non e' nel jar, il che significa che la
     *                     build e' incompleta
     */
    public static synchronized byte[] load() throws IOException {
        if (cached != null) return cached.clone();
        try (InputStream in = BuiltinRom.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("ROM AmiJa non presente nel jar (" + RESOURCE + ")");
            }
            cached = in.readAllBytes();
        }
        return cached.clone();
    }

    /**
     * Come si presenta la ROM inclusa, p.es. {@code "AmiJa ROM 1.0"}: il tag
     * {@code $VER} dell'immagine, senza la data.
     *
     * Serve perche' chi segnala un problema possa dire con quale ROM lo ha
     * visto: il numero e' scritto nell'immagine, non in una costante Java, per
     * cui non puo' descrivere una ROM diversa da quella davvero montata.
     */
    public static synchronized String version() {
        if (version != null) return version;
        version = UNVERSIONED;
        try {
            byte[] rom = load();
            int at = indexOf(rom, VERSION_TAG.getBytes(StandardCharsets.ISO_8859_1));
            if (at >= 0) {
                int start = at + VERSION_TAG.length();
                int end = start;
                while (end < rom.length && rom[end] != 0) end++;
                String tag = new String(rom, start, end - start, StandardCharsets.ISO_8859_1);
                int date = tag.indexOf(" (");
                String name = (date > 0 ? tag.substring(0, date) : tag).trim();
                if (!name.isEmpty()) version = name;
            }
        } catch (IOException e) {
            // Immagine assente: il chiamante lo scoprira' da load(), qui basta
            // non far fallire una riga di log.
        }
        return version;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
