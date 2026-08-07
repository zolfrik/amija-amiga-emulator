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
 * Rimette insieme i frammenti di un frame video ({@code docs/remote-play-plan.md} §4.1).
 *
 * <h2>Tutto o niente</h2>
 * Un frame incompleto <b>non viene mai mostrato</b> e non viene ACKato: l'host se ne accorge
 * e comprime il successivo contro un riferimento piu' vecchio. Non c'e' quindi nessuna
 * ritrasmissione da chiedere e nessun riassemblaggio parziale da disegnare — che sarebbero
 * l'una latenza e l'altra sporcizia a schermo.
 *
 * <h2>Perche' quattro slot e non uno</h2>
 * Con uno solo, due frame che si accavallano (normale: l'ultimo frammento di N arriva dopo
 * il primo di N+1, e su un link con jitter capita di continuo) si distruggerebbero a
 * vicenda. Con quattro, un frame incompleto resta in attesa finche' non arriva {@code N+4},
 * cioe' ~80 ms: piu' di quanto serva, e comunque un tempo <b>limitato e costante</b>.
 *
 * <h2>Tutto e' limitato a monte</h2>
 * {@code fragCount} arriva dalla rete e non viene mai usato per dimensionare niente: i
 * buffer sono allocati alla costruzione su {@link RemotePlayProtocol#MAX_FRAGMENTS}, e un
 * conteggio piu' grande e' un errore, non una richiesta. Anche la lunghezza dei frammenti e'
 * vincolata: tutti tranne l'ultimo devono essere <b>esattamente</b>
 * {@link RemotePlayProtocol#FRAGMENT_DATA} byte, perche' e' quello che rende l'offset di
 * ricomposizione un prodotto invece che una somma di lunghezze ricevute — e quindi
 * impossibile da spostare con frammenti su misura.
 */
public final class FragmentReassembler {

    /** Un frame in corso di ricomposizione. */
    public static final class Slot {
        long frameId = -1;
        int fragCount;
        int received;
        int totalLength = -1;
        final boolean[] have = new boolean[RemotePlayProtocol.MAX_FRAGMENTS];
        final byte[] data = new byte[RemotePlayProtocol.MAX_FRAGMENTS
                * RemotePlayProtocol.FRAGMENT_DATA];

        public long frameId() {
            return frameId;
        }

        public byte[] data() {
            return data;
        }

        public int length() {
            return totalLength;
        }

        boolean complete() {
            return fragCount > 0 && received == fragCount && totalLength >= 0;
        }

        void reset(long id, int count) {
            frameId = id;
            fragCount = count;
            received = 0;
            totalLength = -1;
            java.util.Arrays.fill(have, 0, count, false);
        }
    }

    private final Slot[] slots = new Slot[ReferenceRing.SLOTS];
    private long droppedIncomplete;
    private long duplicates;

    public FragmentReassembler() {
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new Slot();
        }
    }

    /**
     * Accoglie un frammento.
     *
     * @param plain payload in chiaro del datagramma: {@code frameId:u32, fragIdx:u16,
     *              fragCount:u16} seguiti dai dati
     * @return lo slot se il frame e' ora completo, altrimenti {@code null}
     * @throws ProtocolException se il frammento e' malformato o incoerente
     */
    public Slot accept(byte[] plain, int length) throws ProtocolException {
        if (length < RemotePlayProtocol.FRAGMENT_HEADER) {
            throw new ProtocolException("frammento di " + length + " byte: manca l'intestazione");
        }
        long frameId = ((plain[0] & 0xFFL)) | ((plain[1] & 0xFFL) << 8)
                | ((plain[2] & 0xFFL) << 16) | ((plain[3] & 0xFFL) << 24);
        int fragIdx = (plain[4] & 0xFF) | ((plain[5] & 0xFF) << 8);
        int fragCount = (plain[6] & 0xFF) | ((plain[7] & 0xFF) << 8);
        int dataLength = length - RemotePlayProtocol.FRAGMENT_HEADER;

        if (fragCount < 1 || fragCount > RemotePlayProtocol.MAX_FRAGMENTS) {
            throw new ProtocolException("fragCount " + fragCount + " fuori da [1, "
                    + RemotePlayProtocol.MAX_FRAGMENTS + "]");
        }
        if (fragIdx >= fragCount) {
            throw new ProtocolException("fragIdx " + fragIdx + " oltre fragCount " + fragCount);
        }
        if (dataLength > RemotePlayProtocol.FRAGMENT_DATA) {
            throw new ProtocolException("frammento di " + dataLength + " byte, massimo "
                    + RemotePlayProtocol.FRAGMENT_DATA);
        }
        boolean last = fragIdx == fragCount - 1;
        if (!last && dataLength != RemotePlayProtocol.FRAGMENT_DATA) {
            throw new ProtocolException("frammento intermedio di " + dataLength
                    + " byte: devono essere esattamente " + RemotePlayProtocol.FRAGMENT_DATA);
        }
        if (last && dataLength < 1) {
            throw new ProtocolException("ultimo frammento vuoto");
        }

        Slot slot = slots[(int) Math.floorMod(frameId, slots.length)];
        if (slot.frameId != frameId) {
            if (slot.frameId >= 0 && !slot.complete()) {
                droppedIncomplete++;
            }
            slot.reset(frameId, fragCount);
        } else if (slot.fragCount != fragCount) {
            throw new ProtocolException("fragCount incoerente per il frame " + frameId
                    + ": " + slot.fragCount + " poi " + fragCount);
        }
        if (slot.have[fragIdx]) {
            duplicates++;
            return null;
        }

        System.arraycopy(plain, RemotePlayProtocol.FRAGMENT_HEADER, slot.data,
                fragIdx * RemotePlayProtocol.FRAGMENT_DATA, dataLength);
        slot.have[fragIdx] = true;
        slot.received++;
        if (last) {
            slot.totalLength = fragIdx * RemotePlayProtocol.FRAGMENT_DATA + dataLength;
        }
        return slot.complete() ? slot : null;
    }

    /** Frame abbandonati perche' il loro slot e' servito a un frame piu' recente. */
    public long droppedIncomplete() {
        return droppedIncomplete;
    }

    /** Frammenti gia' visti: normale su un link che duplica, sospetto se sono tanti. */
    public long duplicates() {
        return duplicates;
    }

    public void reset() {
        for (Slot s : slots) {
            s.frameId = -1;
            s.fragCount = 0;
            s.received = 0;
            s.totalLength = -1;
        }
    }
}
