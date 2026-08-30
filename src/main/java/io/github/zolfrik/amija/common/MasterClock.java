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
    /** Vero mentre il thread di clock è fermo nel ramo di pausa: vedi {@link #pauseAndWait}. */
    private volatile boolean idle = false;
    private Thread clockThread;
    /**
     * Generazione del thread di clock: {@link #start()} la incrementa e la consegna al
     * thread che crea; {@link #run(int)} esce non appena non è più la propria.
     *
     * <p>⛔ Serve perché {@link #stop()} non può garantire che il thread uscente sia
     * morto: la sua {@code join} ha un timeout, e allo scadere il riferimento veniva
     * comunque azzerato. Una {@code start()} successiva rimette {@code running} a true
     * PRIMA di creare il nuovo thread, quindi il vecchio — se non aveva ancora riletto
     * il flag — lo rileggeva a true e proseguiva: due thread sullo stesso Agnus, che
     * non è né idempotente né thread-safe. Il flag {@code running} da solo non può
     * distinguere «fermati» da «riavviato», la generazione sì.
     */
    private volatile int generation = 0;
    private volatile java.util.function.Consumer<Throwable> failureHandler;

    /**
     * Registra chi avvisare se il thread di emulazione muore per un'eccezione non
     * gestita. Senza qualcuno in ascolto quel guasto è muto: il thread termina, nessun
     * frame raggiunge più il display e la finestra resta nera e apparentemente viva
     * (l'EDT non c'entra nulla ed è ancora reattivo), con i LED power/drive spariti
     * perché disegnati dentro il frame emulato. Il gestore viene invocato sul thread
     * morente: se deve toccare la UI, deve rimbalzare da sé sull'EDT.
     */
    public void setFailureHandler(java.util.function.Consumer<Throwable> handler) {
        this.failureHandler = handler;
    }

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
        idle = false;
        final int myGeneration = ++generation;
        clockThread = new Thread(() -> run(myGeneration), "MasterClock");
        // MAX_PRIORITY: l'emulazione È il produttore dei campioni audio e deve restare a
        // real-time, altrimenti il ring buffer si svuota (underrun). Il thread audio
        // (consumer) usa pochissima CPU — sta quasi sempre bloccato su line.write — quindi
        // non serve metterlo sopra l'emulazione (provato: deprioritizzare l'emulazione la
        // fa scendere sotto real-time su macchina carica → underrun).
        clockThread.setPriority(Thread.MAX_PRIORITY);
        // Il gestore di default stamperebbe lo stack trace su stderr, che in una app
        // lanciata col doppio clic non legge nessuno: l'utente vedrebbe solo lo schermo
        // fermarsi per sempre. Qui lo stato viene messo a "non in esecuzione" (così
        // resumeOrStart può ritentare un boot) e il guasto viene propagato a chi si è
        // registrato con setFailureHandler.
        clockThread.setUncaughtExceptionHandler((t, e) -> {
            running = false;
            System.err.println("[CLOCK] Emulazione interrotta da un errore non gestito:");
            e.printStackTrace();
            java.util.function.Consumer<Throwable> handler = failureHandler;
            if (handler != null) {
                handler.accept(e);
            }
        });
        clockThread.start();
    }

    /**
     * Ferma definitivamente il ciclo di clock.
     */
    public synchronized void stop() {
        running = false;
        // Invalida la generazione corrente: anche se la join qui sotto scade, il thread
        // uscente si spegne da sé al prossimo giro invece di essere risvegliato da una
        // start() successiva. Vedi {@link #generation}.
        generation++;
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
     * Mette in pausa e <b>attende</b> che il thread di clock si sia davvero fermato.
     *
     * <p>{@link #pause()} da solo alza un flag e torna subito: il thread lo guarda una
     * volta per batch (mille cicli), quindi per un attimo continua a far girare la
     * macchina. Chi sta per leggere o sovrascrivere lo stato dei componenti — il save
     * state — deve invece avere la certezza che nessuno stia emulando, altrimenti
     * fotografa una macchina in movimento.
     *
     * @param timeoutMs quanto aspettare al massimo; scaduto, si torna comunque (la
     *                  macchina resta in pausa)
     * @return {@code true} se il thread si è fermato entro il tempo concesso
     */
    public boolean pauseAndWait(long timeoutMs) {
        paused = true;
        if (clockThread == null || !running) return true;
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!idle && System.nanoTime() < deadline) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return idle;
            }
        }
        return idle;
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
    private void run(final int myGeneration) {
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

        while (running && generation == myGeneration) {
            if (paused) {
                idle = true; // nessuno sta emulando: vedi pauseAndWait
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
            idle = false;

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
