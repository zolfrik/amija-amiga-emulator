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

package io.github.zolfrik.amija.net.upnp;

import io.github.zolfrik.amija.net.RemotePlaySettings;

import java.io.IOException;
import java.util.List;

/**
 * Apre la porta sul router e la tiene aperta finche' si ospita
 * ({@code docs/remote-play-plan.md} §7.1).
 *
 * <p>Tutto avviene su un thread suo: cercare il router e parlargli costa secondi, e chi lo
 * chiede — il popup F12, sull'EDT, o l'avvio di una sessione — non puo' aspettarli. Lo
 * stato si legge con {@link #state()} e la riga di stato del popup lo mostra man mano.
 *
 * <h2>Prima si guarda l'indirizzo, poi si apre la porta</h2>
 * L'ordine non e' indifferente. Se il router e' dietro il NAT dell'operatore (§7.3) la
 * mappatura riuscirebbe lo stesso e sarebbe <b>inutile</b>: si chiede quindi prima
 * l'indirizzo esterno, e se non e' raggiungibile ci si ferma li' — nessuna porta aperta
 * per niente, e la diagnosi giusta in un paio di secondi invece di un «Collega» che va in
 * timeout dall'altra parte. Se invece e' il router a rifiutare la domanda (capita), si
 * prosegue comunque con la mappatura: meglio una porta aperta senza sapere l'indirizzo che
 * niente.
 *
 * <h2>La prenotazione ha una scadenza, e si rinnova</h2>
 * {@value #LEASE_SECONDS} secondi, rinnovati ogni {@value #RENEW_MINUTES} minuti. Un lease
 * infinito verrebbe rifiutato da parecchi router — e soprattutto, se AmiJa muore di schianto,
 * una prenotazione a termine sparisce da sola invece di restare sul router per sempre. Chi
 * rifiuta perfino il lease a termine (errore {@value IgdClient#ERROR_ONLY_PERMANENT_LEASES})
 * lo dice con un codice suo, e allora si ritenta senza scadenza.
 */
public final class PortMapper implements AutoCloseable {

    /** Durata della prenotazione chiesta al router. */
    static final int LEASE_SECONDS = 3600;

    /** Ogni quanto si rinnova, con abbondante margine sulla scadenza. */
    static final int RENEW_MINUTES = 30;

    /** Quanto si aspettano le risposte all'M-SEARCH. */
    private static final int DISCOVERY_TIMEOUT_MS = 3000;

    /** Quanto si concede al ritiro della mappatura prima di lasciar perdere. */
    private static final int TEARDOWN_TIMEOUT_MS = 8000;

    /** Come la mappatura si presenta nell'elenco del router. */
    private static final String DESCRIPTION = "AmiJa remote play";

    private static final String[] PROTOCOLS = { "TCP", "UDP" };

    /** A che punto e' l'apertura della porta. */
    public enum State {
        /** Non e' stata chiesta. */
        OFF,
        /** Si sta cercando un router che collabori. */
        SEARCHING,
        /** Porta aperta: {@link #externalAddress()} dice dove si viene raggiunti. */
        MAPPED,
        /** Nessun router UPnP ha risposto (spesso: UPnP disattivato nel router). */
        NO_GATEWAY,
        /** Il router ha risposto ma ha rifiutato di aprire; {@link #detail()} dice perche'. */
        REFUSED,
        /** L'operatore non espone un indirizzo pubblico: da fuori non si arriva (§7.3). */
        CARRIER_NAT
    }

    /**
     * Come si arriva al router.
     *
     * <p>Esiste per una ragione sola: il collaudo. Cercare via SSDP significa spedire
     * multicast dalle interfacce vere, cioe' dipendere dalla rete su cui gira la build —
     * mentre tutto quel che questa classe <b>decide</b> (prima l'indirizzo, poi la porta;
     * il ripiego sul lease permanente; il ritiro alla chiusura) e' logica che non ha niente
     * a che vedere con il modo in cui il router e' stato trovato. Con questa cucitura la si
     * puo' esercitare contro un router finto in loopback.
     */
    interface GatewayFinder {
        IgdClient find();
    }

    private final int port;
    private final GatewayFinder finder;

    private volatile State state = State.OFF;
    private volatile String externalIp;
    private volatile String detail;
    private volatile boolean closed;

    /** Il router con cui si e' parlato, da riusare per il rinnovo e per la rimozione. */
    private volatile IgdClient gateway;
    private Thread worker;

    public PortMapper(int port) {
        this(port, null);
    }

    /** @param finder come trovare il router; {@code null} = SSDP, cioe' il caso vero */
    PortMapper(int port, GatewayFinder finder) {
        this.port = port;
        this.finder = finder;
    }

    /** Comincia a cercare e ad aprire. Torna subito. */
    public synchronized void open() {
        if (worker != null || closed) {
            return;
        }
        state = State.SEARCHING;
        worker = new Thread(this::run, "amija-remoteplay-upnp");
        worker.setDaemon(true);
        worker.start();
    }

    public State state() {
        return state;
    }

    /**
     * {@code indirizzo:porta} da dare all'altro giocatore, o {@code null} se non c'e'.
     *
     * <p>«Non c'e'» comprende il caso in cui l'indirizzo <b>si conosce</b> ma la porta non
     * e' aperta — dietro CGNAT si sa benissimo cosa risponde il router, e proprio per questo
     * non va restituito: sarebbe un indirizzo dall'aspetto giusto a cui nessuno arriva, cioe'
     * il modo piu' efficace di far cercare il problema dalla parte sbagliata. Chi vuole
     * l'indirizzo per la diagnosi usa {@link #externalIp()}, che dice cosa si e' visto e non
     * cosa si puo' usare.
     */
    public String externalAddress() {
        String ip = externalIp;
        return state == State.MAPPED && ip != null ? ip + ":" + port : null;
    }

    /** L'indirizzo pubblico del router, anche quando non e' raggiungibile (CGNAT). */
    public String externalIp() {
        return externalIp;
    }

    /** Il motivo tecnico dell'ultimo esito, o {@code null}. */
    public String detail() {
        return detail;
    }

    /**
     * Ritira la mappatura e ferma il rinnovo.
     *
     * <p>Va chiamata <b>sempre</b>: allo stop dell'ascolto e alla chiusura dell'applicazione
     * (§7.1). Lasciare una porta aperta sul router di casa di qualcuno dopo che ha smesso di
     * giocare non e' accettabile, ed e' il motivo per cui la prenotazione ha comunque una
     * scadenza — la rete non e' affidabile e questa chiamata potrebbe non arrivare mai.
     */
    @Override
    public void close() {
        Thread pending;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            pending = worker;
            worker = null;
            notifyAll();   // sveglia il rinnovo, che sta aspettando
        }
        if (pending != null) {
            try {
                // Si aspetta, ma non si INTERROMPE: il thread sta per fare l'unica cosa che
                // non si puo' perdere — ritirare la mappatura — e un interrupt in mezzo a
                // quella chiamata HTTP lascerebbe la porta aperta sul router.
                pending.join(TEARDOWN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        state = State.OFF;
        externalIp = null;
    }

    // =====================================================================

    private void run() {
        IgdClient igd = findGateway();
        if (closed) {
            return;   // chiusi mentre si cercava: non si apre niente e non c'e' niente da dire
        }
        if (igd == null) {
            state = State.NO_GATEWAY;
            detail = "nessun router UPnP ha risposto";
            RemotePlaySettings.say("UPnP: nessun router ha risposto");
            return;
        }
        gateway = igd;

        // Prima la diagnosi (§7.3): se da fuori non si arriva, aprire non serve a niente.
        String external = null;
        try {
            external = igd.externalAddress();
            externalIp = external;
            if (!NatAddress.isReachable(external)) {
                // CGNAT vero o router in cascata: per chi vuole giocare è lo stesso
                // problema e la stessa cura — ospitare dall'altra parte.
                state = State.CARRIER_NAT;
                detail = external;
                RemotePlaySettings.say("UPnP: il router ha un indirizzo non pubblico (%s):"
                        + " da Internet non si arriva, meglio ospitare dall'altra parte",
                        external);
                return;
            }
        } catch (IOException e) {
            // Non fatale: ci sono router che aprono le porte e non dicono il proprio
            // indirizzo. Si prosegue senza, e la riga di stato mostrera' solo la LAN.
            RemotePlaySettings.log("UPnP: indirizzo esterno non disponibile: %s", e.getMessage());
        }

        if (!map(igd)) {
            return;
        }
        state = State.MAPPED;
        detail = null;
        RemotePlaySettings.say("UPnP: porta %d aperta (TCP+UDP)%s", port,
                external == null ? "" : ", indirizzo pubblico " + external + ":" + port);
        renewLoop(igd);
        removeMapping(igd);
    }

    /** Apre TCP e UDP. {@code false} se il router rifiuta: lo stato lo dice gia'. */
    private boolean map(IgdClient igd) {
        for (String protocol : PROTOCOLS) {
            try {
                igd.addPortMapping(protocol, port, LEASE_SECONDS, DESCRIPTION);
            } catch (IgdClient.UpnpException e) {
                if (e.code() == IgdClient.ERROR_ONLY_PERMANENT_LEASES) {
                    // Questo router vuole mappature senza scadenza. Si accetta la sua
                    // condizione: la rimozione alla chiusura resta, e a quel punto e' l'unica
                    // cosa che le toglie di mezzo.
                    try {
                        igd.addPortMapping(protocol, port, 0, DESCRIPTION);
                        continue;
                    } catch (IOException retry) {
                        return refused(retry);
                    }
                }
                return refused(e);
            } catch (IOException e) {
                return refused(e);
            }
        }
        return true;
    }

    private boolean refused(IOException e) {
        state = State.REFUSED;
        detail = e.getMessage();
        RemotePlaySettings.say("UPnP: il router ha rifiutato di aprire la porta %d (%s)",
                port, e.getMessage());
        return false;
    }

    /** Rinnova finche' non si chiude. */
    private void renewLoop(IgdClient igd) {
        while (true) {
            synchronized (this) {
                if (closed) {
                    return;
                }
                try {
                    wait(RENEW_MINUTES * 60_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (closed) {
                    return;
                }
            }
            if (!map(igd)) {
                return;   // il router ha cambiato idea: lo stato lo dice, niente da ritirare
            }
            RemotePlaySettings.log("UPnP: mappatura della porta %d rinnovata", port);
        }
    }

    private void removeMapping(IgdClient igd) {
        for (String protocol : PROTOCOLS) {
            try {
                igd.deletePortMapping(protocol, port);
            } catch (IOException e) {
                RemotePlaySettings.log("UPnP: rimozione %s fallita: %s", protocol,
                        e.getMessage());
            }
        }
        RemotePlaySettings.log("UPnP: mappatura della porta %d ritirata", port);
    }

    /** Il primo gateway che risponde e sa mappare. */
    private IgdClient findGateway() {
        if (finder != null) {
            return finder.find();
        }
        List<SsdpDiscovery.Gateway> candidates = SsdpDiscovery.search(DISCOVERY_TIMEOUT_MS);
        for (SsdpDiscovery.Gateway candidate : candidates) {
            if (closed) {
                return null;
            }
            try {
                return IgdClient.connect(candidate);
            } catch (IOException e) {
                // Un dispositivo UPnP qualunque — una stampante, una smart TV — risponde
                // all'M-SEARCH e non sa mappare niente. Non e' un errore: e' il prossimo.
                RemotePlaySettings.log("UPnP: %s non e' un gateway utile (%s)",
                        candidate.descriptionUrl(), e.getMessage());
            }
        }
        return null;
    }
}
