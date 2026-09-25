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

package io.github.zolfrik.amija.display;

import io.github.zolfrik.amija.common.AppHome;
import io.github.zolfrik.amija.custom.FloppyDrive;
import io.github.zolfrik.amija.display.render.RendererType;
import io.github.zolfrik.amija.display.render.gl.GlDisplaySurface;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.ObjIntConsumer;

/**
 * View sul raster raw prodotto da VirtualCRT.
 *
 * Il JFrame ha dimensioni fisse (frameWidth x frameHeight).
 * L'area DIW (Display Window) dell'Amiga è sempre centrata nel JFrame.
 * In modalità lores ogni pixel raw viene duplicato orizzontalmente.
 * In modalità non-interlacciata il line doubling è sempre attivo.
 * L'immagine non viene mai croppata: la finestra fa da viewport naturale.
 */
public class AmigaDisplay {

    private static final long DUMP_INTERVAL_MS = 5000;

    // Imposta a true per disegnare il rettangolo bianco di debug dell'area visibile DIW.
    private static final boolean DEBUG_VISIBLE_AREA = false;
    private static final int DEBUG_COLOR = 0xFFFFFF;

    // Interruttore per i LED di stato (power/drive) in sovraimpressione nell'overscan.
    // A false la feature è completamente disattivata (nessun disegno, costo zero).
    // Configurabile da amiga.properties (leds.visible) via setStatusLedsEnabled.
    private volatile boolean statusLedsEnabled = true;

    // Overlay diagnostici opzionali, OFF di default (nessun disegno né calcolo quando off):
    //  - fpsOverlayEnabled: FPS reali di presentazione (basso a sinistra), misurano la
    //    velocità effettiva dell'emulatore sull'host.
    //  - frameCounterOverlayEnabled: contatore dei fotogrammi emulati (basso al centro),
    //    indipendente dalla velocità di emulazione, da usare come riferimento temporale
    //    riproducibile ("a quale frame avviene l'evento").
    // Configurabili solo da amiga.properties (NON presenti nel popup F12).
    private volatile boolean fpsOverlayEnabled = false;
    private volatile boolean frameCounterOverlayEnabled = false;

    // Stato del calcolo FPS (aggiornato solo quando l'overlay FPS è attivo).
    private int  fpsFrameAccum;
    private long fpsWindowStartNs;
    private volatile int fpsValue;

    private boolean dumpEnabled = false;
    private long lastDumpTime = 0;

    private int width;
    private int height;
    private BufferedImage image;
    private int[] pixels;
    private final Object imageLock = new Object();
    private FrameInfo lastFrameInfo;
    private final JFrame frame;
    private final DisplayPanel label;

    // Backend di rendering. In SOFTWARE il display compone via Java2D sul {@code label}
    // (comportamento storico, invariato). In OPENGL il framebuffer RAW è presentato dalla
    // GPU tramite {@code glSurface} (LWJGL), ospitata nella stessa finestra: {@code label}
    // è null e renderFrame() delega alla mailbox GL. Tutto il codice GL è confinato in
    // {@code display.render.gl}; il core non ne è a conoscenza.
    private final RendererType renderer;
    private final GlDisplaySurface glSurface;   // null: software o headless
    // Componente a cui il chiamante aggancia i listener di input: il frame in software
    // (comportamento storico), il canvas GL (heavyweight) in OpenGL.
    private final Component inputComponent;
    // Componente bersaglio del drag-and-drop floppy: il label in software, il canvas GL
    // in OpenGL (in GL l'overlay del drag è assente, ma il drop resta funzionale).
    private final Component dndComponent;

    // Overlay (LED/FPS/contatore) per il backend OpenGL: composto qui con Java2D in una
    // immagine ARGB e consegnato al backend GL, che lo fonde in alpha-blend sull'immagine.
    // Riusa lo STESSO disegno del path software (drawStatusLeds/drawCounters).
    private BufferedImage glOverlayImage;
    private int[] glOverlayPixels;
    private volatile BufferedImage pendingLiveCanvas;
    private final Runnable repaintTask = new Runnable() {
        @Override
        public void run() {
            publishPendingLiveCanvas();
        }
    };

    // Dimensioni fisse del contenuto del JFrame.
    private final int frameWidth;
    private final int frameHeight;

    // Prima riga raster visibile (fine VBlank). L'immagine è ancorata in alto a
    // questa riga, senza centratura verticale, come WinUAE (VBLANK_ENDLINE_PAL /
    // minfirstline = 26). Coincide con VirtualCRT.REGRESSION_CROP_Y (qui ridichiarata
    // per non accoppiare il package display a common).
    private static final int DISPLAY_TOP_LINE = 26;
    // Taglia di COMPOSIZIONE: quella a cui Java2D compone immagine + LED + contatori,
    // sempre pari al frame nativo. Non cresce mai con l'integer scaling — l'ingrandimento
    // è l'ultimo blit e basta (vedi videoScale).
    private final int windowWidth;
    private final int windowHeight;
    private final AmigaWindowMode windowMode;
    /** Fattore di integer scaling della finestra emulata ({@code video.integerScaling}), ≥ 1. */
    private final int videoScale;
    private GraphicsDevice fullScreenDevice;
    /** Monitor scelto in configurazione ({@code video.monitor}); null in headless. */
    private final GraphicsDevice screenDevice;

    // Buffer persistente per interlace: accumula i due campi alternati.
    private int[] interlaceBuffer;
    private int   interlaceBufferWidth = -1;

    // STEP 10 (PERFORMANCE_REPORT_FINAL): double buffering. renderFrame() allocava
    // un nuovo int[] e un nuovo BufferedImage ad OGNI frame (50-60/s). Si mantiene
    // un back buffer persistente in cui comporre il frame; al termine si scambia
    // con la front (image/pixels). Riallocato solo se cambiano le dimensioni
    // (di fatto mai, dato che VirtualCRT usa RAW_WIDTH/RAW_HEIGHT costanti).
    // È sicuro perché buildFrameCanvas() copia sincronamente l'immagine su un canvas
    // separato per l'EDT, quindi il back riusato non viene mai letto concorrentemente.
    private BufferedImage backImage;
    private int[]         backPixels;

    // Drive DF0-DF3 (indice 0..3) bersaglio del drag-and-drop. Impostati via setDrives().
    // La disponibilità di ciascuno si legge a runtime da FloppyDrive.isConnected().
    private volatile FloppyDrive[] drives = new FloppyDrive[4];

    // Stato dell'overlay di drop: attivo durante il trascinamento di un file sul frame.
    private volatile boolean dragOverlayActive = false;
    // Quadrante attualmente sotto il cursore (0=NO/DF0, 1=NE/DF1, 2=SO/DF2, 3=SE/DF3), -1 = nessuno.
    private volatile int hoverQuadrant = -1;
    // Snapshot in scala di grigi del frame, congelato all'avvio del drag come sfondo dell'overlay.
    private BufferedImage dragBgSnapshot;
    // Font base dell'overlay (Segoe UI su Windows, fallback automatico altrove).
    private final Font overlayFont = new Font("Segoe UI", Font.PLAIN, 12);
    private final Font ledLabelFont = overlayFont.deriveFont(Font.BOLD, 11f);

    // Foreground dell'overlay pre-renderizzato (velo scuro + griglia + 4 quadranti).
    // I drive non cambiano a runtime, quindi l'unica variabile è il quadrante in hover:
    // si pre-rende un layer ARGB per ciascuno stato, indicizzato da (hoverQuadrant+1),
    // così ogni frame del drag si riduce a due drawImage (sfondo grigio + foreground).
    private volatile BufferedImage[] overlayFgCache;

    // Sorgenti di stato dei LED (lette una volta per frame nel path di compose, costo
    // trascurabile). powerLedBright: true = LED alimentazione luminoso (filtro audio on).
    // Lo stato dei floppy è letto direttamente dai FloppyDrive (vedi setDrives), quello
    // degli hard disk dai relativi HdController (vedi setHardDisks): nessun polling extra.
    private volatile BooleanSupplier powerLedBright;

    // Sorgenti di attività dei LED "HD", una per unità (in ordine, da sinistra a destra:
    // WHDLoad, master, slave). Ogni supplier è true quando quel disco sta leggendo/scrivendo.
    // Vuoto/null = nessun hard disk → il LED HD e la sua etichetta non vengono disegnati.
    private volatile BooleanSupplier[] hdLedSources;

    // Spia del gioco a distanza: visibile solo durante una sessione, accesa quando l'altro
    // capo c'è davvero. Entrambe null = funzione spenta, e la riga non viene disegnata.
    private volatile BooleanSupplier remotePlayLedVisible;
    private volatile BooleanSupplier remotePlayLedActive;

    // Notifica (percorso assoluto, indice drive 0..3) quando un disco viene inserito via
    // drag-and-drop, così il chiamante puo' aggiornare la configurazione (vedi F12).
    private volatile ObjIntConsumer<String> diskDropListener;

    /** Registra il listener notificato quando un disco viene droppato in un drive. */
    public void setDiskDropListener(ObjIntConsumer<String> listener) {
        this.diskDropListener = listener;
    }

    /** Collega la sorgente di stato del LED di alimentazione (vedi statusLedsEnabled). */
    public void setStatusLedSource(BooleanSupplier powerLedBright) {
        this.powerLedBright = powerLedBright;
    }

    /**
     * Collega le sorgenti di attività usate dal LED "HD", una per unità hard disk
     * (es. WHDLoad, master, slave). L'ordine è significativo: la prima è disegnata a
     * sinistra. Ogni supplier deve essere {@code true} quando quel disco sta leggendo o
     * scrivendo. Passare {@code null}/vuoto se non c'è alcun hard disk: in tal caso il LED
     * HD e la sua etichetta non vengono disegnati.
     */
    public void setHdLedSources(BooleanSupplier... sources) {
        this.hdLedSources = sources;
    }

    /**
     * Collega la spia del gioco a distanza (docs/remote-play-plan.md §9).
     *
     * <p>Risponde alla domanda "sono ancora collegato?" senza aprire F12 — che è
     * l'unico modo attuale di controllare, ed è <b>distruttivo</b>: aprire il popup mette
     * in pausa la macchina, e su un host in pausa lo schermo dell'ospite si ferma. Una spia
     * costa un rettangolo per frame e toglie di mezzo il problema.
     *
     * @param visible {@code true} quando c'è una sessione (si ospita o si è ospiti):
     *                altrimenti la riga non viene disegnata affatto
     * @param active  {@code true} quando l'altro capo c'è davvero; a {@code false} la spia
     *                resta accesa fioca, che è la differenza fra "in ascolto" e "collegato"
     */
    public void setRemotePlayLedSource(BooleanSupplier visible, BooleanSupplier active) {
        this.remotePlayLedVisible = visible;
        this.remotePlayLedActive = active;
    }

    /** Mostra o nasconde il pannello dei LED power/drive (a false: nessun disegno). */
    public void setStatusLedsEnabled(boolean enabled) {
        this.statusLedsEnabled = enabled;
    }

    /** Mostra/nasconde l'overlay degli FPS reali in basso a sinistra (a false: costo zero). */
    public void setFpsOverlayEnabled(boolean enabled) {
        this.fpsOverlayEnabled = enabled;
    }

    /** Mostra/nasconde l'overlay del contatore fotogrammi emulati in basso al centro. */
    public void setFrameCounterOverlayEnabled(boolean enabled) {
        this.frameCounterOverlayEnabled = enabled;
    }

    /** Imposta i quattro drive (DF0..DF3) bersaglio del drag-and-drop. */
    public void setDrives(FloppyDrive df0, FloppyDrive df1, FloppyDrive df2, FloppyDrive df3) {
        this.drives = new FloppyDrive[]{df0, df1, df2, df3};
        prebuildOverlayCacheAsync();
    }

    /** Comodità: imposta il solo DF0 lasciando invariati gli altri drive. */
    public void setDf0(FloppyDrive df0) {
        FloppyDrive[] d = this.drives;
        this.drives = new FloppyDrive[]{df0, d[1], d[2], d[3]};
        prebuildOverlayCacheAsync();
    }

    public AmigaDisplay(int frameWidth, int frameHeight) {
        this(frameWidth, frameHeight, AmigaWindowMode.WINDOWED, RendererType.SOFTWARE);
    }

    public AmigaDisplay(int frameWidth, int frameHeight, AmigaWindowMode windowMode) {
        this(frameWidth, frameHeight, windowMode, RendererType.SOFTWARE, 1);
    }

    public AmigaDisplay(int frameWidth, int frameHeight, AmigaWindowMode windowMode,
                        RendererType renderer) {
        this(frameWidth, frameHeight, windowMode, renderer, 1);
    }

    // Integer scaling: lo fa AmiJa, di un fattore INTERO, uguale per i due renderer.
    // Java2D non scala nulla (Amija spegne sun.java2d.uiScale), così 1 pixel Java = 1
    // pixel fisico e la finestra riceve pixel esatti.
    //  - SOFTWARE: la finestra è creata a frame×videoScale e DisplayPanel disegna il
    //    canvas composto ingrandito ×videoScale con NEAREST_NEIGHBOR.
    //  - OPENGL: idem per la finestra, ma la magnificazione la fa la GPU e lo shader
    //    renderizza a quella risoluzione (decisivo per shader ad alta risoluzione come
    //    crt-royale).
    // In fullscreen/borderless la finestra è grande quanto lo schermo e il frame
    // ingrandito ci viene centrato dentro, con bordi neri.
    public AmigaDisplay(int frameWidth, int frameHeight, AmigaWindowMode windowMode,
                        RendererType renderer, int videoScale) {
        this(frameWidth, frameHeight, windowMode, renderer, videoScale, 0);
    }

    public AmigaDisplay(int frameWidth, int frameHeight, AmigaWindowMode windowMode,
                        RendererType renderer, int videoScale, int monitorIndex) {
        this.screenDevice = screenDeviceFor(monitorIndex);
        this.frameWidth  = frameWidth;
        this.frameHeight = frameHeight;
        this.windowWidth = frameWidth;
        this.windowHeight = frameHeight;
        this.windowMode = windowMode == null ? AmigaWindowMode.WINDOWED : windowMode;
        this.renderer = renderer == null ? RendererType.SOFTWARE : renderer;
        this.videoScale = Math.max(1, videoScale);
        Dimension contentSize = contentSizeFor(this.windowMode,
                windowWidth * this.videoScale, windowHeight * this.videoScale, screenDevice);
        this.width  = frameWidth;
        this.height = frameHeight;
        this.image  = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_RGB);
        this.pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

        if (GraphicsEnvironment.isHeadless()) {
            this.frame = null;
            this.label = null;
            this.glSurface = null;
            this.inputComponent = null;
            this.dndComponent = null;
            return;
        }

        JFrame f = new JFrame(io.github.zolfrik.amija.i18n.Messages.t("app.title")
                + " - " + io.github.zolfrik.amija.common.Version.version());
        f.setUndecorated(this.windowMode != AmigaWindowMode.WINDOWED);
        AppIcons.apply(f);
        AppIcons.applyToTaskbar();

        GlDisplaySurface surface = (this.renderer == RendererType.OPENGL)
                ? createGlSurface(frameWidth, frameHeight) : null;
        DisplayPanel lbl = null;
        Component input;
        Component dnd;

        if (surface != null) {
            // OpenGL: il canvas GL heavyweight sostituisce il pannello Swing. L'input e il
            // drag-and-drop vanno agganciati al canvas (gli eventi non risalgono al frame).
            Component canvas = surface.getComponent();
            canvas.setBackground(Color.BLACK);
            JPanel host = new JPanel(new BorderLayout());
            host.setOpaque(true);
            host.setBackground(Color.BLACK);
            host.setPreferredSize(contentSize);
            host.add(canvas, BorderLayout.CENTER);
            f.setContentPane(host);
            input = canvas;
            dnd = canvas;
        } else {
            // Software (o fallback se il backend GL non è disponibile): path storico.
            lbl = new DisplayPanel(this.videoScale);
            lbl.setPreferredSize(contentSize);
            lbl.setOpaque(true);
            lbl.setBackground(Color.BLACK);
            f.setContentPane(lbl);
            input = f;   // i listener restano sul frame (comportamento invariato)
            dnd = lbl;
        }

        f.pack();
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setResizable(false);

        this.frame = f;
        this.label = lbl;
        this.glSurface = surface;
        this.inputComponent = input;
        this.dndComponent = dnd;

        applyWindowMode();     // rende visibile la finestra
        setupDragAndDrop();
        if (surface != null) {
            surface.start();   // avvia il thread GL dopo che la finestra è visibile
        }
    }

    /** Istanzia il backend GL, ripiegando su software (null) se non disponibile. */
    private static GlDisplaySurface createGlSurface(int frameWidth, int frameHeight) {
        try {
            return new GlDisplaySurface(frameWidth, frameHeight);
        } catch (Throwable t) {
            System.err.println("[GL] Backend OpenGL non disponibile, uso il rendering "
                    + "software: " + t);
            return null;
        }
    }

    /** Componente a cui agganciare i listener di input (canvas GL o frame software). */
    public Component getInputComponent() {
        return inputComponent;
    }

    /**
     * Fattore di ingrandimento della finestra emulata, ≥ 1. Serve a chi lavora in pixel
     * dello schermo e deve tornare a pixel Amiga — il mouse su tutti.
     */
    public int getVideoScale() {
        return videoScale;
    }

    /**
     * Seleziona (a caldo) il preset shader RetroArch, se il backend è OpenGL. No-op in
     * software/headless. {@code preset} = percorso del {@code .glslp}, o {@code null} per
     * nessuno shader (passthrough).
     */
    public void setShaderPreset(java.nio.file.Path preset) {
        if (glSurface != null) {
            glSurface.setShaderPreset(preset);
        }
    }

    /** Aggiorna a caldo un parametro dello shader OpenGL attivo. No-op in software. */
    public void setShaderParam(String name, float value) {
        if (glSurface != null) {
            glSurface.setShaderParam(name, value);
        }
    }

    private static Dimension contentSizeFor(AmigaWindowMode mode, int windowWidth, int windowHeight,
                                            GraphicsDevice device) {
        if (mode == AmigaWindowMode.WINDOWED || GraphicsEnvironment.isHeadless() || device == null) {
            return new Dimension(windowWidth, windowHeight);
        }
        Rectangle bounds = device.getDefaultConfiguration().getBounds();
        return new Dimension(bounds.width, bounds.height);
    }

    /**
     * Monitor su cui aprire la finestra, per indice di configurazione
     * ({@code video.monitor}): 0 = schermo principale, 1..n = altri monitor collegati
     * nell'ordine restituito dal toolkit. Indice fuori range (o headless) -&gt; principale
     * (o {@code null} in headless).
     */
    public static GraphicsDevice screenDeviceFor(int monitorIndex) {
        if (GraphicsEnvironment.isHeadless()) return null;
        GraphicsDevice[] devices = screenDevices();
        if (devices.length == 0) return null;
        int i = (monitorIndex >= 0 && monitorIndex < devices.length) ? monitorIndex : 0;
        return devices[i];
    }

    /**
     * Monitor collegati con lo schermo principale forzato in posizione 0 (l'ordine
     * restituito da AWT non garantisce che il primo sia il principale).
     */
    public static GraphicsDevice[] screenDevices() {
        if (GraphicsEnvironment.isHeadless()) return new GraphicsDevice[0];
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] all = ge.getScreenDevices();
        if (all.length == 0) return all;
        GraphicsDevice def = ge.getDefaultScreenDevice();
        GraphicsDevice[] out = new GraphicsDevice[all.length];
        int n = 0;
        out[n++] = def;
        for (GraphicsDevice d : all) {
            if (d != def && n < out.length) out[n++] = d;
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /**
     * Il pannello su cui vive la finestra emulata nel path software. È qui — e solo qui —
     * che avviene l'ingrandimento: il canvas composto arriva alla risoluzione del frame e
     * viene disegnato ×{@code scale} con NEAREST_NEIGHBOR, centrato, col resto a nero.
     * Nessun buffer intermedio cresce con lo scaling.
     */
    private static final class DisplayPanel extends JComponent {
        private volatile BufferedImage image;
        private final int scale;

        DisplayPanel(int scale) {
            this.scale = Math.max(1, scale);
        }

        void setDisplayImage(BufferedImage image) {
            this.image = image;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            paintScaled(g);
        }

        /**
         * Disegna il canvas corrente ingrandito. Estratto da {@link #paintComponent} perché
         * lo usa anche l'active rendering dell'overlay di drag, che non passa dal repaint.
         */
        void paintScaled(Graphics g) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            BufferedImage current = image;
            if (current == null) {
                return;
            }
            int dw = current.getWidth() * scale;
            int dh = current.getHeight() * scale;
            // Orizzontale: centrato. Se l'immagine è più larga della finestra il taglio è
            // simmetrico, coerente con la centratura sulla DIW fatta in composizione.
            int dx = (getWidth() - dw) / 2;
            // Verticale: centrato finché ci sta, ancorato in ALTO quando non ci sta.
            // Centrare un'immagine più alta della finestra taglierebbe le prime righe, e
            // quelle non si toccano: la composizione ancora apposta il quadro alla prima
            // riga visibile (vedi composeFrameCanvas) perché il contenuto in alto non
            // venga mai spinto giù né tagliato. Succede per davvero: una finestra decorata
            // non può superare lo schermo — Windows la limita e nemmeno setSize la forza —
            // quindi PAL overscan a 2x (1152 px) su un pannello da 1080 finisce qui.
            int dy = Math.max(0, (getHeight() - dh) / 2);
            if (scale == 1) {
                g.drawImage(current, dx, dy, null); // nessuna trasformazione: path storico
                return;
            }
            Graphics2D g2 = (Graphics2D) g;
            Object old = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            // Integer scaling: nearest neighbour, altrimenti Java2D interpolerebbe e i
            // pixel Amiga sfocherebbero — che è esattamente ciò che l'opzione evita.
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(current, dx, dy, dx + dw, dy + dh,
                    0, 0, current.getWidth(), current.getHeight(), null);
            if (old != null) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
            }
        }
    }

    /**
     * Centra la finestra sul monitor configurato ({@code setLocationRelativeTo(null)}
     * userebbe sempre lo schermo principale).
     */
    private void centerOnScreenDevice() {
        if (screenDevice == null) {
            frame.setLocationRelativeTo(null);
            return;
        }
        Rectangle b = screenDevice.getDefaultConfiguration().getBounds();
        frame.setLocation(b.x + (b.width - frame.getWidth()) / 2,
                          b.y + (b.height - frame.getHeight()) / 2);
    }

    private void applyWindowMode() {
        if (frame == null) return;
        if (windowMode == AmigaWindowMode.WINDOWED) {
            centerOnScreenDevice();
            frame.setVisible(true);
            return;
        }

        fullScreenDevice = screenDevice != null ? screenDevice
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        Rectangle bounds = fullScreenDevice.getDefaultConfiguration().getBounds();
        frame.setLocation(bounds.x, bounds.y);
        frame.setBackground(Color.BLACK);
        frame.getContentPane().setBackground(Color.BLACK);

        if (windowMode == AmigaWindowMode.FULLSCREEN && fullScreenDevice.isFullScreenSupported()) {
            frame.setVisible(true);
            fullScreenDevice.setFullScreenWindow(frame);
        } else {
            frame.setBounds(bounds);
            frame.setVisible(true);
        }
    }

    /**
     * Sospende il fullscreen esclusivo prima di aprire dialog Swing modali.
     * Su Windows un JDialog sopra una finestra esclusiva puo' far tornare il desktop
     * in primo piano; lasciamo quindi il frame come borderless fullscreen normale.
     */
    public boolean suspendExclusiveFullScreen() {
        if (frame == null || windowMode != AmigaWindowMode.FULLSCREEN
                || fullScreenDevice == null
                || fullScreenDevice.getFullScreenWindow() != frame) {
            return false;
        }
        Rectangle bounds = fullScreenDevice.getDefaultConfiguration().getBounds();
        fullScreenDevice.setFullScreenWindow(null);
        frame.setBounds(bounds);
        frame.toFront();
        frame.requestFocus();
        return true;
    }

    /** Ripristina il fullscreen esclusivo dopo la chiusura del popup F12. */
    public void resumeExclusiveFullScreen() {
        if (frame == null || windowMode != AmigaWindowMode.FULLSCREEN
                || fullScreenDevice == null
                || !fullScreenDevice.isFullScreenSupported()) {
            return;
        }
        frame.toFront();
        fullScreenDevice.setFullScreenWindow(frame);
        frame.requestFocus();
    }

    /** Rilascia eventuale fullscreen esclusivo e chiude la finestra. */
    public void dispose() {
        if (glSurface != null) {
            glSurface.dispose(); // ferma il thread GL prima di distruggere la finestra
        }
        if (fullScreenDevice != null && fullScreenDevice.getFullScreenWindow() == frame) {
            fullScreenDevice.setFullScreenWindow(null);
        }
        if (frame != null) {
            frame.dispose();
        }
    }

    public void setPixel(int x, int y, int rgb) {
        synchronized (imageLock) {
            if (x >= 0 && x < width && y >= 0 && y < height) {
                pixels[y * width + x] = rgb;
            }
        }
    }

    /**
     * Riceve il framebuffer raw da VirtualCRT e produce l'immagine da visualizzare.
     *
     * <p>Pixel doubling: in modalità lores (non-hires) ogni pixel lores occupa 2 colonne
     * raw in Denise; la colonna destra viene scritta identica alla sinistra, così
     * displayWidth == rawWidth in tutti i casi (912 pixel).
     *
     * <p>Line doubling: in modalità non-interlacciata ogni riga raw viene scritta due
     * volte consecutive, portando displayHeight a rawHeight * 2 (624 righe).
     *
     * <p>Interlace: le 312 righe del campo corrente vengono interleave a quelle del
     * campo precedente nel buffer persistente {@code interlaceBuffer} (righe pari/dispari).
     */
    public void renderFrame(int[] rawBuffer, int rawWidth, int rawHeight, FrameInfo frameInfo) {
        if (rawBuffer == null) {
            return;
        }
        if (fpsOverlayEnabled) {
            updateFps();
        }
        this.lastFrameInfo = frameInfo;

        // OpenGL: consegna il RAW (e l'overlay LED/FPS/contatore) alla mailbox GPU e
        // ritorna. La composizione dell'immagine (crop, line doubling, shader) è sulla GPU;
        // qui si disegna solo l'overlay in una piccola immagine ARGB (come nel software).
        if (glSurface != null) {
            glSurface.present(rawBuffer, rawWidth, rawHeight, frameInfo, renderGlOverlay());
            return;
        }

        boolean hires      = frameInfo != null && frameInfo.hires();
        boolean interlaced = frameInfo != null && frameInfo.interlaced();
        boolean evenField  = frameInfo == null || !interlaced || frameInfo.evenField();

        // Pixel doubling è automatico in lores; line doubling automatico se non interlacciato.
        // In entrambi i casi displayWidth = rawWidth e displayHeight = rawHeight * 2.
        int displayWidth  = rawWidth;
        int displayHeight = rawHeight * 2;

        if (interlaced) {
            if (interlaceBuffer == null || interlaceBufferWidth != displayWidth) {
                interlaceBuffer      = new int[displayWidth * rawHeight * 2];
                interlaceBufferWidth = displayWidth;
                Arrays.fill(interlaceBuffer, 0);
            }
        } else {
            interlaceBuffer      = null;
            interlaceBufferWidth = -1;
        }

        // STEP 10: back buffer persistente, riallocato solo al variare delle dimensioni.
        if (backImage == null || backImage.getWidth() != displayWidth
                              || backImage.getHeight() != displayHeight) {
            backImage  = new BufferedImage(displayWidth, displayHeight, BufferedImage.TYPE_INT_RGB);
            backPixels = ((DataBufferInt) backImage.getRaster().getDataBuffer()).getData();
        }
        int[] outPixels = backPixels;

        // Per interlace si compone nel buffer persistente (accumula i campi) e poi
        // si copia nel back; in modalità normale si scrive direttamente nel back.
        int[] target = interlaced ? interlaceBuffer : outPixels;

        for (int srcY = 0; srcY < rawHeight; srcY++) {
            if (interlaced) {
                copyRow(rawBuffer, rawWidth, srcY, target, displayWidth,
                        srcY * 2 + (evenField ? 0 : 1), hires);
            } else {
                // Line doubling: ogni riga raw scritto due volte consecutive
                int dstY = srcY * 2;
                copyRow(rawBuffer, rawWidth, srcY, target, displayWidth, dstY, hires);
                System.arraycopy(target, dstY * displayWidth,
                        target, (dstY + 1) * displayWidth, displayWidth);
            }
        }

        if (interlaced) {
            System.arraycopy(interlaceBuffer, 0, outPixels, 0, outPixels.length);
        }

        if (DEBUG_VISIBLE_AREA && frameInfo != null) {
            // vScale è sempre 2: interlace o line doubling coprono entrambi i casi.
            drawVisibleAreaRect(outPixels, displayWidth, displayHeight,
                    frameInfo.visibleX(),
                    frameInfo.visibleY()      * 2,
                    frameInfo.visibleWidth(),
                    frameInfo.visibleHeight() * 2);
        }

        synchronized (imageLock) {
            // Swap front/back: il frame appena composto (back) diventa quello mostrato;
            // la vecchia front viene riciclata come prossimo back (zero allocazioni a
            // regime, una volta che entrambi i buffer hanno le dimensioni stabili).
            BufferedImage shown    = backImage;
            int[]         shownPix = backPixels;
            backImage  = image;
            backPixels = pixels;
            image  = shown;
            pixels = shownPix;
            width  = displayWidth;
            height = displayHeight;
        }
        refresh();
    }

    /**
     * Copia una riga dal buffer raw al buffer di destinazione.
     * In modalità hires copia direttamente. In modalità lores duplica ogni pixel
     * (pixel doubling): il pixel lores all'indice x occupa raw[x*2] e viene
     * scritto in dst[x*2] e dst[x*2+1].
     */
    private static void copyRow(int[] src, int srcW, int srcY,
                                int[] dst, int dstW, int dstY, boolean hires) {
        int srcOff = srcY * srcW;
        int dstOff = dstY * dstW;
        if (hires) {
            System.arraycopy(src, srcOff, dst, dstOff, dstW);
        } else {
            int loresW = srcW / 2;
            for (int x = 0; x < loresW; x++) {
                int color = src[srcOff + x * 2];
                dst[dstOff + x * 2]     = color;
                dst[dstOff + x * 2 + 1] = color;
            }
        }
    }

    private static void drawVisibleAreaRect(int[] pixels, int w, int h,
                                            int rx, int ry, int rw, int rh) {
        int x1 = rx,      y1 = ry;
        int x2 = rx + rw - 1, y2 = ry + rh - 1;
        for (int x = x1; x <= x2; x++) {
            if (x >= 0 && x < w) {
                if (y1 >= 0 && y1 < h) pixels[y1 * w + x] = DEBUG_COLOR;
                if (y2 >= 0 && y2 < h) pixels[y2 * w + x] = DEBUG_COLOR;
            }
        }
        for (int y = y1; y <= y2; y++) {
            if (y >= 0 && y < h) {
                if (x1 >= 0 && x1 < w) pixels[y * w + x1] = DEBUG_COLOR;
                if (x2 >= 0 && x2 < w) pixels[y * w + x2] = DEBUG_COLOR;
            }
        }
    }

    // PERF: pool di canvas riutilizzati per il path live (refresh). Prima ogni frame
    // allocava una nuova BufferedImage frameWidth×frameHeight (~1.6-3MB) → era la
    // principale sorgente di garbage (pause GC = micro-cali). Triple-buffering: il
    // render thread compone nel canvas corrente e lo passa all'EDT; quando si torna
    // su quel canvas (3 frame dopo) l'EDT l'ha già disegnato.
    private BufferedImage[] livePool;
    private int livePoolIdx;

    public void refresh() {
        // In OpenGL la presentazione è sulla GPU: nessuna composizione Swing qui.
        if (label == null) {
            return;
        }
        // Durante il drag l'overlay possiede il label: il render thread non deve
        // sovrascriverne l'icona con il frame live (resta congelato sotto l'overlay).
        if (frame != null && !dragOverlayActive) {
            if (livePool == null || livePool[0].getWidth() != windowWidth
                                 || livePool[0].getHeight() != windowHeight) {
                livePool = new BufferedImage[3];
                for (int i = 0; i < livePool.length; i++) {
                    livePool[i] = new BufferedImage(windowWidth, windowHeight,
                            BufferedImage.TYPE_INT_RGB);
                }
                livePoolIdx = 0;
            }
            BufferedImage canvas = livePool[livePoolIdx];
            livePoolIdx = (livePoolIdx + 1) % livePool.length;
            composeFrameCanvas(canvas);
            pendingLiveCanvas = canvas;
            SwingUtilities.invokeLater(repaintTask);
        }

        if (dumpEnabled) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastDumpTime >= DUMP_INTERVAL_MS) {
                saveDump();
                lastDumpTime = currentTime;
            }
        }
    }

    private void publishPendingLiveCanvas() {
        if (frame == null || dragOverlayActive) {
            return;
        }
        BufferedImage canvas = pendingLiveCanvas;
        if (canvas != null) {
            label.setDisplayImage(canvas);
            frame.repaint();
        }
    }

    /**
     * Compone l'immagine corrente, centrata sull'area DIW, dentro il canvas dato — che è
     * sempre delle dimensioni del frame nativo, in OGNI modalità finestra. Il canvas viene
     * prima azzerato a nero perché può essere riusato dal pool e contenere il frame
     * precedente.
     *
     * <p>Qui non si ingrandisce e non si centra nella finestra: l'integer scaling e la
     * centratura (con i bordi neri del fullscreen) sono l'ultimo blit, in
     * {@link DisplayPanel#paintScaled}. Tenere la composizione alla risoluzione del frame
     * è ciò che impedisce al traffico di memoria per fotogramma di crescere con lo
     * scaling o con la risoluzione dello schermo.
     */
    private void composeFrameCanvas(BufferedImage canvas) {
        synchronized (imageLock) {
            Graphics2D g = canvas.createGraphics();

            int imgX, imgY;
            FrameInfo fi = lastFrameInfo;
            // VERTICALE: nessuna centratura. L'immagine è ancorata in alto alla prima
            // riga visibile (fine VBlank = riga 26, come WinUAE: VBLANK_ENDLINE_PAL
            // / minfirstline). Il pennello parte da lì e scende; quel che eccede in basso
            // resta fuori (overscan), ma il contenuto in alto non viene mai spinto giù né
            // tagliato. Centrare verticalmente sulla DIW spingeva in alto i giochi che
            // aprono la DIW su tutto l'overscan ma disegnano una sotto-regione (It Came
            // From The Desert) e poteva tagliare il bordo inferiore.
            // ORIZZONTALE: centratura invariata, basata sul centro DIW.
            imgY = -(DISPLAY_TOP_LINE * 2);
            if (fi != null && fi.visibleWidth() > 0) {
                int visCenterX = fi.visibleX() + fi.visibleWidth() / 2;
                imgX = frameWidth / 2 - visCenterX;
            } else {
                imgX = (frameWidth - image.getWidth()) / 2;
            }

            g.setColor(java.awt.Color.BLACK);
            g.fillRect(0, 0, frameWidth, frameHeight);
            g.drawImage(image, imgX, imgY, null);
            if (statusLedsEnabled) {
                drawStatusLeds(g);
            }
            if (fpsOverlayEnabled || frameCounterOverlayEnabled) {
                drawCounters(g);
            }
            g.dispose();
        }
    }

    /**
     * Compone l'overlay per il backend OpenGL (LED/FPS/contatore) in un'immagine ARGB
     * trasparente delle dimensioni del frame, riusando lo stesso disegno del path software
     * ({@link #drawStatusLeds}/{@link #drawCounters}). Ritorna {@code null} se nessun
     * overlay è attivo (così il backend GL non fa alcun upload).
     */
    private int[] renderGlOverlay() {
        if (!(statusLedsEnabled || fpsOverlayEnabled || frameCounterOverlayEnabled)) {
            return null;
        }
        if (glOverlayImage == null) {
            glOverlayImage = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_ARGB);
            glOverlayPixels = ((DataBufferInt) glOverlayImage.getRaster().getDataBuffer()).getData();
        }
        Arrays.fill(glOverlayPixels, 0); // azzera a trasparente
        Graphics2D g = glOverlayImage.createGraphics();
        if (statusLedsEnabled) {
            drawStatusLeds(g);
        }
        if (fpsOverlayEnabled || frameCounterOverlayEnabled) {
            drawCounters(g);
        }
        g.dispose();
        return glOverlayPixels;
    }

    // -------------------------------------------------------------------------
    // Overlay FPS / contatore fotogrammi (diagnostici, OFF di default)
    // -------------------------------------------------------------------------

    private static final Font   COUNTER_FONT   = new Font("Monospaced", Font.BOLD, 16);
    private static final Color  COUNTER_COLOR  = new Color(0xFF, 0xE0, 0x40); // giallo leggibile
    private static final Color  COUNTER_SHADOW = new Color(0, 0, 0, 190);

    /** Aggiorna la stima degli FPS reali su finestra mobile di 1 secondo (solo se overlay attivo). */
    private void updateFps() {
        long now = System.nanoTime();
        if (fpsWindowStartNs == 0L) {
            fpsWindowStartNs = now;
            fpsFrameAccum = 0;
            return;
        }
        fpsFrameAccum++;
        long elapsed = now - fpsWindowStartNs;
        if (elapsed >= 1_000_000_000L) {
            fpsValue = (int) Math.round(fpsFrameAccum * 1_000_000_000.0 / elapsed);
            fpsFrameAccum = 0;
            fpsWindowStartNs = now;
        }
    }

    /** Disegna gli overlay attivi: FPS (basso a sinistra) e contatore fotogrammi (basso al centro). */
    private void drawCounters(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(COUNTER_FONT);
        FontMetrics fm = g.getFontMetrics();
        int margin = 6;
        int baselineY = frameHeight - margin - fm.getDescent();

        if (fpsOverlayEnabled) {
            drawTextShadowed(g, "FPS " + fpsValue, margin, baselineY);
        }
        if (frameCounterOverlayEnabled) {
            FrameInfo fi = lastFrameInfo;
            long frames = fi != null ? fi.frameCount() : 0L;
            String s = "FRAME " + frames;
            int x = (frameWidth - fm.stringWidth(s)) / 2;
            drawTextShadowed(g, s, x, baselineY);
        }
    }

    /** Disegna una stringa con un'ombra di 1px per la leggibilità su qualsiasi sfondo. */
    private void drawTextShadowed(Graphics2D g, String s, int x, int y) {
        g.setColor(COUNTER_SHADOW);
        g.drawString(s, x + 1, y + 1);
        g.setColor(COUNTER_COLOR);
        g.drawString(s, x, y);
    }

    // -------------------------------------------------------------------------
    // LED di stato (power / drive) — sovraimpressione estetica nell'overscan
    // -------------------------------------------------------------------------

    // Colori dei LED come sull'A500 reale: POWER rosso, DRIVE verde.
    private static final Color LED_POWER = new Color(0xFF, 0x20, 0x40); // rosso alimentazione
    private static final Color LED_DRIVE = new Color(0x5A, 0xFF, 0x2A); // verde attività floppy
    private static final Color LED_REFLECTION_OFF = new Color(255, 255, 255, 0);
    private static final Color LED_BORDER = new Color(0, 0, 0, 130);
    private static final Color LED_LABEL = new Color(210, 210, 210, 180);
    private static final BasicStroke LED_BORDER_STROKE = new BasicStroke(1f);
    private static final LedStyle POWER_BRIGHT_STYLE = ledStyle(LED_POWER, 1.0f);
    private static final LedStyle POWER_DIM_STYLE    = ledStyle(LED_POWER, 0.6f);
    private static final LedStyle DRIVE_ACTIVE_STYLE = ledStyle(LED_DRIVE, 1.0f);
    private static final LedStyle DRIVE_IDLE_STYLE   = ledStyle(LED_DRIVE, 0.16f);

    private static final class LedStyle {
        final Color glowOuter;
        final Color glowInner;
        final Color core;
        final Color reflection;
        final boolean glow;

        LedStyle(Color glowOuter, Color glowInner, Color core, Color reflection, boolean glow) {
            this.glowOuter = glowOuter;
            this.glowInner = glowInner;
            this.core = core;
            this.reflection = reflection;
            this.glow = glow;
        }
    }

    private static LedStyle ledStyle(Color color, float intensity) {
        boolean glow = intensity > 0.4f;
        Color glowOuter = glow ? ledAlpha(color, (int) (55 * intensity / 2)) : color;
        Color glowInner = glow ? ledAlpha(color, (int) (55 * intensity)) : color;
        float k = 0.22f + 0.78f * intensity;
        Color core = new Color(
                Math.min(255, (int) (color.getRed()   * k)),
                Math.min(255, (int) (color.getGreen() * k)),
                Math.min(255, (int) (color.getBlue()  * k)));
        Color reflection = intensity > 0.0f
                ? new Color(255, 255, 255, (int) (95 * intensity))
                : LED_REFLECTION_OFF;
        return new LedStyle(glowOuter, glowInner, core, reflection, glow);
    }

    private static Color ledAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private static final boolean[] NO_LED_STATES = new boolean[0];

    /** Una riga del pannello LED: etichetta + uno o più LED con lo stesso stile. */
    private static final class LedRow {
        final String label;
        final boolean[] states;      // uno stato per LED (true = acceso/attivo)
        final LedStyle activeStyle;  // stile quando lo stato è true
        final LedStyle idleStyle;    // stile quando lo stato è false

        LedRow(String label, boolean[] states, LedStyle activeStyle, LedStyle idleStyle) {
            this.label = label;
            this.states = states;
            this.activeStyle = activeStyle;
            this.idleStyle = idleStyle;
        }
    }

    /**
     * Disegna i LED come sull'A500: barre rettangolari sottili impilate con l'etichetta a
     * sinistra di ciascuna, in basso a destra nell'overscan. POWER è sempre presente; la
     * riga DRIVE compare solo se almeno un floppy è collegato e la riga HD solo se almeno
     * un hard disk è collegato (master a sinistra, slave a destra). Ogni riga è suddivisa
     * in tanti LED quante sono le unità collegate. Disegno vettoriale di pochi rettangoli
     * pieni: costo trascurabile, eseguito una volta per frame nel compose.
     */
    private void drawStatusLeds(Graphics2D g) {
        boolean powerBright = powerLedBright != null && powerLedBright.getAsBoolean();
        boolean[] floppyStates = connectedFloppyLedStates();
        boolean[] hdStates = connectedHdLedStates();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Righe visibili: POWER sempre; DRIVE e HD solo se le rispettive unità sono collegate.
        // L'HD condivide il verde del floppy (stessi stili) ma con etichetta "HD".
        java.util.List<LedRow> rows = new java.util.ArrayList<>(3);
        rows.add(new LedRow("POWER", new boolean[]{powerBright}, POWER_BRIGHT_STYLE, POWER_DIM_STYLE));
        if (floppyStates.length > 0) {
            rows.add(new LedRow("DRIVE", floppyStates, DRIVE_ACTIVE_STYLE, DRIVE_IDLE_STYLE));
        }
        if (hdStates.length > 0) {
            rows.add(new LedRow("HD", hdStates, DRIVE_ACTIVE_STYLE, DRIVE_IDLE_STYLE));
        }
        BooleanSupplier netVisible = remotePlayLedVisible;
        if (netVisible != null && netVisible.getAsBoolean()) {
            BooleanSupplier netActive = remotePlayLedActive;
            // Verde come DRIVE e HD, non rosso come POWER: qui si dice "sta succedendo",
            // e il rosso su questo pannello vuol già dire un'altra cosa. Se a video non
            // convince, è una costante (piano §15.4, decisione da prendere guardando).
            rows.add(new LedRow("NET",
                    new boolean[]{ netActive != null && netActive.getAsBoolean() },
                    DRIVE_ACTIVE_STYLE, DRIVE_IDLE_STYLE));
        }

        final int baseLedW = 59, ledH = 10; // barre sottili come quelle reali (84 ridotto del 30%)
        final int smallLedGap = 3;
        final int labelGap = 10;         // distanza etichetta -> LED
        final int rowGap = 11;           // distanza verticale tra le righe
        final int padX = 14, padY = 12;  // padding interno

        g.setFont(ledLabelFont);
        FontMetrics fm = g.getFontMetrics();

        // Larghezza del LED group allineata sul massimo numero di LED fra le righe, così
        // le colonne restano allineate (POWER è sempre un singolo LED a tutta larghezza).
        int maxLeds = 1;
        int labelColW = 0;
        for (LedRow r : rows) {
            maxLeds = Math.max(maxLeds, r.states.length);
            labelColW = Math.max(labelColW, fm.stringWidth(r.label));
        }
        final int ledW = alignedLedWidth(baseLedW, maxLeds, smallLedGap);
        int rowH = Math.max(ledH, fm.getAscent());

        int contentW = labelColW + labelGap + ledW;
        int contentH = rowH * rows.size() + rowGap * (rows.size() - 1);
        int housingW = contentW + padX * 2;
        int housingH = contentH + padY * 2;

        // Nessun alloggiamento: LED ed etichette sono disegnati direttamente sull'overscan.
        int contentX = frameWidth - housingW + padX;
        int contentY = frameHeight - housingH + padY;
        int ledX = contentX + labelColW + labelGap;
        int labelRight = contentX + labelColW; // colonna di allineamento a destra delle scritte

        for (int i = 0; i < rows.size(); i++) {
            LedRow r = rows.get(i);
            int cy = contentY + i * (rowH + rowGap) + rowH / 2;
            drawLedGroup(g, ledX, cy - ledH / 2, ledW, ledH, smallLedGap, r);
            drawLabelRight(g, fm, r.label, labelRight, cy);
        }
    }

    /** Stati del LED floppy: uno per drive collegato, vuoto se non c'è alcun drive. */
    private boolean[] connectedFloppyLedStates() {
        FloppyDrive[] snapshot = drives;
        if (snapshot == null) return NO_LED_STATES;
        int count = 0;
        for (FloppyDrive drive : snapshot) {
            if (drive != null && drive.isConnected()) count++;
        }
        if (count == 0) return NO_LED_STATES;
        boolean[] states = new boolean[count];
        int out = 0;
        for (FloppyDrive drive : snapshot) {
            if (drive != null && drive.isConnected()) states[out++] = drive.isMotorRunning();
        }
        return states;
    }

    /** Stati del LED HD: uno per hard disk collegato, vuoto se non ce n'è nessuno. */
    private boolean[] connectedHdLedStates() {
        BooleanSupplier[] snapshot = hdLedSources;
        if (snapshot == null) return NO_LED_STATES;
        int count = 0;
        for (BooleanSupplier hd : snapshot) {
            if (hd != null) count++;
        }
        if (count == 0) return NO_LED_STATES;
        boolean[] states = new boolean[count];
        int out = 0;
        for (BooleanSupplier hd : snapshot) {
            if (hd != null) states[out++] = hd.getAsBoolean();
        }
        return states;
    }

    private static int alignedLedWidth(int baseWidth, int ledCount, int gap) {
        if (ledCount <= 1) return baseWidth;
        int gaps = gap * (ledCount - 1);
        int usable = Math.max(1, baseWidth - gaps);
        int remainder = usable % ledCount;
        return remainder == 0 ? baseWidth : baseWidth + (ledCount - remainder);
    }

    /** Disegna la riga di LED suddivisa in {@code states.length} barre uguali. */
    private void drawLedGroup(Graphics2D g, int x, int y, int totalW, int h,
                              int gap, LedRow row) {
        int count = Math.max(1, row.states.length);
        int ledW = (totalW - gap * (count - 1)) / count;
        for (int i = 0; i < count; i++) {
            drawLedBar(g, x + i * (ledW + gap), y, ledW, h,
                    row.states[i] ? row.activeStyle : row.idleStyle);
        }
    }

    /** Disegna un LED rettangolare (spigoli vivi): alone, corpo, riflesso e bordo. */
    private void drawLedBar(Graphics2D g, int x, int y, int w, int h, LedStyle style) {
        if (style.glow) {
            g.setColor(style.glowOuter);
            g.fillRect(x - 6, y - 6, w + 12, h + 12);
            g.setColor(style.glowInner);
            g.fillRect(x - 3, y - 3, w + 6, h + 6);
        }

        g.setColor(style.core);
        g.fillRect(x, y, w, h);

        // Riflesso speculare nella metà superiore.
        g.setColor(style.reflection);
        g.fillRect(x + 1, y + 1, w - 2, Math.max(1, h / 3));

        // Bordo scuro per staccare dal fondo.
        g.setColor(LED_BORDER);
        g.setStroke(LED_BORDER_STROKE);
        g.drawRect(x, y, w - 1, h - 1);
    }

    /** Disegna una stringa allineata a destra a {@code rightX}, centrata verticalmente su {@code cy}. */
    private void drawLabelRight(Graphics2D g, FontMetrics fm, String s, int rightX, int cy) {
        g.setColor(LED_LABEL);
        int x = rightX - fm.stringWidth(s);
        int y = cy + (fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(s, x, y);
    }

    /**
     * Compone un nuovo canvas (allocato) per screenshot/dump (path non hot).
     */
    private BufferedImage buildFrameCanvas() {
        BufferedImage canvas = new BufferedImage(windowWidth, windowHeight,
                BufferedImage.TYPE_INT_RGB);
        composeFrameCanvas(canvas);
        return canvas;
    }

    private void saveDump() {
        try {
            File dir = AppHome.file("screenshots");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File file = new File(dir, "dump_" + timestamp + ".png");
            ImageIO.write(captureForScreenshot(), "png", file);
        } catch (IOException e) {
            System.err.println("Errore durante il salvataggio del dump: " + e.getMessage());
        }
    }

    public void saveScreenshot(File file) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        ImageIO.write(captureForScreenshot(), "png", file);
    }

    /**
     * Immagine da salvare per screenshot/dump: in OpenGL è il backbuffer letto con
     * {@code glReadPixels} sul thread GL; in software (o se la cattura GL non è
     * disponibile) è la composizione Java2D.
     */
    private BufferedImage captureForScreenshot() {
        if (glSurface != null) {
            BufferedImage img = glSurface.snapshot();
            if (img != null) {
                return img;
            }
        }
        return buildFrameCanvas();
    }

    public BufferedImage getImage() {
        synchronized (imageLock) {
            return image;
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public FrameInfo getLastFrameInfo() {
        return lastFrameInfo;
    }

    public JFrame getFrame() {
        return frame;
    }

    // -------------------------------------------------------------------------
    // Drag-and-drop floppy — overlay a quattro quadranti (DF0-DF3)
    // -------------------------------------------------------------------------

    // Palette dell'overlay.
    private static final Color OK_ACCENT     = new Color(0x3D, 0xDC, 0x84); // verde (drive disponibile)
    private static final Color OK_ACCENT_HI  = new Color(0x6B, 0xFF, 0xA8); // verde acceso (hover)
    private static final Color BAD_ACCENT    = new Color(0xFF, 0x52, 0x63); // rosso (drive assente)
    private static final Color DIM_VEIL      = new Color(0, 0, 0, 130);     // velo scuro sullo sfondo
    private static final Color GRID_LINE     = new Color(255, 255, 255, 38);

    /**
     * Installa un DropTarget sul label (contentPane). Rispetto a un semplice
     * TransferHandler, il DropTargetListener fornisce la posizione del cursore
     * durante il drag, così da: (1) disegnare l'overlay a quadranti, (2) accettare
     * o rifiutare il drop per-quadrante (il rifiuto mostra il cursore "no-drop"),
     * (3) recapitare il file al drive corretto.
     */
    private void setupDragAndDrop() {
        DropTargetListener dtl = new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetDragEvent e) {
                if (!isFileDrag(e)) { e.rejectDrag(); return; }
                beginDragOverlay();
                hoverQuadrant = quadrantAt(e.getLocation());
                updateDragAcceptance(e);
                paintDragOverlay();
            }

            @Override
            public void dragOver(DropTargetDragEvent e) {
                if (!dragOverlayActive) { e.rejectDrag(); return; }
                // Ridipingiamo a OGNI dragOver, non solo al cambio di quadrante: il disegno
                // diretto (getGraphics + sync) attecchisce in modo affidabile durante il
                // dragOver, mentre il paint di dragEnter può andare perso. Così qualunque
                // micro-movimento fa comparire e mantenere l'overlay, anche restando fermi
                // sullo stesso drive (incluso un drive disabilitato). Costo minimo: il
                // foreground è in cache, sono due drawImage + un sync.
                hoverQuadrant = quadrantAt(e.getLocation());
                paintDragOverlay();
                updateDragAcceptance(e);
            }

            @Override
            public void dropActionChanged(DropTargetDragEvent e) {
                updateDragAcceptance(e);
            }

            @Override
            public void dragExit(DropTargetEvent e) {
                endDragOverlay();
            }

            @Override
            public void drop(DropTargetDropEvent e) {
                handleDrop(e);
            }
        };
        new DropTarget(dndComponent, DnDConstants.ACTION_COPY, dtl, true);
    }

    private static boolean isFileDrag(DropTargetDragEvent e) {
        return e.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
    }

    /** Accetta il drag (cursore di copia) solo se il quadrante punta a un drive connesso. */
    private void updateDragAcceptance(DropTargetDragEvent e) {
        FloppyDrive d = driveAt(hoverQuadrant);
        if (d != null && d.isConnected()) {
            e.acceptDrag(DnDConstants.ACTION_COPY);
        } else {
            e.rejectDrag(); // mostra il cursore "no-drop" sui drive non disponibili
        }
    }

    private FloppyDrive driveAt(int quadrant) {
        FloppyDrive[] d = drives;
        if (d == null || quadrant < 0 || quadrant >= d.length) return null;
        return d[quadrant];
    }

    /** Mappa una posizione (relativa al componente DnD) sul quadrante: 0=NO,1=NE,2=SO,3=SE. */
    private int quadrantAt(Point p) {
        int w = dndComponent.getWidth(), h = dndComponent.getHeight();
        if (w <= 0 || h <= 0 || p == null) return -1;
        int col = p.x < w / 2 ? 0 : 1;
        int row = p.y < h / 2 ? 0 : 1;
        return row * 2 + col;
    }

    /** Congela il frame corrente come sfondo grigio e attiva l'overlay. */
    private void beginDragOverlay() {
        dragOverlayActive = true;
        if (glSurface != null) {
            // OpenGL: lo sfondo grigio è lo snapshot del backbuffer (glReadPixels).
            BufferedImage snap = glSurface.snapshot();
            dragBgSnapshot = (snap != null) ? toGreyscale(snap) : null;
            return;
        }
        if (label == null) {
            return;
        }
        dragBgSnapshot = toGreyscale(buildFrameCanvas());
    }

    /** Disattiva l'overlay e ripristina il rendering live. */
    private void endDragOverlay() {
        dragOverlayActive = false;
        hoverQuadrant = -1;
        dragBgSnapshot = null;
        if (glSurface != null) {
            glSurface.setDragOverlay(null, 0, 0); // il thread GL riprende il frame live
            return;
        }
        refresh(); // ricompone subito il frame live sul label
    }

    /** Versione scurita e desaturata del canvas, usata come sfondo dell'overlay. */
    private BufferedImage toGreyscale(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        int[] in  = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
        int[] dst = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < in.length; i++) {
            int rgb = in[i];
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            int lum = (r * 77 + g * 150 + b * 29) >> 8;   // luminanza ~Rec.601
            lum = (lum * 90) / 255;                        // scurisce per dare risalto all'overlay
            dst[i] = (lum << 16) | (lum << 8) | lum;
        }
        return out;
    }

    /**
     * Compone l'overlay e lo mostra sul label. Lo sfondo grigio è dinamico (cambia
     * a ogni drag), mentre il foreground (velo + griglia + quadranti) è pre-renderizzato
     * per ogni stato di hover: comporre un frame del drag costa quindi due soli drawImage.
     */
    private void paintDragOverlay() {
        BufferedImage bg = dragBgSnapshot;
        if (bg == null || frame == null) return;

        BufferedImage[] cache = overlayFgCache;
        if (cache == null) {                 // fallback se il pre-render non è ancora pronto
            cache = buildOverlayCache();
            overlayFgCache = cache;
        }
        BufferedImage fg = cache[hoverQuadrant + 1];

        BufferedImage canvas = new BufferedImage(windowWidth, windowHeight,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.drawImage(bg, 0, 0, windowWidth, windowHeight, null);
        g.drawImage(fg, 0, 0, null);
        g.dispose();

        if (glSurface != null) {
            // OpenGL: l'overlay statico viene disegnato dalla GPU a tutta finestra.
            int[] px = ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
            glSurface.setDragOverlay(px, windowWidth, windowHeight);
        } else {
            setOverlayIcon(canvas);
        }
    }

    /**
     * Costruisce in background i layer di foreground dell'overlay, così il primo drag
     * non paga il costo di font/antialiasing/path dei glifi. Nessun effetto in headless.
     *
     * <p>Oltre alla cache, "scalda" il percorso di compose usato a ogni frame del drag
     * (toGreyscale + drawImage) e il sottosistema di trasferimento dati AWT:
     * sono tutti costi di primo utilizzo (class-loading/JIT/Java2D) che altrimenti
     * verrebbero pagati interamente al primo drag.
     */
    private void prebuildOverlayCacheAsync() {
        if (frame == null) return;
        Thread t = new Thread(() -> {
            BufferedImage[] cache = buildOverlayCache();
            overlayFgCache = cache;
            warmupDragPaintPath(cache);
            warmupDndSubsystem();
        }, "overlay-prerender");
        t.setDaemon(true);
        t.start();
    }

    /** Esegue una volta il percorso di compose del drag per pagarne i costi di primo uso. */
    private void warmupDragPaintPath(BufferedImage[] cache) {
        try {
            BufferedImage grey = toGreyscale(buildFrameCanvas());
            BufferedImage canvas = new BufferedImage(windowWidth, windowHeight,
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = canvas.createGraphics();
            g.drawImage(grey, 0, 0, windowWidth, windowHeight, null);
            g.drawImage(cache[0], 0, 0, null);
            g.dispose();
            canvas.getWidth(); // mantiene esplicito il warmup best-effort del canvas
        } catch (Throwable ignore) {
            // Warmup best-effort: un errore qui non deve impattare il funzionamento.
        }
    }

    /** Tocca il sottosistema di trasferimento dati AWT per anticiparne il class-loading. */
    private void warmupDndSubsystem() {
        try {
            DataFlavor.javaFileListFlavor.getHumanPresentableName();
            java.awt.datatransfer.SystemFlavorMap.getDefaultFlavorMap();
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    /**
     * Pre-renderizza il foreground dell'overlay (ARGB trasparente) per ogni stato di
     * hover. Indice = hoverQuadrant+1 (0 = nessun hover). Gli stati che non cambiano
     * aspetto (hover su un drive non connesso) riusano lo stesso layer di "nessun hover".
     */
    private BufferedImage[] buildOverlayCache() {
        BufferedImage[] cache = new BufferedImage[5];
        cache[0] = renderOverlayForeground(-1);          // nessun quadrante in hover
        for (int i = 0; i < 4; i++) {
            FloppyDrive d = driveAt(i);
            boolean connected = d != null && d.isConnected();
            // Solo i drive connessi cambiano aspetto in hover: gli altri riusano cache[0].
            cache[i + 1] = connected ? renderOverlayForeground(i) : cache[0];
        }
        return cache;
    }

    /** Renderizza il foreground completo (velo + griglia + 4 quadranti) per uno stato di hover. */
    private BufferedImage renderOverlayForeground(int hover) {
        BufferedImage img = new BufferedImage(windowWidth, windowHeight,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setColor(DIM_VEIL);
        g.fillRect(0, 0, windowWidth, windowHeight);

        int halfW = windowWidth / 2, halfH = windowHeight / 2;
        g.setColor(GRID_LINE);
        g.setStroke(new BasicStroke(1f));
        g.drawLine(halfW, 0, halfW, windowHeight);
        g.drawLine(0, halfH, windowWidth, halfH);

        for (int i = 0; i < 4; i++) {
            int col = i % 2, row = i / 2;
            paintQuadrant(g, i, col * halfW, row * halfH, halfW, halfH, hover);
        }
        g.dispose();
        return img;
    }

    /** Disegna un singolo quadrante: card, glifo floppy, etichetta DFx e sottotitolo. */
    private void paintQuadrant(Graphics2D g, int idx, int qx, int qy, int qw, int qh, int hover) {
        FloppyDrive d  = driveAt(idx);
        boolean connected = d != null && d.isConnected();
        boolean hovered   = idx == hover;
        Color accent      = connected ? OK_ACCENT : BAD_ACCENT;
        Color accentHi    = connected ? OK_ACCENT_HI : BAD_ACCENT;

        int margin = 24;
        int cardX = qx + margin, cardY = qy + margin;
        int cardW = qw - 2 * margin, cardH = qh - 2 * margin;
        int arc = 30;
        RoundRectangle2D card = new RoundRectangle2D.Float(cardX, cardY, cardW, cardH, arc, arc);

        // Riempimento traslucido: più marcato per il drive disponibile sotto il cursore.
        int fillA = connected ? (hovered ? 64 : 26) : 20;
        g.setColor(withAlpha(accent, fillA));
        g.fill(card);

        // Alone esterno sul quadrante disponibile attivo.
        if (hovered && connected) {
            g.setColor(withAlpha(accentHi, 80));
            g.setStroke(new BasicStroke(11f));
            g.draw(card);
        }

        // Bordo: pieno per i drive disponibili, tratteggiato per quelli assenti.
        g.setColor(hovered ? accentHi : withAlpha(accent, 205));
        if (connected) {
            g.setStroke(new BasicStroke(hovered ? 3.5f : 2.5f));
        } else {
            g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    0f, new float[]{11f, 9f}, 0f));
        }
        g.draw(card);

        int cx = qx + qw / 2, cy = qy + qh / 2;

        drawFloppyGlyph(g, cx, cy - 50, 58, accent, connected);

        // Etichetta DFx.
        g.setFont(overlayFont.deriveFont(Font.BOLD, 46f));
        g.setColor(hovered && connected ? accentHi : accent);
        drawCentered(g, "DF" + idx, cx, cy + 18);

        // Sottotitolo di stato.
        String sub = connected
                ? (hovered ? io.github.zolfrik.amija.i18n.Messages.t("overlay.drop")
                           : io.github.zolfrik.amija.i18n.Messages.t("overlay.available"))
                : io.github.zolfrik.amija.i18n.Messages.t("overlay.unavailable");
        g.setFont(overlayFont.deriveFont(Font.PLAIN, 15f));
        g.setColor(connected ? withAlpha(Color.WHITE, 180) : withAlpha(BAD_ACCENT, 200));
        drawCentered(g, sub, cx, cy + 52);
    }

    /** Glifo stilizzato di un floppy 3.5" centrato in (cx,cy). */
    private void drawFloppyGlyph(Graphics2D g, int cx, int cy, int s, Color accent, boolean connected) {
        int w = (int) (s * 0.86), h = s;
        int x = cx - w / 2, y = cy - h / 2;
        int cut = (int) (w * 0.30);
        int r = 7;

        // Corpo con angolo superiore destro tagliato (tipico del 3.5").
        Path2D body = new Path2D.Float();
        body.moveTo(x, y + r);
        body.quadTo(x, y, x + r, y);
        body.lineTo(x + w - cut, y);
        body.lineTo(x + w, y + cut);
        body.lineTo(x + w, y + h - r);
        body.quadTo(x + w, y + h, x + w - r, y + h);
        body.lineTo(x + r, y + h);
        body.quadTo(x, y + h, x, y + h - r);
        body.closePath();

        g.setColor(withAlpha(accent, connected ? 55 : 36));
        g.fill(body);
        g.setColor(accent);
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(body);

        // Otturatore metallico in alto.
        int shW = (int) (w * 0.40), shH = (int) (h * 0.26);
        int shX = cx - shW / 2, shY = y + (int) (h * 0.07);
        g.fill(new RoundRectangle2D.Float(shX, shY, shW, shH, 4, 4));
        // Tacca nell'otturatore.
        g.setColor(withAlpha(Color.BLACK, 120));
        int notchW = Math.max(3, shW / 5);
        g.fillRect(shX + shW - notchW - 3, shY + 3, notchW, shH - 6);

        // Area etichetta in basso.
        int lbW = (int) (w * 0.66), lbH = (int) (h * 0.44);
        int lbX = cx - lbW / 2, lbY = y + h - lbH - (int) (h * 0.10);
        g.setColor(withAlpha(Color.WHITE, connected ? 45 : 28));
        g.fill(new RoundRectangle2D.Float(lbX, lbY, lbW, lbH, 4, 4));
        g.setColor(withAlpha(accent, 160));
        g.setStroke(new BasicStroke(1.4f));
        g.draw(new RoundRectangle2D.Float(lbX, lbY, lbW, lbH, 4, 4));
    }

    /** Disegna una stringa centrata orizzontalmente e verticalmente attorno a (cx,cy). */
    private void drawCentered(Graphics2D g, String s, int cx, int cy) {
        FontMetrics fm = g.getFontMetrics();
        int x = cx - fm.stringWidth(s) / 2;
        int y = cy - (fm.getAscent() + fm.getDescent()) / 2 + fm.getAscent();
        g.drawString(s, x, y);
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private void setOverlayIcon(BufferedImage canvas) {
        // Le callback DnD girano sull'EDT, ma durante un drag nativo l'EDT è in un loop
        // modale: i repaint asincroni e paintImmediately compaiono solo quando AWT genera
        // il feedback "drag-under" (cioè SOLO su acceptDrag) o al successivo dragOver. Su
        // rejectDrag (drive disabilitato, mouse fermo) l'overlay non appariva mai.
        // Si disegna quindi DIRETTAMENTE sul Graphics del label (active rendering) e si
        // forza il flush del pipeline con Toolkit.sync(): così i pixel raggiungono lo
        // schermo subito, a prescindere da accept/reject e dal movimento del mouse.
        // setDisplayImage mantiene lo stato coerente per gli eventuali repaint successivi.
        label.setDisplayImage(canvas);
        Graphics g = label.getGraphics();
        if (g != null) {
            try {
                // Stesso blit di paintComponent (ingrandimento + centratura): disegnare
                // qui a 1:1 lascerebbe scoperta la finestra con integer scaling > 1.
                label.paintScaled(g);
            } finally {
                g.dispose();
            }
            Toolkit.getDefaultToolkit().sync();
        }
    }

    /** Finalizza un drop: valida quadrante e file, poi inserisce il disco nel drive. */
    private void handleDrop(DropTargetDropEvent e) {
        int q = quadrantAt(e.getLocation());
        FloppyDrive drive = driveAt(q);
        if (drive == null || !drive.isConnected()
                || !e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            e.rejectDrop();
            endDragOverlay();
            return;
        }
        e.acceptDrop(DnDConstants.ACTION_COPY);
        boolean ok = false;
        try {
            @SuppressWarnings("unchecked")
            List<File> files = (List<File>) e.getTransferable()
                    .getTransferData(DataFlavor.javaFileListFlavor);
            File disk = firstSupportedDisk(files);
            if (disk != null) {
                handleDiskDrop(drive, q, disk);
                ok = true;
            }
        } catch (Exception ex) {
            System.err.println("[DND] Errore ricezione file: " + ex.getMessage());
        }
        e.dropComplete(ok);
        endDragOverlay();
    }

    /**
     * Primo file .adf/.ipf/.zip della lista trascinata, o null.
     *
     * <p>{@code Locale.ROOT} e non la lingua del sistema: in turco {@code 'I'} minuscolo
     * e' {@code 'i'} senza punto, quindi {@code "GIOCO.IPF"} diventerebbe
     * {@code "gioco.ıpf"} e non corrisponderebbe a {@code ".ipf"} — un file trascinato
     * che l'emulatore ignora in silenzio, e solo su alcune macchine. Le estensioni sono
     * identificatori, non testo da mostrare: si confrontano nella locale neutra.
     */
    private static File firstSupportedDisk(List<File> files) {
        if (files == null) return null;
        for (File f : files) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.endsWith(".adf") || n.endsWith(".ipf") || n.endsWith(".zip")) return f;
        }
        return null;
    }

    /**
     * Inserisce il disco droppato nel drive indicato. Se il drive contiene già un
     * disco: espelle, attende 1500 ms per dare al Kickstart il tempo di rilevare
     * l'espulsione, poi inserisce il nuovo. Eseguito su un thread daemon per non
     * bloccare l'EDT.
     */
    private void handleDiskDrop(FloppyDrive drive, int driveIndex, File file) {
        if (drive == null) return;
        final String id = drive.getId();
        Thread t = new Thread(() -> {
            try {
                if (drive.isDiskInserted()) {
                    System.out.println("[DND] " + id + ": espulsione disco corrente...");
                    drive.ejectDisk();
                    Thread.sleep(1500);
                }
                System.out.println("[DND] " + id + ": inserimento " + file.getName());
                drive.loadAdf(file.getAbsolutePath(), false);
                System.out.println("[DND] " + id + ": disco inserito.");
                // Allinea la configurazione (popup F12) al disco appena inserito.
                ObjIntConsumer<String> l = diskDropListener;
                if (l != null) l.accept(file.getAbsolutePath(), driveIndex);
            } catch (Exception e) {
                System.err.println("[DND] Errore inserimento disco: " + e.getMessage());
            }
        }, "disk-drop");
        t.setDaemon(true);
        t.start();
    }

}
