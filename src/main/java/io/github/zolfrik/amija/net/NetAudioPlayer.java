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

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * L'uscita audio dell'ospite ({@code docs/remote-play-plan.md} §4.2).
 *
 * <h2>La trappola che questa classe esiste per evitare</h2>
 * I campioni che arrivano hanno <b>gia'</b> passato il filtro RC e, se acceso, quello del
 * LED: li ha applicati l'host prima di spedirli. Rimetterli dentro
 * {@code JavaSoundAudioOutput} vorrebbe dire filtrarli <b>due volte</b>, e il risultato non
 * sarebbe un po' piu' scuro — sarebbe sbagliato. Quindi si scrivono <b>direttamente</b> su
 * una {@code SourceDataLine}, e questa classe non condivide una riga con quella.
 *
 * <p>Corollario per la UI: sull'ospite la spunta «filtro LED» non ha effetto. Lo decide chi
 * ospita, e va scritto nel {@code .tip} (§10).
 *
 * <h2>La deriva dei clock, che non e' un dettaglio su dieci minuti</h2>
 * Host e ospite hanno due schede audio con quarzi diversi: una produce piu' campioni di
 * quanti l'altra ne consumi. Su qualche minuto il buffer dell'ospite si svuota o si riempie
 * fino a rompersi. L'ASR esistente sta sul <i>produttore</i> (Paula) e serve l'host; l'ospite
 * non puo' pilotarlo.
 *
 * <p>Rimedio locale: sorvegliare il proprio riempimento e, fuori da una banda morta,
 * <b>duplicare o saltare un campione ogni 1024</b>. E' lo 0,1%: al di sotto di qualunque
 * soglia di percezione, e sufficiente a compensare quarzi che differiscono molto meno di
 * cosi'. Correggere di piu' si sentirebbe; correggere solo fuori dalla banda morta evita di
 * inseguire il rumore di misura.
 *
 * <h2>Senza scheda audio non succede niente</h2>
 * Come {@code FloppySound}: se la linea non si apre, {@link #isActive()} e' {@code false} e
 * ogni chiamata e' un no-op. Un ospite senza audio deve poter giocare, e i collaudi girano su
 * macchine che una scheda audio non ce l'hanno.
 */
public final class NetAudioPlayer implements AudioReceiver.PcmSink {

    /** Riempimento a cui si punta: tre blocchi, ~70 ms. Copre il jitter senza aggiungere latenza. */
    public static final int TARGET_FILL_FRAMES = AudioBlockHeader.FRAMES_PER_BLOCK * 3;

    /** Banda morta attorno al bersaglio: dentro non si corregge nulla. */
    public static final int DEADBAND_FRAMES = AudioBlockHeader.FRAMES_PER_BLOCK;

    private final int sampleRate;
    private final int capacityFrames;
    private final byte[] ring;
    private final Object lock = new Object();

    private SourceDataLine line;
    private volatile boolean active;
    private volatile boolean running;
    private Thread thread;

    private int head;          // byte in scrittura
    private int tail;          // byte in lettura
    private int fillBytes;

    private long framesWritten;
    private long framesPlayed;
    private long underruns;
    private long overflows;
    private long framesDuplicated;
    private long framesDropped;

    public NetAudioPlayer(int sampleRate) {
        this.sampleRate = sampleRate;
        // Mezzo secondo di margine: abbastanza per assorbire una pausa dell'host o un
        // sussulto dello scheduler, non tanto da poter accumulare latenza senza accorgersene.
        this.capacityFrames = sampleRate / 2;
        this.ring = new byte[capacityFrames * 4];
    }

    /**
     * Apre la linea audio e avvia la riproduzione.
     *
     * @return {@code false} se non c'e' una scheda audio utilizzabile: non e' un errore
     */
    public boolean start() {
        AudioFormat format = new AudioFormat(sampleRate, 16, 2, true, false);
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format, AudioBlockHeader.FRAMES_PER_BLOCK * 4 * 8);
            line.start();
        } catch (LineUnavailableException | IllegalArgumentException e) {
            RemotePlaySettings.say("nessuna uscita audio disponibile: si gioca in silenzio"
                    + " (%s)", e.getMessage());
            line = null;
            return false;
        }
        active = true;
        running = true;
        thread = new Thread(this::playLoop, "amija-remoteplay-audio");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.NORM_PRIORITY, Thread.MAX_PRIORITY - 1));
        thread.start();
        return true;
    }

    public boolean isActive() {
        return active;
    }

    /** Campioni attualmente in coda: e' la telemetria che l'ospite riporta all'host (§4.3). */
    public int fillFrames() {
        synchronized (lock) {
            return fillBytes / 4;
        }
    }

    /** Riempimento in millisecondi, per il campo {@code audioFillMs} del feedback. */
    public int fillMillis() {
        return (int) Math.min(65535L, (long) fillFrames() * 1000 / sampleRate);
    }

    @Override
    public void onPcm(byte[] pcm, int offset, int frames) {
        if (!active) {
            return;
        }
        int bytes = frames * 4;
        synchronized (lock) {
            if (fillBytes + bytes > ring.length) {
                // Il consumatore non sta dietro: si butta il piu' vecchio, non il piu'
                // nuovo. Restare indietro e' l'unico modo di accumulare latenza, ed e'
                // esattamente cio' che non si vuole in un gioco.
                int excess = fillBytes + bytes - ring.length;
                tail = (tail + excess) % ring.length;
                fillBytes -= excess;
                overflows++;
            }
            int first = Math.min(bytes, ring.length - head);
            System.arraycopy(pcm, offset, ring, head, first);
            if (first < bytes) {
                System.arraycopy(pcm, offset + first, ring, 0, bytes - first);
            }
            head = (head + bytes) % ring.length;
            fillBytes += bytes;
            framesWritten += frames;
            lock.notifyAll();
        }
    }

    public void stop() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (line != null) {
            line.flush();
            line.close();
            line = null;
        }
        active = false;
    }

    public long framesPlayed() {
        return framesPlayed;
    }

    public long underruns() {
        return underruns;
    }

    public long overflows() {
        return overflows;
    }

    /** Campioni duplicati per inseguire un clock piu' lento: la deriva, misurata. */
    public long framesDuplicated() {
        return framesDuplicated;
    }

    /** Campioni saltati per inseguire un clock piu' veloce. */
    public long framesDropped() {
        return framesDropped;
    }

    // =====================================================================

    private void playLoop() {
        byte[] chunk = new byte[AudioBlockHeader.FRAMES_PER_BLOCK * 4];
        byte[] corrected = new byte[chunk.length + 4];
        // Priming: si parte con un cuscinetto, o il primo sussulto e' un underrun.
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 2000;
            while (running && fillBytes < TARGET_FILL_FRAMES * 4
                    && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        while (running) {
            int frames;
            int correction;
            synchronized (lock) {
                while (running && fillBytes < chunk.length) {
                    try {
                        lock.wait(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!running) {
                    return;
                }
                int fillFrames = fillBytes / 4;
                correction = driftCorrection(fillFrames);
                int first = Math.min(chunk.length, ring.length - tail);
                System.arraycopy(ring, tail, chunk, 0, first);
                if (first < chunk.length) {
                    System.arraycopy(ring, 0, chunk, first, chunk.length - first);
                }
                tail = (tail + chunk.length) % ring.length;
                fillBytes -= chunk.length;
                frames = chunk.length / 4;
            }

            int outFrames = applyCorrection(chunk, frames, correction, corrected);
            line.write(corrected, 0, outFrames * 4);
            framesPlayed += outFrames;
        }
    }

    /**
     * @return {@code +1} per duplicare un campione, {@code -1} per saltarne uno, 0 dentro la
     *         banda morta
     */
    private int driftCorrection(int fillFrames) {
        if (fillFrames > TARGET_FILL_FRAMES + DEADBAND_FRAMES) {
            return -1;    // troppa roba in coda: il nostro clock e' piu' lento, si accelera
        }
        if (fillFrames < TARGET_FILL_FRAMES - DEADBAND_FRAMES) {
            return +1;    // coda che si assottiglia: si rallenta
        }
        return 0;
    }

    /**
     * Duplica o salta <b>un</b> campione a meta' del blocco.
     *
     * <p>A meta' e non in testa: nel punto di giunzione fra due blocchi il segnale ha gia'
     * una discontinuita' potenziale, e sommarci la correzione la renderebbe piu' udibile.
     */
    private int applyCorrection(byte[] chunk, int frames, int correction, byte[] out) {
        if (correction == 0) {
            System.arraycopy(chunk, 0, out, 0, frames * 4);
            return frames;
        }
        int mid = frames / 2;
        if (correction > 0) {
            System.arraycopy(chunk, 0, out, 0, mid * 4);
            System.arraycopy(chunk, (mid - 1) * 4, out, mid * 4, 4);       // campione ripetuto
            System.arraycopy(chunk, mid * 4, out, (mid + 1) * 4, (frames - mid) * 4);
            framesDuplicated++;
            return frames + 1;
        }
        System.arraycopy(chunk, 0, out, 0, mid * 4);
        System.arraycopy(chunk, (mid + 1) * 4, out, mid * 4, (frames - mid - 1) * 4);
        framesDropped++;
        return frames - 1;
    }
}
