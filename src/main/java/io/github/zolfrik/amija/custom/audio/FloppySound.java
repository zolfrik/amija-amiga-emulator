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

package io.github.zolfrik.amija.custom.audio;

import io.github.zolfrik.amija.common.AppHome;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Emulazione PURAMENTE ESTETICA dei suoni del floppy drive (click della testina,
 * ronzio del motore, avvio, "snatch" del disco), ispirata a {@code driveclick.cpp}
 * di WinUAE ma COMPLETAMENTE DISACCOPPIATA dall'emulazione e dalla pipeline audio
 * di Paula.
 *
 * <p>Filosofia: per questa feature le PRESTAZIONI hanno priorità assoluta
 * sull'accuratezza.
 * <ul>
 *   <li>I campioni WAV vengono caricati e decodificati una sola volta (cache
 *       statica) e riprodotti tramite {@link javax.sound.sampled.Clip}, gestiti
 *       dal mixer audio di sistema su thread propri: il thread di emulazione NON
 *       esegue mai mixing per-campione (a differenza di WinUAE).</li>
 *   <li>Gli eventi (motore/step/disco) vengono accodati su un singolo thread
 *       daemon dedicato tramite una ring queue preallocata, senza creare task per
 *       evento sul thread di emulazione.</li>
 * </ul>
 *
 * <p>L'intera feature si disabilita con un singolo flag booleano: se non viene
 * creata/agganciata alcuna istanza (o se {@code enabled=false}), il
 * {@code FloppyDrive} resta silenzioso a costo zero (solo un null-check sugli
 * eventi, che sono comunque rari). Attualmente viene abilitata solo in
 * {@code Amija}.
 */
public final class FloppySound {

    /** Variante di campioni da usare (prefisso file: drive_<tipo>_<VARIANT>.wav). */
    private static final String VARIANT = "A500";

    /**
     * Cartella dei campioni WAV, relativa alla home dell'applicazione (la stessa in cui
     * si trova {@code amiga.properties}). I file stanno lì e non dentro il jar, così
     * l'utente può sostituirli con i propri tenendo i nomi. Se la cartella manca o è
     * vuota la feature resta semplicemente inerte, come con
     * {@code floppy.sound.volume=0}.
     */
    private static final Path SAMPLE_DIR = AppHome.resolve("floppysounds");

    // I campioni vengono normalizzati a PCM signed 16-bit mono little-endian,
    // MANTENENDO la frequenza di campionamento NATIVA del file (i WAV WinUAE hanno
    // rate misti: click=48 kHz, resto=44.1 kHz). Evitare la conversione di sample-rate
    // è importante: i convertitori di default della JVM spesso NON la supportano,
    // mentre la sola normalizzazione di encoding/canali è sempre disponibile (e qui
    // è di fatto un'identità, dato che i file sono già 16-bit mono LE).
    private static final int FRAME_BYTES = 2; // mono 16-bit

    /** Campione decodificato: formato (con rate nativo) + PCM grezzo. */
    private record Sample(AudioFormat fmt, byte[] pcm) {}

    /** Lunghezza di un singolo click estratto dal WAV (frame). ~63 ms a 44.1 kHz. */
    private static final int CLICK_FRAMES = 2800;
    private static final int CLICK_POOL = 6;   // click sovrapponibili durante un seek
    private static final int EVENT_QUEUE_SIZE = 128;
    private static final int EVENT_MOTOR = 1;
    private static final int EVENT_STEP = 2;
    private static final int EVENT_DISK = 3;
    private static final int EVENT_GAIN = 4;
    private static final int EVENT_CLOSE = 5;

    /**
     * Attenuazione di default applicata a tutti i clip (dB) per non sovrastare l'audio
     * del gioco. Valore negativo = più silenzioso (ogni -6 dB ~ dimezza il volume
     * percepito). Sovrascrivibile dal costruttore o a runtime con {@link #setGainDb}.
     */
    public static final float DEFAULT_GAIN_DB = -18.0f;

    // Cache statica dei campioni decodificati (condivisa tra istanze): caricamento una volta sola.
    private static final Map<String, Sample> SAMPLE_CACHE = new HashMap<>();

    private final boolean active;          // false => istanza inerte (no audio / disabilitata)
    private volatile float gainDb;         // attenuazione corrente in dB (configurabile)
    private final Object eventLock = new Object();
    private final int[] eventTypes = new int[EVENT_QUEUE_SIZE];
    private final int[] eventFlags = new int[EVENT_QUEUE_SIZE];
    private final float[] eventValues = new float[EVENT_QUEUE_SIZE];
    private int eventHead;
    private int eventTail;
    private int eventSize;
    private volatile boolean workerRunning;
    private Thread worker;

    private Clip startupClip;
    private Clip snatchClip;
    private Clip spinClip;                  // motore con disco
    private Clip spinNdClip;                // motore senza disco (fallback a spinClip se assente)
    private Clip[] clickPool;
    private int clickIdx;

    // Stato interno (mutato solo sul thread worker).
    private boolean spinning;
    private boolean hasDisk;
    private Clip currentSpin;

    /**
     * @param enabled se {@code false} l'istanza non carica nulla e tutti i metodi
     *                sono no-op (feature disabilitata in toto).
     */
    public FloppySound(boolean enabled) {
        this(enabled, DEFAULT_GAIN_DB);
    }

    /**
     * @param enabled se {@code false} l'istanza non carica nulla e tutti i metodi
     *                sono no-op (feature disabilitata in toto).
     * @param gainDb  attenuazione in dB applicata a tutti i suoni del drive
     *                (negativo = più silenzioso). Modificabile a runtime con
     *                {@link #setGainDb}.
     */
    public FloppySound(boolean enabled, float gainDb) {
        this.gainDb = gainDb;
        boolean ok = false;
        if (enabled) {
            try {
                Sample click   = loadSample("drive_click_"   + VARIANT);
                Sample spin     = loadSample("drive_spin_"    + VARIANT);
                Sample spinNd   = loadSample("drive_spinnd_"  + VARIANT); // spesso assente per A500
                Sample startup  = loadSample("drive_startup_" + VARIANT);
                Sample snatch   = loadSample("drive_snatch_"  + VARIANT);

                startupClip = makeClip(startup);
                snatchClip  = makeClip(snatch);
                spinClip    = makeClip(spin);
                spinNdClip  = (spinNd != null) ? makeClip(spinNd) : spinClip;

                Sample oneClick = extractClick(click);
                if (oneClick != null) {
                    clickPool = new Clip[CLICK_POOL];
                    for (int i = 0; i < CLICK_POOL; i++) clickPool[i] = makeClip(oneClick);
                }

                // Almeno un campione caricato => feature operativa.
                ok = (startupClip != null || spinClip != null || clickPool != null);
                if (ok) {
                    workerRunning = true;
                    worker = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            workerLoop();
                        }
                    }, "floppy-sound");
                    worker.setDaemon(true);
                    worker.start();
                }
            } catch (Throwable t) {
                // Nessun dispositivo audio, risorse mancanti, ecc.: feature silenziosamente inattiva.
                System.out.println("[FLOPPYSND] disabilitato (init fallita): " + t);
                ok = false;
            }
        }
        this.active = ok;
    }

    // ── API eventi (chiamata dal thread di emulazione: enqueue su ring buffer) ──

    /** Motore acceso/spento. {@code hasDisk} = disco presente nel drive. */
    public void onMotor(boolean running, boolean hasDisk) {
        if (!active) return;
        enqueueEvent(EVENT_MOTOR, (running ? 1 : 0) | (hasDisk ? 2 : 0), 0.0f);
    }

    /** Impulso di step della testina (un click). */
    public void onStep() {
        if (!active) return;
        enqueueEvent(EVENT_STEP, 0, 0.0f);
    }

    /** Inserimento/espulsione del disco. */
    public void onDiskChange(boolean inserted) {
        if (!active) return;
        enqueueEvent(EVENT_DISK, inserted ? 1 : 0, 0.0f);
    }

    /**
     * Imposta a runtime il volume dei suoni del drive (dB, negativo = più silenzioso).
     * Applicato a tutti i clip sul thread audio; valori fuori dal range del controllo
     * vengono automaticamente limitati.
     */
    public void setGainDb(float db) {
        if (!active) return;
        enqueueEvent(EVENT_GAIN, 0, db);
    }

    /** Volume corrente in dB. */
    public float getGainDb() { return gainDb; }

    /** Rilascia thread e linee audio. */
    public void close() {
        if (!active) return;
        enqueueEvent(EVENT_CLOSE, 0, 0.0f);
        if (Thread.currentThread() != worker) {
            try {
                worker.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── Logica (eseguita sul thread worker) ────────────────────────────────────

    private void enqueueEvent(int type, int flags, float value) {
        synchronized (eventLock) {
            if (!workerRunning) return;
            if (eventSize == EVENT_QUEUE_SIZE) {
                if (type == EVENT_STEP) return;
                eventTypes[eventHead] = 0;
                eventHead = (eventHead + 1) & (EVENT_QUEUE_SIZE - 1);
                eventSize--;
            }
            eventTypes[eventTail] = type;
            eventFlags[eventTail] = flags;
            eventValues[eventTail] = value;
            eventTail = (eventTail + 1) & (EVENT_QUEUE_SIZE - 1);
            eventSize++;
            eventLock.notifyAll();
        }
    }

    private void workerLoop() {
        while (true) {
            int type;
            int flags;
            float value;
            synchronized (eventLock) {
                while (workerRunning && eventSize == 0) {
                    try {
                        eventLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        workerRunning = false;
                        break;
                    }
                }
                if (!workerRunning && eventSize == 0) return;
                type = eventTypes[eventHead];
                flags = eventFlags[eventHead];
                value = eventValues[eventHead];
                eventTypes[eventHead] = 0;
                eventHead = (eventHead + 1) & (EVENT_QUEUE_SIZE - 1);
                eventSize--;
            }

            switch (type) {
                case EVENT_MOTOR -> doMotor((flags & 1) != 0, (flags & 2) != 0);
                case EVENT_STEP -> doStep();
                case EVENT_DISK -> doDiskChange((flags & 1) != 0);
                case EVENT_GAIN -> {
                    gainDb = value;
                    applyGainAll();
                }
                case EVENT_CLOSE -> {
                    closeResources();
                    synchronized (eventLock) {
                        workerRunning = false;
                        eventHead = eventTail = eventSize = 0;
                    }
                    return;
                }
                default -> { }
            }
        }
    }

    private void closeResources() {
        stopSpin();
        closeClip(startupClip);
        closeClip(snatchClip);
        closeClip(spinClip);
        if (spinNdClip != spinClip) closeClip(spinNdClip);
        if (clickPool != null) for (Clip c : clickPool) closeClip(c);
    }

    private void doMotor(boolean running, boolean disk) {
        this.hasDisk = disk;
        if (running) {
            if (!spinning) {
                spinning = true;
                playOnce(startupClip);
            }
            startSpin();
        } else {
            spinning = false;
            stopSpin();
            stop(startupClip);
        }
    }

    private void doStep() {
        if (clickPool == null) return;
        Clip c = clickPool[clickIdx];
        clickIdx = (clickIdx + 1) % clickPool.length;
        playOnce(c);
    }

    private void doDiskChange(boolean inserted) {
        boolean wasDisk = this.hasDisk;
        this.hasDisk = inserted;
        if (inserted && !wasDisk) playOnce(snatchClip);
        if (spinning) startSpin(); // passa al loop corretto (con/senza disco)
    }

    private void startSpin() {
        Clip target = hasDisk ? spinClip : spinNdClip;
        if (target == null) target = (spinClip != null) ? spinClip : spinNdClip;
        if (target == null) return;
        if (currentSpin == target && target.isRunning()) return;
        stopSpin();
        currentSpin = target;
        target.setFramePosition(0);
        target.loop(Clip.LOOP_CONTINUOUSLY);
    }

    private void stopSpin() {
        if (currentSpin != null) currentSpin.stop();
        currentSpin = null;
    }

    private static void playOnce(Clip c) {
        if (c == null) return;
        c.stop();
        c.setFramePosition(0);
        c.start();
    }

    private static void stop(Clip c) {
        if (c != null && c.isRunning()) c.stop();
    }

    private static void closeClip(Clip c) {
        if (c == null) return;
        try { c.stop(); c.close(); } catch (RuntimeException ignored) {}
    }

    // ── Caricamento campioni ────────────────────────────────────────────────────

    private Clip makeClip(Sample s) {
        if (s == null || s.pcm() == null || s.pcm().length == 0) return null;
        try {
            Clip clip = AudioSystem.getClip();
            clip.open(s.fmt(), s.pcm(), 0, s.pcm().length);
            applyGain(clip);
            return clip;
        } catch (Exception e) {
            return null;
        }
    }

    private void applyGain(Clip clip) {
        if (clip == null) return;
        try {
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl g = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                g.setValue(Math.max(g.getMinimum(), Math.min(g.getMaximum(), gainDb)));
            }
        } catch (RuntimeException ignored) {}
    }

    /** Riapplica il gain corrente a tutti i clip aperti. */
    private void applyGainAll() {
        applyGain(startupClip);
        applyGain(snatchClip);
        applyGain(spinClip);
        if (spinNdClip != spinClip) applyGain(spinNdClip);
        if (clickPool != null) for (Clip c : clickPool) applyGain(c);
    }

    /**
     * Carica un WAV da {@link #SAMPLE_DIR} e lo normalizza a PCM signed 16-bit
     * mono little-endian MANTENENDO il sample-rate nativo. Risultato cached.
     * {@code null} se il file è assente o non decodificabile.
     */
    private static synchronized Sample loadSample(String name) {
        if (SAMPLE_CACHE.containsKey(name)) return SAMPLE_CACHE.get(name);
        Sample result = null;
        Path path = SAMPLE_DIR.resolve(name + ".wav");
        try (InputStream res = Files.isReadable(path) ? Files.newInputStream(path) : null) {
            if (res != null) {
                try (AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(res))) {
                    AudioFormat src = in.getFormat();
                    // Target: stesso rate, ma encoding/canali normalizzati (identità se già conforme).
                    AudioFormat target = new AudioFormat(src.getSampleRate(), 16, 1, true, false);
                    AudioInputStream conv = src.matches(target) ? in
                            : AudioSystem.getAudioInputStream(target, in);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = conv.read(buf)) > 0) bos.write(buf, 0, n);
                    result = new Sample(target, bos.toByteArray());
                }
            }
        } catch (Exception e) {
            result = null;
        }
        SAMPLE_CACHE.put(name, result);
        return result;
    }

    /**
     * Estrae un singolo "click" dal WAV (che ne contiene molti, uno per traccia),
     * prendendo una finestra di {@link #CLICK_FRAMES} frame centrata sul picco di
     * ampiezza. Euristica equivalente in spirito a {@code processclicks()} di WinUAE,
     * ma ne serve uno solo (resa puramente estetica).
     */
    private static Sample extractClick(Sample s) {
        if (s == null || s.pcm() == null || s.pcm().length < FRAME_BYTES) return null;
        byte[] pcm = s.pcm();
        int frames = pcm.length / FRAME_BYTES;
        int peak = 0, peakIdx = 0;
        for (int f = 0; f < frames; f++) {
            int lo = pcm[f * 2] & 0xFF;
            int hi = pcm[f * 2 + 1];
            int sm = (hi << 8) | lo;    // sample firmato little-endian
            int a = Math.abs(sm);
            if (a > peak) { peak = a; peakIdx = f; }
        }
        int start = Math.max(0, peakIdx - 128);
        int len = Math.min(CLICK_FRAMES, frames - start);
        if (len <= 0) return null;
        byte[] out = new byte[len * FRAME_BYTES];
        System.arraycopy(pcm, start * FRAME_BYTES, out, 0, out.length);
        return new Sample(s.fmt(), out);
    }
}
