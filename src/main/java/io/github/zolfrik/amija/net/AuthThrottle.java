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

import java.net.InetAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quanto in fretta si puo' sbagliare il join code, per indirizzo
 * ({@code docs/remote-play-plan.md} §8).
 *
 * <h2>Il codice ha ~40 bit, ma i tentativi erano illimitati</h2>
 * L'handshake era gia' costoso da indovinare — PBKDF2 a {@value SessionCrypto#PBKDF2_ROUNDS}
 * giri — ma non c'era <b>niente</b> che limitasse quante volte lo si potesse ritentare, e
 * quella lentezza da sola non basta a chiudere due cose diverse:
 * <ul>
 *   <li><b>indovinare</b>. Un codice di 8 simboli e' comodo da leggere al telefono proprio
 *       perche' e' corto, e la stessa sessione lo tiene per tutto il tempo in cui ospita.
 *       Senza un freno, chi ci prova ha a disposizione tutte le ore in cui si gioca;</li>
 *   <li><b>far lavorare la vittima</b>. La derivazione costa ~60 ms di CPU <i>all'host</i>,
 *       e la paga <b>prima</b> di sapere se il codice e' giusto: e' l'attaccante a scegliere
 *       quanto lavoro far fare, gratis. Un ciclo di connessioni tiene occupato il thread che
 *       accoglie e ruba tempo all'emulazione, che gira sulla stessa macchina.</li>
 * </ul>
 *
 * <h2>Il freno cresce, e non tocca chi sbaglia a digitare</h2>
 * I primi {@value #FREE_ATTEMPTS} errori non costano niente: un codice letto male al
 * telefono e' il caso normale, e punirlo trasformerebbe una difesa in un guasto. Dopo, ogni
 * errore raddoppia l'attesa — {@value #FIRST_BACKOFF_MS} ms, poi il doppio, fino a
 * {@value #MAX_BACKOFF_MS} — e in quell'attesa il rifiuto arriva <b>prima</b> della
 * derivazione, quindi non costa piu' niente a chi lo subisce. Un handshake riuscito azzera
 * tutto: chi il codice ce l'ha non porta con se' gli errori di prima.
 *
 * <p>Il conto e' <b>per indirizzo</b>. Non e' una barriera contro chi ne ha molti — quella
 * la fa la lunghezza del codice — ma contro l'unico attaccante che si presenta sempre dallo
 * stesso posto, che e' la forma in cui il problema esiste davvero su una rete di casa.
 *
 * <p>La tabella ha un tetto di {@value #MAX_TRACKED} indirizzi, ripulita dalle voci scadute
 * prima di crescere: senza, sarebbe lei la memoria che un attaccante puo' far allocare.
 */
final class AuthThrottle {

    /** Errori che non costano niente: un codice letto male al telefono ci sta. */
    static final int FREE_ATTEMPTS = 5;

    /** Attesa dopo il primo errore oltre la soglia. Raddoppia a ogni errore successivo. */
    static final long FIRST_BACKOFF_MS = 2_000;

    /** Tetto dell'attesa: oltre, si punirebbe l'utente distratto quanto l'attaccante. */
    static final long MAX_BACKOFF_MS = 5 * 60_000;

    /** Dopo tanto silenzio, un indirizzo torna pulito: gli errori di ieri non contano. */
    static final long FORGET_MS = 30 * 60_000;

    /** Indirizzi seguiti contemporaneamente. */
    static final int MAX_TRACKED = 256;

    private static final class Entry {
        int failures;
        long blockedUntilNanos;
        long lastSeenNanos;
    }

    private final Map<InetAddress, Entry> entries = new ConcurrentHashMap<>();

    /** Sorgente del tempo, sostituibile nei test: {@link System#nanoTime} in produzione. */
    private final java.util.function.LongSupplier clock;

    AuthThrottle() {
        this(System::nanoTime);
    }

    AuthThrottle(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Millisecondi che mancano prima che questo indirizzo possa riprovare, o 0 se puo' subito.
     *
     * <p>Va chiamata <b>prima</b> della derivazione della chiave: e' li' che sta il costo che
     * si vuole non far pagare all'host.
     */
    long retryAfterMillis(InetAddress peer) {
        if (peer == null) {
            return 0;
        }
        Entry e = entries.get(peer);
        if (e == null) {
            return 0;
        }
        synchronized (e) {
            long remaining = e.blockedUntilNanos - clock.getAsLong();
            return remaining > 0 ? (remaining / 1_000_000L) + 1 : 0;
        }
    }

    /** Un handshake non arrivato in fondo: il prossimo tentativo puo' costare un'attesa. */
    void recordFailure(InetAddress peer) {
        if (peer == null) {
            return;
        }
        long now = clock.getAsLong();
        Entry e = entries.get(peer);
        if (e == null) {
            // La voce nuova si aggiunge solo se c'e' posto, e il posto si cerca prima
            // buttando via quelle dimenticabili. Se non ce n'e', questo indirizzo non viene
            // seguito: vedi evictIfCrowded per il perche' non se ne sfratta una viva.
            evictIfCrowded(now);
            if (entries.size() >= MAX_TRACKED) {
                return;
            }
            e = entries.computeIfAbsent(peer, k -> new Entry());
        }
        synchronized (e) {
            e.lastSeenNanos = now;
            e.failures++;
            if (e.failures > FREE_ATTEMPTS) {
                int over = e.failures - FREE_ATTEMPTS;
                // 2s, 4s, 8s... e poi il tetto. Lo shift si ferma a 30 per non avvolgersi.
                long backoff = FIRST_BACKOFF_MS << Math.min(over - 1, 30);
                if (backoff <= 0 || backoff > MAX_BACKOFF_MS) {
                    backoff = MAX_BACKOFF_MS;
                }
                e.blockedUntilNanos = now + backoff * 1_000_000L;
            }
        }
    }

    /** Handshake riuscito: chi conosce il codice non si porta dietro gli errori di prima. */
    void recordSuccess(InetAddress peer) {
        if (peer != null) {
            entries.remove(peer);
        }
    }

    /** Quanti indirizzi si stanno seguendo. Diagnostica. */
    int tracked() {
        return entries.size();
    }

    /**
     * Butta via le voci dimenticabili, e solo se la tabella e' piena.
     *
     * <p>Se dopo la pulizia e' ancora piena, la voce nuova <b>non</b> viene aggiunta a spese
     * di una vecchia, ed e' la scelta che conta: sfrattare per fare posto vorrebbe dire che
     * chi sta provando a tappeto puo' <b>farsi dimenticare</b> riempiendo la tabella da
     * altri indirizzi, cioe' che il freno si disinnesca proprio sotto l'attacco per cui
     * esiste. Meglio smettere di seguire i nuovi arrivati: il tetto e' abbastanza alto che
     * si arrivi qui solo sotto attacco, e li' l'unica cosa che deve reggere e' che chi e'
     * gia' bloccato resti bloccato. Chi non viene seguito non e' comunque libero di
     * indovinare: davanti ha ancora i ~40 bit del codice e i ~60 ms di PBKDF2 per tentativo.
     */
    private void evictIfCrowded(long now) {
        if (entries.size() < MAX_TRACKED) {
            return;
        }
        Iterator<Map.Entry<InetAddress, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            boolean stale;
            synchronized (e) {
                stale = now - e.lastSeenNanos > FORGET_MS * 1_000_000L
                        && e.blockedUntilNanos - now <= 0;
            }
            if (stale) {
                it.remove();
            }
        }
    }
}
