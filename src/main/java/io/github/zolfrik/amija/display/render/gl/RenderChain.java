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

package io.github.zolfrik.amija.display.render.gl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Catena di post-processing eseguita sulla GPU fra il frame Amiga e il backbuffer.
 *
 * <p>Esistono due realizzazioni, una per formato di preset RetroArch:
 * {@link ShaderChain} per i {@code .glslp} (GLSL "classico") e {@link SlangChain} per i
 * {@code .slangp} (Vulkan-GLSL tradotto). {@link GlDisplaySurface} non distingue fra le
 * due: sceglie in base all'estensione con {@link #build(Path)}.
 *
 * <p>Tutti i metodi tranne {@link #setParam} vanno chiamati sul thread GL.
 */
public interface RenderChain {

    /**
     * Esegue la catena: {@code origTex} (frame Amiga ad altezza nativa) attraversa i pass
     * e l'ultimo disegna sul framebuffer di default nel rettangolo di viewport indicato.
     */
    void render(int origTex, int origW, int origH,
                int vpX, int vpY, int vpW, int vpH, long frameCount);

    /**
     * Aggiorna a caldo un parametro. Chiamabile da un thread diverso da quello GL
     * (l'implementazione sincronizza); no-op se il parametro non esiste.
     */
    void setParam(String name, float value);

    /** Libera tutte le risorse GL. */
    void dispose();

    /** Compila il preset scegliendo l'implementazione dall'estensione del file. */
    static RenderChain build(Path preset) throws IOException {
        String n = preset.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".slangp")) {
            return SlangChain.build(preset);
        }
        return ShaderChain.build(preset);
    }
}
