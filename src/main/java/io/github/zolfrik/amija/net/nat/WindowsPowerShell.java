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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs a PowerShell script on Windows and returns what it printed. */
public final class WindowsPowerShell {

    /** How long the output is waited for once the process has ended. */
    private static final long DRAIN_SECONDS = 20;

    private WindowsPowerShell() {
    }

    /** The script as {@code -EncodedCommand} wants it: UTF-16LE in Base64. */
    static String encode(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    /**
     * Runs {@code script} with the system's PowerShell, by absolute path.
     *
     * @return standard output and error together
     * @throws IOException if it cannot start, does not end in time, or its output is lost
     */
    public static String run(String script, long timeoutSeconds)
            throws IOException, InterruptedException {
        // -EncodedCommand: the script reaches PowerShell as is, with no command-line parsing.
        Process p = new ProcessBuilder(WindowsFirewall.powershell().toString(), "-NoProfile",
                "-NonInteractive", "-EncodedCommand", encode(script))
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
        boolean ended;
        try {
            ended = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            p.destroyForcibly();   // nobody will read it any more
            throw e;
        }
        if (!ended) {
            p.destroyForcibly();
            throw new IOException("PowerShell did not finish");
        }
        try {
            return new String(out.get(DRAIN_SECONDS, TimeUnit.SECONDS), Charset.defaultCharset());
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("PowerShell output not read", e);
        }
    }
}
