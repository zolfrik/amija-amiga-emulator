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
 * Estrazione e persistenza dei parametri {@code #pragma parameter} di un preset shader
 * RetroArch. Non tocca OpenGL.
 *
 * <p>Ogni pass del preset può dichiarare parametri con la sintassi
 * {@code #pragma parameter NOME "descrizione" default min max [step]}. Questa classe li
 * raccoglie (deduplicando per nome) con i relativi metadati, e legge/scrive gli override
 * dell'utente in un file affiancato al preset ({@code <preset>.params}, formato
 * {@code nome=valore}). {@link ShaderChain} applica quegli override al build.
 */
public final class ShaderParams {

    /** Metadati di un parametro shader. */
    public record Param(String name, String desc, float def, float min, float max, float step) { }

    private static final Pattern PRAGMA = Pattern.compile(
            "#pragma\\s+parameter\\s+(\\w+)\\s+\"([^\"]*)\"\\s+"
            + "([-+0-9.eE]+)\\s+([-+0-9.eE]+)\\s+([-+0-9.eE]+)(?:\\s+([-+0-9.eE]+))?");

    private ShaderParams() { }

    /**
     * Elenca i parametri del preset, in ordine di prima apparizione fra i pass. Riconosce
     * sia i {@code .glslp} sia i {@code .slangp}. Lancia se il preset non è leggibile.
     */
    public static List<Param> parse(Path preset) throws IOException {
        LinkedHashMap<String, Param> out = new LinkedHashMap<>();
        if (isSlang(preset)) {
            SlangPreset sp = SlangPreset.load(preset);
            for (SlangPreset.PassSpec pass : sp.passes()) {
                try {
                    // La versione GLSL è irrilevante qui: interessano solo i #pragma.
                    for (Param p : SlangSource.load(pass.shader, 330).parameters()) {
                        out.putIfAbsent(p.name(), p);
                    }
                } catch (RuntimeException | IOException e) {
                    // pass illeggibile o non traducibile: salta, non bloccare l'intero
                    // elenco — e soprattutto non lasciar salire l'eccezione fino all'EDT.
                }
            }
            // Il preset (e la sua catena di #reference) può cambiare il valore di default
            // mostrato dal popup: senza questo, i cursori partono dal default dello shader
            // invece che da quello effettivamente in uso.
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
                continue; // pass illeggibile: salta, non bloccare l'intero elenco
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

    /** {@code true} per i preset in formato slang ({@code .slangp}). */
    public static boolean isSlang(Path preset) {
        return preset.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                .endsWith(".slangp");
    }

    /** File affiancato al preset con gli override dell'utente. */
    public static Path sidecar(Path glslp) {
        return glslp.resolveSibling(glslp.getFileName().toString() + ".params");
    }

    /** Legge gli override dell'utente ({@code nome->valore}); mappa vuota se assenti/illeggibili. */
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
                // valore non numerico: ignorato
            }
        }
        return out;
    }

    /** Scrive gli override dell'utente accanto al preset. */
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
