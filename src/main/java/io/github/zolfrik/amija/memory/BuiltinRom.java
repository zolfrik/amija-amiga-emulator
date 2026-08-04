/*
 * AmiJa - an Amiga 500 emulator
 * Copyright (C) 2026  zolfrik
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

    private static byte[] cached;

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
}
