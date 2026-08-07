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
import io.github.zolfrik.amija.net.codec.CodecException;
import io.github.zolfrik.amija.net.codec.FrameDecoder;

import java.nio.ByteBuffer;

/**
 * Lato ospite del video: rimette insieme i frammenti, decodifica e consegna il raster
 * ({@code docs/remote-play-plan.md} §4.1). E' il gemello di {@link VideoSender}.
 *
 * <h2>Perche' e' questo l'oggetto che "sostituisce VirtualCRT"</h2>
 * Il §2 del piano osserva che {@code AmigaDisplay.renderFrame(int[], w, h, FrameInfo)} prende
 * il <b>raster grezzo</b> e fa tutto il resto da se': crop, scaling intero, overscan, shader,
 * LED, fullscreen. Questa classe produce esattamente quegli argomenti, quindi da qui in poi
 * l'ospite riusa la catena di presentazione che gia' esiste, senza saperne nulla, e tutte
 * quelle scelte restano <b>locali</b>.
 *
 * <h2>I frame vecchi si buttano, non si decodificano</h2>
 * Un frame che arriva dopo uno piu' recente non serve a niente — nessuno vuole vedere
 * indietro — ma soprattutto <b>non va scritto nell'anello</b>: {@code frameId} e
 * {@code frameId+4} condividono lo slot, quindi decodificare un ritardatario sfratterebbe un
 * riferimento piu' nuovo, cioe' farebbe danno per consegnare qualcosa di inutile. La regola
 * e' percio' secca: si accetta solo cio' che avanza.
 *
 * <h2>Un frame o si ricostruisce del tutto o non e' mai esistito</h2>
 * Non si ACKa niente che non sia stato decodificato con successo. E' il perno di tutto lo
 * schema: l'host comprime contro l'ultimo ACK, quindi un frame perso, incompleto, ostile o
 * semplicemente non decodificabile fa solo allargare il delta successivo. Non c'e' nessuna
 * strada per cui un errore qui diventi un riferimento sbagliato che si trascina.
 */
public final class VideoReceiver implements AutoCloseable {

    /** Riceve un frame ricostruito. Il raster e' valido solo per la durata della chiamata. */
    public interface FrameSink {
        void onFrame(int[] pixels, int width, int height, FrameInfo info, long frameId);
    }

    private final int width;
    private final int height;
    private final FragmentReassembler reassembler = new FragmentReassembler();
    private final FrameDecoder decoder;
    private final ReferenceRing ring;
    private final FrameInfo info = new FrameInfo();

    /** Letto dal thread che spedisce il feedback: e' il numero che l'ACK porta all'host. */
    private volatile long lastGoodFrameId = -1;

    private long framesDelivered;
    private long framesLate;
    private long framesNoReference;
    private long framesRejected;
    private long keyframesReceived;
    private long decodeNanos;

    public VideoReceiver(int width, int height) {
        this.width = width;
        this.height = height;
        this.decoder = new FrameDecoder(width, height);
        this.ring = new ReferenceRing(width * height);
    }

    /**
     * Accoglie il payload in chiaro di un datagramma video.
     *
     * @return {@code true} se il frame e' stato completato, decodificato e consegnato
     * @throws ProtocolException se il frammento e' malformato: il chiamante scarta e continua
     */
    public boolean accept(byte[] plain, int length, FrameSink sink) throws ProtocolException {
        FragmentReassembler.Slot slot = reassembler.accept(plain, length);
        if (slot == null) {
            return false;
        }
        long frameId = slot.frameId();
        if (frameId <= lastGoodFrameId) {
            framesLate++;
            return false;
        }

        int blobLength = slot.length();
        if (blobLength < VideoFrameHeader.SIZE) {
            throw new ProtocolException("blob di " + blobLength
                    + " byte: piu' corto della sua intestazione");
        }
        ByteBuffer b = RemotePlayProtocol.wrap(slot.data(), 0, VideoFrameHeader.SIZE);
        VideoFrameHeader header = VideoFrameHeader.readFrom(b, width, height, blobLength);

        int[] reference;
        if (header.isKeyframe()) {
            reference = null;
            keyframesReceived++;
        } else {
            reference = ring.get(header.refFrameId());
            if (reference == null) {
                // L'host ha compresso contro un frame che qui non c'e' piu'. Succede solo se
                // gli ACK si perdono a lungo; non e' un errore, e la cura arriva da se': non
                // ACKando, l'host finisce per non avere piu' riferimenti e manda un keyframe.
                framesNoReference++;
                return false;
            }
        }

        int[] dst = ring.buffer(frameId);
        long t0 = System.nanoTime();
        try {
            decoder.decode(slot.data(), VideoFrameHeader.SIZE, header.payloadLength(),
                    reference, dst);
        } catch (CodecException e) {
            // decode() e' tutto-o-niente: 'dst' non e' stato toccato, quindi lo slot resta
            // quello che era e nessun riferimento e' stato sporcato.
            framesRejected++;
            throw new ProtocolException("frame " + frameId + " non decodificabile", e);
        }
        decodeNanos += System.nanoTime() - t0;

        ring.commit(frameId);
        lastGoodFrameId = frameId;
        framesDelivered++;
        header.applyTo(info);
        if (sink != null) {
            sink.onFrame(dst, width, height, info, frameId);
        }
        return true;
    }

    /** Il numero che l'ospite mette nell'ACK: l'ultimo frame ricevuto <b>per intero</b>. */
    public long ackFrameId() {
        return lastGoodFrameId;
    }

    /** Riparte da zero: nuova generazione di sessione, nessun riferimento condiviso. */
    public void reset() {
        reassembler.reset();
        ring.clear();
        lastGoodFrameId = -1;
    }

    public long framesDelivered() {
        return framesDelivered;
    }

    /** Frame arrivati dopo uno piu' recente: scartati per non sfrattare un riferimento buono. */
    public long framesLate() {
        return framesLate;
    }

    /** Frame il cui riferimento era gia' uscito dall'anello: si aspetta il keyframe. */
    public long framesNoReference() {
        return framesNoReference;
    }

    /** Frame completi ma non decodificabili: payload corrotto o ostile. */
    public long framesRejected() {
        return framesRejected;
    }

    public long keyframesReceived() {
        return keyframesReceived;
    }

    public long droppedIncomplete() {
        return reassembler.droppedIncomplete();
    }

    public long duplicateFragments() {
        return reassembler.duplicates();
    }

    public double meanDecodeMs() {
        return framesDelivered == 0 ? 0 : decodeNanos / 1e6 / framesDelivered;
    }

    @Override
    public void close() {
        decoder.close();
    }
}
