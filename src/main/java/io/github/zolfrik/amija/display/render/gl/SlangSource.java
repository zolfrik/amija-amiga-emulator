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
 * Translation of a single RetroArch {@code .slang} shader (Vulkan GLSL) into two desktop GLSL
 * sources OpenGL can compile, plus the <i>reflection</i> needed to bind its uniforms.
 *
 * <p>No native binaries are needed: the subset of Vulkan GLSL used by slang shaders is brought
 * back to desktop GLSL with textual rewrites, because the only real differences are in the
 * {@code layout} qualifiers:
 *
 * <ul>
 *   <li><b>{@code #pragma stage vertex|fragment}</b>: one file holds both stages. What precedes
 *       the first directive is common to both.</li>
 *   <li><b>{@code layout(push_constant) uniform Push {...} params;}</b>: push constants do not
 *       exist in GL: they become a normal {@code std140} uniform block, uploaded by
 *       {@link SlangChain} with a dedicated UBO.</li>
 *   <li><b>{@code layout(std140, set = 0, binding = 0) uniform UBO {...} global;}</b>:
 *       {@code set}/{@code binding} are Vulkan concepts: only {@code std140} is kept and the
 *       binding point is assigned from code.</li>
 *   <li><b>{@code layout(set = 0, binding = N) uniform sampler2D X;}</b>: the sampler binding is
 *       assigned from code with {@code glUniform1i}: the qualifier goes away.</li>
 *   <li><b>{@code layout(location = N)}</b> on varyings: legal only from GLSL 4.10 up. It is
 *       removed where it is a varying (the stages are linked together, names are enough) and
 *       kept on vertex attributes and on the fragment output.</li>
 * </ul>
 *
 * <p>The uniform block is kept <b>whole</b>, unused members included: that is what makes the
 * {@code std140} offsets computable without asking the driver (a shader compiler would
 * remove the dead members, which is why a member with no known semantic, e.g.
 * {@code OriginalFeedbackSize} in the Mega Bezel, causes no error: it disappears).
 */
public final class SlangSource {

    /** A member of a uniform block, with its {@code std140} offset in the buffer. */
    public record Member(String name, String type, int offset, int size) { }

    /** A uniform block ({@code UBO} or {@code Push}) translated to {@code layout(std140)}. */
    public static final class Block {
        public final String name;          // block name: "UBO", "Push", ...
        public final List<Member> members;
        public final int size;             // std140 size, a multiple of 16

        Block(String name, List<Member> members, int size) {
            this.name = name;
            this.members = members;
            this.size = size;
        }

        /** Offset of the member, or -1 if the block does not declare it. */
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
    // Anchored at a word start and barred from newlines: without the first,
    // "phosphor_layout(" would be a qualifier; without the second, a "layout(" inside a
    // comment would swallow all the code up to the first closing parenthesis it finds.
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
    /** Names of the declared samplers (semantics resolved by {@link SlangChain}). */
    public Set<String> samplers() { return samplers; }
    public List<ShaderParams.Param> parameters() { return parameters; }
    /** Value of {@code #pragma format}, or {@code null}. */
    public String format() { return format; }
    /** Value of {@code #pragma name} (the pass alias), or {@code null}. */
    public String name() { return name; }

    /**
     * Reads and translates a {@code .slang}. {@code glslVersion} is the {@code #version}
     * directive to emit (e.g. 430).
     */
    public static SlangSource load(Path slang, int glslVersion) throws IOException {
        String body = readWithIncludes(slang);

        // Metadata (#pragma) collected before removing the directives from the source.
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

        // Split into common / vertex / fragment. The text before the first "#pragma stage"
        // belongs to both stages.
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
                cur.append('\n');   // line replaced, but the log line numbers stay useful
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
     * Expands the {@code #include}s as the C preprocessor would: <b>without</b> deduplicating
     * (unlike the {@code .glslp} path), because slang shaders rely on repeated inclusion (an
     * {@code .inc} can be included once per stage). A missing file is not fatal: some includes
     * of the Mega Bezel pack point outside the tree, to RetroArch's common {@code include/}
     * folder, and in practice they are not needed (the functions they define are not
     * called). If they were, the error would come from the GLSL compiler anyway, with a
     * better message than this.
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
     * Rewrites the {@code layout(...)} qualifiers. The decision depends on the qualifier's
     * content and, for {@code location}, on what follows: in a vertex shader an {@code out} is
     * a varying (location to remove) while an {@code in} is an attribute (location to keep);
     * in the fragment shader the opposite holds.
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
                repl = "";   // sampler or image: glUniform1i assigns the binding
            } else {
                repl = m.group();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(repl));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** {@code true} if the {@code layout(location=...)} ending at {@code from} must be kept. */
    private static boolean keepLocation(String src, int from, Stage stage) {
        int i = from;
        while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
        boolean isIn = src.startsWith("in", i) && isBoundary(src, i + 2);
        boolean isOut = src.startsWith("out", i) && isBoundary(src, i + 3);
        if (!isIn && !isOut) return true;               // not a varying: leave it alone
        return switch (stage) {
            case VERTEX -> isIn;                        // vertex attribute: kept
            case FRAGMENT -> isOut;                     // fragment output: kept
            case COMMON -> false;                       // should not happen: removed
        };
    }

    private static boolean isBoundary(String s, int i) {
        return i >= s.length() || !(Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_');
    }

    // =====================================================================================
    // Reflection of the uniform blocks (std140 offsets)
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
            if (out.containsKey(blockName)) continue;   // same block in both stages
            out.put(blockName, layoutStd140(blockName, clean.substring(open + 1, close)));
        }
        return new ArrayList<>(out.values());
    }

    /** Computes the std140 offsets of the members declared in the block's body. */
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
                // In std140 every array element is aligned (and so spaced) to 16 bytes.
                int align = array ? Math.max(as[0], 16) : as[0];
                int stride = array ? roundUp(as[1], 16) : as[1];
                offset = roundUp(offset, align);
                members.add(new Member(memberName, type, offset, stride * count));
                offset += stride * count;
            }
        }
        return new Block(blockName, members, roundUp(offset, 16));
    }

    /** std140 {@code [baseAlignment, size]} of the type, or {@code null} if unknown. */
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

    /** Removes {@code //} and {@code /*...} comments, keeping the newlines. */
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
