/*
 * AmiJa - a cycle-exact Amiga emulator in Java.
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
 * The MasterClock generates the clock signal for the whole system. It runs at the colour
 * clock (CCK) frequency and drives the execution by calling Agnus's risingEdge and
 * fallingEdge.
 */
public final class MasterClock {

    private final AbstractAgnus agnus;
    private final VideoMode mode;
    private final VirtualCRT virtualCRT;
    private final ClockDriver driver;

    private volatile boolean running = false;
    private volatile boolean paused = false;
    /** True while the clock thread is stopped in the pause branch: see {@link #pauseAndWait}. */
    private volatile boolean idle = false;
    private Thread clockThread;
    /**
     * Generation of the clock thread: {@link #start()} increments it and hands it to the
     * thread it creates; {@link #run(int)} exits as soon as it is no longer its own.
     *
     * <p>Needed because {@link #stop()} cannot guarantee that the exiting thread is dead: its
     * {@code join} has a timeout. A later {@code start()} sets {@code running} back to true
     * BEFORE creating the new thread, so the old one, if it had not re-read the flag yet,
     * would read it as true and carry on: two threads on the same Agnus, which is neither
     * idempotent nor thread-safe. The {@code running} flag alone cannot tell "stop" from
     * "restarted"; the generation can.
     */
    private volatile int generation = 0;
    private volatile java.util.function.Consumer<Throwable> failureHandler;

    /**
     * Registers whom to notify if the emulation thread dies of an unhandled exception.
     * Without a listener that failure is silent: the thread ends, no frame reaches the
     * display any more and the window stays black and apparently alive (the EDT is not
     * involved and still responsive), with the power/drive LEDs gone because they are drawn
     * inside the emulated frame. The handler is called on the dying thread: if it must touch
     * the UI, it must bounce to the EDT by itself.
     */
    public void setFailureHandler(java.util.function.Consumer<Throwable> handler) {
        this.failureHandler = handler;
    }

    /**
     * Creates a new MasterClock.
     *
     * @param agnus the Agnus instance to send the clock signals to.
     * @param mode  the video mode (PAL or NTSC) that sets the frequency.
     */
    public MasterClock(AbstractAgnus agnus, VideoMode mode, VirtualCRT virtualCRT) {
        this(agnus, mode, virtualCRT, null);
    }

    /**
     * Creates a new MasterClock that advances the machine with {@code driver}; with
     * {@code null} the clocked processor is ticked edge by edge ({@link #singleTick()}).
     */
    public MasterClock(AbstractAgnus agnus, VideoMode mode, VirtualCRT virtualCRT, ClockDriver driver) {
        this.agnus = Objects.requireNonNull(agnus, "agnus");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.virtualCRT = Objects.requireNonNull(virtualCRT, "virtualCRT");
        this.driver = driver;
    }

    /**
     * Runs a single master clock tick (a rising edge followed by a falling edge).
     */
    public void singleTick() {
        agnus.risingEdge();
        agnus.fallingEdge();
        virtualCRT.tick();
    }

    /**
     * Starts the endless clock loop on a separate thread, throttled to keep the right
     * execution speed.
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
        // MAX_PRIORITY: the emulation IS the producer of the audio samples and must stay real
        // time, or the ring buffer empties (underrun). The audio thread (consumer) uses very
        // little CPU (it is almost always blocked on line.write), so there is no need to put
        // it above the emulation (tried: deprioritising the emulation drops it below real
        // time on a loaded machine, with underruns).
        clockThread.setPriority(Thread.MAX_PRIORITY);
        // The default handler would print the stack trace on stderr, which nobody reads in an
        // app launched by double click: the user would only see the screen stop forever. Here
        // the state is set to "not running" (so resumeOrStart can retry a boot) and the
        // failure is passed to whoever registered with setFailureHandler.
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
     * Stops the clock loop for good.
     */
    public synchronized void stop() {
        running = false;
        // Invalidate the current generation: even if the join below times out, the exiting
        // thread switches itself off at the next round instead of being woken by a later
        // start(). See {@link #generation}.
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
     * Pauses the clock.
     */
    public void pause() {
        paused = true;
    }

    /**
     * Pauses and <b>waits</b> until the clock thread has really stopped.
     *
     * <p>{@link #pause()} alone raises a flag and returns at once: the thread looks at it once
     * per batch (a thousand cycles), so for a moment it keeps running the machine. Whoever is
     * about to read or overwrite the components' state (the save state) must be sure nobody
     * is emulating, or it photographs a machine in motion.
     *
     * @param timeoutMs how long to wait at most; when it expires the call returns anyway (the
     *                  machine stays paused)
     * @return {@code true} if the thread stopped within the time allowed
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
     * Resumes the clock if paused.
     */
    public void resume() {
        paused = false;
    }

    /**
     * Returns true if the clock is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns true if the clock is paused.
     */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Main loop of the clock thread.
     */
    private void run(final int myGeneration) {
        final int batchSize = 1000;
        final double periodNs = mode.getPeriodNs();

        // Maximum delay tolerated against the real-time schedule before the time base is
        // resynchronised. Without this clamp, a single load peak (e.g. heavy Blitter/audio
        // during gameplay) would make actualElapsed exceed expectedElapsed forever: from then
        // on the throttle would no longer wait and the emulator would run "flat out" chasing
        // the lost time. On a capable CPU that means generating samples much faster than
        // 44100 Hz, a constant overflow of the audio ring buffer and a huge, persistent
        // crackle. Discarding the accumulated deficit goes straight back to real-time pacing
        // (at most a little emulated time is "lost", imperceptibly, instead of sprinting).
        final long MAX_LAG_NS = 50_000_000L; // 50 ms (~2-3 frame)
        final boolean DEBUG_PERF_LOG = false; // [CLOCK] diagnostics on stdout (off by default)

        long totalCycles = 0;
        long startTime = System.nanoTime();

        // Diagnostics: emulation speed and number of delay clamps, one line per second.
        final long palClock = mode.getFrequency();
        long logStart = System.nanoTime();
        long logCycles = 0;
        long clampCount = 0;

        while (running && generation == myGeneration) {
            if (paused) {
                idle = true; // nobody is emulating: see pauseAndWait
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // Reset the timing after the pause to avoid jerks
                startTime = System.nanoTime();
                totalCycles = 0;
                continue;
            }
            idle = false;

            // Run a batch of cycles to reduce the overhead of the time check
            final long ran;
            if (driver == null) {
                for (int i = 0; i < batchSize; i++) {
                    singleTick();
                }
                ran = batchSize;
            } else {
                ran = driver.run(batchSize);
            }
            totalCycles += ran;
            logCycles += ran;

            // Throttling
            long expectedElapsed = (long) (totalCycles * periodNs);
            long actualElapsed = System.nanoTime() - startTime;

            if (actualElapsed < expectedElapsed) {
                long waitTimeNs = expectedElapsed - actualElapsed;

                if (waitTimeNs > 1_000_000) {
                    // If we must wait more than 1 ms, sleep
                    try {
                        Thread.sleep(waitTimeNs / 1_000_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    // Otherwise busy-wait for better precision
                    while (System.nanoTime() - startTime < expectedElapsed) {
                        Thread.onSpinWait();
                    }
                }
            } else if (actualElapsed - expectedElapsed > MAX_LAG_NS) {
                // Too far behind: discard the deficit and rebase the clock on "now" to go back
                // to real-time pacing, avoiding the flat-out run that would flood the audio
                // buffer.
                totalCycles = 0;
                startTime = System.nanoTime();
                clampCount++;
            }

            // To avoid excessive drift or (theoretical) overflow over very long runs,
            // recalibrate every billion cycles (~2 minutes)
            if (totalCycles > 1_000_000_000L) {
                totalCycles = 0;
                startTime = System.nanoTime();
            }

            // Diagnostics, one line per second: real speed vs real time and delay clamps. Off
            // by default: the (synchronised) printf on System.out on this emulation thread
            // can block briefly and introduced micro-stalls.
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
