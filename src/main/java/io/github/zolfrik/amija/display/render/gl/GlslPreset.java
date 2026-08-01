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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser di un preset shader RetroArch {@code .glslp} (formato GLSL "classico").
 *
 * <p>Non tocca OpenGL: legge il file, ne estrae la lista dei pass e delle texture LUT,
 * e conserva le coppie {@code chiave=valore} grezze per gli override dei parametri
 * ({@code #pragma parameter}). La compilazione/esecuzione è in {@link ShaderChain}.
 *
 * <p>Formato: righe {@code chiave = valore} (valori eventualmente fra virgolette),
 * commenti con {@code #} o {@code ;}. Chiavi per pass {@code K} = 0..{@code shaders}-1:
 * {@code shaderK}, {@code filter_linearK}, {@code wrap_modeK}, {@code scale_typeK} /
 * {@code scale_type_xK} / {@code scale_type_yK}, {@code scaleK} / {@code scale_xK} /
 * {@code scale_yK}, {@code float_framebufferK}, {@code srgb_framebufferK},
 * {@code mipmap_inputK}, {@code aliasK}, {@code frame_count_modK}. Texture LUT:
 * {@code textures = "n1;n2"} + {@code n1 = path.png}, {@code n1_linear},
 * {@code n1_wrap_mode}, {@code n1_mipmap}.
 */
public final class GlslPreset {

    /** Descrittore di un singolo pass. */
    public static final class PassSpec {
        public Path shader;             // percorso assoluto del .glsl
        public boolean filterLinear = true;   // default RetroArch: bilineare
        public String wrapMode = "clamp_to_border";
        public String scaleTypeX = "source";  // source | viewport | absolute
        public String scaleTypeY = "source";
        public float scaleX = 1.0f;
        public float scaleY = 1.0f;
        public boolean floatFramebuffer = false;
        public boolean srgbFramebuffer = false;
        public boolean mipmapInput = false;
        public String alias = "";
        public int frameCountMod = 0;   // 0 = nessun modulo
    }

    /** Descrittore di una texture LUT. */
    public static final class TextureSpec {
        public String name;
        public Path path;
        public boolean linear = false;
        public String wrapMode = "clamp_to_border";
        public boolean mipmap = false;
    }

    private final Path baseDir;
    private final List<PassSpec> passes;
    private final List<TextureSpec> textures;
    private final Map<String, String> raw;

    private GlslPreset(Path baseDir, List<PassSpec> passes, List<TextureSpec> textures,
                       Map<String, String> raw) {
        this.baseDir = baseDir;
        this.passes = passes;
        this.textures = textures;
        this.raw = raw;
    }

    public List<PassSpec> passes() { return passes; }
    public List<TextureSpec> textures() { return textures; }
    public Path baseDir() { return baseDir; }

    /** Valore grezzo di una chiave del preset, o {@code null}. Usato per gli override dei parametri. */
    public String rawValue(String key) {
        return raw.get(key);
    }

    /** Carica e valida un preset. Lancia {@link IOException} se il file è assente o malformato. */
    public static GlslPreset load(Path glslp) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(glslp, StandardCharsets.UTF_8)) {
            String s = stripComment(line).trim();
            if (s.isEmpty()) continue;
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            String key = s.substring(0, eq).trim();
            String val = unquote(s.substring(eq + 1).trim());
            map.put(key, val);
        }

        Path baseDir = glslp.toAbsolutePath().getParent();
        int count = parseInt(map.get("shaders"), 0);
        if (count <= 0) {
            throw new IOException("Preset senza pass (shaders=" + map.get("shaders") + "): " + glslp);
        }

        List<PassSpec> passes = new ArrayList<>(count);
        for (int k = 0; k < count; k++) {
            PassSpec p = new PassSpec();
            String shaderRel = map.get("shader" + k);
            if (shaderRel == null || shaderRel.isEmpty()) {
                throw new IOException("Pass " + k + " senza 'shader" + k + "': " + glslp);
            }
            p.shader = resolve(baseDir, shaderRel);
            p.filterLinear = parseBool(map.get("filter_linear" + k), true);
            p.wrapMode = orDefault(map.get("wrap_mode" + k), "clamp_to_border");

            String st = map.get("scale_type" + k);
            p.scaleTypeX = orDefault(map.get("scale_type_x" + k), orDefault(st, "source"));
            p.scaleTypeY = orDefault(map.get("scale_type_y" + k), orDefault(st, "source"));
            float sc = parseFloat(map.get("scale" + k), 1.0f);
            p.scaleX = parseFloat(map.get("scale_x" + k), sc);
            p.scaleY = parseFloat(map.get("scale_y" + k), sc);

            p.floatFramebuffer = parseBool(map.get("float_framebuffer" + k), false);
            p.srgbFramebuffer = parseBool(map.get("srgb_framebuffer" + k), false);
            p.mipmapInput = parseBool(map.get("mipmap_input" + k), false);
            p.alias = orDefault(map.get("alias" + k), "");
            p.frameCountMod = parseInt(map.get("frame_count_mod" + k), 0);
            passes.add(p);
        }

        List<TextureSpec> textures = new ArrayList<>();
        String texList = map.get("textures");
        if (texList != null && !texList.isEmpty()) {
            for (String name : texList.split(";")) {
                name = name.trim();
                if (name.isEmpty()) continue;
                String path = map.get(name);
                if (path == null || path.isEmpty()) continue;
                TextureSpec t = new TextureSpec();
                t.name = name;
                t.path = resolve(baseDir, path);
                t.linear = parseBool(map.get(name + "_linear"), false);
                t.wrapMode = orDefault(map.get(name + "_wrap_mode"), "clamp_to_border");
                t.mipmap = parseBool(map.get(name + "_mipmap"), false);
                textures.add(t);
            }
        }

        return new GlslPreset(baseDir, passes, textures, map);
    }

    // ---- helper di parsing ----------------------------------------------------------

    private static String stripComment(String line) {
        // Un commento ('#' o ';') vale solo FUORI da una stringa fra virgolette: la
        // chiave RetroArch {@code textures = "a;b;c"} usa ';' come separatore dentro le
        // virgolette e NON deve essere troncata (era il bug che azzerava le LUT).
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes && (c == '#' || c == ';')) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static Path resolve(Path baseDir, String rel) {
        // I preset usano '/' come separatore: normalizzato da Path su Windows.
        return baseDir.resolve(rel.replace('\\', '/')).normalize();
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static int parseInt(String v, int def) {
        if (v == null || v.isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static float parseFloat(String v, float def) {
        if (v == null || v.isEmpty()) return def;
        try { return Float.parseFloat(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static boolean parseBool(String v, boolean def) {
        if (v == null || v.isEmpty()) return def;
        String s = v.trim();
        return s.equalsIgnoreCase("true") || s.equals("1");
    }
}
