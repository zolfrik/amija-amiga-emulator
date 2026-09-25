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

package io.github.zolfrik.amija.whdload;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lettore minimale delle icone AmigaDOS ({@code .info}), limitato a ciò che serve
 * qui: il <b>tool di default</b> e le <b>tooltype</b>.
 *
 * <p>In un pacchetto WHDLoad l'icona del gioco non è decorazione: è il file che
 * dice <i>come</i> va lanciato lo slave. Il tool di default è {@code WHDLoad} e le
 * tooltype portano gli argomenti — {@code SLAVE=}, {@code DATA=}, {@code PRELOAD},
 * {@code CUSTOM1=}, {@code QUITKEY=}… Senza di esse molti giochi si fermano
 * subito: chi tiene le immagini in una sottocartella dichiara {@code DATA=data},
 * e WHDLoad senza quell'argomento cerca i file nella cartella dello slave e non
 * li trova (DOS-Error #205).
 *
 * <p>Struttura del file (Workbench 1.x–3.x): {@code DiskObject} di 78 byte, poi in
 * ordine — se i puntatori corrispondenti non sono nulli — {@code DrawerData} (56
 * byte), l'immagine del gadget, l'immagine selezionata, la stringa del tool di
 * default, l'elenco delle tooltype e la {@code ToolWindow}. Ogni stringa è una
 * lunghezza a 32 bit (NUL incluso) seguita dai byte.
 */
final class AmigaIcon {

    /** Magic del {@code DiskObject}. */
    private static final int DO_MAGIC = 0xE310;
    private static final int DISK_OBJECT_SIZE = 78;
    private static final int DRAWER_DATA_SIZE = 56;
    private static final int IMAGE_SIZE = 20;

    private final String defaultTool;
    private final List<String> toolTypes;

    private AmigaIcon(String defaultTool, List<String> toolTypes) {
        this.defaultTool = defaultTool;
        this.toolTypes = toolTypes;
    }

    /** Tool di default dell'icona (mai {@code null}; vuoto se assente). */
    String defaultTool() { return defaultTool; }

    /** Tooltype dell'icona, nell'ordine in cui compaiono. */
    List<String> toolTypes() { return toolTypes; }

    /**
     * Legge {@code data} come icona AmigaDOS. Restituisce {@code null} se il file
     * non è un'icona leggibile: qui un'icona illeggibile non è un errore, è solo
     * un file che non ha nulla da dire sul lancio del gioco.
     */
    static AmigaIcon parse(byte[] data) {
        if (data == null || data.length < DISK_OBJECT_SIZE) return null;
        if (u16(data, 0) != DO_MAGIC) return null;

        int gadgetRender = i32(data, 4 + 18);
        int selectRender = i32(data, 4 + 22);
        int pDefaultTool = i32(data, 50);
        int pToolTypes   = i32(data, 54);
        int pDrawerData  = i32(data, 66);

        int pos = DISK_OBJECT_SIZE;
        if (pDrawerData != 0) pos += DRAWER_DATA_SIZE;
        if (gadgetRender != 0) { pos = skipImage(data, pos); if (pos < 0) return null; }
        if (selectRender != 0) { pos = skipImage(data, pos); if (pos < 0) return null; }

        String tool = "";
        if (pDefaultTool != 0) {
            String[] s = readString(data, pos);
            if (s == null) return null;
            tool = s[0];
            pos = Integer.parseInt(s[1]);
        }

        List<String> types = new ArrayList<>();
        if (pToolTypes != 0) {
            if (pos + 4 > data.length) return null;
            // Il campo memorizzato è (numero di tooltype + 1) * 4: è la dimensione
            // dell'array di puntatori terminato da NULL, non il conteggio.
            long raw = u32(data, pos);
            pos += 4;
            long count = raw / 4 - 1;
            if (count < 0 || count > 4096) return null;
            for (long i = 0; i < count; i++) {
                String[] s = readString(data, pos);
                if (s == null) break;
                types.add(s[0]);
                pos = Integer.parseInt(s[1]);
            }
        }
        return new AmigaIcon(tool, types);
    }

    /** Salta una {@code struct Image} e i suoi piani di bit. */
    private static int skipImage(byte[] d, int pos) {
        if (pos + IMAGE_SIZE > d.length) return -1;
        int width  = u16(d, pos + 4);
        int height = u16(d, pos + 6);
        int depth  = u16(d, pos + 8);
        long words = ((long) width + 15) / 16;
        long bytes = words * 2 * height * depth;
        long end = (long) pos + IMAGE_SIZE + bytes;
        if (end > d.length || end < 0) return -1;
        return (int) end;
    }

    /**
     * Legge una stringa (lunghezza a 32 bit NUL inclusa, poi i byte). Restituisce
     * {@code {valore, posizioneSuccessiva}} oppure {@code null} se non ci sta.
     */
    private static String[] readString(byte[] d, int pos) {
        if (pos + 4 > d.length) return null;
        long len = u32(d, pos);
        if (len < 1 || pos + 4 + len > d.length) return null;
        int n = (int) len - 1;      // esclude il NUL finale
        String s = new String(d, pos + 4, n, StandardCharsets.ISO_8859_1);
        return new String[] { s, Integer.toString(pos + 4 + (int) len) };
    }

    private static int u16(byte[] d, int o) { return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF); }
    private static int i32(byte[] d, int o) {
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16)
             | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }
    private static long u32(byte[] d, int o) { return i32(d, o) & 0xFFFFFFFFL; }
}
