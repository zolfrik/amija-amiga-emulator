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

package io.github.zolfrik.amija.net.upnp;

import io.github.zolfrik.amija.net.RemotePlaySettings;
import io.github.zolfrik.amija.net.nat.WindowsFirewall;
import io.github.zolfrik.amija.net.nat.WindowsPowerShell;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The gateways Windows already knows, from the cache of its SSDP service.
 *
 * <p>Some routers never answer a search and only announce themselves every few minutes. The
 * system's SSDP service listens to those announcements all the time and keeps each one for
 * its {@code max-age}, so a router that announced itself while the machine was on the network
 * is found here at once, instead of after the next announcement. The cache is read through the
 * UPnP device finder, whose search returns the cached devices once its own search window ends
 * (about nine seconds); the description URL comes from {@code IUPnPDeviceDocumentAccess}.
 *
 * <h2>Only the default gateway is believed</h2>
 * An answer to a search is believed only when its URL points to whoever answered. The cache
 * does not say who announced a device, so that check cannot be made here, and any machine on
 * the network could otherwise point AmiJa at a third one. A URL from the cache is therefore
 * taken only when its host is one of this machine's default gateways (asked to the system in
 * the same script), and then passes the same check as a remembered one
 * ({@link SsdpDiscovery#gatewayAt}): plain {@code http(s)} on a literal address of a network
 * this machine is on. At most {@value #MAX_GATEWAYS} are kept.
 */
public final class WindowsSsdpCache {

    /** The finder's search window is about nine seconds; this leaves room for PowerShell. */
    static final long TIMEOUT_SECONDS = 30;

    /** Prefix of the lines of the script's output that carry a gateway's description URL. */
    private static final String PREFIX = "IGD ";

    /** Prefix of the lines that carry a default gateway's IPv4 address. */
    private static final String GATEWAY_PREFIX = "GW ";

    /** As for a search: real networks have one router, two at most. */
    static final int MAX_GATEWAYS = 8;

    private static final String SCRIPT = """
            $ProgressPreference = 'SilentlyContinue'
            Add-Type -TypeDefinition @'
            using System;
            using System.Runtime.InteropServices;
            [ComImport, Guid("E7772804-3287-418e-9072-CF2B47238981"),
             InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            public interface IAmiJaUpnpDocumentAccess {
                [return: MarshalAs(UnmanagedType.BStr)] string GetDocumentURL();
            }
            public static class AmiJaUpnpDocument {
                public static string Url(object device) {
                    return ((IAmiJaUpnpDocumentAccess) device).GetDocumentURL();
                }
            }
            '@
            foreach ($r in Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue) {
                'GW ' + $r.NextHop
            }
            $finder = New-Object -ComObject UPnP.UPnPDeviceFinder
            foreach ($d in $finder.FindByType('upnp:rootdevice', 0)) {
                if ($d.Type -like 'urn:schemas-upnp-org:device:InternetGatewayDevice:*') {
                    try { 'IGD ' + [AmiJaUpnpDocument]::Url($d) } catch { }
                }
            }
            """;

    private WindowsSsdpCache() {
    }

    /** True where the cache exists: on Windows. */
    public static boolean applies() {
        return WindowsFirewall.applies();
    }

    /**
     * The gateways in the system's SSDP cache that can be trusted; empty when there are none,
     * off Windows, or when the cache cannot be read.
     */
    public static List<SsdpDiscovery.Gateway> gateways() {
        if (!applies()) {
            return List.of();
        }
        try {
            List<SsdpDiscovery.Gateway> found = parse(WindowsPowerShell.run(SCRIPT, TIMEOUT_SECONDS));
            RemotePlaySettings.log("UPnP: %d gateway(s) in the Windows SSDP cache", found.size());
            return found;
        } catch (IOException e) {
            RemotePlaySettings.log("UPnP: Windows SSDP cache not readable: %s", e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /** The trusted gateways named by the script's output. */
    static List<SsdpDiscovery.Gateway> parse(String output) {
        String[] lines = output.split("\r\n|\n");
        Set<String> defaultGateways = new HashSet<>();
        for (String line : lines) {
            if (line.startsWith(GATEWAY_PREFIX)) {
                defaultGateways.add(line.substring(GATEWAY_PREFIX.length()).trim());
            }
        }
        List<SsdpDiscovery.Gateway> list = new ArrayList<>();
        for (String line : lines) {
            if (!line.startsWith(PREFIX) || list.size() >= MAX_GATEWAYS) {
                continue;
            }
            try {
                URI url = new URI(line.substring(PREFIX.length()).trim());
                if (url.getHost() == null || !defaultGateways.contains(url.getHost())) {
                    RemotePlaySettings.log(
                            "UPnP: cached gateway %s is not a default gateway: ignored", url);
                    continue;
                }
                SsdpDiscovery.Gateway g = SsdpDiscovery.gatewayAt(url);
                if (g != null && list.stream().noneMatch(
                        x -> x.descriptionUrl().equals(g.descriptionUrl()))) {
                    list.add(g);
                }
            } catch (URISyntaxException e) {
                RemotePlaySettings.log("UPnP: cached LOCATION not parseable: %s", line);
            }
        }
        return list;
    }
}
