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

package io.github.zolfrik.amija.net.upnp;

/**
 * Che tipo di indirizzo IPv4 e' quello che il router dichiara di avere verso il mondo
 * ({@code docs/remote-play-plan.md} §7.3).
 *
 * <h2>Perche' e' la diagnosi che conta</h2>
 * Il modo piu' comune in cui il gioco a distanza su Internet fallira' non e' un router
 * scortese: e' il <b>CGNAT</b>. L'operatore mette il cliente dietro un proprio NAT, il
 * router ottiene la sua mappatura e la dichiara riuscita, ma il suo «indirizzo esterno» e'
 * a sua volta privato e dall'esterno non arriva niente. Senza questo controllo l'utente
 * vedrebbe «porta aperta» e poi un «Collega» che va in timeout, e andrebbe a cercare il
 * problema nel posto sbagliato — di solito il firewall, che non c'entra.
 *
 * <p>Si riconosce dall'indirizzo e basta, senza chiedere niente a nessun servizio esterno:
 * <b>100.64.0.0/10</b> e' lo spazio che la RFC 6598 riserva proprio a questo, e un
 * indirizzo RFC1918 al posto di quello pubblico dice la stessa cosa (router in cascata a
 * un altro router, che dal punto di vista di chi gioca e' lo stesso problema).
 *
 * <h2>Il testo si analizza a mano, di proposito</h2>
 * La stringa arriva da un apparecchio di rete, quindi non la si passa a
 * {@code InetAddress.getByName}: quel metodo, davanti a qualcosa che non e' un indirizzo,
 * <b>fa una risoluzione DNS</b>. Sarebbe una richiesta di rete verso un nome scelto da
 * chi ha risposto all'M-SEARCH, per rispondere a una domanda che e' pura aritmetica.
 */
public final class NatAddress {

    private NatAddress() {
    }

    /** Vero se l'indirizzo e' nello spazio del carrier-grade NAT ({@code 100.64.0.0/10}). */
    public static boolean isCarrierGrade(String address) {
        int[] o = parse(address);
        return o != null && o[0] == 100 && o[1] >= 64 && o[1] <= 127;
    }

    /** Vero per gli indirizzi RFC1918, il loopback, il link-local e {@code 0.0.0.0}. */
    public static boolean isPrivate(String address) {
        int[] o = parse(address);
        if (o == null) {
            return false;
        }
        return o[0] == 10                                   // 10.0.0.0/8
                || (o[0] == 172 && o[1] >= 16 && o[1] <= 31) // 172.16.0.0/12
                || (o[0] == 192 && o[1] == 168)              // 192.168.0.0/16
                || o[0] == 127                               // loopback
                || (o[0] == 169 && o[1] == 254)              // link-local
                || o[0] == 0;
    }

    /**
     * Vero se un altro giocatore, da Internet, puo' davvero arrivare a questo indirizzo.
     *
     * <p>Cioe': e' un IPv4 valido, non e' privato e non e' CGNAT. Non prova che i pacchetti
     * arrivino — quello lo impedisce ancora un firewall (§7.4) — ma prova che non arrivare
     * sia possibile.
     */
    public static boolean isReachable(String address) {
        return parse(address) != null && !isPrivate(address) && !isCarrierGrade(address);
    }

    /** I quattro ottetti, o {@code null} se non e' un IPv4 in forma decimale puntata. */
    private static int[] parse(String address) {
        if (address == null) {
            return null;
        }
        String[] parts = address.trim().split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) {
                return null;
            }
            int value = 0;
            for (int c = 0; c < parts[i].length(); c++) {
                char digit = parts[i].charAt(c);
                if (digit < '0' || digit > '9') {
                    return null;
                }
                value = value * 10 + (digit - '0');
            }
            if (value > 255) {
                return null;
            }
            octets[i] = value;
        }
        return octets;
    }
}
