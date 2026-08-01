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
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.opengl.GL30.*;

/**
 * Catena shader RetroArch (GLSL "classico") eseguita in OpenGL.
 *
 * <p>Data una texture <b>ORIG</b> (il frame Amiga già croppato ad altezza nativa,
 * prodotto dal prepass di {@link GlDisplaySurface}), compila i pass del preset e li
 * esegue in cascata su FBO intermedi, con l'ultimo pass che disegna sul framebuffer di
 * default (viewport aspect-fit). Supporta: {@code #include} ricorsivi, {@code #version},
 * iniezione di {@code #define VERTEX/FRAGMENT/PARAMETER_UNIFORM}, estrazione dei
 * {@code #pragma parameter} (con override dal preset), uniform standard
 * (MVPMatrix/Texture/TextureSize/InputSize/OutputSize/FrameCount/FrameDirection),
 * semantica ORIG/PassN/PassPrev/alias, texture LUT, {@code scale_type}
 * source/viewport/absolute, {@code float_framebuffer} e {@code srgb_framebuffer}.
 *
 * <p>Robustezza: {@link #build} lancia in caso di errore di parsing/compilazione; il
 * chiamante ripiega sul passthrough. Nessuna chiamata qui deve mai crashare a runtime.
 */
public final class ShaderChain {

    // Locazioni fisse degli attributi (bindate prima del link su tutti i program).
    private static final int ATTR_VERTEX = 0;
    private static final int ATTR_COLOR = 1;
    private static final int ATTR_TEXCOORD = 2;
    private static final int ATTR_LUTTEXCOORD = 3;

    // MVP ortho [0,1] -> [-1,1] (column-major).
    private static final float[] MVP = {
            2f, 0f, 0f, 0f,
            0f, 2f, 0f, 0f,
            0f, 0f, -1f, 0f,
            -1f, -1f, 0f, 1f
    };

    private static final Pattern PRAGMA_PARAM = Pattern.compile(
            "^\\s*#pragma\\s+parameter\\s+(\\w+)\\s+\"[^\"]*\"\\s+([-+0-9.eE]+)");
    private static final Pattern INCLUDE = Pattern.compile(
            "^\\s*#include\\s+[\"<]([^\">]+)[\">]");
    private static final Pattern VERSION = Pattern.compile("^\\s*#version\\b.*$");

    private final GlslPreset preset;
    private final int[] program;           // program per pass
    private final GlslPreset.PassSpec[] specs;
    private final Map<String, Float> params = new LinkedHashMap<>();
    // I parametri sono letti dal thread GL (bindParams) e scritti dall'EDT (setParam,
    // dal popup dei parametri): protetti da paramLock.
    private final Object paramLock = new Object();

    // Risorse GL allocate al build().
    private int quadVbo;
    private final List<int[]> lutTextures = new ArrayList<>(); // [texId]
    private final List<GlslPreset.TextureSpec> lutSpecs;

    // FBO intermedi (ricreati al variare delle dimensioni). Indice = pass; l'ultimo pass
    // va sul backbuffer e non ha FBO.
    private int[] fbo;
    private int[] fboTex;
    private int[] outW, outH, inW, inH;
    private int builtOrigW = -1, builtOrigH = -1, builtVpW = -1, builtVpH = -1;

    private ShaderChain(GlslPreset preset, int[] program, GlslPreset.PassSpec[] specs,
                        List<GlslPreset.TextureSpec> lutSpecs) {
        this.preset = preset;
        this.program = program;
        this.specs = specs;
        this.lutSpecs = lutSpecs;
    }

    /**
     * Compila un preset in una catena pronta all'uso. Deve essere invocato sul thread GL
     * (contesto attivo). Lancia se il preset non è caricabile/compilabile.
     */
    public static ShaderChain build(Path glslp) throws IOException {
        GlslPreset preset = GlslPreset.load(glslp);
        List<GlslPreset.PassSpec> passList = preset.passes();
        GlslPreset.PassSpec[] specs = passList.toArray(new GlslPreset.PassSpec[0]);
        int[] program = new int[specs.length];

        ShaderChain chain = new ShaderChain(preset, program, specs, preset.textures());

        // Compila i pass e raccoglie i parametri (default dei #pragma parameter).
        for (int i = 0; i < specs.length; i++) {
            String body = loadWithIncludes(specs[i].shader, new HashSet<>());
            chain.collectParams(body);
            program[i] = buildProgram(specs[i].shader, body);
        }
        // Override dei parametri dal preset (chiavi con lo stesso nome del #pragma parameter).
        for (String name : new ArrayList<>(chain.params.keySet())) {
            String v = preset.rawValue(name);
            if (v != null && !v.isEmpty()) {
                try {
                    chain.params.put(name, Float.parseFloat(v.trim()));
                } catch (NumberFormatException ignore) {
                    // valore non numerico: si tiene il default
                }
            }
        }
        // Override dell'utente (file <preset>.params affiancato al preset), se presenti.
        for (Map.Entry<String, Float> e : ShaderParams.loadOverrides(glslp).entrySet()) {
            if (chain.params.containsKey(e.getKey())) {
                chain.params.put(e.getKey(), e.getValue());
            }
        }

        chain.createQuad();
        chain.loadLuts();
        return chain;
    }

    // =====================================================================================
    // Esecuzione
    // =====================================================================================

    /**
     * Esegue la catena: ORIG -> pass0 -> ... -> ultimo pass sul backbuffer (viewport
     * aspect-fit). {@code origTex} è la texture sorgente ad altezza nativa.
     */
    public void render(int origTex, int origW, int origH,
                       int vpX, int vpY, int vpW, int vpH, long frameCount) {
        ensureFramebuffers(origW, origH, vpW, vpH);

        // Attributi del quad (stato globale in profilo compatibility: nessun VAO).
        glBindBuffer(GL_ARRAY_BUFFER, quadVbo);
        glVertexAttribPointer(ATTR_VERTEX, 2, GL_FLOAT, false, 16, 0L);
        glEnableVertexAttribArray(ATTR_VERTEX);
        glVertexAttribPointer(ATTR_TEXCOORD, 2, GL_FLOAT, false, 16, 8L);
        glEnableVertexAttribArray(ATTR_TEXCOORD);
        glVertexAttrib4f(ATTR_COLOR, 1f, 1f, 1f, 1f); // COLOR costante bianco

        int n = specs.length;
        for (int i = 0; i < n; i++) {
            boolean last = (i == n - 1);
            int inputTex = (i == 0) ? origTex : fboTex[i - 1];

            // Filtro/wrap dell'input di questo pass.
            applyFilterWrap(inputTex, specs[i].filterLinear, specs[i].wrapMode);

            if (last) {
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glViewport(vpX, vpY, vpW, vpH);
            } else {
                glBindFramebuffer(GL_FRAMEBUFFER, fbo[i]);
                glViewport(0, 0, outW[i], outH[i]);
            }

            boolean srgb = specs[i].srgbFramebuffer && !last;
            if (srgb) glEnable(GL_FRAMEBUFFER_SRGB);

            int prog = program[i];
            glUseProgram(prog);
            bindStandardUniforms(prog, inW[i], inH[i], outW[i], outH[i], frameCount, specs[i].frameCountMod);
            bindTextures(prog, i, inputTex, inW[i], inH[i], origTex, origW, origH);
            bindParams(prog);

            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

            if (srgb) glDisable(GL_FRAMEBUFFER_SRGB);
        }

        glUseProgram(0);
        glDisableVertexAttribArray(ATTR_VERTEX);
        glDisableVertexAttribArray(ATTR_TEXCOORD);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        for (int u = 0; u < 8; u++) {
            glActiveTexture(GL_TEXTURE0 + u);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
        glActiveTexture(GL_TEXTURE0);
    }

    private void bindStandardUniforms(int prog, int in_w, int in_h, int out_w, int out_h,
                                      long frameCount, int frameCountMod) {
        int mvp = glGetUniformLocation(prog, "MVPMatrix");
        if (mvp >= 0) {
            FloatBuffer fb = BufferUtils.createFloatBuffer(16);
            fb.put(MVP).flip();
            glUniformMatrix4fv(mvp, false, fb);
        }
        setVec2(prog, "TextureSize", in_w, in_h);
        setVec2(prog, "InputSize", in_w, in_h);
        setVec2(prog, "OutputSize", out_w, out_h);
        long fc = frameCountMod > 0 ? (frameCount % frameCountMod) : frameCount;
        setInt(prog, "FrameCount", (int) fc);
        setInt(prog, "FrameDirection", 1);
    }

    private void bindTextures(int prog, int passIndex, int inputTex, int in_w, int in_h,
                              int origTex, int origW, int origH) {
        int unit = 0;
        // Input principale.
        if (bindSampler(prog, unit, inputTex, "Texture")) unit++;
        // ORIG.
        if (bindSampler(prog, unit, origTex, "OrigTexture", "Origtexture")) {
            setVec2Multi(prog, origW, origH, "OrigTextureSize", "OrigInputSize",
                    "Origtexture_size", "Origvideo_size");
            unit++;
        }
        // Pass precedenti (assoluti PassN + alias) e PassPrevD.
        // NB: le shader cg2glsl (crt-royale) dichiarano il sampler alias col suffisso
        // "texture" MINUSCOLO (es. VERTICAL_SCANLINEStexture) e le dimensioni come
        // "<alias>texture_size"/"<alias>video_size"; altre convenzioni usano
        // "Texture"/"TextureSize"/"InputSize". GLSL è case-sensitive, quindi bindiamo
        // tutte le grafie sullo stesso texture-unit/valore.
        for (int p = 0; p < passIndex; p++) {
            int tex = fboTex[p];
            if (bindSampler(prog, unit, tex, "Pass" + p + "Texture", "Pass" + p + "texture")) {
                setVec2Multi(prog, outW[p], outH[p], "Pass" + p + "TextureSize", "Pass" + p + "InputSize",
                        "Pass" + p + "texture_size", "Pass" + p + "video_size");
                unit++;
            }
            String alias = specs[p].alias;
            if (alias != null && !alias.isEmpty()
                    && bindSampler(prog, unit, tex, alias + "Texture", alias + "texture")) {
                setVec2Multi(prog, outW[p], outH[p], alias + "TextureSize", alias + "InputSize",
                        alias + "texture_size", alias + "video_size");
                unit++;
            }
            int d = passIndex - p;
            if (bindSampler(prog, unit, tex, "PassPrev" + d + "Texture", "PassPrev" + d + "texture")) {
                setVec2Multi(prog, outW[p], outH[p], "PassPrev" + d + "TextureSize", "PassPrev" + d + "InputSize",
                        "PassPrev" + d + "texture_size", "PassPrev" + d + "video_size");
                unit++;
            }
        }
        // LUT (sampler col nome della texture).
        for (int l = 0; l < lutSpecs.size(); l++) {
            GlslPreset.TextureSpec ts = lutSpecs.get(l);
            int[] lut = lutTextures.get(l);
            if (bindSampler(prog, unit, lut[0], ts.name)) {
                setVec2Multi(prog, lut[1], lut[2], ts.name + "Size");
                unit++;
            }
        }
    }

    private void bindParams(int prog) {
        synchronized (paramLock) {
            for (Map.Entry<String, Float> e : params.entrySet()) {
                int loc = glGetUniformLocation(prog, e.getKey());
                if (loc >= 0) glUniform1f(loc, e.getValue());
            }
        }
    }

    /**
     * Aggiorna a caldo un parametro (dal popup dei parametri). Ha effetto solo se il
     * parametro esiste nel preset; applicato al frame successivo da {@link #bindParams}.
     */
    public void setParam(String name, float value) {
        synchronized (paramLock) {
            if (params.containsKey(name)) {
                params.put(name, value);
            }
        }
    }

    /**
     * Bind di {@code tex} sul texture-unit {@code unit}, impostando <b>tutti</b> i sampler
     * fra {@code names} che esistono nel program (grafie alternative dello stesso nome).
     * La texture è legata una sola volta; restituisce {@code true} se almeno un nome è
     * stato trovato (nel qual caso il chiamante consuma il texture-unit).
     */
    private boolean bindSampler(int prog, int unit, int tex, String... names) {
        boolean bound = false;
        for (String name : names) {
            int loc = glGetUniformLocation(prog, name);
            if (loc < 0) continue;
            if (!bound) {
                glActiveTexture(GL_TEXTURE0 + unit);
                glBindTexture(GL_TEXTURE_2D, tex);
                bound = true;
            }
            glUniform1i(loc, unit);
        }
        return bound;
    }

    private static void setVec2(int prog, String name, float x, float y) {
        int loc = glGetUniformLocation(prog, name);
        if (loc >= 0) glUniform2f(loc, x, y);
    }

    /** Come {@link #setVec2} ma prova più grafie dello stesso nome (tutte impostate se presenti). */
    private static void setVec2Multi(int prog, float x, float y, String... names) {
        for (String name : names) setVec2(prog, name, x, y);
    }

    private static void setInt(int prog, String name, int v) {
        int loc = glGetUniformLocation(prog, name);
        if (loc >= 0) glUniform1i(loc, v);
    }

    private static void applyFilterWrap(int tex, boolean linear, String wrapMode) {
        glBindTexture(GL_TEXTURE_2D, tex);
        int filter = linear ? GL_LINEAR : GL_NEAREST;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        int wrap = wrapModeGl(wrapMode);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
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

    // =====================================================================================
    // Allocazione FBO
    // =====================================================================================

    private void ensureFramebuffers(int origW, int origH, int vpW, int vpH) {
        if (origW == builtOrigW && origH == builtOrigH && vpW == builtVpW && vpH == builtVpH
                && fbo != null) {
            return;
        }
        deleteFramebuffers();

        int n = specs.length;
        fbo = new int[n];
        fboTex = new int[n];
        outW = new int[n];
        outH = new int[n];
        inW = new int[n];
        inH = new int[n];

        int curW = origW, curH = origH;
        for (int i = 0; i < n; i++) {
            inW[i] = curW;
            inH[i] = curH;
            boolean last = (i == n - 1);
            if (last) {
                outW[i] = vpW;
                outH[i] = vpH;
                // niente FBO: va sul backbuffer
            } else {
                outW[i] = scaled(specs[i].scaleTypeX, specs[i].scaleX, curW, vpW);
                outH[i] = scaled(specs[i].scaleTypeY, specs[i].scaleY, curH, vpH);
                allocFbo(i, outW[i], outH[i], specs[i].floatFramebuffer, specs[i].srgbFramebuffer);
            }
            curW = outW[i];
            curH = outH[i];
        }

        builtOrigW = origW;
        builtOrigH = origH;
        builtVpW = vpW;
        builtVpH = vpH;
    }

    private void allocFbo(int i, int w, int h, boolean floatFb, boolean srgbFb) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        int internal = floatFb ? GL_RGBA16F : (srgbFb ? GL_SRGB8_ALPHA8 : GL_RGBA8);
        int type = floatFb ? GL_FLOAT : GL_UNSIGNED_BYTE;
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, internal, w, h, 0, GL_RGBA, type, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);

        int f = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, f);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        fbo[i] = f;
        fboTex[i] = tex;
    }

    private static int scaled(String type, float scale, int inSize, int vpSize) {
        return switch (type == null ? "source" : type.trim()) {
            case "viewport" -> Math.max(1, Math.round(vpSize * scale));
            case "absolute" -> Math.max(1, Math.round(scale));
            default -> Math.max(1, Math.round(inSize * scale)); // source
        };
    }

    // =====================================================================================
    // Compilazione
    // =====================================================================================

    private void collectParams(String body) {
        for (String line : body.split("\n")) {
            Matcher m = PRAGMA_PARAM.matcher(line);
            if (m.find()) {
                params.putIfAbsent(m.group(1), Float.parseFloat(m.group(2)));
            }
        }
    }

    private static int buildProgram(Path shaderFile, String body) throws IOException {
        String[] vf = splitVersion(body);
        String versionLine = vf[0];
        String rest = vf[1];
        String defsV = "#define VERTEX\n#define PARAMETER_UNIFORM\n";
        String defsF = "#define FRAGMENT\n#define PARAMETER_UNIFORM\n";
        String vsrc = versionLine + defsV + rest;
        String fsrc = versionLine + defsF + rest;

        int vs = compileShader(GL_VERTEX_SHADER, vsrc, shaderFile + " [VERTEX]");
        int fs;
        try {
            fs = compileShader(GL_FRAGMENT_SHADER, fsrc, shaderFile + " [FRAGMENT]");
        } catch (IOException e) {
            glDeleteShader(vs);
            throw e;
        }

        int prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glBindAttribLocation(prog, ATTR_VERTEX, "VertexCoord");
        glBindAttribLocation(prog, ATTR_COLOR, "COLOR");
        glBindAttribLocation(prog, ATTR_TEXCOORD, "TexCoord");
        glBindAttribLocation(prog, ATTR_LUTTEXCOORD, "LUTTexCoord");
        glLinkProgram(prog);
        glDeleteShader(vs);
        glDeleteShader(fs);
        if (glGetProgrami(prog, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(prog);
            glDeleteProgram(prog);
            throw new IOException("Link fallito (" + shaderFile + "): " + log);
        }
        return prog;
    }

    private static int compileShader(int type, String source, String label) throws IOException {
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

    /** Estrae la riga {@code #version} (portata in cima) e restituisce {@code [versionLine, resto]}. */
    private static String[] splitVersion(String body) {
        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (VERSION.matcher(lines[i]).matches()) {
                StringBuilder rest = new StringBuilder();
                for (int j = 0; j < lines.length; j++) {
                    if (j == i) continue;
                    rest.append(lines[j]).append('\n');
                }
                return new String[]{lines[i].trim() + "\n", rest.toString()};
            }
        }
        return new String[]{"", body};
    }

    /** Legge un {@code .glsl} risolvendo ricorsivamente gli {@code #include "..."}. */
    static String loadWithIncludes(Path file, Set<Path> visited) throws IOException {
        Path abs = file.toAbsolutePath().normalize();
        if (!visited.add(abs)) {
            return ""; // già incluso: evita cicli
        }
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(abs, StandardCharsets.UTF_8)) {
            Matcher m = INCLUDE.matcher(line);
            if (m.find()) {
                Path inc = abs.getParent().resolve(m.group(1).replace('\\', '/')).normalize();
                sb.append(loadWithIncludes(inc, visited)).append('\n');
            } else {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    // =====================================================================================
    // Quad + LUT
    // =====================================================================================

    private void createQuad() {
        // pos.xy in [0,1], tex.xy in [0,1]; TRIANGLE_STRIP.
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

    private void loadLuts() {
        for (GlslPreset.TextureSpec ts : lutSpecs) {
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
            lutTextures.add(new int[]{tex, w, h});
        }
    }

    private int uploadLut(BufferedImage img, GlslPreset.TextureSpec ts) {
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
        int filter = ts.linear ? GL_LINEAR : GL_NEAREST;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, ts.mipmap
                ? (ts.linear ? GL_LINEAR_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_NEAREST) : filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        int wrap = wrapModeGl(ts.wrapMode);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
        if (ts.mipmap) glGenerateMipmap(GL_TEXTURE_2D);
        return tex;
    }

    // =====================================================================================
    // Cleanup
    // =====================================================================================

    public void dispose() {
        deleteFramebuffers();
        for (int p : program) {
            if (p != 0) glDeleteProgram(p);
        }
        for (int[] t : lutTextures) {
            if (t[0] != 0) glDeleteTextures(t[0]);
        }
        lutTextures.clear();
        if (quadVbo != 0) {
            glDeleteBuffers(quadVbo);
            quadVbo = 0;
        }
    }

    private void deleteFramebuffers() {
        if (fbo != null) {
            for (int f : fbo) if (f != 0) glDeleteFramebuffers(f);
        }
        if (fboTex != null) {
            for (int t : fboTex) if (t != 0) glDeleteTextures(t);
        }
        fbo = null;
        fboTex = null;
        builtOrigW = builtOrigH = builtVpW = builtVpH = -1;
    }
}
