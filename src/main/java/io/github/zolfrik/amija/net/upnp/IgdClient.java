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
 * Talks to a UPnP router: reads its description and asks it to open a port
 * ({@code docs/remote-play-plan.md} section 7.1).
 *
 * <h2>XML that comes from a network device</h2>
 * The description document and the answer to every action are XML written by someone else,
 * so the same rules as for the packets apply (section 8.2): <b>a constant cap before
 * allocating</b> (the body is read in chunks and reading stops at {@value #MAX_BODY_BYTES}
 * bytes instead of trusting {@code Content-Length}) and a <b>hardened parser</b>: no DTD, no
 * external entities, {@code SECURE_PROCESSING} on. An {@code <!ENTITY>} pointing to a local
 * file in a description document is the classic attack on whoever parses someone else's
 * XML, and here that XML comes from a device often nobody has updated in years.
 *
 * <h2>Three service types, only one of which answers</h2>
 * The service that opens ports is called {@code WANIPConnection} (version 1 or 2) on routers
 * with a direct IP and {@code WANPPPConnection} on those with PPPoE. The first one present is
 * taken: looking for one only would not work on half of the Italian lines, which are PPPoE.
 */
public final class IgdClient {

    /** Cap on the body of every answer: it is a description XML, not a transfer. */
    private static final int MAX_BODY_BYTES = 512 * 1024;

    /** A router that does not answer within this time is a router that does not cooperate. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(4);

    /** The services that can map a port, in order of preference. */
    private static final List<String> CONNECTION_SERVICES = List.of(
            "urn:schemas-upnp-org:service:WANIPConnection:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1");

    /** UPnP error code: this router accepts only mappings with no expiry. */
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
     * Queries a gateway found by {@link SsdpDiscovery} and derives its control point.
     *
     * @throws IOException if it does not answer, if the XML cannot be read, or if it exposes
     *                     none of the services that can map a port
     */
    public static IgdClient connect(SsdpDiscovery.Gateway gateway) throws IOException {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                // No redirects: a router that sends elsewhere is not a case to serve, and
                // following a redirect would mean talking SOAP to an arbitrary host.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        byte[] description = get(http, gateway.descriptionUrl());
        Document document = parse(description);

        // <URLBase>, if present, takes precedence over the URL the description came from:
        // some routers serve the document on one port and want the actions on another.
        URI deviceUrl = gateway.descriptionUrl();
        URI base = deviceUrl;
        String urlBase = firstValue(document, "URLBase");
        if (urlBase != null && !urlBase.isBlank()) {
            try {
                URI candidate = URI.create(urlBase.trim());
                if (sameDevice(deviceUrl, candidate)) {
                    base = candidate;
                } else {
                    // A URLBase that leads elsewhere is not a different port: it is another
                    // device. Stay on the one that answered.
                    RemotePlaySettings.log(
                            "URLBase %s non e' lo stesso apparecchio di %s: ignorato",
                            candidate, deviceUrl);
                }
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
                URI url;
                try {
                    url = base.resolve(control.trim());
                } catch (IllegalArgumentException e) {
                    RemotePlaySettings.log("controlURL non valido, ignorato: %s", control);
                    continue;
                }
                if (!sameDevice(deviceUrl, url)) {
                    // The controlURL is relative in the vast majority of firmware; when it is
                    // absolute it must still stay on the device that answered, or the XML
                    // would choose whom AmiJa talks SOAP to.
                    RemotePlaySettings.log(
                            "controlURL %s fuori dall'apparecchio %s: ignorato", url, deviceUrl);
                    continue;
                }
                RemotePlaySettings.log("IGD %s -> %s", type, url);
                return new IgdClient(http, url, type, gateway.localAddress());
            }
        }
        throw new IOException("il dispositivo non espone WANIPConnection ne' WANPPPConnection");
    }

    /** The address of this machine on the router's network: the one to have mapped. */
    public InetAddress localAddress() {
        return localAddress;
    }

    /**
     * The address the router shows the world.
     *
     * <p>It is also the diagnosis of section 7.3: if this address is private or in
     * {@code 100.64.0.0/10}, the router is itself behind the operator's NAT and the mapping,
     * however successful, is useless.
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
     * Asks to forward a port to this machine.
     *
     * @param protocol {@code "TCP"} or {@code "UDP"}: both are needed, same number (section 4)
     * @param seconds  duration of the reservation; 0 = no expiry
     * @throws UpnpException with the router's code, if it refuses
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

    /** Withdraws the mapping. Always to be done: ports in someone else's home are not left open. */
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
        // A UPnP refusal is an HTTP 500 with the real reason inside. Throwing it away would
        // leave "error 500" to a user who can do something instead, e.g. switch UPnP off and
        // on on the router (606) or free a port already taken (718).
        String code = firstValue(document, "errorCode");
        String detail = firstValue(document, "errorDescription");
        throw new UpnpException(action, parseInt(code), detail);
    }

    /**
     * Whether two URLs are on the <b>same device</b>: allowed scheme and identical host.
     *
     * <p>The device description is XML written by whoever answered the M-SEARCH, and it holds
     * two fields ({@code URLBase} and {@code controlURL}) that decide to which address AmiJa
     * will then send its SOAP POSTs. Leaving them free would mean whoever controls that XML
     * chooses whom AmiJa talks to: a service on {@code localhost}, another device on the
     * network, any host on the Internet. Bound to the host the description came from (which
     * {@link SsdpDiscovery} has already bound to the datagram's sender), the document can
     * still choose <i>path and port</i>, all a real router needs, but no longer the
     * <i>machine</i>.
     *
     * <p>The port stays free on purpose: some firmware serves the description on one port and
     * wants the actions on another, which is why {@code URLBase} exists. A different port on
     * the same device does not widen the surface (the device is already reachable), while a
     * different host changes it entirely.
     */
    private static boolean sameDevice(URI deviceUrl, URI candidate) {
        String scheme = candidate.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        String host = candidate.getHost();
        return host != null && host.equalsIgnoreCase(deviceUrl.getHost());
    }

    private static String arg(String name, String value) {
        return "<" + name + ">" + escape(value) + "</" + name + ">";
    }

    /**
     * Escapes the characters that have a meaning in XML.
     *
     * <p>The only argument that can contain them is the mapping's description, a constant
     * today; one day it might carry the game's name, and then an apostrophe would produce a
     * malformed request instead of an understandable error.
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
    // HTTP and XML
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

    /** Reads up to the cap and not one byte more: the length is declared by the router. */
    private static byte[] read(InputStream in) throws IOException {
        try (InputStream stream = in) {
            byte[] body = stream.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new IOException("risposta oltre " + MAX_BODY_BYTES + " byte");
            }
            return body;
        }
    }

    /** The hardened parser: no DTD, no external entities, no XInclude. */
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
            // No network EntityResolver: if a document asked for something external anyway,
            // the answer is empty instead of fetching it.
            builder.setEntityResolver((publicId, systemId) ->
                    new org.xml.sax.InputSource(new ByteArrayInputStream(new byte[0])));
            // Without an ErrorHandler, the parser prints to stderr by itself before throwing:
            // a malformed XML would produce two reports, one of which goes through no AmiJa
            // error path and would appear even for a handled case. Here the only outcome is
            // the exception.
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
                    // A warning is not a failure: carry on, silently.
                }
            });
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("XML non interpretabile: " + e.getMessage(), e);
        }
    }

    /**
     * The text of the first element with this name, namespace prefix excluded.
     *
     * <p>The comparison is on the <b>local name</b> derived by hand, not through
     * {@code getElementsByTagNameNS}, because routers are not consistent: the same answer
     * arrives as {@code <NewExternalIPAddress>} from one firmware and
     * {@code <u:NewExternalIPAddress>} from another, sometimes without declaring the
     * namespace it uses.
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

    /** A refusal from the router, with the code that says what can be done. */
    public static final class UpnpException extends IOException {
        private static final long serialVersionUID = 1L;

        private final int code;

        UpnpException(String action, int code, String description) {
            super(action + " rifiutata dal router: "
                    + (code > 0 ? "errore " + code : "errore sconosciuto")
                    + (description == null || description.isBlank() ? "" : " (" + description + ")"));
            this.code = code;
        }

        /** The UPnP code, or 0 if the router did not declare it. */
        public int code() {
            return code;
        }
    }
}
