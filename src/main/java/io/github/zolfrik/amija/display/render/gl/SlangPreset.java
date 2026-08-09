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
 * Parser di un preset shader RetroArch {@code .slangp} (formato "slang", Vulkan-GLSL).
 *
 * <p>Non tocca OpenGL: legge il preset, ne estrae pass, texture LUT e override dei
 * parametri. La traduzione dei {@code .slang} è in {@link SlangSource}, l'esecuzione in
 * {@link SlangChain}.
 *
 * <p>Rispetto al {@code .glslp} (vedi {@link GlslPreset}) il formato aggiunge due cose
 * che qui sono il grosso del lavoro:
 *
 * <ul>
 *   <li><b>{@code #reference "altro-preset"}</b> — il preset "semplice": eredita tutte le
 *       chiavi del preset referenziato e ne sovrascrive alcune. Le reference si annidano
 *       (Mega Bezel ne usa catene di 3-4 livelli) e possono puntare anche a file
 *       {@code .params}, che sono soli {@code chiave = valore}. <b>Ogni chiave ricorda la
 *       cartella del file da cui proviene</b>: i percorsi relativi di {@code shaderK} e
 *       delle texture vanno risolti rispetto a quel file, non rispetto al preset radice.</li>
 *   <li><b>Wildcard</b> nei percorsi ({@code $VID-DRV$}, {@code $CORE$},
 *       {@code $CORE-REQ-ROT$}, …). Come in RetroArch, la wildcard è sostituita e — se il
 *       file risultante non esiste — si ricade sul percorso <i>letterale</i>: nel pacchetto
 *       esistono davvero file chiamati {@code $CORE$.params}, che fanno da default.</li>
 * </ul>
 */
public final class SlangPreset {

    /** Descrittore di un singolo pass. */
    public static final class PassSpec {
        public Path shader;
        /** Filtro dell'ingresso del pass. Default RetroArch: {@code video_smooth} = lineare. */
        public boolean filterLinear = true;
        public String wrapMode = "clamp_to_border";
        public String scaleTypeX = "source";
        public String scaleTypeY = "source";
        public float scaleX = 1.0f;
        public float scaleY = 1.0f;
        public boolean floatFramebuffer = false;
        public boolean srgbFramebuffer = false;
        /** {@code mipmap_inputK}: il pass campiona il proprio ingresso con i mipmap. */
        public boolean mipmapInput = false;
        public String alias = "";
        public int frameCountMod = 0;
    }

    /** Descrittore di una texture LUT. */
    public static final class TextureSpec {
        public String name;
        public Path path;
        public boolean linear = false;
        public String wrapMode = "clamp_to_border";
        public boolean mipmap = false;
    }

    /** Chiavi "strutturali": tutto il resto che è numerico è un override di parametro. */
    private static final Pattern STRUCTURAL = Pattern.compile(
            "shaders|parameters|textures"
            + "|(shader|filter_linear|wrap_mode|scale_type|scale_type_x|scale_type_y"
            + "|scale|scale_x|scale_y|float_framebuffer|srgb_framebuffer|mipmap_input"
            + "|alias|frame_count_mod)\\d+");

    /** Valore di una chiave + cartella del file che l'ha definita (per i path relativi). */
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
     * Override numerici dei parametri dichiarati nel preset (e in tutta la catena di
     * {@code #reference}): tutte le chiavi non strutturali il cui valore è un numero.
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
                // percorso di texture o flag testuale: non è un parametro
            }
        }
        return out;
    }

    /** Carica e valida un preset, risolvendo l'intera catena di {@code #reference}. */
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
     * Fonde {@code preset} in {@code map}: prima, in ordine, i file citati dalle sue righe
     * {@code #reference}, poi le sue proprie chiavi (che quindi vincono). {@code stack}
     * spezza i cicli.
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

            // 1) Le reference, nell'ordine in cui compaiono.
            for (String line : lines) {
                String s = line.trim();
                // '//#reference "..."' è come i pacchetti disattivano un'alternativa.
                if (s.startsWith("//") || !s.startsWith("#reference")) continue;
                String arg = extractValue(s.substring("#reference".length()));
                if (arg.isEmpty()) continue;
                Path target = resolve(dir, arg, wildcards);
                if (!Files.isRegularFile(target)) {
                    // Le reference con wildcard non risolvibili sono opzionali in RetroArch.
                    System.err.println("[GL] #reference saltata (assente): " + target);
                    continue;
                }
                merge(target, map, stack, wildcards);
            }

            // 2) Le chiavi proprie, che sovrascrivono quanto ereditato.
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
     * Tabella di sostituzione delle wildcard RetroArch. AmiJa non è un core libretro:
     * i valori sono quelli coerenti con come l'emulatore presenta il frame (driver
     * "glcore", nessuna rotazione, orientamento orizzontale). Le wildcard senza
     * corrispondenza restano letterali e vengono intercettate dal fallback di
     * {@link #resolve} — nel pacchetto Mega Bezel esistono file il cui nome <i>è</i> la
     * wildcard, che fanno da default.
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
     * Risolve un percorso relativo del preset. Se contiene wildcard prova prima la
     * versione sostituita e, solo se quel file non esiste, ricade sul percorso letterale.
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
            // Su Windows i caratteri illegali (virgolette, ':') sono un'eccezione unchecked:
            // senza questo salta fuori dall'EDT e chiude il popup dei parametri.
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
     * Come in {@link GlslPreset}, ma con in più {@code //}: un commento apre con
     * {@code '#'}, {@code ';'} o {@code "//"} e solo <b>fuori</b> dalle virgolette — la
     * chiave {@code textures = "a;b;c"} usa ';' come separatore e non va troncata.
     * I pacchetti della community commentano in stile C (è tutto il preamble di TheNamec).
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
     * Estrae il valore di una chiave — o l'argomento di {@code #reference} — con la regola
     * di RetroArch ({@code config_file_extract_value}): <b>se è fra virgolette il valore
     * finisce alla virgoletta di chiusura e tutto ciò che segue è ignorato</b>, altrimenti
     * è il resto della riga meno il commento.
     *
     * <p>È quel "tutto ciò che segue è ignorato" che serve: i pacchetti scrivono
     * {@code #reference "../x/preset.params" // commento} e
     * {@code BackgroundImage = "../assets/bg.png" // BACKGROUND texture}, e la vecchia
     * {@code unquote} — che pretendeva la virgoletta come <i>ultimo</i> carattere — in quei
     * casi restituiva la riga intera, virgolette e commento compresi.
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
