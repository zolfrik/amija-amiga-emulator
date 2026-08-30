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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Dichiara il processo <em>per-monitor DPI aware</em> su Windows, via
 * {@code user32!SetProcessDpiAwarenessContext} (Foreign Function API, come
 * {@link WindowsKeyState}).
 *
 * <p>Va invocata come <b>prima</b> istruzione di {@code main()}, prima che il
 * toolkit AWT si inizializzi: è una correzione di <em>tempistica</em>, non di
 * valore. AWT dichiara comunque {@code PER_MONITOR_AWARE_V2} da solo quando parte,
 * quindi lo stato finale del processo è identico in ogni caso; quello che cambia
 * è il momento in cui accade.
 *
 * <p><b>Perché serve.</b> {@code java.exe}/{@code javaw.exe} portano un manifest
 * con {@code dpiAware=true/PM}, quindi il processo è DPI aware già dalla prima
 * istruzione: lanciando il jar va tutto bene. Il launcher nativo generato da
 * jpackage, invece, <b>non ha alcun manifest</b>: il processo parte
 * {@code DPI_UNAWARE} e viene promosso solo più tardi, durante l'init di AWT. In
 * quella finestra AWT fotografa i font di sistema ({@code win.defaultGUI.font},
 * {@code win.icon.font}, …) a 96 DPI. Quando poi qualcosa provoca una rilettura
 * di quelle desktop property — in pratica la prima {@code JFileChooser}, che
 * inizializza la shell di Windows — l'OS le restituisce alla DPI reale e i font
 * dell'<b>intero</b> Look and Feel saltano di colpo al fattore di scala del
 * desktop (con display al 150%: Tahoma 11 → 16, Segoe UI 12 → 18). L'effetto è
 * permanente: da quel momento ogni componente creato o rimisurato è sovradimensionato.
 *
 * <p>Dichiarando l'awareness prima di AWT la fotografia iniziale è già quella
 * giusta e la rilettura non cambia nulla.
 *
 * <p>Su piattaforme non-Windows, su Windows &lt; 10 1703 (dove il simbolo non
 * esiste) o se il native access è negato, il metodo è un no-op silenzioso. Lo è
 * anche sotto {@code java.exe}, dove la chiamata fallisce con
 * {@code ERROR_ACCESS_DENIED} perché l'awareness è già fissata dal manifest.
 */
public final class WindowsDpiAwareness {

    /** {@code DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2} (WinUser.h): pseudo-handle -4. */
    private static final long PER_MONITOR_AWARE_V2 = -4L;

    private WindowsDpiAwareness() {}

    /**
     * Dichiara il processo per-monitor DPI aware (V2), se non lo è già.
     *
     * @return true se questa chiamata ha effettivamente fissato l'awareness;
     *         false se era già fissata o se la chiamata non è disponibile.
     */
    public static boolean ensurePerMonitorAware() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("win")) {
            return false;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
            MethodHandle setContext = linker.downcallHandle(
                    user32.find("SetProcessDpiAwarenessContext").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            return (int) setContext.invoke(MemorySegment.ofAddress(PER_MONITOR_AWARE_V2)) != 0;
        } catch (Throwable t) {
            // Simbolo assente, native access negato, ecc.: si prosegue senza.
            return false;
        }
    }
}
