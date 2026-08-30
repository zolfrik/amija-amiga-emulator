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

/**
 * Dove finiscono i tasti tradotti in codici grezzi Amiga.
 *
 * <p>Esiste per una ragione sola, ed e' il gioco a distanza: {@code AmigaKeyAdapter}
 * contiene la traduzione da {@code KeyEvent} AWT ai 128 codici grezzi, ed e' 500 righe di
 * mappe piu' tre trappole risolte a caro prezzo — il keyup mancante del primo shift su
 * Windows, il Ctrl sintetico che AltGr si porta dietro, le accentate italiane. Sull'ospite
 * quei tasti non devono andare a una {@code Keyboard} (che li' non esiste: l'ospite non
 * emula niente) ma dentro un pacchetto di rete.
 *
 * <p>Con questa interfaccia cambia solo <b>dove</b> vanno: la traduzione resta una, e resta
 * quella collaudata. Duplicarla per l'ospite avrebbe voluto dire mantenerne due copie e
 * scoprire le stesse trappole una seconda volta.
 */
public interface AmigaKeyTarget {

    /** @param rawKeyNum codice grezzo Amiga a 7 bit (vedi le costanti {@code KEY_*}) */
    void pressKey(int rawKeyNum);

    void releaseKey(int rawKeyNum);

    /**
     * Vero mentre CTRL+Amiga+Amiga risulta premuto e la macchina e' tenuta in reset dalla
     * tastiera.
     *
     * <p>Serve al poller di {@code AmigaKeyAdapter}, che mentre la combinazione e' premuta
     * sorveglia lo stato <i>fisico</i> dei tre tasti: se Windows perde uno dei keyup — e coi
     * due Alt lo perde — la macchina resterebbe ferma in reset per sempre.
     *
     * <p>Il default e' {@code false} ed e' la risposta giusta per l'ospite del gioco a
     * distanza: li' non c'e' nessuna macchina da tenere in reset, e la decisione se il
     * reset debba avvenire o no appartiene comunque all'host (piano §4.3), non a chi
     * preme i tasti.
     */
    default boolean isResetComboHeld() {
        return false;
    }

    /**
     * Vero se premere {@code rawKeyNum} <b>completerebbe adesso</b> CTRL+Amiga+Amiga, cioe'
     * se gli altri due tasti della terna risultano gia' premuti sulla macchina.
     *
     * <p>La usa il filtro dell'ospite ({@code input.RemoteInputApplier}) per il caso che il
     * suo solo pacchetto non puo' vedere: la terna formata <b>a cavallo delle due
     * tastiere</b>, con l'host che ne tiene due e l'ospite che porta la terza.
     *
     * <p>Il default e' {@code false} ed e' la risposta giusta dove non c'e' una macchina
     * dietro — l'ospite del gioco a distanza, i doppi di collaudo — perche' li' non c'e'
     * nessuna terna da completare.
     */
    default boolean resetComboPendingOn(int rawKeyNum) {
        return false;
    }
}
