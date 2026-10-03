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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extraction and persistence of the {@code #pragma parameter} parameters of a RetroArch shader
 * preset. It does not touch OpenGL.
 *
 * <p>Every pass of the preset can declare parameters with the syntax
 * {@code #pragma parameter NAME "description" default min max [step]}. This class collects them
 * (deduplicated by name) with their metadata, and reads/writes the user's overrides in a file
 * next to the preset ({@code <preset>.params}, format {@code name=value}). {@link ShaderChain}
 * applies those overrides at build time.
 */
public final class ShaderParams {

    /** Metadata of a shader parameter. */
    public record Param(String name, String desc, float def, float min, float max, float step) { }

    private static final Pattern PRAGMA = Pattern.compile(
            "#pragma\\s+parameter\\s+(\\w+)\\s+\"([^\"]*)\"\\s+"
            + "([-+0-9.eE]+)\\s+([-+0-9.eE]+)\\s+([-+0-9.eE]+)(?:\\s+([-+0-9.eE]+))?");

    private ShaderParams() { }

    /**
     * Lists the preset's parameters, in order of first appearance among the passes. Recognises
     * both {@code .glslp} and {@code .slangp}. Throws if the preset cannot be read.
     */
    public static List<Param> parse(Path preset) throws IOException {
        LinkedHashMap<String, Param> out = new LinkedHashMap<>();
        if (isSlang(preset)) {
            SlangPreset sp = SlangPreset.load(preset);
            for (SlangPreset.PassSpec pass : sp.passes()) {
                try {
                    // The GLSL version is irrelevant here: only the #pragmas matter.
                    for (Param p : SlangSource.load(pass.shader, 330).parameters()) {
                        out.putIfAbsent(p.name(), p);
                    }
                } catch (RuntimeException | IOException e) {
                    // unreadable or untranslatable pass: skip it, do not block the whole list,
                    // and above all do not let the exception climb up to the EDT.
                }
            }
            // The preset (and its #reference chain) can change the default value the popup
            // shows: without this, the sliders would start from the shader's default instead of
            // the one really in use.
            for (Map.Entry<String, Float> e : sp.paramOverrides().entrySet()) {
                Param p = out.get(e.getKey());
                if (p != null) {
                    out.put(p.name(), new Param(p.name(), p.desc(), e.getValue(),
                            p.min(), p.max(), p.step()));
                }
            }
            return new ArrayList<>(out.values());
        }

        GlslPreset gp = GlslPreset.load(preset);
        for (GlslPreset.PassSpec pass : gp.passes()) {
            String body;
            try {
                body = ShaderChain.loadWithIncludes(pass.shader, new HashSet<>());
            } catch (IOException e) {
                continue; // unreadable pass: skip it, do not block the whole list
            }
            for (String line : body.split("\n")) {
                Matcher m = PRAGMA.matcher(line);
                if (!m.find()) continue;
                String name = m.group(1);
                if (out.containsKey(name)) continue;
                float step = m.group(6) != null ? parseF(m.group(6), 0f) : 0f;
                out.put(name, new Param(name, m.group(2),
                        parseF(m.group(3), 0f), parseF(m.group(4), 0f),
                        parseF(m.group(5), 1f), step));
            }
        }
        return new ArrayList<>(out.values());
    }

    /** {@code true} for presets in slang format ({@code .slangp}). */
    public static boolean isSlang(Path preset) {
        return preset.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                .endsWith(".slangp");
    }

    /** File next to the preset with the user's overrides. */
    public static Path sidecar(Path glslp) {
        return glslp.resolveSibling(glslp.getFileName().toString() + ".params");
    }

    /** Reads the user overrides ({@code name->value}); empty map if absent/unreadable. */
    public static Map<String, Float> loadOverrides(Path glslp) {
        Map<String, Float> out = new HashMap<>();
        Path s = sidecar(glslp);
        if (!Files.isRegularFile(s)) return out;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(s)) {
            p.load(in);
        } catch (IOException e) {
            return out;
        }
        for (String k : p.stringPropertyNames()) {
            try {
                out.put(k, Float.parseFloat(p.getProperty(k).trim()));
            } catch (NumberFormatException ignore) {
                // non-numeric value: ignored
            }
        }
        return out;
    }

    /** Writes the user overrides next to the preset. */
    public static void saveOverrides(Path glslp, Map<String, Float> values) throws IOException {
        Properties p = new Properties();
        for (Map.Entry<String, Float> e : values.entrySet()) {
            p.setProperty(e.getKey(), Float.toString(e.getValue()));
        }
        try (OutputStream out = Files.newOutputStream(sidecar(glslp))) {
            p.store(out, "Override parametri shader per " + glslp.getFileName());
        }
    }

    private static float parseF(String s, float def) {
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
