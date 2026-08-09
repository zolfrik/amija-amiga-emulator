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

import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.opengl.GL33.*;

/**
 * Catena shader RetroArch in formato <b>slang</b> ({@code .slangp}) eseguita in OpenGL.
 *
 * <p>Fa per i {@code .slangp} quello che {@link ShaderChain} fa per i {@code .glslp}, ma
 * il modello slang è molto più ricco e quasi tutto il codice qui serve a coprirlo:
 *
 * <ul>
 *   <li><b>Uniform per blocco.</b> Gli slang non hanno uniform sciolte: dichiarano un
 *       {@code UBO} e un blocco {@code push_constant}, i cui membri sono legati <i>per
 *       nome</i> a una semantica. {@link SlangSource} ne calcola gli offset std140; qui si
 *       precompila al build la lista di scritture da rifare a ogni frame ({@code Setter})
 *       e si carica il tutto con un UBO per blocco. Il resto del buffer resta a zero per
 *       sempre, che è esattamente il destino dei membri senza semantica.</li>
 *   <li><b>Semantica delle texture.</b> {@code Source}, {@code Original},
 *       {@code OriginalHistoryN} (l'anello dei frame precedenti), {@code PassOutputN},
 *       {@code PassFeedbackN} (l'uscita di un pass al frame <i>precedente</i>), gli alias
 *       dichiarati con {@code aliasN} / {@code #pragma name} e le LUT del preset.</li>
 *   <li><b>Feedback.</b> Un pass la cui uscita è letta come feedback ha due texture in
 *       ping-pong: si scrive su quella del frame corrente e si legge l'altra.</li>
 *   <li><b>Formati.</b> {@code #pragma format} dello shader, sovrascritto — come in
 *       RetroArch — da {@code float_framebufferN} / {@code srgb_framebufferN} del preset.</li>
 *   <li><b>Mipmap.</b> {@code mipmap_inputK} rende mipmappata l'uscita del pass e fa
 *       campionare con i mipmap il suo ingresso.</li>
 * </ul>
 *
 * <p>Il filtro e il wrap non si applicano alla texture ma a <b>sampler object</b>
 * (GL 3.3): la stessa texture può comparire in due sampler dello stesso pass con
 * impostazioni diverse — succede appena un pass legge sia {@code Source} sia l'alias del
 * pass che gliela produce — e con i parametri per-texture vincerebbe l'ultimo scritto.
 *
 * <p>Come per {@link ShaderChain}: {@link #build} lancia in caso di errore e il chiamante
 * ripiega sul passthrough; a regime nessuna chiamata deve poter crashare.
 */
public final class SlangChain implements RenderChain {

    private static final int ATTR_POSITION = 0;
    private static final int ATTR_TEXCOORD = 1;

    /** MVP ortho [0,1] -> [-1,1], column-major: identica a quella del percorso .glslp. */
    private static final float[] MVP = {
            2f, 0f, 0f, 0f,
            0f, 2f, 0f, 0f,
            0f, 0f, -1f, 0f,
            -1f, -1f, 0f, 1f
    };

    private static final Pattern HISTORY = Pattern.compile("OriginalHistory(\\d+)");
    private static final Pattern PASS_OUTPUT = Pattern.compile("PassOutput(\\d+)");
    private static final Pattern PASS_FEEDBACK = Pattern.compile("PassFeedback(\\d+)");

    // =====================================================================================
    // Modello
    // =====================================================================================

    /** Che cosa scrivere in un membro di uniform block. */
    private enum Op { MVP, SIZE, FRAME_COUNT, FRAME_DIRECTION, ROTATION,
                      SUBFRAME_TOTAL, SUBFRAME_CURRENT, FRAME_TIME_DELTA, ORIGINAL_FPS, PARAM }

    /** Di quale superficie prendere la dimensione per una semantica {@code …Size}. */
    private enum SizeOf { SOURCE, ORIGINAL, OUTPUT, FINAL_VIEWPORT, PASS, LUT }

    /** Da dove viene la texture legata a un sampler. */
    private enum TexOf { SOURCE, ORIGINAL, HISTORY, PASS, FEEDBACK, LUT, NONE }

    private record Setter(Op op, int offset, String type, SizeOf sizeOf, int index, String param) { }

    /** Un sampler dello shader, con la sua sorgente e il sampler object già pronto. */
    private static final class TexBind {
        final String name;
        final TexOf of;
        final int index;
        int location = -1;   // -1 = eliminato dal compilatore: non consuma texture unit
        int samplerObj;

        TexBind(String name, TexOf of, int index) {
            this.name = name;
            this.of = of;
            this.index = index;
        }
    }

    private static final class BlockBind {
        final String name;
        final int size;
        final List<Setter> setters;
        int buffer;
        int blockIndex = GL_INVALID_INDEX;
        ByteBuffer data;

        BlockBind(String name, int size, List<Setter> setters) {
            this.name = name;
            this.size = size;
            this.setters = setters;
        }
    }

    private static final class Pass {
        SlangPreset.PassSpec spec;
        SlangSource src;
        int program;
        final List<BlockBind> blocks = new ArrayList<>();
        final List<TexBind> texBinds = new ArrayList<>();
        /** L'uscita di questo pass è letta come feedback: serve il ping-pong. */
        boolean feedback;
        /** L'uscita va corredata di mipmap. */
        boolean mipmapOutput;
        /** Il pass disegna su un FBO (l'ultimo va sul backbuffer, se nessuno lo rilegge). */
        boolean offscreen = true;
        int internalFormat = GL_RGBA8;
        boolean srgb;
        final int[] fbo = {0, 0};
        final int[] tex = {0, 0};
        int inW, inH, outW, outH;
    }

    private final Path presetFile;
    private final SlangPreset preset;
    private final Pass[] passes;
    private final List<SlangPreset.TextureSpec> lutSpecs;
    private final List<int[]> luts = new ArrayList<>();   // [texId, w, h]
    private final Map<String, Float> params = new LinkedHashMap<>();
    private final Object paramLock = new Object();
    /** Alias -> indice del pass che lo produce ({@code aliasN} o {@code #pragma name}). */
    private final Map<String, Integer> aliases = new LinkedHashMap<>();
    /** Sampler object per combinazione (filtro, wrap, mipmap). */
    private final Map<String, Integer> samplerObjects = new LinkedHashMap<>();

    private int quadVbo;
    private int blackTex;
    private int maxTexUnits = 16;

    // Anello della storia di Original: historyTexture(k) = frame di k fa (k >= 1).
    private int historyDepth;
    private int[] historyTex = new int[0];
    private int[] historyFbo = new int[0];
    private int historyReadFbo;
    private int historyHead;

    // Ping-pong dei feedback + geometria con cui sono stati allocati gli FBO.
    private int parity;
    private int builtOrigW = -1, builtOrigH = -1, builtVpW = -1, builtVpH = -1;
    private int origW, origH, vpW, vpH;
    private boolean unitOverflowLogged;

    private SlangChain(Path presetFile, SlangPreset preset, Pass[] passes,
                       List<SlangPreset.TextureSpec> lutSpecs) {
        this.presetFile = presetFile;
        this.preset = preset;
        this.passes = passes;
        this.lutSpecs = lutSpecs;
    }

    // =====================================================================================
    // Build
    // =====================================================================================

    /** Compila un preset {@code .slangp}. Da chiamare sul thread GL, con contesto attivo. */
    public static SlangChain build(Path slangp) throws IOException {
        int major = glGetInteger(GL_MAJOR_VERSION);
        int minor = glGetInteger(GL_MINOR_VERSION);
        if (major < 3 || (major == 3 && minor < 3)) {
            throw new IOException("I preset .slangp richiedono OpenGL 3.3+, qui c'è "
                    + major + "." + minor);
        }
        boolean gl43 = major > 4 || (major == 4 && minor >= 3);
        if (!gl43) {
            // Non è un limite della traduzione ma degli shader: buona parte di quelli in
            // circolazione — Mega Bezel compreso — usa lo swizzle sugli scalari
            // (`float f; f.x`), che è un cascame Cg che i driver accettano solo da GLSL
            // 4.x in su. Sotto, i preset semplici passano e i grossi no.
            System.err.println("[GL] OpenGL " + major + "." + minor + ": i preset .slangp"
                    + " complessi vogliono la 4.3, qui alcuni non compileranno.");
        }
        int version = gl43 ? 430 : 330;

        SlangPreset preset = SlangPreset.load(slangp);
        List<SlangPreset.PassSpec> specs = preset.passes();
        int n = specs.size();
        Pass[] passes = new Pass[n];
        SlangChain chain = new SlangChain(slangp, preset, passes, preset.textures());
        chain.maxTexUnits = Math.max(8, glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS));

        // 1) Traduzione dei sorgenti + raccolta di alias e parametri.
        for (int i = 0; i < n; i++) {
            Pass p = new Pass();
            p.spec = specs.get(i);
            p.src = SlangSource.load(p.spec.shader, version);
            passes[i] = p;

            String alias = !p.spec.alias.isEmpty() ? p.spec.alias : p.src.name();
            if (alias != null && !alias.isEmpty()) {
                chain.aliases.put(alias, i);
            }
            for (ShaderParams.Param prm : p.src.parameters()) {
                chain.params.putIfAbsent(prm.name(), prm.def());
            }
        }
        // Override: prima il preset (con tutta la sua catena di #reference), poi l'utente.
        applyOverrides(chain.params, preset.paramOverrides());
        applyOverrides(chain.params, ShaderParams.loadOverrides(slangp));

        // 2) Semantica dei sampler. Serve conoscere tutti gli alias: secondo giro.
        for (Pass p : passes) {
            for (String sampler : p.src.samplers()) {
                TexBind b = chain.textureSemantic(sampler);
                if (b == null) {
                    System.err.println("[GL] Sampler senza semantica in "
                            + p.spec.shader.getFileName() + ": " + sampler + " (legato a nero)");
                    b = new TexBind(sampler, TexOf.NONE, 0);
                }
                p.texBinds.add(b);
            }
        }

        // 3) Chi ha bisogno di feedback, di mipmap, di un FBO in uscita.
        boolean lastIsRead = false;
        for (Pass p : passes) {
            for (TexBind t : p.texBinds) {
                if (t.of == TexOf.FEEDBACK) passes[t.index].feedback = true;
                if (t.of == TexOf.HISTORY) chain.historyDepth = Math.max(chain.historyDepth, t.index);
                if ((t.of == TexOf.PASS || t.of == TexOf.FEEDBACK) && t.index == n - 1) {
                    lastIsRead = true;
                }
            }
        }
        // L'ultimo pass disegna direttamente sul backbuffer, salvo che qualcuno ne rilegga
        // l'uscita (alias o feedback): in quel caso passa da un FBO e poi si copia.
        passes[n - 1].offscreen = lastIsRead;
        for (int i = 0; i < n; i++) {
            // mipmap_inputK: mipmap sull'uscita del pass K e campionamento con mipmap del
            // suo ingresso, che è l'uscita di K-1.
            if (passes[i].spec.mipmapInput) {
                passes[i].mipmapOutput = true;
                if (i > 0) passes[i - 1].mipmapOutput = true;
            }
        }

        // 4) Formati dei framebuffer.
        for (Pass p : passes) {
            int[] fmt = internalFormat(p);
            p.internalFormat = fmt[0];
            p.srgb = fmt[1] != 0;
        }

        // 5) Compilazione + risoluzione delle locazioni e dei sampler object.
        for (Pass p : passes) {
            p.program = buildProgram(p.src);
            for (SlangSource.Block b : p.src.blocks()) {
                BlockBind bb = new BlockBind(b.name, b.size, chain.settersFor(b));
                bb.blockIndex = glGetUniformBlockIndex(p.program, b.name);
                p.blocks.add(bb);
            }
            for (TexBind t : p.texBinds) {
                t.location = glGetUniformLocation(p.program, t.name);
                t.samplerObj = chain.samplerFor(p, t);
            }
        }

        chain.createQuad();
        chain.createBlackTexture();
        chain.createUniformBuffers();
        chain.loadLuts();
        return chain;
    }

    private static void applyOverrides(Map<String, Float> params, Map<String, Float> over) {
        for (Map.Entry<String, Float> e : over.entrySet()) {
            if (params.containsKey(e.getKey())) {
                params.put(e.getKey(), e.getValue());
            }
        }
    }

    /** {@code [internalFormat, srgb]} del framebuffer del pass. Il preset vince sul pragma. */
    private static int[] internalFormat(Pass p) {
        int fmt = GL_RGBA8;
        boolean srgb = false;
        String pragma = p.src.format();
        if (pragma != null) {
            switch (pragma) {
                case "R8G8B8A8_SRGB" -> { fmt = GL_SRGB8_ALPHA8; srgb = true; }
                case "R16G16B16A16_SFLOAT" -> fmt = GL_RGBA16F;
                case "R32G32B32A32_SFLOAT" -> fmt = GL_RGBA32F;
                case "A2B10G10R10_UNORM_PACK32" -> fmt = GL_RGB10_A2;
                case "R8G8B8A8_UNORM" -> fmt = GL_RGBA8;
                default -> System.err.println("[GL] #pragma format non gestito: " + pragma);
            }
        }
        if (p.spec.srgbFramebuffer) {
            fmt = GL_SRGB8_ALPHA8;
            srgb = true;
        } else if (p.spec.floatFramebuffer && fmt != GL_RGBA32F) {
            fmt = GL_RGBA16F;
            srgb = false;
        }
        return new int[]{fmt, srgb ? 1 : 0};
    }

    // =====================================================================================
    // Semantiche
    // =====================================================================================

    /** Risolve il nome di un sampler nella texture che lo alimenta, o {@code null}. */
    private TexBind textureSemantic(String name) {
        if (name.equals("Source")) return new TexBind(name, TexOf.SOURCE, 0);
        if (name.equals("Original")) return new TexBind(name, TexOf.ORIGINAL, 0);

        Matcher m = HISTORY.matcher(name);
        if (m.matches()) {
            int k = Integer.parseInt(m.group(1));
            return k == 0 ? new TexBind(name, TexOf.ORIGINAL, 0)
                          : new TexBind(name, TexOf.HISTORY, k);
        }
        m = PASS_OUTPUT.matcher(name);
        if (m.matches()) {
            int k = Integer.parseInt(m.group(1));
            return k < passes.length ? new TexBind(name, TexOf.PASS, k) : null;
        }
        m = PASS_FEEDBACK.matcher(name);
        if (m.matches()) {
            int k = Integer.parseInt(m.group(1));
            return k < passes.length ? new TexBind(name, TexOf.FEEDBACK, k) : null;
        }
        if (name.endsWith("Feedback")) {
            Integer a = aliases.get(name.substring(0, name.length() - "Feedback".length()));
            if (a != null) return new TexBind(name, TexOf.FEEDBACK, a);
        }
        Integer a = aliases.get(name);
        if (a != null) return new TexBind(name, TexOf.PASS, a);

        for (int i = 0; i < lutSpecs.size(); i++) {
            if (lutSpecs.get(i).name.equals(name)) return new TexBind(name, TexOf.LUT, i);
        }
        return null;
    }

    /** Precompila la lista di scritture nel buffer di un uniform block. */
    private List<Setter> settersFor(SlangSource.Block block) {
        List<Setter> out = new ArrayList<>();
        for (SlangSource.Member mem : block.members) {
            String n = mem.name();
            Op op = switch (n) {
                case "MVP" -> Op.MVP;
                case "FrameCount" -> Op.FRAME_COUNT;
                case "FrameDirection" -> Op.FRAME_DIRECTION;
                case "Rotation" -> Op.ROTATION;
                case "TotalSubFrames" -> Op.SUBFRAME_TOTAL;
                case "CurrentSubFrame" -> Op.SUBFRAME_CURRENT;
                case "FrameTimeDelta" -> Op.FRAME_TIME_DELTA;
                case "OriginalFPS" -> Op.ORIGINAL_FPS;
                default -> null;
            };
            if (op != null) {
                out.add(new Setter(op, mem.offset(), mem.type(), null, 0, null));
                continue;
            }
            if (n.endsWith("Size")) {
                Setter s = sizeSetter(n.substring(0, n.length() - "Size".length()), mem);
                if (s != null) { out.add(s); continue; }
            }
            if (params.containsKey(n)) {
                out.add(new Setter(Op.PARAM, mem.offset(), mem.type(), null, 0, n));
            }
            // Membro senza semantica né parametro: in RetroArch glslang lo eliminerebbe
            // perché inutilizzato. Qui resta a zero, che è lo stesso risultato.
        }
        return out;
    }

    private Setter sizeSetter(String base, SlangSource.Member mem) {
        SizeOf of;
        int idx = 0;
        Matcher m;
        if (base.equals("Source")) {
            of = SizeOf.SOURCE;
        } else if (base.equals("Original") || base.equals("OriginalFeedback")) {
            of = SizeOf.ORIGINAL;
        } else if (base.equals("Output")) {
            of = SizeOf.OUTPUT;
        } else if (base.equals("FinalViewport")) {
            of = SizeOf.FINAL_VIEWPORT;
        } else if (HISTORY.matcher(base).matches()) {
            of = SizeOf.ORIGINAL;   // la storia ha la stessa geometria di Original
        } else if ((m = PASS_OUTPUT.matcher(base)).matches()) {
            idx = Integer.parseInt(m.group(1));
            if (idx >= passes.length) return null;
            of = SizeOf.PASS;
        } else {
            String plain = base.endsWith("Feedback")
                    ? base.substring(0, base.length() - "Feedback".length()) : base;
            Integer a = aliases.get(plain);
            if (a != null) {
                idx = a;
                of = SizeOf.PASS;
            } else {
                int lut = -1;
                for (int i = 0; i < lutSpecs.size(); i++) {
                    if (lutSpecs.get(i).name.equals(base)) { lut = i; break; }
                }
                if (lut < 0) return null;
                idx = lut;
                of = SizeOf.LUT;
            }
        }
        return new Setter(Op.SIZE, mem.offset(), mem.type(), of, idx, null);
    }

    // =====================================================================================
    // Esecuzione
    // =====================================================================================

    @Override
    public void render(int origTex, int origWidth, int origHeight,
                       int vpX, int vpY, int vpW, int vpH, long frameCount) {
        this.origW = origWidth;
        this.origH = origHeight;
        this.vpW = vpW;
        this.vpH = vpH;
        ensureFramebuffers();

        glBindBuffer(GL_ARRAY_BUFFER, quadVbo);
        glVertexAttribPointer(ATTR_POSITION, 2, GL_FLOAT, false, 16, 0L);
        glEnableVertexAttribArray(ATTR_POSITION);
        glVertexAttribPointer(ATTR_TEXCOORD, 2, GL_FLOAT, false, 16, 8L);
        glEnableVertexAttribArray(ATTR_TEXCOORD);

        int n = passes.length;
        int usedUnits = 0;
        for (int i = 0; i < n; i++) {
            Pass p = passes[i];
            int inputTex = (i == 0) ? origTex : outputTexture(passes[i - 1]);
            boolean toScreen = (i == n - 1) && !p.offscreen;

            if (toScreen) {
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glViewport(vpX, vpY, vpW, vpH);
            } else {
                glBindFramebuffer(GL_FRAMEBUFFER, p.fbo[p.feedback ? parity : 0]);
                glViewport(0, 0, p.outW, p.outH);
            }
            // La conversione sRGB vale per gli FBO che l'hanno chiesta, non per il
            // backbuffer: quello non è detto che sia sRGB, e convertirci sopra
            // schiarirebbe l'immagine finale.
            boolean srgbNow = p.srgb && !toScreen;
            if (srgbNow) glEnable(GL_FRAMEBUFFER_SRGB);

            glUseProgram(p.program);
            uploadBlocks(p, frameCount);
            usedUnits = Math.max(usedUnits, bindTextures(p, inputTex, origTex));
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

            if (srgbNow) glDisable(GL_FRAMEBUFFER_SRGB);

            if (p.mipmapOutput && !toScreen) {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, p.tex[p.feedback ? parity : 0]);
                glGenerateMipmap(GL_TEXTURE_2D);
            }
        }

        // Se l'ultimo pass è finito su un FBO (perché qualcuno ne rilegge l'uscita), il
        // risultato va copiato sul backbuffer. L'FBO ha già la misura del viewport.
        Pass lastPass = passes[n - 1];
        if (lastPass.offscreen) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, lastPass.fbo[lastPass.feedback ? parity : 0]);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            glBlitFramebuffer(0, 0, lastPass.outW, lastPass.outH,
                    vpX, vpY, vpX + vpW, vpY + vpH, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        }

        pushHistory(origTex);
        parity ^= 1;

        glUseProgram(0);
        glDisableVertexAttribArray(ATTR_POSITION);
        glDisableVertexAttribArray(ATTR_TEXCOORD);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        // Ripulisce lo stato per il passthrough/overlay fixed-function che segue.
        for (int u = 0; u < usedUnits; u++) {
            glActiveTexture(GL_TEXTURE0 + u);
            glBindSampler(u, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
        glActiveTexture(GL_TEXTURE0);
    }

    private void uploadBlocks(Pass p, long frameCount) {
        long fc = p.spec.frameCountMod > 0 ? (frameCount % p.spec.frameCountMod) : frameCount;
        int binding = 0;
        for (BlockBind b : p.blocks) {
            for (Setter s : b.setters) {
                apply(b.data, s, p, fc);
            }
            glBindBuffer(GL_UNIFORM_BUFFER, b.buffer);
            b.data.clear();
            glBufferSubData(GL_UNIFORM_BUFFER, 0, b.data);
            if (b.blockIndex != GL_INVALID_INDEX) {
                glUniformBlockBinding(p.program, b.blockIndex, binding);
                glBindBufferBase(GL_UNIFORM_BUFFER, binding, b.buffer);
            }
            binding++;
        }
        glBindBuffer(GL_UNIFORM_BUFFER, 0);
    }

    private void apply(ByteBuffer buf, Setter s, Pass p, long frameCount) {
        int off = s.offset();
        switch (s.op()) {
            case MVP -> {
                for (int i = 0; i < 16; i++) buf.putFloat(off + i * 4, MVP[i]);
            }
            case SIZE -> {
                float[] wh = sizeOf(s.sizeOf(), s.index(), p);
                buf.putFloat(off, wh[0]);
                buf.putFloat(off + 4, wh[1]);
                buf.putFloat(off + 8, wh[0] == 0 ? 0 : 1f / wh[0]);
                buf.putFloat(off + 12, wh[1] == 0 ? 0 : 1f / wh[1]);
            }
            case FRAME_COUNT -> putNumber(buf, off, s.type(), frameCount);
            case FRAME_DIRECTION -> putNumber(buf, off, s.type(), 1);
            case ROTATION, FRAME_TIME_DELTA -> putNumber(buf, off, s.type(), 0);
            case SUBFRAME_TOTAL, SUBFRAME_CURRENT -> putNumber(buf, off, s.type(), 1);
            case ORIGINAL_FPS -> putNumber(buf, off, s.type(), 50);
            case PARAM -> {
                float v;
                synchronized (paramLock) {
                    Float f = params.get(s.param());
                    v = f == null ? 0f : f;
                }
                if (isFloat(s.type())) buf.putFloat(off, v);
                else buf.putInt(off, (int) v);
            }
        }
    }

    private static boolean isFloat(String type) {
        return type.equals("float") || type.startsWith("vec") || type.startsWith("mat");
    }

    private static void putNumber(ByteBuffer buf, int off, String type, long value) {
        if (isFloat(type)) buf.putFloat(off, value);
        else buf.putInt(off, (int) value);
    }

    private float[] sizeOf(SizeOf of, int idx, Pass p) {
        return switch (of) {
            case SOURCE -> new float[]{p.inW, p.inH};
            case ORIGINAL -> new float[]{origW, origH};
            case OUTPUT -> new float[]{p.outW, p.outH};
            case FINAL_VIEWPORT -> new float[]{vpW, vpH};
            case PASS -> new float[]{passes[idx].outW, passes[idx].outH};
            case LUT -> {
                int[] l = luts.get(idx);
                yield new float[]{l[1], l[2]};
            }
        };
    }

    /** Lega i sampler del pass; restituisce quante texture unit ha usato. */
    private int bindTextures(Pass p, int inputTex, int origTex) {
        int unit = 0;
        for (TexBind b : p.texBinds) {
            if (b.location < 0) continue;   // sampler eliminato: nessuna unit sprecata
            if (unit >= maxTexUnits) {
                if (!unitOverflowLogged) {
                    unitOverflowLogged = true;
                    System.err.println("[GL] Troppi sampler in " + p.spec.shader.getFileName()
                            + ": oltre " + maxTexUnits + " ignorati");
                }
                break;
            }
            glActiveTexture(GL_TEXTURE0 + unit);
            glBindTexture(GL_TEXTURE_2D, resolveTexture(b, inputTex, origTex));
            glBindSampler(unit, b.samplerObj);
            glUniform1i(b.location, unit);
            unit++;
        }
        return unit;
    }

    private int resolveTexture(TexBind b, int inputTex, int origTex) {
        return switch (b.of) {
            case SOURCE -> inputTex;
            case ORIGINAL -> origTex;
            case HISTORY -> historyTexture(b.index);
            case PASS -> outputTexture(passes[b.index]);
            case FEEDBACK -> feedbackTexture(passes[b.index]);
            case LUT -> luts.get(b.index)[0];
            case NONE -> blackTex;
        };
    }

    private int outputTexture(Pass p) {
        return p.tex[p.feedback ? parity : 0];
    }

    private int feedbackTexture(Pass p) {
        return p.feedback ? p.tex[parity ^ 1] : p.tex[0];
    }

    private int historyTexture(int k) {
        if (k < 1 || k > historyDepth || historyTex.length == 0) return blackTex;
        int idx = ((historyHead - k) % historyTex.length + historyTex.length) % historyTex.length;
        return historyTex[idx];
    }

    /** Copia Original nell'anello della storia, per gli {@code OriginalHistoryN} del prossimo frame. */
    private void pushHistory(int origTex) {
        if (historyDepth <= 0) return;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, historyReadFbo);
        glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, origTex, 0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, historyFbo[historyHead]);
        glBlitFramebuffer(0, 0, origW, origH, 0, 0, origW, origH, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        historyHead = (historyHead + 1) % historyTex.length;
    }

    // =====================================================================================
    // Sampler object
    // =====================================================================================

    /**
     * Il sampler object da usare per un binding. Filtro e wrap vengono dal pass che
     * <i>produce</i> la texture (o dalla LUT); per {@code Source} e {@code Original}
     * valgono invece le impostazioni del pass che la consuma, come in RetroArch.
     */
    private int samplerFor(Pass consumer, TexBind b) {
        boolean linear = consumer.spec.filterLinear;
        String wrap = consumer.spec.wrapMode;
        boolean mipmap = false;
        switch (b.of) {
            case PASS, FEEDBACK -> {
                Pass src = passes[b.index];
                linear = src.spec.filterLinear;
                wrap = src.spec.wrapMode;
                mipmap = src.mipmapOutput;
            }
            case LUT -> {
                SlangPreset.TextureSpec ts = lutSpecs.get(b.index);
                linear = ts.linear;
                wrap = ts.wrapMode;
                mipmap = ts.mipmap;
            }
            case SOURCE -> mipmap = consumer.spec.mipmapInput;
            default -> { }
        }
        return sampler(linear, wrap, mipmap);
    }

    private int sampler(boolean linear, String wrapMode, boolean mipmap) {
        String key = linear + "|" + wrapMode + "|" + mipmap;
        Integer cached = samplerObjects.get(key);
        if (cached != null) return cached;
        int mag = linear ? GL_LINEAR : GL_NEAREST;
        int min = mipmap
                ? (linear ? GL_LINEAR_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_NEAREST)
                : mag;
        int wrap = wrapModeGl(wrapMode);
        int s = glGenSamplers();
        glSamplerParameteri(s, GL_TEXTURE_MIN_FILTER, min);
        glSamplerParameteri(s, GL_TEXTURE_MAG_FILTER, mag);
        glSamplerParameteri(s, GL_TEXTURE_WRAP_S, wrap);
        glSamplerParameteri(s, GL_TEXTURE_WRAP_T, wrap);
        samplerObjects.put(key, s);
        return s;
    }

    private static int wrapModeGl(String mode) {
        if (mode == null) return GL_CLAMP_TO_BORDER;
        return switch (mode.trim()) {
            case "clamp_to_edge" -> GL_CLAMP_TO_EDGE;
            case "repeat" -> GL_REPEAT;
            case "mirrored_repeat" -> GL_MIRRORED_REPEAT;
            default -> GL_CLAMP_TO_BORDER;
        };
    }

    @Override
    public void setParam(String name, float value) {
        synchronized (paramLock) {
            if (params.containsKey(name)) {
                params.put(name, value);
            }
        }
    }

    // =====================================================================================
    // Allocazione delle risorse GL
    // =====================================================================================

    private void ensureFramebuffers() {
        if (origW == builtOrigW && origH == builtOrigH && vpW == builtVpW && vpH == builtVpH) {
            return;
        }
        deleteFramebuffers();

        int curW = origW, curH = origH;
        int n = passes.length;
        for (int i = 0; i < n; i++) {
            Pass p = passes[i];
            p.inW = curW;
            p.inH = curH;
            boolean last = (i == n - 1);
            if (last) {
                p.outW = vpW;
                p.outH = vpH;
            } else {
                p.outW = scaled(p.spec.scaleTypeX, p.spec.scaleX, curW, vpW);
                p.outH = scaled(p.spec.scaleTypeY, p.spec.scaleY, curH, vpH);
            }
            if (!last || p.offscreen) {
                int copies = p.feedback ? 2 : 1;
                for (int c = 0; c < copies; c++) {
                    p.tex[c] = createTarget(p.outW, p.outH, p.internalFormat, p.mipmapOutput);
                    p.fbo[c] = createFbo(p.tex[c]);
                }
            }
            curW = p.outW;
            curH = p.outH;
        }

        if (historyDepth > 0) {
            historyTex = new int[historyDepth];
            historyFbo = new int[historyDepth];
            for (int i = 0; i < historyDepth; i++) {
                historyTex[i] = createTarget(origW, origH, GL_RGBA8, false);
                historyFbo[i] = createFbo(historyTex[i]);
            }
            historyReadFbo = glGenFramebuffers();
            historyHead = 0;
        }

        builtOrigW = origW;
        builtOrigH = origH;
        builtVpW = vpW;
        builtVpH = vpH;
    }

    private static int createTarget(int w, int h, int internalFormat, boolean mipmap) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        int type = (internalFormat == GL_RGBA16F || internalFormat == GL_RGBA32F)
                ? GL_FLOAT : GL_UNSIGNED_BYTE;
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, w, h, 0, GL_RGBA, type, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER,
                mipmap ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        if (mipmap) {
            // I livelli devono esistere prima del primo campionamento, altrimenti la
            // texture è incompleta e restituisce nero.
            glGenerateMipmap(GL_TEXTURE_2D);
        }
        return tex;
    }

    private static int createFbo(int tex) {
        int f = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, f);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return f;
    }

    private static int scaled(String type, float scale, int inSize, int vpSize) {
        return switch (type == null ? "source" : type.trim()) {
            case "viewport" -> Math.max(1, Math.round(vpSize * scale));
            case "absolute" -> Math.max(1, Math.round(scale));
            default -> Math.max(1, Math.round(inSize * scale));
        };
    }

    private void createQuad() {
        float[] verts = {
                0f, 0f, 0f, 0f,
                1f, 0f, 1f, 0f,
                0f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
        };
        FloatBuffer fb = BufferUtils.createFloatBuffer(verts.length);
        fb.put(verts).flip();
        quadVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, quadVbo);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /** Texture 1×1 nera per i sampler senza semantica: campionarli deve restare definito. */
    private void createBlackTexture() {
        blackTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, blackTex);
        IntBuffer px = BufferUtils.createIntBuffer(1);
        px.put(0).flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, px);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }

    private void createUniformBuffers() {
        for (Pass p : passes) {
            for (BlockBind b : p.blocks) {
                // Direct + zero-inizializzato: i membri senza semantica non vengono mai
                // toccati e restano a zero per tutta la vita della catena.
                b.data = ByteBuffer.allocateDirect(b.size).order(ByteOrder.nativeOrder());
                b.buffer = glGenBuffers();
                glBindBuffer(GL_UNIFORM_BUFFER, b.buffer);
                glBufferData(GL_UNIFORM_BUFFER, b.size, GL_DYNAMIC_DRAW);
            }
        }
        glBindBuffer(GL_UNIFORM_BUFFER, 0);
    }

    private void loadLuts() {
        for (SlangPreset.TextureSpec ts : lutSpecs) {
            int tex = 0, w = 0, h = 0;
            try {
                BufferedImage img = ImageIO.read(ts.path.toFile());
                if (img != null) {
                    tex = uploadLut(img, ts);
                    w = img.getWidth();
                    h = img.getHeight();
                } else {
                    System.err.println("[GL] LUT illeggibile: " + ts.path);
                }
            } catch (IOException e) {
                System.err.println("[GL] LUT non caricata (" + ts.path + "): " + e.getMessage());
            }
            if (tex == 0) {
                tex = blackTex;
                w = 1;
                h = 1;
            }
            luts.add(new int[]{tex, w, h});
        }
    }

    private int uploadLut(BufferedImage img, SlangPreset.TextureSpec ts) {
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        // Flip verticale: BufferedImage ha origine in alto, GL in basso.
        IntBuffer buf = BufferUtils.createIntBuffer(w * h);
        for (int y = h - 1; y >= 0; y--) {
            buf.put(argb, y * w, w);
        }
        buf.flip();
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER,
                ts.mipmap ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        if (ts.mipmap) glGenerateMipmap(GL_TEXTURE_2D);
        return tex;
    }

    // =====================================================================================
    // Compilazione
    // =====================================================================================

    private static int buildProgram(SlangSource src) throws IOException {
        int vs = compile(GL_VERTEX_SHADER, src.vertexSource(), src.file() + " [VERTEX]");
        int fs;
        try {
            fs = compile(GL_FRAGMENT_SHADER, src.fragmentSource(), src.file() + " [FRAGMENT]");
        } catch (IOException e) {
            glDeleteShader(vs);
            throw e;
        }
        int prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glBindAttribLocation(prog, ATTR_POSITION, "Position");
        glBindAttribLocation(prog, ATTR_TEXCOORD, "TexCoord");
        glLinkProgram(prog);
        glDeleteShader(vs);
        glDeleteShader(fs);
        if (glGetProgrami(prog, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(prog);
            glDeleteProgram(prog);
            throw new IOException("Link fallito (" + src.file() + "): " + log);
        }
        return prog;
    }

    private static int compile(int type, String source, String label) throws IOException {
        int sh = glCreateShader(type);
        glShaderSource(sh, source);
        glCompileShader(sh);
        if (glGetShaderi(sh, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(sh);
            glDeleteShader(sh);
            throw new IOException("Compilazione fallita (" + label + "): " + log);
        }
        return sh;
    }

    // =====================================================================================
    // Cleanup
    // =====================================================================================

    @Override
    public void dispose() {
        deleteFramebuffers();
        for (Pass p : passes) {
            if (p.program != 0) glDeleteProgram(p.program);
            p.program = 0;
            for (BlockBind b : p.blocks) {
                if (b.buffer != 0) glDeleteBuffers(b.buffer);
                b.buffer = 0;
            }
        }
        for (int[] l : luts) {
            if (l[0] != 0 && l[0] != blackTex) glDeleteTextures(l[0]);
        }
        luts.clear();
        for (int s : samplerObjects.values()) glDeleteSamplers(s);
        samplerObjects.clear();
        if (blackTex != 0) {
            glDeleteTextures(blackTex);
            blackTex = 0;
        }
        if (quadVbo != 0) {
            glDeleteBuffers(quadVbo);
            quadVbo = 0;
        }
    }

    private void deleteFramebuffers() {
        for (Pass p : passes) {
            for (int c = 0; c < 2; c++) {
                if (p.fbo[c] != 0) glDeleteFramebuffers(p.fbo[c]);
                if (p.tex[c] != 0) glDeleteTextures(p.tex[c]);
                p.fbo[c] = 0;
                p.tex[c] = 0;
            }
        }
        for (int t : historyTex) if (t != 0) glDeleteTextures(t);
        for (int f : historyFbo) if (f != 0) glDeleteFramebuffers(f);
        if (historyReadFbo != 0) {
            glDeleteFramebuffers(historyReadFbo);
            historyReadFbo = 0;
        }
        historyTex = new int[0];
        historyFbo = new int[0];
        builtOrigW = builtOrigH = builtVpW = builtVpH = -1;
    }

    /** Il preset caricato: usato dai test e dalla diagnostica. */
    public SlangPreset preset() {
        return preset;
    }

    @Override
    public String toString() {
        return "SlangChain[" + presetFile.getFileName() + ", " + passes.length + " pass]";
    }
}
