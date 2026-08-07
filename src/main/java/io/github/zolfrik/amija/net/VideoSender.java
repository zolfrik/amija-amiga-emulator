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
import io.github.zolfrik.amija.net.codec.FrameEncoder;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Lato host del video: codifica un frame contro l'ultimo riferimento confermato dall'ospite
 * e lo taglia in frammenti da spedire ({@code docs/remote-play-plan.md} §4.1).
 *
 * <p>Non conosce i socket. Emette payload <b>in chiaro</b> a un {@link FragmentSink}, che li
 * sigilla e li spedisce. E' cio' che rende collaudabile senza rete tutta la parte che puo'
 * davvero sbagliare: scelta del riferimento, frammentazione, contabilita' degli ACK.
 *
 * <p>Non e' thread-safe: vive sul thread di codifica, che e' l'unico a chiamarlo. L'unico
 * campo condiviso e' l'ACK, che arriva dal thread di rete ed e' {@code volatile} — una
 * lettura leggermente vecchia costa al massimo un delta piu' grosso, mai un frame sbagliato.
 */
public final class VideoSender implements AutoCloseable {

    /** Riceve il payload in chiaro di un datagramma. Chi lo implementa lo sigilla e lo manda. */
    public interface FragmentSink {
        void send(byte[] plain, int length) throws IOException;
    }

    private static final int BLOB_CAPACITY =
            RemotePlayProtocol.MAX_FRAGMENTS * RemotePlayProtocol.FRAGMENT_DATA;

    private final int width;
    private final int height;
    private final FrameEncoder encoder;
    private final ReferenceRing ring;
    private final byte[] blob = new byte[BLOB_CAPACITY];
    private final byte[] fragment =
            new byte[RemotePlayProtocol.FRAGMENT_HEADER + RemotePlayProtocol.FRAGMENT_DATA];

    private long nextFrameId;
    private volatile long ackedFrameId = -1;
    private long framesSent;
    private long keyframesSent;
    private long bytesSent;
    private long framesTooBig;
    private long encodeNanos;

    public VideoSender(int width, int height) {
        this.width = width;
        this.height = height;
        this.encoder = new FrameEncoder(width, height);
        this.ring = new ReferenceRing(width * height);
    }

    /**
     * Codifica e spedisce un frame.
     *
     * <p>Il riferimento e' <b>l'ultimo frame che l'ospite ha confermato</b>, se l'anello lo
     * tiene ancora; altrimenti si manda un keyframe. Non c'e' nessun altro caso: niente
     * keyframe periodici, niente NACK, niente ritrasmissioni (§4.1).
     *
     * @return {@code true} se il frame e' partito, {@code false} se e' stato saltato perche'
     *         troppo grande per la frammentazione
     */
    public boolean sendFrame(int[] pixels, FrameInfo info, FragmentSink sink) throws IOException {
        long acked = ackedFrameId;
        int[] reference = acked >= 0 ? ring.get(acked) : null;
        long refFrameId = reference != null ? acked : RemotePlayProtocol.NO_REFERENCE;

        long t0 = System.nanoTime();
        int payloadLength = encoder.encode(pixels, reference);
        encodeNanos += System.nanoTime() - t0;

        int blobLength = VideoFrameHeader.SIZE + payloadLength;
        if (blobLength > BLOB_CAPACITY) {
            // Non e' mai accaduto sulle scene misurate — il keyframe piu' caro e' 22 KB
            // contro i 74 disponibili — ma un frame che non entra nella frammentazione va
            // saltato, non troncato: meglio un frame in meno che uno corrotto. Il frame
            // successivo ritenta, e non essendo stato ACKato nulla si perde.
            framesTooBig++;
            nextFrameId++;
            return false;
        }

        long frameId = nextFrameId++;
        ByteBuffer b = RemotePlayProtocol.wrap(blob, 0, VideoFrameHeader.SIZE);
        VideoFrameHeader.of(refFrameId, width, height, info, payloadLength).writeTo(b);
        System.arraycopy(encoder.payload(), 0, blob, VideoFrameHeader.SIZE, payloadLength);

        int fragCount = (blobLength + RemotePlayProtocol.FRAGMENT_DATA - 1)
                / RemotePlayProtocol.FRAGMENT_DATA;
        for (int i = 0; i < fragCount; i++) {
            int off = i * RemotePlayProtocol.FRAGMENT_DATA;
            int n = Math.min(RemotePlayProtocol.FRAGMENT_DATA, blobLength - off);
            fragment[0] = (byte) frameId;
            fragment[1] = (byte) (frameId >>> 8);
            fragment[2] = (byte) (frameId >>> 16);
            fragment[3] = (byte) (frameId >>> 24);
            fragment[4] = (byte) i;
            fragment[5] = (byte) (i >>> 8);
            fragment[6] = (byte) fragCount;
            fragment[7] = (byte) (fragCount >>> 8);
            System.arraycopy(blob, off, fragment, RemotePlayProtocol.FRAGMENT_HEADER, n);
            sink.send(fragment, RemotePlayProtocol.FRAGMENT_HEADER + n);
        }

        ring.store(frameId, pixels);
        framesSent++;
        bytesSent += blobLength;
        if (refFrameId == RemotePlayProtocol.NO_REFERENCE) {
            keyframesSent++;
        }
        return true;
    }

    /**
     * Registra l'ACK arrivato dall'ospite.
     *
     * <p>Gli ACK sono dati di rete come tutti gli altri: si accettano solo se avanzano e
     * solo se parlano di un frame che l'host ha davvero spedito. Un ACK "dal futuro" non
     * romperebbe niente — l'anello non lo terrebbe e si finirebbe su un keyframe — ma
     * scartarlo qui evita di doverci ragionare piu' in la'.
     */
    public void noteAck(long frameId) {
        if (frameId > ackedFrameId && frameId < nextFrameId) {
            ackedFrameId = frameId;
        }
    }

    /** Riparte da zero: nuova generazione di sessione, nessun riferimento condiviso. */
    public void reset() {
        ring.clear();
        ackedFrameId = -1;
        nextFrameId = 0;
    }

    public long framesSent() {
        return framesSent;
    }

    public long keyframesSent() {
        return keyframesSent;
    }

    public long bytesSent() {
        return bytesSent;
    }

    public long framesTooBig() {
        return framesTooBig;
    }

    public long ackedFrameId() {
        return ackedFrameId;
    }

    public double meanEncodeMs() {
        return framesSent == 0 ? 0 : encodeNanos / 1e6 / framesSent;
    }

    @Override
    public void close() {
        encoder.close();
    }
}
