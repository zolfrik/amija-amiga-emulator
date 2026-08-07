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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Parla con un router UPnP: legge la sua descrizione e gli chiede di aprire una porta
 * ({@code docs/remote-play-plan.md} §7.1).
 *
 * <h2>XML che arriva da un dispositivo di rete</h2>
 * Il documento di descrizione e la risposta a ogni azione sono XML scritto da qualcun
 * altro, quindi si applicano le stesse regole del §8.2 usate per i pacchetti: <b>tetto
 * costante prima di allocare</b> — il corpo si legge a pezzi e ci si ferma a
 * {@value #MAX_BODY_BYTES} byte invece di fidarsi del {@code Content-Length} — e
 * <b>parser blindato</b>: niente DTD, niente entita' esterne, {@code SECURE_PROCESSING}
 * acceso. Un {@code <!ENTITY>} che punta a un file locale in un documento di descrizione
 * e' l'attacco classico a chi analizza XML altrui, e qui l'XML altrui arriva da un
 * apparecchio che spesso nessuno aggiorna da anni.
 *
 * <h2>Tre tipi di servizio, uno solo dei quali risponde</h2>
 * Il servizio che apre le porte si chiama {@code WANIPConnection} (versione 1 o 2) sui
 * router con IP diretto e {@code WANPPPConnection} su quelli con PPPoE. Si prende il primo
 * che c'e': cercarne uno solo vorrebbe dire non funzionare su meta' delle linee italiane,
 * che sono PPPoE.
 */
public final class IgdClient {

    /** Tetto sul corpo di ogni risposta: e' XML di descrizione, non un trasferimento. */
    private static final int MAX_BODY_BYTES = 512 * 1024;

    /** Un router che non risponde entro questo tempo e' un router che non collabora. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(4);

    /** I servizi che sanno mappare una porta, in ordine di preferenza. */
    private static final List<String> CONNECTION_SERVICES = List.of(
            "urn:schemas-upnp-org:service:WANIPConnection:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1");

    /** Codice d'errore UPnP: questo router accetta solo mappature senza scadenza. */
    static final int ERROR_ONLY_PERMANENT_LEASES = 725;

    private final HttpClient http;
    private final URI controlUrl;
    private final String serviceType;
    private final InetAddress localAddress;

    private IgdClient(HttpClient http, URI controlUrl, String serviceType,
                      InetAddress localAddress) {
        this.http = http;
        this.controlUrl = controlUrl;
        this.serviceType = serviceType;
        this.localAddress = localAddress;
    }

    /**
     * Interroga un gateway trovato da {@link SsdpDiscovery} e ne ricava il punto di
     * controllo.
     *
     * @throws IOException se non risponde, se l'XML non si legge, o se non espone nessuno
     *                     dei servizi che sanno mappare una porta
     */
    public static IgdClient connect(SsdpDiscovery.Gateway gateway) throws IOException {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                // Niente redirect: un router che rimanda altrove non e' un caso da servire,
                // e seguire un redirect vorrebbe dire parlare SOAP con un host arbitrario.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        byte[] description = get(http, gateway.descriptionUrl());
        Document document = parse(description);

        // <URLBase>, se c'e', ha la precedenza sull'URL da cui e' arrivata la descrizione:
        // ci sono router che servono il documento su una porta e vogliono le azioni su
        // un'altra.
        URI base = gateway.descriptionUrl();
        String urlBase = firstValue(document, "URLBase");
        if (urlBase != null && !urlBase.isBlank()) {
            try {
                base = URI.create(urlBase.trim());
            } catch (IllegalArgumentException e) {
                RemotePlaySettings.log("URLBase non valido, si usa LOCATION: %s", urlBase);
            }
        }

        for (String wanted : CONNECTION_SERVICES) {
            for (Element service : elements(document, "service")) {
                String type = childValue(service, "serviceType");
                if (!wanted.equalsIgnoreCase(type)) {
                    continue;
                }
                String control = childValue(service, "controlURL");
                if (control == null || control.isBlank()) {
                    continue;
                }
                URI url = base.resolve(control.trim());
                RemotePlaySettings.log("IGD %s -> %s", type, url);
                return new IgdClient(http, url, type, gateway.localAddress());
            }
        }
        throw new IOException("il dispositivo non espone WANIPConnection ne' WANPPPConnection");
    }

    /** L'indirizzo di questa macchina sulla rete del router: quello che si fa mappare. */
    public InetAddress localAddress() {
        return localAddress;
    }

    /**
     * L'indirizzo che il router mostra al mondo.
     *
     * <p>E' anche la diagnosi del §7.3: se questo indirizzo e' privato o in
     * {@code 100.64.0.0/10}, il router e' a sua volta dietro un NAT dell'operatore e la
     * mappatura, per quanto riuscita, non serve a niente.
     */
    public String externalAddress() throws IOException {
        Document response = invoke("GetExternalIPAddress", "");
        String address = firstValue(response, "NewExternalIPAddress");
        if (address == null || address.isBlank()) {
            throw new IOException("il router non ha dichiarato nessun indirizzo esterno");
        }
        return address.trim();
    }

    /**
     * Chiede di inoltrare una porta verso questa macchina.
     *
     * @param protocol {@code "TCP"} o {@code "UDP"} — servono entrambi, stesso numero (§4)
     * @param seconds  durata della prenotazione; 0 = senza scadenza
     * @throws UpnpException col codice del router, se rifiuta
     */
    public void addPortMapping(String protocol, int port, int seconds, String description)
            throws IOException {
        String arguments = arg("NewRemoteHost", "")
                + arg("NewExternalPort", Integer.toString(port))
                + arg("NewProtocol", protocol)
                + arg("NewInternalPort", Integer.toString(port))
                + arg("NewInternalClient", localAddress.getHostAddress())
                + arg("NewEnabled", "1")
                + arg("NewPortMappingDescription", description)
                + arg("NewLeaseDuration", Integer.toString(seconds));
        invoke("AddPortMapping", arguments);
    }

    /** Ritira la mappatura. Va fatto sempre: le porte di casa d'altri non si lasciano aperte. */
    public void deletePortMapping(String protocol, int port) throws IOException {
        String arguments = arg("NewRemoteHost", "")
                + arg("NewExternalPort", Integer.toString(port))
                + arg("NewProtocol", protocol);
        invoke("DeletePortMapping", arguments);
    }

    // =====================================================================
    // SOAP
    // =====================================================================

    private Document invoke(String action, String arguments) throws IOException {
        String envelope = """
                <?xml version="1.0"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"\
                 s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">\
                <s:Body><u:%s xmlns:u="%s">%s</u:%s></s:Body></s:Envelope>"""
                .formatted(action, serviceType, arguments, action);

        HttpRequest request = HttpRequest.newBuilder(controlUrl)
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("SOAPAction", "\"" + serviceType + "#" + action + "\"")
                .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrotto durante " + action, e);
        }
        byte[] body = read(response.body());
        Document document = parse(body);
        if (response.statusCode() == 200) {
            return document;
        }
        // Un rifiuto UPnP e' un HTTP 500 con dentro il motivo vero. Buttarlo via lascerebbe
        // «errore 500» a un utente che invece puo' fare qualcosa, per esempio spegnere e
        // riaccendere UPnP sul router (606) o liberare una porta gia' presa (718).
        String code = firstValue(document, "errorCode");
        String detail = firstValue(document, "errorDescription");
        throw new UpnpException(action, parseInt(code), detail);
    }

    private static String arg(String name, String value) {
        return "<" + name + ">" + escape(value) + "</" + name + ">";
    }

    /**
     * Sfugge i caratteri che in XML hanno un significato.
     *
     * <p>L'unico argomento che puo' contenerli e' la descrizione della mappatura, che oggi
     * e' una costante — ma un giorno potrebbe portarci dentro il nome del gioco, e allora
     * un'apostrofo produrrebbe una richiesta malformata invece di un errore comprensibile.
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static int parseInt(String text) {
        try {
            return text == null ? 0 : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // =====================================================================
    // HTTP e XML
    // =====================================================================

    private static byte[] get(HttpClient http, URI url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(url).timeout(HTTP_TIMEOUT).GET().build();
        try {
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("descrizione non disponibile: HTTP "
                        + response.statusCode());
            }
            return read(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrotto leggendo la descrizione", e);
        }
    }

    /** Legge fino al tetto e non un byte di piu': la lunghezza la dichiara il router. */
    private static byte[] read(InputStream in) throws IOException {
        try (InputStream stream = in) {
            byte[] body = stream.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new IOException("risposta oltre " + MAX_BODY_BYTES + " byte");
            }
            return body;
        }
    }

    /** Il parser blindato: nessun DTD, nessuna entita' esterna, nessun XInclude. */
    static Document parse(byte[] xml) throws IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            // Nessun EntityResolver di rete: se un documento chiedesse comunque qualcosa
            // di esterno, si risponde con il vuoto invece di andarlo a prendere.
            builder.setEntityResolver((publicId, systemId) ->
                    new org.xml.sax.InputSource(new ByteArrayInputStream(new byte[0])));
            // Senza un ErrorHandler, il parser stampa da se' su stderr prima di sollevare:
            // un XML malformato produrrebbe due segnalazioni, una delle quali non passa da
            // nessun percorso di errore di AmiJa e comparirebbe anche a fronte di un caso
            // gestito. Qui l'unico esito e' l'eccezione.
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override
                public void error(org.xml.sax.SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void warning(org.xml.sax.SAXParseException e) {
                    // Un avviso non e' un guasto: si prosegue, in silenzio.
                }
            });
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("XML non interpretabile: " + e.getMessage(), e);
        }
    }

    /**
     * Il testo del primo elemento con questo nome, prefisso di namespace escluso.
     *
     * <p>Il confronto e' sul <b>nome locale</b> ricavato a mano, e non su
     * {@code getElementsByTagNameNS}, perche' i router non sono coerenti: la stessa risposta
     * arriva come {@code <NewExternalIPAddress>} da un firmware e {@code <u:NewExternalIPAddress>}
     * da un altro, e a volte senza dichiarare il namespace che usa.
     */
    static String firstValue(Document document, String localName) {
        for (Element element : elements(document, localName)) {
            String text = element.getTextContent();
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private static String childValue(Element parent, String localName) {
        NodeList children = parent.getElementsByTagName("*");
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && localName.equals(local(element))) {
                return element.getTextContent();
            }
        }
        return null;
    }

    static List<Element> elements(Document document, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList all = document.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node node = all.item(i);
            if (node instanceof Element element && localName.equals(local(element))) {
                result.add(element);
            }
        }
        return result;
    }

    private static String local(Element element) {
        String name = element.getLocalName();
        if (name != null) {
            return name;
        }
        name = element.getTagName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /** Un rifiuto del router, con il codice che dice cosa si puo' fare. */
    public static final class UpnpException extends IOException {
        private static final long serialVersionUID = 1L;

        private final int code;

        UpnpException(String action, int code, String description) {
            super(action + " rifiutata dal router: "
                    + (code > 0 ? "errore " + code : "errore sconosciuto")
                    + (description == null || description.isBlank() ? "" : " (" + description + ")"));
            this.code = code;
        }

        /** Il codice UPnP, o 0 se il router non l'ha dichiarato. */
        public int code() {
            return code;
        }
    }
}
