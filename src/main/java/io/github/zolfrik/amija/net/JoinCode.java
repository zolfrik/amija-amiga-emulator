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

package io.github.zolfrik.amija.net;

import java.security.SecureRandom;

/**
 * Il <i>join code</i>: 8 caratteri, ~40 bit, generati a ogni avvio del server e mostrati
 * nel popup ({@code docs/remote-play-plan.md} §8.1, §9).
 *
 * <p>L'alfabeto e' quello di Crockford base32 — 32 simboli da cui sono state <b>tolte</b>
 * le lettere che si confondono con le cifre. Il codice va letto ad alta voce al telefono o
 * incollato in una chat: I/L/O/U sono fuori perche' 1/1/0/V sono gia' dentro, e
 * {@link #normalise} le riporta comunque sulla cifra giusta invece di rifiutarle. Un codice
 * che fallisce perche' l'utente ha scritto O invece di 0 e' un bug di progettazione, non un
 * errore dell'utente.
 *
 * <p>Il trattino al centro (`K7M2-Q4XZ`) e' solo presentazione: {@link #normalise} lo
 * ignora, come ignora spazi e maiuscole/minuscole.
 */
public final class JoinCode {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 8;

    private JoinCode() {
    }

    /** Nuovo codice casuale, gia' formattato con il trattino. */
    public static String generate() {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(LENGTH + 1);
        for (int i = 0; i < LENGTH; i++) {
            if (i == LENGTH / 2) {
                sb.append('-');
            }
            sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * Riduce un codice scritto a mano alla sua forma canonica: maiuscolo, senza separatori,
     * con le confusioni tipografiche ricondotte alla cifra ({@code I,L -> 1}, {@code O -> 0}).
     *
     * <p>E' la funzione che l'intera derivazione di chiave usa come password, quindi
     * <b>deve</b> essere la stessa sui due lati: normalizzare da una parte sola significa
     * fallire l'autenticazione senza capire perche'.
     */
    public static String normalise(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = Character.toUpperCase(code.charAt(i));
            switch (c) {
                case '-', ' ', '_', '\t' -> { }
                case 'I', 'L' -> sb.append('1');
                case 'O' -> sb.append('0');
                case 'U' -> sb.append('V');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Vero se il codice, normalizzato, ha la forma giusta. Non dice se e' quello buono. */
    public static boolean isWellFormed(String code) {
        String n = normalise(code);
        if (n.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < n.length(); i++) {
            if (indexOf(n.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(char c) {
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == c) {
                return i;
            }
        }
        return -1;
    }
}
