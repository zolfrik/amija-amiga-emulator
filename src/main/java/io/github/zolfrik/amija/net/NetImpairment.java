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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Perdita, ritardo e jitter <b>simulati</b> sul canale media
 * ({@code docs/remote-play-plan.md} §13).
 *
 * <h2>Perche' e' indispensabile e non un lusso</h2>
 * Su un loopback non si perde mai niente. Ma tutta la robustezza di questo protocollo — il
 * riferimento all'ultimo ACK, il riassemblaggio tutto-o-niente, il keyframe di ripiego — e'
 * <b>codice che su un loopback perfetto non viene mai eseguito</b>. Senza un modo di rompere
 * il link a comando, quei rami resterebbero non collaudati fino alla prima partita vera su
 * una rete vera, cioe' esattamente quando non si possono osservare.
 *
 * <p>Si applica in <b>uscita</b>, subito prima della {@code send}: e' indifferente da quale
 * lato si butti via un pacchetto, e cosi' il ricevente resta codice di produzione puro,
 * senza un solo {@code if} di collaudo dentro il percorso caldo.
 *
 * <p>Configurazione da riga di comando, secondo la convenzione del progetto per le property
 * diagnostiche (solo {@code -D}, mai nel popup):
 * <pre>
 *   -Damija.remoteplay.simloss=0.05      frazione di datagrammi buttati (0..1)
 *   -Damija.remoteplay.simlatency=60     ritardo di base, ms
 *   -Damija.remoteplay.simjitter=10      variazione casuale sul ritardo, ms
 * </pre>
 *
 * <p>{@link #NONE} non e' una configurazione con i valori a zero: e' un oggetto che non fa
 * <i>niente</i>, non alloca uno scheduler e non consulta un generatore casuale. Quando la
 * simulazione e' spenta — cioe' sempre, tranne nei test — il costo e' una chiamata virtuale
 * che il JIT devirtualizza.
 */
public class NetImpairment {

    /** Nessuna alterazione: e' il caso normale, e non deve costare niente. */
    public static final NetImpairment NONE = new NetImpairment();

    private NetImpairment() {
    }

    /** Spedisce, o simula la sorte che il link avrebbe riservato al datagramma. */
    public void send(DatagramSocket socket, DatagramPacket packet) throws IOException {
        socket.send(packet);
    }

    /** Ferma lo scheduler, se ne era stato creato uno. */
    public void close() {
    }

    /** Vero se qualcosa viene alterato: serve solo a decidere cosa scrivere nei log. */
    public boolean active() {
        return false;
    }

    /**
     * Sospende o riprende l'alterazione, a sessione aperta.
     *
     * <p>Serve ai collaudi che devono <b>chiudere in pulito</b>: la garanzia del §4.2 e' che
     * i buchi interni diventino silenzio della lunghezza giusta, ma l'ultimo blocco di una
     * prova, se si perde, non ha nessun successore che possa accorgersene. Spegnere la
     * perdita per la coda rende esatta un'asserzione che altrimenti sarebbe un lancio di
     * moneta — invece di indebolirla fino a non dire piu' niente.
     */
    public void setEnabled(boolean enabled) {
    }

    /**
     * Legge la configurazione dalle property {@code -D}.
     *
     * @return {@link #NONE} se nessuna property e' impostata
     */
    public static NetImpairment fromSystemProperties() {
        double loss = doubleProperty("amija.remoteplay.simloss", 0);
        int latency = (int) doubleProperty("amija.remoteplay.simlatency", 0);
        int jitter = (int) doubleProperty("amija.remoteplay.simjitter", 0);
        return of(loss, latency, jitter);
    }

    /**
     * @param lossProbability frazione di datagrammi da buttare, 0..1
     * @param latencyMs       ritardo di base applicato a ogni datagramma
     * @param jitterMs        variazione casuale <b>aggiunta</b> al ritardo, 0..jitterMs
     */
    public static NetImpairment of(double lossProbability, int latencyMs, int jitterMs) {
        if (lossProbability <= 0 && latencyMs <= 0 && jitterMs <= 0) {
            return NONE;
        }
        return new Simulated(lossProbability, latencyMs, jitterMs);
    }

    private static double doubleProperty(String name, double fallback) {
        String v = System.getProperty(name);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("[NET] valore non numerico per " + name + ": " + v);
            return fallback;
        }
    }

    // =====================================================================

    private static final class Simulated extends NetImpairment {

        private final double lossProbability;
        private final int latencyMs;
        private final int jitterMs;
        private final Random random = new Random();
        private final ScheduledExecutorService scheduler;
        private volatile boolean enabled = true;
        private long dropped;
        private long delayed;

        Simulated(double lossProbability, int latencyMs, int jitterMs) {
            this.lossProbability = lossProbability;
            this.latencyMs = Math.max(0, latencyMs);
            this.jitterMs = Math.max(0, jitterMs);
            this.scheduler = (this.latencyMs > 0 || this.jitterMs > 0)
                    ? Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "amija-net-impairment");
                        t.setDaemon(true);
                        return t;
                    })
                    : null;
            System.out.printf("[NET] impairment simulato: perdita %.1f%%, ritardo %d ms"
                    + " (+jitter %d ms)%n", lossProbability * 100, this.latencyMs, this.jitterMs);
        }

        @Override
        public void send(DatagramSocket socket, DatagramPacket packet) throws IOException {
            if (!enabled) {
                socket.send(packet);
                return;
            }
            if (lossProbability > 0 && random.nextDouble() < lossProbability) {
                dropped++;
                return;
            }
            if (scheduler == null) {
                socket.send(packet);
                return;
            }
            // Il DatagramPacket e il suo array sono riusati dal chiamante a ogni invio: se lo
            // si consegnasse allo scheduler cosi' com'e', partirebbe il contenuto di un altro
            // pacchetto. La copia e' il prezzo della simulazione, e sta solo qui.
            byte[] copy = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), packet.getOffset(), copy, 0, copy.length);
            DatagramPacket delayedPacket =
                    new DatagramPacket(copy, copy.length, packet.getSocketAddress());
            long delay = latencyMs + (jitterMs > 0 ? random.nextInt(jitterMs + 1) : 0);
            try {
                scheduler.schedule(() -> {
                    try {
                        socket.send(delayedPacket);
                    } catch (IOException ignored) {
                        // Il socket si e' chiuso mentre il pacchetto aspettava: e' la fine
                        // della sessione, non un guasto da segnalare.
                    }
                }, delay, TimeUnit.MILLISECONDS);
                delayed++;
            } catch (RejectedExecutionException e) {
                // La sessione si sta chiudendo e lo scheduler e' gia' fermo. Un pacchetto
                // che non parte durante la chiusura e' un pacchetto perso, che e' proprio
                // cio' che questa classe simula: contarlo come tale e' la verita', farne
                // un'eccezione sarebbe rumore su un thread che sta comunque per finire.
                dropped++;
            }
        }

        @Override
        public boolean active() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public void close() {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            System.out.printf("[NET] impairment: %d datagrammi buttati, %d ritardati%n",
                    dropped, delayed);
        }
    }
}
