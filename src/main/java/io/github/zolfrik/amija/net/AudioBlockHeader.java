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

import io.github.zolfrik.amija.net.codec.ImaAdpcm;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Intestazione del blocco audio <b>AJA1</b> ({@code docs/remote-play-plan.md} §4.2).
 *
 * <pre>
 *   u64 sampleIndex   indice ASSOLUTO del primo campione del blocco
 *   u8  format        PCM16 | ADPCM
 *   u16 frames        campioni per canale
 *   ... payload
 * </pre>
 *
 * <h2>Il contatore assoluto e' tutto il meccanismo</h2>
 * Senza, un blocco perso farebbe <b>slittare in avanti</b> tutto quello che segue: l'audio
 * resterebbe continuo ma andrebbe fuori sincrono col video, e ogni perdita successiva
 * aggiungerebbe il suo pezzetto di ritardo. Con l'indice assoluto l'ospite si accorge del
 * buco, ci mette **silenzio della lunghezza giusta** e riprende in fase.
 *
 * <p>E' la stessa idea dei contatori cumulativi del mouse (§4.3): dove la cosa naturale
 * sarebbe un delta, si spedisce una posizione — cosi' una perdita costa quel che ha perso e
 * non lascia strascichi.
 */
public record AudioBlockHeader(long sampleIndex, int format, int frames) {

    public static final int SIZE = 11;

    /** S16LE stereo, bit-esatto: quel che sente l'host, campione per campione. */
    public static final int FORMAT_PCM16 = 1;
    /** IMA-ADPCM 4:1: un quarto della banda, ogni blocco indipendente (§15.5). */
    public static final int FORMAT_ADPCM = 2;

    /** Campioni per canale in un blocco: il chunk di {@code JavaSoundAudioOutput}. */
    public static final int FRAMES_PER_BLOCK = 1024;

    /** Tetto di sicurezza: un blocco piu' lungo di cosi' non lo produce nessuno. */
    public static final int MAX_FRAMES = 4096;

    public static String formatName(int format) {
        return switch (format) {
            case FORMAT_PCM16 -> "PCM S16";
            case FORMAT_ADPCM -> "IMA-ADPCM";
            default -> "sconosciuto(" + format + ")";
        };
    }

    /** Byte di payload che questo blocco deve avere. */
    public int payloadBytes() {
        return format == FORMAT_ADPCM
                ? ImaAdpcm.blockBytes(frames, 2)
                : frames * 4;
    }

    public void writeTo(ByteBuffer b) {
        b.putLong(sampleIndex);
        b.put((byte) format);
        b.putShort((short) frames);
    }

    /**
     * Legge e <b>valida</b> un'intestazione.
     *
     * @param blobLength lunghezza totale del blob, intestazione compresa
     * @throws ProtocolException se il blocco e' troncato o dichiara qualcosa di impossibile
     */
    public static AudioBlockHeader readFrom(ByteBuffer b, int blobLength)
            throws ProtocolException {
        try {
            long sampleIndex = b.getLong();
            int format = b.get() & 0xFF;
            int frames = b.getShort() & 0xFFFF;

            if (format != FORMAT_PCM16 && format != FORMAT_ADPCM) {
                throw new ProtocolException("formato audio sconosciuto: " + format);
            }
            if (frames < 1 || frames > MAX_FRAMES) {
                throw new ProtocolException("blocco audio di " + frames
                        + " campioni, massimo " + MAX_FRAMES);
            }
            if (sampleIndex < 0) {
                throw new ProtocolException("indice di campione negativo: " + sampleIndex);
            }
            AudioBlockHeader h = new AudioBlockHeader(sampleIndex, format, frames);
            if (blobLength - SIZE != h.payloadBytes()) {
                throw new ProtocolException("blocco audio con " + (blobLength - SIZE)
                        + " byte di payload, attesi " + h.payloadBytes() + " per " + frames
                        + " campioni in " + formatName(format));
            }
            return h;
        } catch (BufferUnderflowException e) {
            throw new ProtocolException("blocco audio troncato nell'intestazione", e);
        }
    }
}
