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

package io.github.zolfrik.amija.display.render.gl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parser of a RetroArch {@code .slangp} shader preset ("slang" format, Vulkan GLSL).
 *
 * <p>It does not touch OpenGL: it reads the preset and extracts its passes, LUT textures and
 * parameter overrides. The translation of the {@code .slang} files is in {@link SlangSource},
 * the execution in {@link SlangChain}.
 *
 * <p>Compared with {@code .glslp} (see {@link GlslPreset}) the format adds two things, which
 * are the bulk of the work here:
 *
 * <ul>
 *   <li><b>{@code #reference "other-preset"}</b>: the "simple" preset, which inherits every key
 *       of the referenced preset and overrides some. References nest (the Mega Bezel uses
 *       chains of 3-4 levels) and can also point to {@code .params} files, which are only
 *       {@code key = value}. <b>Every key remembers the folder of the file it comes from</b>:
 *       relative paths of {@code shaderK} and of the textures are resolved against that
 *       file, not against the root preset.</li>
 *   <li><b>Wildcards</b> in the paths ({@code $VID-DRV$}, {@code $CORE$},
 *       {@code $CORE-REQ-ROT$}, ...). The wildcard is replaced and, if the resulting file
 *       does not exist, the <i>literal</i> path is used: the pack really contains files
 *       called {@code $CORE$.params}, which act as defaults.</li>
 * </ul>
 */
public final class SlangPreset {

    /** Descriptor of a single pass. */
    public static final class PassSpec {
        public Path shader;
        /** Filter of the pass input. RetroArch default: {@code video_smooth} = linear. */
        public boolean filterLinear = true;
        public String wrapMode = "clamp_to_border";
        public String scaleTypeX = "source";
        public String scaleTypeY = "source";
        public float scaleX = 1.0f;
        public float scaleY = 1.0f;
        public boolean floatFramebuffer = false;
        public boolean srgbFramebuffer = false;
        /** {@code mipmap_inputK}: the pass samples its input with mipmaps. */
        public boolean mipmapInput = false;
        public String alias = "";
        public int frameCountMod = 0;
    }

    /** Descriptor of a LUT texture. */
    public static final class TextureSpec {
        public String name;
        public Path path;
        public boolean linear = false;
        public String wrapMode = "clamp_to_border";
        public boolean mipmap = false;
    }

    /** "Structural" keys: anything else that is numeric is a parameter override. */
    private static final Pattern STRUCTURAL = Pattern.compile(
            "shaders|parameters|textures"
            + "|(shader|filter_linear|wrap_mode|scale_type|scale_type_x|scale_type_y"
            + "|scale|scale_x|scale_y|float_framebuffer|srgb_framebuffer|mipmap_input"
            + "|alias|frame_count_mod)\\d+");

    /** Value of a key + folder of the file that defined it (for relative paths). */
    private record Entry(String value, Path dir) { }

    private final Path file;
    private final List<PassSpec> passes;
    private final List<TextureSpec> textures;
    private final Map<String, Entry> merged;

    private SlangPreset(Path file, List<PassSpec> passes, List<TextureSpec> textures,
                        Map<String, Entry> merged) {
        this.file = file;
        this.passes = passes;
        this.textures = textures;
        this.merged = merged;
    }

    public Path file() { return file; }
    public List<PassSpec> passes() { return passes; }
    public List<TextureSpec> textures() { return textures; }

    /**
     * Numeric overrides of the parameters declared in the preset (and in the whole
     * {@code #reference} chain): every non-structural key whose value is a number.
     */
    public Map<String, Float> paramOverrides() {
        Map<String, Float> out = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : merged.entrySet()) {
            if (STRUCTURAL.matcher(e.getKey()).matches()) continue;
            String v = e.getValue().value().trim();
            if (v.isEmpty()) continue;
            try {
                out.put(e.getKey(), Float.parseFloat(v));
            } catch (NumberFormatException ignore) {
                // texture path or text flag: not a parameter
            }
        }
        return out;
    }

    /** Loads and validates a preset, resolving the whole {@code #reference} chain. */
    public static SlangPreset load(Path slangp) throws IOException {
        Path root = slangp.toAbsolutePath().normalize();
        Map<String, String> wildcards = wildcardsFor(root);
        Map<String, Entry> map = new LinkedHashMap<>();
        merge(root, map, new ArrayDeque<>(), wildcards);

        int count = intOf(map, "shaders", 0);
        if (count <= 0) {
            throw new IOException("Preset senza pass (shaders=" + valueOf(map, "shaders") + "): " + root);
        }

        List<PassSpec> passes = new ArrayList<>(count);
        for (int k = 0; k < count; k++) {
            Entry shader = map.get("shader" + k);
            if (shader == null || shader.value().isEmpty()) {
                throw new IOException("Pass " + k + " senza 'shader" + k + "': " + root);
            }
            PassSpec p = new PassSpec();
            p.shader = resolve(shader.dir(), shader.value(), wildcards);
            p.filterLinear = boolOf(map, "filter_linear" + k, true);
            p.wrapMode = strOf(map, "wrap_mode" + k, "clamp_to_border");

            String st = valueOf(map, "scale_type" + k);
            p.scaleTypeX = strOf(map, "scale_type_x" + k, st == null ? "source" : st);
            p.scaleTypeY = strOf(map, "scale_type_y" + k, st == null ? "source" : st);
            float sc = floatOf(map, "scale" + k, 1.0f);
            p.scaleX = floatOf(map, "scale_x" + k, sc);
            p.scaleY = floatOf(map, "scale_y" + k, sc);

            p.floatFramebuffer = boolOf(map, "float_framebuffer" + k, false);
            p.srgbFramebuffer = boolOf(map, "srgb_framebuffer" + k, false);
            p.mipmapInput = boolOf(map, "mipmap_input" + k, false);
            p.alias = strOf(map, "alias" + k, "");
            p.frameCountMod = intOf(map, "frame_count_mod" + k, 0);
            passes.add(p);
        }

        List<TextureSpec> textures = new ArrayList<>();
        String texList = valueOf(map, "textures");
        if (texList != null && !texList.isEmpty()) {
            for (String name : texList.split(";")) {
                name = name.trim();
                if (name.isEmpty()) continue;
                Entry path = map.get(name);
                if (path == null || path.value().isEmpty()) continue;
                TextureSpec t = new TextureSpec();
                t.name = name;
                t.path = resolve(path.dir(), path.value(), wildcards);
                t.linear = boolOf(map, name + "_linear", false);
                t.wrapMode = strOf(map, name + "_wrap_mode", "clamp_to_border");
                t.mipmap = boolOf(map, name + "_mipmap", false);
                textures.add(t);
            }
        }

        return new SlangPreset(root, passes, textures, map);
    }

    // =====================================================================================
    // #reference: merge ricorsivo
    // =====================================================================================

    /**
     * Merges {@code preset} into {@code map}: first, in order, the files named by its
     * {@code #reference} lines, then its own keys (which therefore win). {@code stack} breaks
     * cycles.
     */
    private static void merge(Path preset, Map<String, Entry> map, Deque<Path> stack,
                              Map<String, String> wildcards) throws IOException {
        Path abs = preset.toAbsolutePath().normalize();
        if (stack.contains(abs)) {
            System.err.println("[GL] Ciclo di #reference ignorato: " + abs);
            return;
        }
        if (!Files.isRegularFile(abs)) {
            throw new IOException("Preset non trovato: " + abs);
        }
        stack.push(abs);
        try {
            List<String> lines = Files.readAllLines(abs, StandardCharsets.UTF_8);
            Path dir = abs.getParent();

            // 1) The references, in the order they appear.
            for (String line : lines) {
                String s = line.trim();
                // '//#reference "..."' is how packs disable an alternative.
                if (s.startsWith("//") || !s.startsWith("#reference")) continue;
                String arg = extractValue(s.substring("#reference".length()));
                if (arg.isEmpty()) continue;
                Path target = resolve(dir, arg, wildcards);
                if (!Files.isRegularFile(target)) {
                    // References with unresolvable wildcards are optional.
                    System.err.println("[GL] #reference saltata (assente): " + target);
                    continue;
                }
                merge(target, map, stack, wildcards);
            }

            // 2) Its own keys, which override what was inherited.
            for (String line : lines) {
                String s = stripComment(line).trim();
                if (s.isEmpty()) continue;
                int eq = s.indexOf('=');
                if (eq <= 0) continue;
                String key = s.substring(0, eq).trim();
                String val = extractValue(s.substring(eq + 1));
                map.put(key, new Entry(val, dir));
            }
        } finally {
            stack.pop();
        }
    }

    // =====================================================================================
    // Wildcard
    // =====================================================================================

    /**
     * Substitution table of the RetroArch wildcards. AmiJa is not a libretro core: the values
     * are those consistent with how the emulator presents the frame ("glcore" driver, no
     * rotation, landscape orientation). Wildcards with no match stay literal and are caught by
     * the fallback of {@link #resolve}: the Mega Bezel pack contains files whose name <i>is</i>
     * the wildcard, which act as defaults.
     */
    private static Map<String, String> wildcardsFor(Path root) {
        Map<String, String> w = new LinkedHashMap<>();
        w.put("$VID-DRV$", "glcore");
        w.put("$VID-DRV-PRESET-EXT$", "slangp");
        w.put("$VID-DRV-SHADER-EXT$", "slang");
        w.put("$CORE-REQ-ROT$", "CORE-REQ-ROT-0");
        w.put("$VID-USER-ROT$", "VID-USER-ROT-0");
        w.put("$VID-FINAL-ROT$", "VID-FINAL-ROT-0");
        w.put("$VID-ALLOW-CORE-ROT$", "VID-ALLOW-CORE-ROT-OFF");
        w.put("$SCREEN-ORIENT$", "SCREEN-ORIENT-HORZ");
        w.put("$VIEW-ASPECT-ORIENT$", "VIEW-ASPECT-ORIENT-HORZ");
        w.put("$CORE-ASPECT-ORIENT$", "CORE-ASPECT-ORIENT-HORZ");
        String name = root.getFileName().toString();
        int dot = name.lastIndexOf('.');
        w.put("$PRESET$", dot > 0 ? name.substring(0, dot) : name);
        Path parent = root.getParent();
        String dirName = parent == null || parent.getFileName() == null
                ? "" : parent.getFileName().toString();
        w.put("$PRESET_DIR$", dirName);
        w.put("$PRESET-DIR$", dirName);
        return w;
    }

    /**
     * Resolves a relative path of the preset. If it contains wildcards it tries the
     * substituted version first and, only if that file does not exist, falls back on the
     * literal path.
     */
    private static Path resolve(Path baseDir, String rel, Map<String, String> wildcards)
            throws IOException {
        String raw = rel.replace('\\', '/');
        try {
            Path literal = baseDir.resolve(raw).normalize();
            if (raw.indexOf('$') < 0) return literal;
            String subst = raw;
            for (Map.Entry<String, String> e : wildcards.entrySet()) {
                subst = subst.replace(e.getKey(), e.getValue());
            }
            Path p = baseDir.resolve(subst).normalize();
            return Files.exists(p) ? p : literal;
        } catch (InvalidPathException e) {
            // On Windows illegal characters (quotes, ':') are an unchecked exception: without
            // this it escapes from the EDT and closes the parameters popup.
            throw new IOException(
                    "Percorso non valido nel preset " + baseDir + ": \"" + rel + '"', e);
        }
    }

    // =====================================================================================
    // Helper
    // =====================================================================================

    private static String valueOf(Map<String, Entry> map, String key) {
        Entry e = map.get(key);
        return e == null ? null : e.value();
    }

    private static String strOf(Map<String, Entry> map, String key, String def) {
        String v = valueOf(map, key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static int intOf(Map<String, Entry> map, String key, int def) {
        String v = valueOf(map, key);
        if (v == null || v.isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static float floatOf(Map<String, Entry> map, String key, float def) {
        String v = valueOf(map, key);
        if (v == null || v.isEmpty()) return def;
        try { return Float.parseFloat(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static boolean boolOf(Map<String, Entry> map, String key, boolean def) {
        String v = valueOf(map, key);
        if (v == null || v.isEmpty()) return def;
        String s = v.trim();
        return s.equalsIgnoreCase("true") || s.equals("1");
    }

    /**
     * As in {@link GlslPreset}, but with {@code //} as well: a comment opens with {@code '#'},
     * {@code ';'} or {@code "//"} and only <b>outside</b> quotes (the key
     * {@code textures = "a;b;c"} uses ';' as a separator and must not be truncated). Community
     * packs comment in C style (TheNamec's whole preamble).
     */
    private static String stripComment(String line) {
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes) {
                if (c == '#' || c == ';') return line.substring(0, i);
                if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    return line.substring(0, i);
                }
            }
        }
        return line;
    }

    /**
     * Extracts the value of a key, or the argument of {@code #reference}: <b>if it is quoted,
     * the value ends at the closing quote and everything after it is ignored</b>, otherwise it
     * is the rest of the line minus the comment.
     *
     * <p>That "everything after it is ignored" is what is needed: packs write
     * {@code #reference "../x/preset.params" // comment} and
     * {@code BackgroundImage = "../assets/bg.png" // BACKGROUND texture}, and requiring the
     * quote as the <i>last</i> character would return the whole line in those cases, quotes
     * and comment included.
     */
    private static String extractValue(String s) {
        String v = s.trim();
        if (!v.isEmpty() && v.charAt(0) == '"') {
            int end = v.indexOf('"', 1);
            return end < 0 ? v.substring(1).trim() : v.substring(1, end);
        }
        return stripComment(v).trim();
    }
}
