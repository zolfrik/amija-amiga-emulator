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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Trova i router che parlano UPnP, con SSDP ({@code docs/remote-play-plan.md} §7.1).
 *
 * <p>Un M-SEARCH in multicast a {@code 239.255.255.250:1900}, si raccolgono per qualche
 * secondo le risposte e da ognuna si tiene l'header {@code LOCATION}: l'URL del documento
 * che descrive il dispositivo. Da li' in poi e' {@link IgdClient} a lavorare.
 *
 * <h2>Si cerca da ogni interfaccia, non da quella predefinita</h2>
 * E' la sola cosa non ovvia di questa classe, e senza di essa la funzione non parte
 * proprio sulle macchine in cui serve di piu'. Un multicast spedito da un socket con
 * destinazione predefinita esce da <b>una</b> interfaccia — quella che il sistema sceglie —
 * e su un computer con una VPN, una macchina virtuale o due schede di rete quella scelta
 * e' spesso la scheda sbagliata, dove nessun router risponde. Si spedisce quindi da
 * ciascun indirizzo IPv4 attivo, e si tiene traccia di <b>quale</b> ha ricevuto risposta:
 * quell'indirizzo e' anche quello da dichiarare al router come destinazione della
 * mappatura, ed e' l'unico che si sa per certo essere sulla stessa rete di chi ha
 * risposto (§7.2 lo cercava con l'euristica della {@code connect} verso l'esterno; qui non
 * serve indovinare, perche' e' dimostrato dalla risposta).
 *
 * <h2>Si chiede piu' volte, e a piu' bersagli</h2>
 * UDP non garantisce la consegna e un M-SEARCH perso e' una funzione che «non funziona».
 * Si ripete la richiesta, e con due {@code ST} diversi: alcuni router rispondono al tipo di
 * dispositivo, altri solo al tipo di servizio.
 */
public final class SsdpDiscovery {

    /** L'indirizzo multicast di SSDP, fissato dalla specifica UPnP. */
    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    /** Attesa dichiarata nel campo MX: il router risponde entro questo numero di secondi. */
    private static final int MX_SECONDS = 2;

    /** Quante volte si ripete la richiesta, per non farsi fermare da un datagramma perso. */
    private static final int ATTEMPTS = 2;

    private static final int RECEIVE_BUFFER = 2048;

    /**
     * I bersagli della ricerca. Il dispositivo per primo — e' quello che risponde con la
     * descrizione completa — poi i due servizi, per i router che rispondono solo a quelli.
     */
    private static final String[] SEARCH_TARGETS = {
        "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
        "urn:schemas-upnp-org:service:WANIPConnection:1",
        "urn:schemas-upnp-org:service:WANPPPConnection:1",
    };

    private SsdpDiscovery() {
    }

    /**
     * Un router che ha risposto.
     *
     * @param descriptionUrl l'URL del documento di descrizione (header {@code LOCATION})
     * @param localAddress   l'indirizzo di <b>questa</b> macchina sulla rete da cui e'
     *                       arrivata la risposta: e' quello che il router deve mappare
     */
    public record Gateway(URI descriptionUrl, InetAddress localAddress) {
    }

    /**
     * Cerca i gateway raggiungibili.
     *
     * @param timeoutMs quanto si resta in ascolto delle risposte, per interfaccia
     * @return i gateway trovati, senza duplicati, nell'ordine in cui hanno risposto
     */
    public static List<Gateway> search(int timeoutMs) {
        // Chiave = URL della descrizione: lo stesso router risponde piu' volte (una per
        // tentativo e una per ST) e senza deduplica lo si interrogherebbe sei volte.
        Map<URI, Gateway> found = new LinkedHashMap<>();
        for (InetAddress local : localIpv4Addresses()) {
            searchFrom(local, timeoutMs, found);
        }
        return new ArrayList<>(found.values());
    }

    private static void searchFrom(InetAddress local, int timeoutMs, Map<URI, Gateway> found) {
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(local, 0))) {
            socket.setSoTimeout(Math.max(50, timeoutMs / ATTEMPTS));
            InetAddress group = InetAddress.getByName(SSDP_ADDRESS);
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                for (String target : SEARCH_TARGETS) {
                    byte[] request = mSearch(target).getBytes(StandardCharsets.US_ASCII);
                    socket.send(new DatagramPacket(request, request.length, group, SSDP_PORT));
                }
                collect(socket, local, found);
            }
        } catch (IOException e) {
            // Un'interfaccia che non sa spedire multicast non e' un guasto: e' il caso
            // normale di una scheda virtuale spenta. Si prova la prossima.
            RemotePlaySettings.log("SSDP da %s non praticabile: %s", local.getHostAddress(), e.getMessage());
        }
    }

    /** Raccoglie le risposte finche' il socket non va in timeout. */
    private static void collect(DatagramSocket socket, InetAddress local,
                                Map<URI, Gateway> found) throws IOException {
        byte[] buffer = new byte[RECEIVE_BUFFER];
        while (true) {
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(response);
            } catch (SocketTimeoutException e) {
                return;   // nessuno risponde piu': e' cosi' che finisce una ricerca SSDP
            }
            String text = new String(response.getData(), response.getOffset(),
                    response.getLength(), StandardCharsets.US_ASCII);
            String location = header(text, "LOCATION");
            if (location == null) {
                continue;
            }
            try {
                URI url = new URI(location.trim());
                if (url.getHost() != null) {
                    found.putIfAbsent(url, new Gateway(url, local));
                }
            } catch (URISyntaxException e) {
                RemotePlaySettings.log("LOCATION non interpretabile: %s", location);
            }
        }
    }

    /**
     * Il valore di un header di una risposta SSDP, o {@code null} se non c'e'.
     *
     * <p>Il confronto e' <b>senza distinzione di maiuscole</b> perche' HTTP lo prescrive e
     * i router lo sfruttano: si vedono {@code LOCATION}, {@code Location} e {@code location}
     * dallo stesso modello a seconda del firmware.
     */
    static String header(String message, String name) {
        String wanted = name.toLowerCase(Locale.ROOT) + ":";
        for (String line : message.split("\r\n|\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith(wanted)) {
                return line.substring(wanted.length()).trim();
            }
        }
        return null;
    }

    private static String mSearch(String searchTarget) {
        // Le due virgolette attorno a "ssdp:discover" sono obbligatorie: senza, diversi
        // router non rispondono affatto. Le righe finiscono in CRLF e il messaggio in una
        // riga vuota, come ogni richiesta HTTP.
        return "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: " + MX_SECONDS + "\r\n"
                + "ST: " + searchTarget + "\r\n"
                + "\r\n";
    }

    /**
     * Gli indirizzi IPv4 da cui vale la pena cercare.
     *
     * <p>Si scartano le interfacce spente, il loopback e quelle che non sanno fare
     * multicast: non risponderebbe nessuno e ognuna costerebbe l'intera attesa.
     */
    private static List<InetAddress> localIpv4Addresses() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback() || !nic.supportsMulticast()) {
                    continue;
                }
                Enumeration<InetAddress> local = nic.getInetAddresses();
                while (local.hasMoreElements()) {
                    InetAddress address = local.nextElement();
                    if (address instanceof Inet4Address) {
                        addresses.add(address);
                    }
                }
            }
        } catch (IOException e) {
            RemotePlaySettings.log("elenco delle interfacce non disponibile: %s", e.getMessage());
        }
        return addresses;
    }
}
