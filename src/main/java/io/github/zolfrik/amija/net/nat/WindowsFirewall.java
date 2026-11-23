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

package io.github.zolfrik.amija.net.nat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Windows firewall and the host's executable ({@code docs/remote-play-r1-plan.md} section 6).
 *
 * <p>The release is an app image in a zip, so no installer can add a rule: only the application
 * can. Two operations, kept apart on purpose:
 * <ul>
 *   <li>{@link #detect}: reads the inbound rules of this executable without privileges and
 *       says whether they let a guest in on the network profile in use;</li>
 *   <li>{@link #allowCommand}: the command that replaces the inbound rules of this executable
 *       with one allowing rule. It runs only through Windows' elevation prompt, on the user's
 *       explicit request: nothing happens without their consent.</li>
 * </ul>
 * Neither is offered for {@code java.exe}: a rule on the Java runtime would open every Java
 * program of the machine. Rules are identified by the full path of the executable, the only
 * way Windows ties them to it; the text of every command is built from that path alone and
 * the path is passed as a quoted literal, never interpolated into a command line.
 */
public final class WindowsFirewall {

    /** What the inbound rules say about this executable on the profile in use. */
    public enum Status {
        /** An enabled rule lets it in. */
        ALLOWED,
        /** An enabled rule blocks it: Windows will not ask again. */
        BLOCKED,
        /** No rule: Windows will ask at the first inbound connection. */
        NO_RULE,
        /** The rules could not be read. */
        UNKNOWN
    }

    private static final String RULE_NAME = "AmiJa";

    /**
     * The system tools, by absolute path. A bare name would be searched for in the
     * application's folder and the working folder before the system's, so a file planted
     * there would run, and, for the elevated command, with administrator rights.
     */
    static Path systemTool(String relative) {
        String root = System.getenv("SystemRoot");
        Path base = root != null && !root.isBlank() && Path.of(root).isAbsolute()
                ? Path.of(root) : Path.of("C:\\Windows");
        return base.resolve(relative);
    }

    static Path powershell() {
        return systemTool("System32\\WindowsPowerShell\\v1.0\\powershell.exe");
    }

    static Path netsh() {
        return systemTool("System32\\netsh.exe");
    }
    private static final long TIMEOUT_SECONDS = 20;
    /** The rule change waits on the elevation prompt, which the user may take a while to answer. */
    private static final long ALLOW_TIMEOUT_SECONDS = 300;

    private WindowsFirewall() {
    }

    /** True on Windows. */
    public static boolean applies() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * The executable a firewall rule would name: this process's own, unless it is the Java
     * runtime (run from an IDE or with {@code java -jar}), in which case there is none.
     */
    public static Optional<Path> launcherExecutable() {
        return ProcessHandle.current().info().command().map(Path::of)
                .filter(WindowsFirewall::isLauncher);
    }

    static boolean isLauncher(Path exe) {
        Path name = exe.getFileName();
        if (name == null) {
            return false;
        }
        String n = name.toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".exe") && !n.equals("java.exe") && !n.equals("javaw.exe");
    }

    /** Reads the rules of {@code exe} (no privileges needed). Blocks for a few seconds. */
    public static Status detect(Path exe) {
        try {
            String out = runPowerShell(detectScript(exe), TIMEOUT_SECONDS);
            return parse(out);
        } catch (IOException e) {
            return Status.UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Status.UNKNOWN;
        }
    }

    /**
     * Asks Windows, through its elevation prompt, to replace the inbound rules of {@code exe}
     * with one that allows it on the type of network in use only (private, public or domain),
     * not on every network the machine may join later. Blocks until the prompt is answered.
     *
     * @return {@code true} if the command ran (the user said yes)
     */
    public static boolean allow(Path exe) {
        try {
            String out = runPowerShell(allowScript(exe), ALLOW_TIMEOUT_SECONDS);
            return out.contains("DONE");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The {@code netsh} commands {@link #allow} runs elevated, for the tooltip and the log. */
    public static String allowCommand(Path exe) {
        String p = checkedPath(exe);
        return "netsh advfirewall firewall delete rule name=all dir=in program=\"" + p + "\" & "
                + "netsh advfirewall firewall add rule name=\"" + RULE_NAME + "\" dir=in "
                + "action=allow program=\"" + p + "\" enable=yes profile=<network in use>";
    }

    // =====================================================================

    /** A PowerShell single-quoted literal: the only escape is doubling the quote. */
    static String psLiteral(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** The path as text, refused if it holds characters no command should ever carry. */
    static String checkedPath(Path exe) {
        String p = exe.toAbsolutePath().toString();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '"' || c < 0x20) {
                throw new IllegalArgumentException("unusable executable path");
            }
        }
        return p;
    }

    static String detectScript(Path exe) {
        return "$ErrorActionPreference='SilentlyContinue';"
                + "$p=" + psLiteral(checkedPath(exe)) + ";"
                + "$c=(Get-NetConnectionProfile | Select-Object -First 1).NetworkCategory;"
                + "'CATEGORY=' + $c;"
                + "Get-NetFirewallApplicationFilter | Where-Object { $_.Program -ieq $p } | "
                + "Get-NetFirewallRule | Where-Object { $_.Direction -eq 'Inbound' -and "
                + "$_.Enabled -eq 'True' } | ForEach-Object { 'RULE=' + $_.Action + '|' + "
                + "$_.Profile }";
    }

    static String allowScript(Path exe) {
        String p = checkedPath(exe);
        // netsh receives each argument as one element; the program path keeps its quotes.
        String delete = "@('advfirewall','firewall','delete','rule','name=all','dir=in',"
                + psLiteral("program=\"" + p + "\"") + ")";
        String add = "@('advfirewall','firewall','add','rule'," + psLiteral("name=" + RULE_NAME)
                + ",'dir=in','action=allow'," + psLiteral("program=\"" + p + "\"")
                + ",'enable=yes',('profile=' + $pr))";
        String netsh = psLiteral(netsh().toString());
        // The profile is read again here, elevated, at the moment the rule is made.
        String inner = "$c=\"$((Get-NetConnectionProfile | Select-Object -First 1)"
                + ".NetworkCategory)\"; $pr = if ($c -eq 'Public') { 'public' } "
                + "elseif ($c -like 'Domain*') { 'domain' } else { 'private' }; "
                + "Start-Process -FilePath " + netsh + " -ArgumentList " + delete
                + " -WindowStyle Hidden -Wait; Start-Process -FilePath " + netsh
                + " -ArgumentList " + add + " -WindowStyle Hidden -Wait";
        // One elevated PowerShell runs both, so Windows asks once.
        return "$ErrorActionPreference='Stop';"
                + "try { Start-Process -FilePath " + psLiteral(powershell().toString())
                + " -Verb RunAs -Wait "
                + "-WindowStyle Hidden -ArgumentList @('-NoProfile','-EncodedCommand',"
                + psLiteral(encode(inner)) + "); 'DONE' } catch { 'CANCELLED' }";
    }

    /** Reads the output of {@link #detectScript}. */
    static Status parse(String out) {
        String category = null;
        List<String[]> rules = new ArrayList<>();
        for (String line : out.split("\r?\n")) {
            String l = line.trim();
            if (l.startsWith("CATEGORY=")) {
                category = l.substring(9).trim();
            } else if (l.startsWith("RULE=")) {
                String[] parts = l.substring(5).split("\\|", 2);
                if (parts.length == 2) {
                    rules.add(parts);
                }
            }
        }
        if (category == null) {
            return Status.UNKNOWN;
        }
        String wanted = category.toLowerCase(Locale.ROOT).startsWith("domain") ? "domain"
                : category.toLowerCase(Locale.ROOT);
        boolean allowed = false;
        for (String[] r : rules) {
            String profile = r[1].toLowerCase(Locale.ROOT);
            if (!(profile.contains("any") || profile.contains(wanted))) {
                continue;
            }
            if (r[0].trim().equalsIgnoreCase("Block")) {
                return Status.BLOCKED;   // a blocking rule wins over any allowing one
            }
            if (r[0].trim().equalsIgnoreCase("Allow")) {
                allowed = true;
            }
        }
        return allowed ? Status.ALLOWED : Status.NO_RULE;
    }

    private static String encode(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    private static String runPowerShell(String script, long timeoutSeconds)
            throws IOException, InterruptedException {
        // -EncodedCommand: the script reaches PowerShell as is, with no command-line parsing.
        Process p = new ProcessBuilder(powershell().toString(), "-NoProfile", "-NonInteractive",
                "-EncodedCommand", encode(script))
                .redirectErrorStream(true)
                .start();
        p.getOutputStream().close();
        // The output is drained on its own thread, so a process that never ends cannot hold
        // this one past the timeout.
        CompletableFuture<byte[]> out = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try {
                out.complete(p.getInputStream().readAllBytes());
            } catch (IOException e) {
                out.completeExceptionally(e);
            }
        }, "amija-net-powershell-out");
        reader.setDaemon(true);
        reader.start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("PowerShell did not finish");
        }
        try {
            return new String(out.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    java.nio.charset.Charset.defaultCharset());
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("PowerShell output not read", e);
        }
    }
}
