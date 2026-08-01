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

package io.github.zolfrik.amija.input;

import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tasto host associato a una funzione del joystick da tastiera.
 *
 * <p>Un tasto è identificato dalla coppia (<i>key code</i> AWT, <i>posizione</i>): la
 * posizione distingue i tasti "doppi" della tastiera — Ctrl/Shift/Alt sinistro e destro,
 * e il tastierino numerico — che condividono lo stesso key code. Ad esempio il Ctrl
 * sinistro e quello destro sono entrambi {@code VK_CONTROL} e si distinguono solo per
 * {@link KeyEvent#KEY_LOCATION_LEFT} / {@link KeyEvent#KEY_LOCATION_RIGHT}.
 *
 * <p>In {@code amiga.properties} il tasto è scritto come nome simbolico della costante
 * {@code KeyEvent.VK_*} senza prefisso, con la posizione come suffisso quando non è
 * quella standard: {@code W}, {@code UP}, {@code CONTROL:RIGHT}, {@code SHIFT:LEFT}.
 * È accettato anche il key code numerico puro, per robustezza.
 */
public record KeyBinding(int keyCode, int keyLocation) {

    private static final String LOC_LEFT   = "LEFT";
    private static final String LOC_RIGHT  = "RIGHT";
    private static final String LOC_NUMPAD = "NUMPAD";

    /** Nome simbolico {@code VK_*} (senza prefisso) &harr; key code AWT. */
    private static final Map<String, Integer> NAME_TO_CODE = new HashMap<>();
    private static final Map<Integer, String> CODE_TO_NAME = new HashMap<>();

    static {
        for (Field f : KeyEvent.class.getFields()) {
            String name = f.getName();
            if (!name.startsWith("VK_") || f.getType() != int.class
                    || !Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            try {
                int code = f.getInt(null);
                String token = name.substring(3);
                NAME_TO_CODE.put(token, code);
                // Più costanti possono condividere lo stesso codice: si sceglie sempre il
                // nome minore in ordine alfabetico, così il file di configurazione resta
                // stabile fra un avvio e l'altro (l'ordine dei campi non è garantito).
                String prev = CODE_TO_NAME.get(code);
                if (prev == null || token.compareTo(prev) < 0) CODE_TO_NAME.put(code, token);
            } catch (IllegalAccessException ignored) {
                // campo pubblico di una classe pubblica: non può accadere
            }
        }
    }

    public KeyBinding {
        keyLocation = normalize(keyLocation);
    }

    /** Tasto premuto in un evento AWT, con la sua posizione sulla tastiera. */
    public static KeyBinding fromEvent(KeyEvent e) {
        return new KeyBinding(e.getKeyCode(), e.getKeyLocation());
    }

    /** {@code true} se l'evento riguarda esattamente questo tasto (codice e posizione). */
    public boolean matches(KeyEvent e) {
        return e.getKeyCode() == keyCode && normalize(e.getKeyLocation()) == keyLocation;
    }

    /** Valore da scrivere in {@code amiga.properties} (vedi documentazione di classe). */
    public String configValue() {
        String name = CODE_TO_NAME.get(keyCode);
        String base = (name != null) ? name : Integer.toString(keyCode);
        return switch (keyLocation) {
            case KeyEvent.KEY_LOCATION_LEFT   -> base + ':' + LOC_LEFT;
            case KeyEvent.KEY_LOCATION_RIGHT  -> base + ':' + LOC_RIGHT;
            case KeyEvent.KEY_LOCATION_NUMPAD -> base + ':' + LOC_NUMPAD;
            default -> base;
        };
    }

    /**
     * Converte il valore letto da {@code amiga.properties} nel tasto corrispondente,
     * oppure {@code null} se la stringa è vuota, non riconosciuta o indica ESC (che non
     * è associabile: nel popup di configurazione annulla l'operazione).
     */
    public static KeyBinding parse(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;

        int location = KeyEvent.KEY_LOCATION_STANDARD;
        int colon = v.indexOf(':');
        if (colon >= 0) {
            String loc = v.substring(colon + 1).trim().toUpperCase(Locale.ROOT);
            location = switch (loc) {
                case LOC_LEFT   -> KeyEvent.KEY_LOCATION_LEFT;
                case LOC_RIGHT  -> KeyEvent.KEY_LOCATION_RIGHT;
                case LOC_NUMPAD -> KeyEvent.KEY_LOCATION_NUMPAD;
                default -> KeyEvent.KEY_LOCATION_STANDARD;
            };
            v = v.substring(0, colon).trim();
        }

        String token = v.toUpperCase(Locale.ROOT);
        if (token.startsWith("VK_")) token = token.substring(3);
        Integer code = NAME_TO_CODE.get(token);
        if (code == null) {
            try {
                code = Integer.valueOf(v);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (code == KeyEvent.VK_UNDEFINED || code == KeyEvent.VK_ESCAPE) return null;
        return new KeyBinding(code, location);
    }

    /**
     * Nome simbolico della costante {@code KeyEvent.VK_*} (senza prefisso), o
     * {@code null} se il key code non corrisponde ad alcuna costante nota. Serve a
     * cercare il nome localizzato del tasto ({@code key.name.<NOME>}).
     */
    public String keyName() {
        return CODE_TO_NAME.get(keyCode);
    }

    /**
     * Nome del tasto secondo AWT, senza indicazione della posizione (es. {@code "W"},
     * {@code "Up"}, {@code "Ctrl"}). È tradotto nella lingua della JVM, non in quella
     * scelta per l'interfaccia: va usato come ripiego quando manca la voce
     * {@code key.name.<NOME>} nel bundle dei messaggi.
     */
    public String keyText() {
        return KeyEvent.getKeyText(keyCode);
    }

    /**
     * Chiave i18n con cui qualificare {@link #keyText()} quando il tasto ha una posizione
     * specifica (sinistra/destra/tastierino), o {@code null} per la posizione standard.
     */
    public String locationMessageKey() {
        return switch (keyLocation) {
            case KeyEvent.KEY_LOCATION_LEFT   -> "key.location.left";
            case KeyEvent.KEY_LOCATION_RIGHT  -> "key.location.right";
            case KeyEvent.KEY_LOCATION_NUMPAD -> "key.location.numpad";
            default -> null;
        };
    }

    /** Posizione sconosciuta e standard sono equivalenti: si normalizzano a standard. */
    private static int normalize(int location) {
        return switch (location) {
            case KeyEvent.KEY_LOCATION_LEFT, KeyEvent.KEY_LOCATION_RIGHT,
                 KeyEvent.KEY_LOCATION_NUMPAD -> location;
            default -> KeyEvent.KEY_LOCATION_STANDARD;
        };
    }
}
