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

import io.github.zolfrik.amija.display.FrameInfo;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Intestazione del <b>blob</b> di un frame video: metadati piu' la lunghezza del payload
 * codificato ({@code docs/remote-play-plan.md} §4.1).
 *
 * <h2>Perche' sta nel blob e non in ogni frammento</h2>
 * Il §4.1 disegnava questi campi dentro ogni datagramma. Ma un frame incompleto viene
 * <b>scartato intero</b> — mai riassemblaggi parziali a schermo — quindi ripetere i metadati
 * in ogni frammento paga per un'informazione che, se manca il frammento 0, non serve
 * comunque a nulla. Qui l'intestazione e' il prefisso del blob, il blob viene tagliato in
 * pezzi <b>tutti della stessa capacita'</b>, e il riassemblaggio diventa un semplice
 * {@code arraycopy} all'offset {@code fragIdx * FRAGMENT_DATA}: nessun caso speciale per il
 * primo frammento, nessuna aritmetica di offset che dipende dai dati ricevuti.
 *
 * <h2>Geometria ridondante, di proposito</h2>
 * {@code width}/{@code height} sono gia' concordati nel WELCOME. Vengono ripetuti perche'
 * il decodificatore ha buffer dimensionati su quella geometria, ed e' meglio che un frame
 * incoerente venga rifiutato dal <i>controllo</i> che dal buffer.
 */
public record VideoFrameHeader(
        long refFrameId,      // NO_REFERENCE = keyframe
        int codec,
        boolean hires,
        boolean interlaced,
        boolean evenField,
        int width,
        int height,
        int visibleX,
        int visibleY,
        int visibleWidth,
        int visibleHeight,
        long frameCount,
        int payloadLength) {

    public static final int SIZE = 38;

    public static VideoFrameHeader of(long refFrameId, int width, int height,
                                      FrameInfo info, int payloadLength) {
        return new VideoFrameHeader(refFrameId, RemotePlayProtocol.CODEC_AJV1,
                info.hires(), info.interlaced(), info.evenField(), width, height,
                info.visibleX(), info.visibleY(), info.visibleWidth(), info.visibleHeight(),
                info.frameCount(), payloadLength);
    }

    public boolean isKeyframe() {
        return refFrameId == RemotePlayProtocol.NO_REFERENCE;
    }

    public void writeTo(ByteBuffer b) {
        b.putInt((int) refFrameId);
        b.put((byte) codec);
        b.put((byte) ((hires ? 1 : 0) | (interlaced ? 2 : 0) | (evenField ? 4 : 0)));
        b.putShort((short) width);
        b.putShort((short) height);
        b.putInt(visibleX);
        b.putInt(visibleY);
        b.putInt(visibleWidth);
        b.putInt(visibleHeight);
        b.putLong(frameCount);
        b.putInt(payloadLength);
    }

    /**
     * Legge e <b>valida</b> un'intestazione contro la geometria concordata.
     *
     * @throws ProtocolException se il blob e' troncato o dichiara qualcosa di impossibile
     */
    public static VideoFrameHeader readFrom(ByteBuffer b, int expectedWidth, int expectedHeight,
                                            int blobLength) throws ProtocolException {
        try {
            long refFrameId = b.getInt() & 0xFFFFFFFFL;
            int codec = b.get() & 0xFF;
            int flags = b.get() & 0xFF;
            int width = b.getShort() & 0xFFFF;
            int height = b.getShort() & 0xFFFF;
            int visX = b.getInt();
            int visY = b.getInt();
            int visW = b.getInt();
            int visH = b.getInt();
            long frameCount = b.getLong();
            int payloadLength = b.getInt();

            if (codec != RemotePlayProtocol.CODEC_AJV1) {
                throw new ProtocolException("codec video sconosciuto: " + codec);
            }
            if ((flags & ~0x07) != 0) {
                throw new ProtocolException("flag di frame non riconosciuti: 0x"
                        + Integer.toHexString(flags));
            }
            if (width != expectedWidth || height != expectedHeight) {
                throw new ProtocolException("geometria " + width + "x" + height
                        + " diversa da quella concordata " + expectedWidth + "x" + expectedHeight);
            }
            if (payloadLength < 0 || payloadLength != blobLength - SIZE) {
                throw new ProtocolException("payload dichiarato " + payloadLength
                        + " byte, nel blob ce ne sono " + (blobLength - SIZE));
            }
            // La finestra visibile non dimensiona buffer, ma esce da qui e finisce dentro
            // FrameInfo, cioe' nella catena di presentazione locale, che la usa per centrare
            // l'immagine. Sono quattro interi arbitrari che arrivano dalla rete: sommarli
            // senza guardarli e' come minimo un'immagine spinta fuori dallo schermo, e i
            // valori estremi fanno avvolgere l'aritmetica di chi li combina. Il tetto e' la
            // geometria stessa del frame, che e' l'unica cosa di cui la finestra visibile
            // possa parlare.
            if (visX < 0 || visY < 0 || visW < 0 || visH < 0
                    || visX > width || visY > height
                    || visW > width || visH > height
                    || visX + visW > width || visY + visH > height) {
                throw new ProtocolException("finestra visibile " + visW + "x" + visH + " a ("
                        + visX + "," + visY + ") fuori dal raster " + width + "x" + height);
            }
            return new VideoFrameHeader(refFrameId, codec,
                    (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0,
                    width, height, visX, visY, visW, visH, frameCount, payloadLength);
        } catch (BufferUnderflowException e) {
            throw new ProtocolException("blob di frame troncato nell'intestazione", e);
        }
    }

    public void applyTo(FrameInfo info) {
        info.set(hires, interlaced, evenField, visibleX, visibleY, visibleWidth, visibleHeight);
        info.setFrameCount(frameCount);
    }
}
