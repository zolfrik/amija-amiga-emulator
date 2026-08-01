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

package io.github.zolfrik.amija.common;

import io.github.zolfrik.amija.custom.AbstractAgnus;

import java.util.Objects;

/**
 * Il MasterClock genera il segnale di clock per l'intero sistema.
 * In questa implementazione opera alla frequenza del Color Clock (CCK).
 * Coordina l'esecuzione chiamando i metodi risingEdge e fallingEdge di Agnus.
 */
public final class MasterClock {

    private final AbstractAgnus agnus;
    private final VideoMode mode;
    private final VirtualCRT virtualCRT;

    private volatile boolean running = false;
    private volatile boolean paused = false;
    private Thread clockThread;

    /**
     * Crea un nuovo MasterClock.
     *
     * @param agnus L'istanza di Agnus a cui inviare i segnali di clock.
     * @param mode La modalità video (PAL o NTSC) che determina la frequenza.
     */
    public MasterClock(AbstractAgnus agnus, VideoMode mode, VirtualCRT virtualCRT) {
        this.agnus = Objects.requireNonNull(agnus, "agnus");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.virtualCRT = Objects.requireNonNull(virtualCRT, "virtualCRT");
    }

    /**
     * Esegue un singolo tick del master clock (un rising edge seguito da un falling edge).
     */
    public void singleTick() {
        agnus.risingEdge();
        agnus.fallingEdge();
        virtualCRT.tick();
    }

    /**
     * Avvia il ciclo infinito di clock in un thread separato.
     * Utilizza il throttling per mantenere la velocità d'esecuzione corretta.
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        paused = false;
        clockThread = new Thread(this::run, "MasterClock");
        // MAX_PRIORITY: l'emulazione È il produttore dei campioni audio e deve restare a
        // real-time, altrimenti il ring buffer si svuota (underrun). Il thread audio
        // (consumer) usa pochissima CPU — sta quasi sempre bloccato su line.write — quindi
        // non serve metterlo sopra l'emulazione (provato: deprioritizzare l'emulazione la
        // fa scendere sotto real-time su macchina carica → underrun).
        clockThread.setPriority(Thread.MAX_PRIORITY);
        clockThread.start();
    }

    /**
     * Ferma definitivamente il ciclo di clock.
     */
    public synchronized void stop() {
        running = false;
        if (clockThread != null) {
            try {
                clockThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            clockThread = null;
        }
    }

    /**
     * Mette in pausa l'esecuzione del clock.
     */
    public void pause() {
        paused = true;
    }

    /**
     * Riprende l'esecuzione del clock se in pausa.
     */
    public void resume() {
        paused = false;
    }

    /**
     * Ritorna true se il clock sta girando.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Ritorna true se il clock è in pausa.
     */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Loop principale del thread di clock.
     */
    private void run() {
        final int batchSize = 1000;
        final double periodNs = mode.getPeriodNs();

        // Massimo ritardo tollerato rispetto alla schedule real-time prima di
        // ri-sincronizzare la base temporale. Senza questo clamp, un singolo picco
        // di carico (es. blitter/audio pesante durante l'interazione col gioco)
        // farebbe sì che actualElapsed superi per sempre expectedElapsed: da quel
        // momento il throttle non attende più e l'emulatore gira "a tutta velocità"
        // rincorrendo il tempo perso. Su una CPU capace ciò significa generare
        // sample molto più veloce di 44100 Hz → overflow continuo del ring buffer
        // audio → gracchio enorme e persistente. Scartando il deficit accumulato
        // si torna subito al pacing real-time (al massimo si "perde" un po' di
        // tempo emulato, impercettibile, invece di sprintare).
        final long MAX_LAG_NS = 50_000_000L; // 50 ms (~2-3 frame)
        final boolean DEBUG_PERF_LOG = false; // diagnostica [CLOCK] su stdout (off di default)

        long totalCycles = 0;
        long startTime = System.nanoTime();

        // Diagnostica: velocità di emulazione e numero di clamp del ritardo, una riga/sec.
        final long palClock = mode.getFrequency();
        long logStart = System.nanoTime();
        long logCycles = 0;
        long clampCount = 0;

        while (running) {
            if (paused) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // Reset del timing dopo la pausa per evitare scatti
                startTime = System.nanoTime();
                totalCycles = 0;
                continue;
            }

            // Esegue un batch di cicli per ridurre l'overhead del controllo del tempo
            for (int i = 0; i < batchSize; i++) {
                singleTick();
            }
            totalCycles += batchSize;
            logCycles += batchSize;

            // Throttling
            long expectedElapsed = (long) (totalCycles * periodNs);
            long actualElapsed = System.nanoTime() - startTime;

            if (actualElapsed < expectedElapsed) {
                long waitTimeNs = expectedElapsed - actualElapsed;

                if (waitTimeNs > 1_000_000) {
                    // Se dobbiamo aspettare più di 1ms, usiamo sleep
                    try {
                        Thread.sleep(waitTimeNs / 1_000_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    // Altrimenti busy wait per maggiore precisione
                    while (System.nanoTime() - startTime < expectedElapsed) {
                        Thread.onSpinWait();
                    }
                }
            } else if (actualElapsed - expectedElapsed > MAX_LAG_NS) {
                // Siamo rimasti troppo indietro: scartiamo il deficit e ri-basiamo
                // l'orologio su "adesso" per tornare al pacing real-time, evitando
                // la corsa a tutta velocità che inonderebbe il buffer audio.
                totalCycles = 0;
                startTime = System.nanoTime();
                clampCount++;
            }

            // Per evitare drift eccessivi o overflow (teorici) su tempi lunghissimi,
            // ricalibriamo ogni miliardo di cicli (~2 minuti)
            if (totalCycles > 1_000_000_000L) {
                totalCycles = 0;
                startTime = System.nanoTime();
            }

            // Diagnostica una riga/sec: velocità reale vs real-time e clamp del ritardo.
            // Disattivata di default: la printf su System.out (sincronizzata) su questo
            // thread di emulazione può bloccare brevemente e introduceva micro-stalli.
            long logNow = System.nanoTime();
            long logElapsed = logNow - logStart;
            if (DEBUG_PERF_LOG && logElapsed >= 1_000_000_000L) {
                double speedPct = 100.0 * logCycles / palClock * 1_000_000_000.0 / logElapsed;
                System.out.printf("[CLOCK] speed=%.1f%% lagClamp=%d/s%n", speedPct, clampCount);
                logStart = logNow;
                logCycles = 0;
                clampCount = 0;
            }
        }
    }
}
