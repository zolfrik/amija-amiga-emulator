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

/**
 * Chi porta lo stato dei controlli dell'ospite dentro la macchina dell'host.
 *
 * <p>Esiste per tenere il gioco a distanza fuori dall'emulazione: {@code net/} sa spedire,
 * ricevere e autenticare, e non deve sapere che esistono una game port e una tastiera.
 * L'implementazione ({@code input.RemoteInputApplier}) sa il contrario.
 *
 * <p><b>Viene invocato dal thread di emulazione</b>, una volta per frame, tramite
 * {@link RemotePlayHost#applyPendingInput()} — mai dal thread di rete (piano §4.3).
 */
public interface GuestInputTarget {

    /**
     * @param state ultimo stato autenticato dell'ospite. E' un buffer <b>riusato</b>, valido
     *              solo per la durata della chiamata: chi lo riceve lo consuma, non lo tiene
     */
    void apply(FeedbackPacket state);

    /**
     * Riporta a riposo tutto cio' che l'ospite teneva premuto.
     *
     * <p>Fa parte del contratto e non e' una comodita': quando l'ospite se ne va non arriva
     * piu' nessuno stato che contraddica l'ultimo, quindi il tasto o il fuoco che aveva
     * premuto <b>resterebbero premuti per sempre</b>. Non perche' il protocollo abbia perso
     * qualcosa — proprio perche' non ha piu' niente da dire.
     *
     * <p>Come {@link #apply}, va invocato dal <b>thread di emulazione</b>: tocca la macchina.
     */
    void releaseAll();
}
