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
 * Anello di frame di riferimento, tenuto identico sui due lati della sessione
 * ({@code docs/remote-play-plan.md} §4.1).
 *
 * <h2>Il meccanismo che rende inutili ritrasmissioni, NACK e keyframe periodici</h2>
 * L'host comprime sempre contro <b>l'ultimo frame che l'ospite ha confermato di avere per
 * intero</b>. Se un frame si perde, l'ospite semplicemente non lo conferma: l'host se ne
 * accorge e comprime il successivo contro un riferimento piu' vecchio. Il delta e' piu'
 * grosso — misurato in Fase 0: al massimo il doppio, e meno quanto piu' la scena e' gia'
 * cara — ma e' <b>corretto</b>. Il sistema si ripara da solo e degrada con continuita'.
 *
 * <p>Quattro slot, indicizzati da {@code frameId % 4}: tengono gli ultimi quattro frame
 * spediti. Se l'ospite resta indietro di piu' di quattro frame il riferimento che ha
 * confermato e' gia' stato sfrattato, e allora — e solo allora — si manda un keyframe.
 *
 * <p>L'indice per resto e' anche cio' che permette all'ospite di <b>decodificare in
 * loco</b>: quando {@code frameId} e {@code refFrameId} cadono nello stesso slot (cioe'
 * distano esattamente 4) la decodifica sovrascrive il proprio riferimento, che e' sicuro
 * per contratto di {@code FrameDecoder} ed e' anche esattamente cio' che si vuole — quel
 * riferimento sta per uscire dall'anello di entrambi i lati.
 */
public final class ReferenceRing {

    public static final int SLOTS = 4;

    private final long[] ids = new long[SLOTS];
    private final int[][] frames = new int[SLOTS][];
    private final int pixelCount;

    public ReferenceRing(int pixelCount) {
        this.pixelCount = pixelCount;
        for (int i = 0; i < SLOTS; i++) {
            ids[i] = -1;
            frames[i] = new int[pixelCount];
        }
    }

    private static int slot(long frameId) {
        return (int) Math.floorMod(frameId, SLOTS);
    }

    /** Il buffer di uno slot, valido o no: serve a decodificarci dentro prima di validarlo. */
    public int[] buffer(long frameId) {
        return frames[slot(frameId)];
    }

    /** Il frame richiesto, o {@code null} se l'anello non lo tiene piu'. */
    public int[] get(long frameId) {
        int s = slot(frameId);
        return ids[s] == frameId ? frames[s] : null;
    }

    public boolean holds(long frameId) {
        return ids[slot(frameId)] == frameId;
    }

    /** Copia {@code pixels} nello slot di {@code frameId}, sfrattando quel che c'era. */
    public void store(long frameId, int[] pixels) {
        int s = slot(frameId);
        System.arraycopy(pixels, 0, frames[s], 0, pixelCount);
        ids[s] = frameId;
    }

    /**
     * Dichiara valido lo slot di {@code frameId}, il cui buffer e' gia' stato riempito
     * tramite {@link #buffer}. Evita la copia sul lato ospite, che decodifica direttamente
     * nell'anello.
     */
    public void commit(long frameId) {
        ids[slot(frameId)] = frameId;
    }

    public void clear() {
        for (int i = 0; i < SLOTS; i++) {
            ids[i] = -1;
        }
    }
}
