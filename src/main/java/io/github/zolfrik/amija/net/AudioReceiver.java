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

import io.github.zolfrik.amija.net.codec.CodecException;
import io.github.zolfrik.amija.net.codec.ImaAdpcm;

import java.nio.ByteBuffer;

/**
 * Lato ospite dell'audio: rimette insieme i blocchi, li decodifica e li consegna <b>in
 * ordine e in fase</b> ({@code docs/remote-play-plan.md} §4.2).
 *
 * <h2>Un buco si riempie di silenzio, non si chiude</h2>
 * E' la sola cosa che questa classe fa di non ovvio, ed e' quella che conta. Se un blocco si
 * perde e si semplicemente saltasse, l'audio resterebbe continuo ma <b>slitterebbe in avanti
 * di 23 ms</b>, e ogni perdita successiva aggiungerebbe la sua: dopo un minuto di link
 * mediocre l'audio sarebbe secondi avanti al video. Con l'indice assoluto del §4.2 il buco si
 * riconosce e si riempie di <b>silenzio della lunghezza esatta</b>: si perde quel che si e'
 * perso, e la fase resta.
 *
 * <p>Il caso opposto — un blocco che arriva dopo che il suo posto e' gia' passato — si
 * scarta. Rimetterlo in coda vorrebbe dire riprodurlo fuori posto, cioe' un artefatto al
 * posto di un silenzio gia' avvenuto.
 */
public final class AudioReceiver {

    /** Riceve PCM S16LE stereo pronto da suonare. */
    public interface PcmSink {
        void onPcm(byte[] pcm, int offset, int frames);
    }

    /**
     * Tetto al silenzio che si accetta di generare per un solo buco: mezzo secondo.
     *
     * <p>Oltre, non e' un buco ma una ripresa dopo un'interruzione lunga (o un indice
     * malevolo), e riempire di silenzio proporzionalmente vorrebbe dire allocare tempo — o
     * memoria — su richiesta di chi sta dall'altra parte. Si riparte in fase e basta.
     */
    public static final int MAX_GAP_FRAMES = 22_050;

    private final FragmentReassembler reassembler = new FragmentReassembler();
    private final byte[] pcm = new byte[AudioBlockHeader.MAX_FRAMES * 4];
    private final byte[] silence = new byte[4096];

    private long expectedSampleIndex = -1;
    private long blocksDelivered;
    private long blocksLate;
    private long gapFrames;
    private long gaps;
    private long resyncs;

    /**
     * Accoglie il payload in chiaro di un datagramma audio.
     *
     * @return {@code true} se un blocco e' stato completato e consegnato
     * @throws ProtocolException se il frammento o il blocco sono malformati
     */
    public boolean accept(byte[] plain, int length, PcmSink sink) throws ProtocolException {
        FragmentReassembler.Slot slot = reassembler.accept(plain, length);
        if (slot == null) {
            return false;
        }
        int blobLength = slot.length();
        if (blobLength < AudioBlockHeader.SIZE) {
            throw new ProtocolException("blocco audio di " + blobLength
                    + " byte: piu' corto della sua intestazione");
        }
        ByteBuffer b = RemotePlayProtocol.wrap(slot.data(), 0, AudioBlockHeader.SIZE);
        AudioBlockHeader header = AudioBlockHeader.readFrom(b, blobLength);

        if (expectedSampleIndex >= 0 && header.sampleIndex() < expectedSampleIndex) {
            // Il suo posto e' gia' passato: suonarlo adesso sarebbe un artefatto al posto di
            // un silenzio gia' avvenuto.
            blocksLate++;
            return false;
        }

        int frames = header.frames();
        if (header.format() == AudioBlockHeader.FORMAT_ADPCM) {
            try {
                ImaAdpcm.decode(slot.data(), AudioBlockHeader.SIZE,
                        blobLength - AudioBlockHeader.SIZE, frames, pcm, 0);
            } catch (CodecException e) {
                throw new ProtocolException("blocco ADPCM non decodificabile", e);
            }
        } else {
            System.arraycopy(slot.data(), AudioBlockHeader.SIZE, pcm, 0, frames * 4);
        }

        if (expectedSampleIndex >= 0 && header.sampleIndex() > expectedSampleIndex) {
            long missing = header.sampleIndex() - expectedSampleIndex;
            gaps++;
            if (missing <= MAX_GAP_FRAMES) {
                gapFrames += missing;
                emitSilence(missing, sink);
            } else {
                // Interruzione lunga: si riparte in fase invece di generare mezzo minuto
                // di silenzio per rispettare un orologio che ha smesso di essere utile.
                resyncs++;
            }
        }

        sink.onPcm(pcm, 0, frames);
        expectedSampleIndex = header.sampleIndex() + frames;
        blocksDelivered++;
        return true;
    }

    /** Riparte da zero: nuova sessione, nuovo orologio. */
    public void reset() {
        reassembler.reset();
        expectedSampleIndex = -1;
    }

    public long blocksDelivered() {
        return blocksDelivered;
    }

    /** Blocchi arrivati dopo il loro turno: scartati. */
    public long blocksLate() {
        return blocksLate;
    }

    /** Quanti buchi sono stati riempiti di silenzio. */
    public long gaps() {
        return gaps;
    }

    /** Quanti campioni di silenzio in totale: e' la misura vera di quanto audio si e' perso. */
    public long gapFrames() {
        return gapFrames;
    }

    /** Riallineamenti dopo un'interruzione piu' lunga di {@link #MAX_GAP_FRAMES}. */
    public long resyncs() {
        return resyncs;
    }

    public long droppedIncomplete() {
        return reassembler.droppedIncomplete();
    }

    private void emitSilence(long frames, PcmSink sink) {
        int chunk = silence.length / 4;
        while (frames > 0) {
            int n = (int) Math.min(chunk, frames);
            sink.onPcm(silence, 0, n);
            frames -= n;
        }
    }
}
