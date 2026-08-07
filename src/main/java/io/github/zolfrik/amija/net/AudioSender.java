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

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Lato host dell'audio: prende il chunk PCM gia' pronto e lo spedisce
 * ({@code docs/remote-play-plan.md} §4.2).
 *
 * <h2>Dove si prende, e perche' proprio li'</h2>
 * In {@code JavaSoundAudioOutput} il chunk e' gia' convertito a S16LE e ha gia' passato il
 * filtro RC e, se acceso, quello del LED. Prenderlo un istante prima di {@code line.write()}
 * costa niente, sta <b>fuori dal thread di emulazione</b>, e fa sentire all'ospite
 * <b>esattamente</b> quel che sente l'host — non una ricostruzione che gli somiglia.
 *
 * <p>Il rovescio della medaglia va detto e sta nel {@code .tip} del popup: sull'ospite la
 * spunta «filtro LED» non ha effetto, perche' quel filtro e' gia' dentro i campioni che
 * riceve. Lo decide chi ospita.
 *
 * <h2>Mai accodare</h2>
 * Come per il video: se il socket non e' pronto il blocco si salta. Un blocco audio in
 * ritardo non serve a nessuno — l'ospite lo scarterebbe comunque, perche' il suo indice
 * sarebbe gia' passato.
 */
public final class AudioSender {

    private final int format;
    private final byte[] blob;
    private final byte[] fragment =
            new byte[RemotePlayProtocol.FRAGMENT_HEADER + RemotePlayProtocol.FRAGMENT_DATA];

    private long sampleIndex;
    private int blockId;
    private long blocksSent;
    private long bytesSent;
    private long blocksDropped;

    public AudioSender(int format) {
        if (format != AudioBlockHeader.FORMAT_PCM16 && format != AudioBlockHeader.FORMAT_ADPCM) {
            throw new IllegalArgumentException("formato audio non valido: " + format);
        }
        this.format = format;
        this.blob = new byte[AudioBlockHeader.SIZE
                + AudioBlockHeader.MAX_FRAMES * 4];
    }

    public int format() {
        return format;
    }

    /**
     * Codifica e spedisce un chunk.
     *
     * @param pcm    S16LE stereo interlacciato, {@code frames*4} byte
     * @param frames campioni per canale
     */
    public void sendBlock(byte[] pcm, int frames, VideoSender.FragmentSink sink)
            throws IOException {
        if (frames < 1 || frames > AudioBlockHeader.MAX_FRAMES) {
            throw new IllegalArgumentException("blocco di " + frames + " campioni");
        }
        AudioBlockHeader header = new AudioBlockHeader(sampleIndex, format, frames);
        ByteBuffer b = RemotePlayProtocol.wrap(blob, 0, AudioBlockHeader.SIZE);
        header.writeTo(b);

        int payload;
        if (format == AudioBlockHeader.FORMAT_ADPCM) {
            payload = ImaAdpcm.encode(pcm, 0, frames, blob, AudioBlockHeader.SIZE);
        } else {
            payload = frames * 4;
            System.arraycopy(pcm, 0, blob, AudioBlockHeader.SIZE, payload);
        }
        int blobLength = AudioBlockHeader.SIZE + payload;

        int fragCount = (blobLength + RemotePlayProtocol.FRAGMENT_DATA - 1)
                / RemotePlayProtocol.FRAGMENT_DATA;
        if (fragCount > RemotePlayProtocol.MAX_FRAGMENTS) {
            blocksDropped++;
            sampleIndex += frames;
            return;
        }
        int id = blockId++;
        for (int i = 0; i < fragCount; i++) {
            int off = i * RemotePlayProtocol.FRAGMENT_DATA;
            int n = Math.min(RemotePlayProtocol.FRAGMENT_DATA, blobLength - off);
            fragment[0] = (byte) id;
            fragment[1] = (byte) (id >>> 8);
            fragment[2] = (byte) (id >>> 16);
            fragment[3] = (byte) (id >>> 24);
            fragment[4] = (byte) i;
            fragment[5] = (byte) (i >>> 8);
            fragment[6] = (byte) fragCount;
            fragment[7] = (byte) (fragCount >>> 8);
            System.arraycopy(blob, off, fragment, RemotePlayProtocol.FRAGMENT_HEADER, n);
            sink.send(fragment, RemotePlayProtocol.FRAGMENT_HEADER + n);
        }

        // L'indice avanza SEMPRE, anche se il blocco non e' partito: e' un orologio, non un
        // conteggio di cio' che e' arrivato. Se avanzasse solo sui blocchi spediti, l'ospite
        // non avrebbe modo di accorgersi di un buco.
        sampleIndex += frames;
        blocksSent++;
        bytesSent += blobLength;
    }

    /** Riparte da zero: nuova sessione, nuovo orologio dei campioni. */
    public void reset() {
        sampleIndex = 0;
        blockId = 0;
    }

    public long blocksSent() {
        return blocksSent;
    }

    public long bytesSent() {
        return bytesSent;
    }

    public long blocksDropped() {
        return blocksDropped;
    }

    /** Bitrate medio del solo audio, in kbit/s, sul rate di uscita indicato. */
    public double bitrateKbps(int sampleRate) {
        if (blocksSent == 0) {
            return 0;
        }
        double seconds = (double) sampleIndex / sampleRate;
        return seconds <= 0 ? 0 : bytesSent * 8.0 / seconds / 1000.0;
    }
}
