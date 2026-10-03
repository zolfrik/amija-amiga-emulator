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
 * Post-processing chain run on the GPU between the Amiga frame and the back buffer.
 *
 * <p>There are two implementations, one per RetroArch preset format: {@link ShaderChain} for
 * {@code .glslp} ("classic" GLSL) and {@link SlangChain} for {@code .slangp} (translated Vulkan
 * GLSL). {@link GlDisplaySurface} does not tell them apart: it chooses by extension with
 * {@link #build(Path)}.
 *
 * <p>Every method except {@link #setParam} must be called on the GL thread.
 */
public interface RenderChain {

    /**
     * Runs the chain: {@code origTex} (Amiga frame at native height) goes through the passes and
     * the last one draws on the default framebuffer in the given viewport rectangle.
     */
    void render(int origTex, int origW, int origH,
                int vpX, int vpY, int vpW, int vpH, long frameCount);

    /**
     * Updates a parameter live. Callable from a thread other than the GL one (the
     * implementation synchronises); no-op if the parameter does not exist.
     */
    void setParam(String name, float value);

    /** Releases every GL resource. */
    void dispose();

    /** Compiles the preset, choosing the implementation from the file's extension. */
    static RenderChain build(Path preset) throws IOException {
        String n = preset.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".slangp")) {
            return SlangChain.build(preset);
        }
        return ShaderChain.build(preset);
    }
}
