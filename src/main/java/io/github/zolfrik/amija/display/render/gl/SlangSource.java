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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traduzione di un singolo shader RetroArch {@code .slang} (Vulkan-GLSL) in due sorgenti
 * GLSL desktop compilabili da OpenGL, più la <i>riflessione</i> che serve a legarne le
 * uniform.
 *
 * <p>RetroArch fa lo stesso passaggio con glslang (→ SPIR-V) e SPIRV-Cross. Qui non
 * servono binari nativi: il sottoinsieme di Vulkan-GLSL usato dagli shader slang si
 * riporta a GLSL desktop con riscritture testuali, perché le uniche differenze reali
 * sono nei qualificatori {@code layout}:
 *
 * <ul>
 *   <li><b>{@code #pragma stage vertex|fragment}</b> — un solo file contiene entrambi gli
 *       stadi. Quello che precede la prima direttiva è comune a tutti e due.</li>
 *   <li><b>{@code layout(push_constant) uniform Push {...} params;}</b> — i push constant
 *       non esistono in GL: diventano un normale uniform block {@code std140}, caricato da
 *       {@link SlangChain} con un UBO dedicato.</li>
 *   <li><b>{@code layout(std140, set = 0, binding = 0) uniform UBO {...} global;}</b> —
 *       {@code set}/{@code binding} sono concetti Vulkan: si tengono solo {@code std140} e
 *       si assegna il binding point da codice.</li>
 *   <li><b>{@code layout(set = 0, binding = N) uniform sampler2D X;}</b> — il binding dei
 *       sampler è assegnato da codice con {@code glUniform1i}: il qualificatore sparisce.</li>
 *   <li><b>{@code layout(location = N)}</b> sui varying — legale solo da GLSL 4.10 in poi.
 *       Si toglie dove è un varying (gli stadi sono linkati insieme, i nomi bastano) e si
 *       tiene sugli attributi di vertice e sull'output del fragment.</li>
 * </ul>
 *
 * <p>Il blocco uniform è tenuto <b>intero</b>, membri inutilizzati compresi: è ciò che
 * rende calcolabili gli offset {@code std140} senza interrogare il driver (in RetroArch
 * glslang elimina i membri morti, e infatti là un membro senza semantica nota — es.
 * {@code OriginalFeedbackSize} in Mega Bezel — non dà errore proprio perché sparisce).
 */
public final class SlangSource {

    /** Un membro di uniform block, con il suo offset {@code std140} nel buffer. */
    public record Member(String name, String type, int offset, int size) { }

    /** Un uniform block ({@code UBO} o {@code Push}) tradotto in {@code layout(std140)}. */
    public static final class Block {
        public final String name;          // nome del blocco: "UBO", "Push", ...
        public final List<Member> members;
        public final int size;             // dimensione std140, multiplo di 16

        Block(String name, List<Member> members, int size) {
            this.name = name;
            this.members = members;
            this.size = size;
        }

        /** Offset del membro, o -1 se il blocco non lo dichiara. */
        public int offsetOf(String member) {
            for (Member m : members) {
                if (m.name().equals(member)) return m.offset();
            }
            return -1;
        }
    }

    private static final Pattern VERSION = Pattern.compile("^\\s*#version\\b.*$");
    private static final Pattern STAGE = Pattern.compile("^\\s*#pragma\\s+stage\\s+(\\w+)");
    private static final Pattern PRAGMA_LINE = Pattern.compile("^\\s*#pragma\\s+(parameter|name|format|stage)\\b.*$");
    private static final Pattern PRAGMA_NAME = Pattern.compile("^\\s*#pragma\\s+name\\s+(\\S+)");
    private static final Pattern PRAGMA_FORMAT = Pattern.compile("^\\s*#pragma\\s+format\\s+(\\S+)");
    private static final Pattern PARAMETER = Pattern.compile(
            "#pragma\\s+parameter\\s+(\\w+)\\s+\"([^\"]*)\"\\s+"
            + "([-+0-9.eE]+)\\s+([-+0-9.eE]+)\\s+([-+0-9.eE]+)(?:\\s+([-+0-9.eE]+))?");
    private static final Pattern INCLUDE = Pattern.compile(
            "^\\s*#include\\s+[\"<]([^\">]+)[\">]");
    // Ancorato a inizio parola e vietato ai newline: senza il primo, "phosphor_layout("
    // sarebbe un qualificatore; senza il secondo, un "layout(" dentro un commento
    // divorerebbe tutto il codice fino alla prima parentesi chiusa che trova.
    private static final Pattern LAYOUT = Pattern.compile(
            "(?<![A-Za-z0-9_])layout\\s*\\(([^)\\n]*)\\)");
    private static final Pattern SAMPLER_DECL = Pattern.compile(
            "\\buniform\\s+(?:sampler2D|sampler2DArray|sampler3D|samplerCube)\\s+(\\w+)\\s*;");
    private static final Pattern BLOCK_OPEN = Pattern.compile(
            "layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+(\\w+)\\s*\\{");

    private final Path file;
    private final String vertexSrc;
    private final String fragmentSrc;
    private final List<Block> blocks;
    private final Set<String> samplers;
    private final List<ShaderParams.Param> parameters;
    private final String format;
    private final String name;

    private SlangSource(Path file, String vertexSrc, String fragmentSrc, List<Block> blocks,
                        Set<String> samplers, List<ShaderParams.Param> parameters,
                        String format, String name) {
        this.file = file;
        this.vertexSrc = vertexSrc;
        this.fragmentSrc = fragmentSrc;
        this.blocks = blocks;
        this.samplers = samplers;
        this.parameters = parameters;
        this.format = format;
        this.name = name;
    }

    public Path file() { return file; }
    public String vertexSource() { return vertexSrc; }
    public String fragmentSource() { return fragmentSrc; }
    public List<Block> blocks() { return blocks; }
    /** Nomi dei sampler dichiarati (semantica risolta da {@link SlangChain}). */
    public Set<String> samplers() { return samplers; }
    public List<ShaderParams.Param> parameters() { return parameters; }
    /** Valore di {@code #pragma format}, o {@code null}. */
    public String format() { return format; }
    /** Valore di {@code #pragma name} (alias del pass), o {@code null}. */
    public String name() { return name; }

    /**
     * Legge e traduce un {@code .slang}. {@code glslVersion} è la direttiva
     * {@code #version} da emettere (es. 430).
     */
    public static SlangSource load(Path slang, int glslVersion) throws IOException {
        String body = readWithIncludes(slang);

        // Metadati (#pragma) raccolti prima di togliere le direttive dal sorgente.
        List<ShaderParams.Param> params = new ArrayList<>();
        Set<String> seenParams = new LinkedHashSet<>();
        String format = null;
        String passName = null;
        for (String line : body.split("\n")) {
            Matcher mp = PARAMETER.matcher(line);
            if (mp.find() && seenParams.add(mp.group(1))) {
                params.add(new ShaderParams.Param(mp.group(1), mp.group(2),
                        parseF(mp.group(3)), parseF(mp.group(4)), parseF(mp.group(5)),
                        mp.group(6) != null ? parseF(mp.group(6)) : 0f));
                continue;
            }
            Matcher mf = PRAGMA_FORMAT.matcher(line);
            if (mf.find()) { format = mf.group(1); continue; }
            Matcher mn = PRAGMA_NAME.matcher(line);
            if (mn.find()) { passName = mn.group(1); }
        }

        // Divisione in comune / vertex / fragment. Il testo che precede la prima
        // "#pragma stage" appartiene a entrambi gli stadi.
        StringBuilder common = new StringBuilder();
        StringBuilder vert = new StringBuilder();
        StringBuilder frag = new StringBuilder();
        StringBuilder cur = common;
        for (String line : body.split("\n", -1)) {
            Matcher ms = STAGE.matcher(line);
            if (ms.find()) {
                String stage = ms.group(1);
                cur = stage.equalsIgnoreCase("vertex") ? vert
                        : stage.equalsIgnoreCase("fragment") ? frag : common;
                continue;
            }
            if (VERSION.matcher(line).matches() || PRAGMA_LINE.matcher(line).matches()) {
                cur.append('\n');   // riga sostituita, ma la numerazione dei log resta utile
                continue;
            }
            cur.append(line).append('\n');
        }
        if (vert.length() == 0 || frag.length() == 0) {
            throw new IOException("Shader senza '#pragma stage vertex/fragment': " + slang);
        }

        String commonGl = rewrite(common.toString(), Stage.COMMON);
        String vertGl = rewrite(vert.toString(), Stage.VERTEX);
        String fragGl = rewrite(frag.toString(), Stage.FRAGMENT);

        String header = "#version " + glslVersion + "\n";
        String vertexSrc = header + commonGl + "\n" + vertGl;
        String fragmentSrc = header + commonGl + "\n" + fragGl;

        String all = commonGl + "\n" + vertGl + "\n" + fragGl;
        List<Block> blocks = reflectBlocks(all);
        Set<String> samplers = new LinkedHashSet<>();
        Matcher msam = SAMPLER_DECL.matcher(stripComments(all));
        while (msam.find()) samplers.add(msam.group(1));

        return new SlangSource(slang, vertexSrc, fragmentSrc, blocks, samplers, params,
                format, passName);
    }

    // =====================================================================================
    // #include
    // =====================================================================================

    /**
     * Espande gli {@code #include} come farebbe il preprocessore C: <b>senza</b>
     * deduplicare (a differenza del percorso {@code .glslp}), perché gli slang contano
     * sull'inclusione ripetuta — un {@code .inc} può essere incluso una volta per stadio.
     * Un file mancante non è fatale: alcuni include del pacchetto Mega Bezel puntano fuori
     * dall'albero, alla cartella {@code include/} comune di RetroArch, e nei fatti non
     * servono (le funzioni che definiscono non vengono chiamate). Se invece servissero,
     * l'errore arriva comunque dal compilatore GLSL, con un messaggio migliore di questo.
     */
    private static String readWithIncludes(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        expand(file.toAbsolutePath().normalize(), sb, new ArrayDeque<>());
        return sb.toString();
    }

    private static void expand(Path file, StringBuilder sb, Deque<Path> stack) throws IOException {
        if (stack.contains(file)) {
            throw new IOException("Ciclo di #include su " + file);
        }
        stack.push(file);
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Matcher m = INCLUDE.matcher(line);
                if (!m.find()) {
                    sb.append(line).append('\n');
                    continue;
                }
                Path inc = file.getParent().resolve(m.group(1).replace('\\', '/')).normalize();
                if (!Files.isRegularFile(inc)) {
                    System.err.println("[GL] #include assente, ignorato: " + inc);
                    sb.append("// [AmiJa] include assente: ").append(inc).append('\n');
                    continue;
                }
                expand(inc, sb, stack);
            }
        } finally {
            stack.pop();
        }
    }

    // =====================================================================================
    // Riscrittura Vulkan-GLSL -> GLSL desktop
    // =====================================================================================

    private enum Stage { COMMON, VERTEX, FRAGMENT }

    /**
     * Riscrive i qualificatori {@code layout(...)}. La decisione dipende dal contenuto del
     * qualificatore e, per {@code location}, da cosa segue: in un vertex shader un
     * {@code out} è un varying (location da togliere) mentre un {@code in} è un attributo
     * (location da tenere); nel fragment shader vale l'opposto.
     */
    private static String rewrite(String src, Stage stage) {
        Matcher m = LAYOUT.matcher(src);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String inner = m.group(1);
            String repl;
            if (inner.contains("push_constant") || inner.contains("std140")) {
                repl = "layout(std140)";
            } else if (inner.contains("location")) {
                repl = keepLocation(src, m.end(), stage) ? m.group() : "";
            } else if (inner.contains("binding") || inner.contains("set")) {
                repl = "";   // sampler o immagine: il binding lo assegna glUniform1i
            } else {
                repl = m.group();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(repl));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** {@code true} se il {@code layout(location=…)} che finisce a {@code from} va tenuto. */
    private static boolean keepLocation(String src, int from, Stage stage) {
        int i = from;
        while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
        boolean isIn = src.startsWith("in", i) && isBoundary(src, i + 2);
        boolean isOut = src.startsWith("out", i) && isBoundary(src, i + 3);
        if (!isIn && !isOut) return true;               // non è un varying: non toccarlo
        return switch (stage) {
            case VERTEX -> isIn;                        // attributo di vertice: si tiene
            case FRAGMENT -> isOut;                     // output del fragment: si tiene
            case COMMON -> false;                       // non dovrebbe capitare: si toglie
        };
    }

    private static boolean isBoundary(String s, int i) {
        return i >= s.length() || !(Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_');
    }

    // =====================================================================================
    // Riflessione dei blocchi uniform (offset std140)
    // =====================================================================================

    private static List<Block> reflectBlocks(String src) throws IOException {
        String clean = stripComments(src);
        Map<String, Block> out = new LinkedHashMap<>();
        Matcher m = BLOCK_OPEN.matcher(clean);
        while (m.find()) {
            String blockName = m.group(1);
            int open = m.end() - 1;
            int close = matchBrace(clean, open);
            if (close < 0) throw new IOException("Blocco uniform '" + blockName + "' non chiuso");
            if (out.containsKey(blockName)) continue;   // stesso blocco nei due stadi
            out.put(blockName, layoutStd140(blockName, clean.substring(open + 1, close)));
        }
        return new ArrayList<>(out.values());
    }

    /** Calcola gli offset std140 dei membri dichiarati nel corpo del blocco. */
    private static Block layoutStd140(String blockName, String body) {
        List<Member> members = new ArrayList<>();
        int offset = 0;
        for (String decl : body.split(";")) {
            String d = decl.trim().replaceAll("\\s+", " ");
            if (d.isEmpty()) continue;
            int sp = d.indexOf(' ');
            if (sp <= 0) continue;
            String type = d.substring(0, sp).trim();
            int[] as = alignSize(type);
            if (as == null) {
                System.err.println("[GL] Tipo non gestito nel blocco " + blockName + ": " + d);
                continue;
            }
            for (String nm : d.substring(sp + 1).split(",")) {
                String memberName = nm.trim();
                int br = memberName.indexOf('[');
                boolean array = br >= 0;
                int count = 1;
                if (array) {
                    count = Math.max(1, parseArrayLen(memberName.substring(br)));
                    memberName = memberName.substring(0, br).trim();
                }
                if (memberName.isEmpty()) continue;
                // In std140 ogni elemento di array è allineato (e quindi spaziato) a 16 byte.
                int align = array ? Math.max(as[0], 16) : as[0];
                int stride = array ? roundUp(as[1], 16) : as[1];
                offset = roundUp(offset, align);
                members.add(new Member(memberName, type, offset, stride * count));
                offset += stride * count;
            }
        }
        return new Block(blockName, members, roundUp(offset, 16));
    }

    /** {@code [baseAlignment, size]} std140 del tipo, o {@code null} se sconosciuto. */
    private static int[] alignSize(String type) {
        return switch (type) {
            case "float", "int", "uint", "bool" -> new int[]{4, 4};
            case "vec2", "ivec2", "uvec2", "bvec2" -> new int[]{8, 8};
            case "vec3", "ivec3", "uvec3", "bvec3" -> new int[]{16, 12};
            case "vec4", "ivec4", "uvec4", "bvec4" -> new int[]{16, 16};
            case "mat2" -> new int[]{16, 32};
            case "mat3" -> new int[]{16, 48};
            case "mat4" -> new int[]{16, 64};
            default -> null;
        };
    }

    private static int parseArrayLen(String s) {
        int close = s.indexOf(']');
        if (close < 0) return 1;
        try {
            return Integer.parseInt(s.substring(1, close).trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static int roundUp(int v, int a) {
        return (v + a - 1) / a * a;
    }

    private static int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    /** Toglie commenti {@code //} e {@code /*…}, preservando i newline. */
    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
                out.append('\n');
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < s.length() && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                    if (s.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static float parseF(String s) {
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }
}
