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

package io.github.zolfrik.amija;

import com.formdev.flatlaf.FlatDarkLaf;

import io.github.zolfrik.amija.config.AmigaConfig;
import io.github.zolfrik.amija.display.AmigaWindowMode;
import io.github.zolfrik.amija.common.AppHome;
import io.github.zolfrik.amija.common.BusRequest;
import io.github.zolfrik.amija.common.Chipset;
import io.github.zolfrik.amija.common.BusResponse;
import io.github.zolfrik.amija.common.CIAPinSet;
import io.github.zolfrik.amija.common.MOS8520;
import io.github.zolfrik.amija.common.MasterClock;
import io.github.zolfrik.amija.common.VirtualCRT;
import io.github.zolfrik.amija.common.VideoMode;
import io.github.zolfrik.amija.common.WindowsDpiAwareness;
import io.github.zolfrik.amija.cpu.AbstractMotorola;
import io.github.zolfrik.amija.custom.AbstractAgnus;
import io.github.zolfrik.amija.custom.OcsAgnus;
import io.github.zolfrik.amija.custom.EcsAgnus;
import io.github.zolfrik.amija.custom.AmigaJoystickKeyAdapter;
import io.github.zolfrik.amija.custom.AmigaKeyAdapter;
import io.github.zolfrik.amija.custom.AmigaMouseAdapter;
import io.github.zolfrik.amija.custom.Keyboard;
import io.github.zolfrik.amija.custom.AbstractDenise;
import io.github.zolfrik.amija.custom.OcsDenise;
import io.github.zolfrik.amija.custom.EcsDenise;
import io.github.zolfrik.amija.custom.AgaAgnus;
import io.github.zolfrik.amija.custom.AgaDenise;
import io.github.zolfrik.amija.custom.DiskImage;
import io.github.zolfrik.amija.custom.FastRam;
import io.github.zolfrik.amija.custom.FloppyDrive;
import io.github.zolfrik.amija.custom.HdController;
import io.github.zolfrik.amija.memory.BuiltinRom;
import io.github.zolfrik.amija.memory.HardFile;
import io.github.zolfrik.amija.net.AudioBlockHeader;
import io.github.zolfrik.amija.net.Handshake;
import io.github.zolfrik.amija.net.JoinCode;
import io.github.zolfrik.amija.net.NetImpairment;
import io.github.zolfrik.amija.net.RemotePlayControl;
import io.github.zolfrik.amija.net.RemotePlayGuest;
import io.github.zolfrik.amija.net.RemotePlayHost;
import io.github.zolfrik.amija.net.RemotePlayProtocol;
import io.github.zolfrik.amija.net.RemotePlaySettings;
import io.github.zolfrik.amija.net.VideoReceiver;
import io.github.zolfrik.amija.net.upnp.PortMapper;
import io.github.zolfrik.amija.whdload.WhdLoadManager;
import io.github.zolfrik.amija.custom.audio.FloppySound;
import io.github.zolfrik.amija.custom.Gary;
import io.github.zolfrik.amija.custom.Paula;
import io.github.zolfrik.amija.display.AmigaDisplay;
import io.github.zolfrik.amija.display.ConfigDialog;
import io.github.zolfrik.amija.display.render.RendererType;
import io.github.zolfrik.amija.i18n.Messages;
import io.github.zolfrik.amija.input.GamePort;
import io.github.zolfrik.amija.input.GamePortDevice;
import io.github.zolfrik.amija.input.GlfwJoystickService;
import io.github.zolfrik.amija.input.PortDevice;
import io.github.zolfrik.amija.input.RemoteInputApplier;
import io.github.zolfrik.amija.input.RemoteInputSender;
import io.github.zolfrik.amija.memory.KickstartImage;
import io.github.zolfrik.amija.memory.RAM;
import io.github.zolfrik.amija.memory.ROM;
import io.github.zolfrik.amija.state.SaveStateManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

/**
 * Main class of AmiJa, the Amiga 500 emulator. Creates and wires all the internal
 * components and starts the cycle-exact simulation.
 *
 * <p>It is also the controller of the machine's life cycle: the configuration is read from
 * {@link AmigaConfig} and can be changed at run time through the popup opened with
 * <b>F12</b> ({@link ConfigDialog}). While the popup is open the emulation is paused and the
 * host mouse released; when it closes the machine restarts (cold, or resuming) and the
 * mouse is captured again.
 */
public class Amija implements RemotePlayControl {

    private static final boolean VERBOSE = false;

    /**
     * JIT tuning passed to every JVM that runs the emulator. These options change only how
     * HotSpot generates machine code: the emulated behaviour, and therefore every reference
     * the project is validated against, is untouched by construction.
     *
     * <p>The emulation loop is one very large method ({@code AbstractAgnus.risingEdge},
     * ~1880 bytecodes) invoked 7.09 million times a second. At the default limits the
     * per-cycle helpers it calls stay real call instructions: raising the limits lets them
     * fold into the loop body. Measured on a demo workload with the A/B bench in
     * {@code tools/ab-bench}: <b>-2 %</b> of wall-time, reproduced in two independent
     * sessions with six adjacent pairs agreeing in sign each time.
     *
     * <p><b>The optimum is not monotonic: more inlining is worse.</b> Doubling these values
     * ({@code MaxInlineSize=200 FreqInlineSize=1000 InlineSmallCode=5000 MaxInlineLevel=20})
     * measures <b>+3.8 %</b>, a slowdown twice the size of the gain, because the compiled
     * body outgrows the register file and the instruction cache. Neither half of the set
     * pays on its own: the effect belongs to the combination. Re-measure before changing a
     * number, and never extrapolate one.
     *
     * <p>The same four options are passed by the native launcher, so they must be kept in
     * step with {@code packaging/build-image.sh} and {@code packaging/build-image.ps1} in
     * the public tree. Details and method: {@code docs/performance-jit-plan-2026-09-25.md}.
     */
    static final String[] JIT_OPTIONS = {
        "-XX:MaxInlineSize=100",
        "-XX:FreqInlineSize=500",
        "-XX:InlineSmallCode=3000",
        "-XX:MaxInlineLevel=15",
    };

    // Current video mode (PAL/NTSC): read from the config at every cold start
    // (bootMachine). Changing it in the F12 popup takes effect only after a cold restart.
    private VideoMode videoMode = VideoMode.PAL;
    // The explanation about the missing Kickstart is given once per run: if the user closes
    // the popup without setting it, the second time a reminder of what is missing is enough.
    private boolean welcomeShown;

    // --- State kept across restarts (window/display + configuration) ---
    private AmigaConfig config;
    private AmigaDisplay display;
    private JFrame frame;
    // Component the input listeners are attached to: the frame in software, the GL canvas
    // (heavyweight) in OpenGL. Set at every (re)creation of the display.
    private java.awt.Component inputComponent;

    // --- Components of the current machine (recreated at every cold start) ---
    // The media mounted on the running machine, taken when it was built; null when there
    // is no machine. They are the hardware in force: the popup compares its media list with
    // them to know what the user is adding, which then waits for the next start instead of
    // changing the machine under the game.
    private ConfigDialog.DriveHardware activeHardware;
    private MasterClock clock;
    private Paula paula;
    private VirtualCRT virtualCRT;
    /** The clocked processor; null when the processor is event-driven. */
    private AbstractMotorola cpu;
    /** The processor, whatever drives it. */
    private io.github.zolfrik.amija.cpu.Processor processor;
    /** The bus of an event-driven processor; null when the processor is clocked. */
    private io.github.zolfrik.amija.custom.EventCpuBus eventBus;
    private AbstractAgnus agnus;
    private Keyboard keyboard;
    private AmigaMouseAdapter mouseAdapter;
    private AmigaKeyAdapter keyAdapter;
    private AmigaJoystickKeyAdapter joystickAdapter;
    // Current game ports (recreated at every boot): Port 0 = mouse/JOY0DAT, Port 1 = joystick/JOY1DAT.
    private GamePort gamePort1;
    private GamePort gamePort2;
    // Real joystick service (GLFW): singleton, started lazily only if selected.
    private final GlfwJoystickService joystickService = GlfwJoystickService.getInstance();

    // --- Remote play (docs/remote-play-plan.md). One of the two, never both: either this
    // machine emulates and serves a guest, or it does not emulate at all and is the guest.
    private RemotePlayHost remotePlayHost;
    /** Port forwarding on the router while hosting (section 7.1); null if not requested. */
    private volatile PortMapper portMapper;
    private Thread routerCloser;
    private volatile RemotePlayGuest remotePlayGuest;
    private RemotePlaySettings remotePlaySettings = RemotePlaySettings.off();
    /** Guest side: gathers the local gestures into the packet to send. */
    private RemoteInputSender remoteInputSender;
    // --- Connecting to a host: the session opens with the popup STILL OPEN ---------------
    // The actual connection happens on its own thread (the handshake can take seconds and
    // the EDT cannot wait for it), while the local machine stays mounted: so the popup can
    // report how it is going, and a failure still has a window in which to be told.
    // Unmounting the machine, which cannot be done from inside a modal dialog, happens only
    // if the user confirms with OK (applyPendingRemotePlayTransition).
    private volatile boolean joining;
    private String joiningAddress;
    /** Close requested while the attempt was in flight. Guarded by {@code this}. */
    private boolean joinCancelled;
    /** True when the machine has really been unmounted to act as a guest. */
    private volatile boolean guestMode;
    /** Last negative outcome of remote play, kept in the status line. */
    private volatile String remotePlayLastError;
    private boolean pendingLeave;
    private final FloppySound[] floppySounds = new FloppySound[4];
    private final java.util.List<HardFile> hardFiles = new java.util.ArrayList<>();
    // Activity sources for the display's HD LED, one per unit (WHDLoad, master, slave), in
    // the order they appear from left to right.
    private final java.util.List<java.util.function.BooleanSupplier> hdLedSources =
            new java.util.ArrayList<>();

    public static void main(String[] args) {
        // BEFORE anything else (in particular before AWT initialises): declare the process
        // per-monitor DPI aware. The jpackage native launcher has no manifest and would start
        // DPI-unaware, making AWT take the system fonts at 96 DPI; at the first JFileChooser
        // those desktop properties would be re-read at the real DPI and the fonts of the
        // whole Look and Feel would grow. Under java.exe (manifest dpiAware=true/PM) it is a
        // no-op.
        WindowsDpiAwareness.ensurePerMonitorAware();
        // Java2D HiDPI scaling is ALWAYS off, and must be turned off HERE, before the
        // AWT/Swing toolkit initialises (the first init happens in
        // applyWindowsLookAndFeel): the toolkit fixes it once, at start-up.
        //
        // From here on 1 Java pixel = 1 physical pixel, and the two scales are independent:
        //  - the emulated window is enlarged by AmiJa, by an INTEGER factor
        //    (video.integerScaling), in AmigaDisplay/DisplayPanel for the software renderer
        //    and on the GPU for the OpenGL one;
        //  - the interface is enlarged by the Look and Feel, by a factor that may be
        //    fractional, without touching the emulated window.
        // Letting Java decide, a desktop at 150% would scale EVERYTHING by 1.5, emulated
        // window included, with interpolation: the opposite of integer scaling.
        // The two properties together are not redundant: the first turns system scaling
        // off, the second is the safety belt.
        // See docs/gui-scaling-flatlaf-plan.md.
        System.setProperty("sun.java2d.uiScale.enabled", "false");
        System.setProperty("sun.java2d.uiScale", "1");
        applyUiScale(AmigaConfig.load());
        applyLookAndFeel();
        new Amija().start();
    }

    /**
     * Passes the interface scale factor ({@code ui.scale}) to FlatLaf. It must be done
     * BEFORE installing the Look and Feel: {@code flatlaf.uiScale} is read when
     * {@code UIScale} initialises, i.e. once, and cannot be changed live; that is why
     * changing it in the F12 popup relaunches the process.
     *
     * <p><b>The property is always set, even when it is 1.</b> With system scaling off (the
     * two lines above) and no explicit value, FlatLaf would derive the factor from the size
     * of the system font. Under the jpackage launcher there is a known state in which AWT
     * re-reads the fonts at the real DPI and a 12 becomes 18 (see WindowsDpiAwareness), and
     * there the derivation would give 1.5. An explicit value makes the behaviour
     * independent of which font AWT took, and when.
     */
    private static void applyUiScale(AmigaConfig cfg) {
        System.setProperty("flatlaf.uiScale", cfg.uiScaleValue());
    }

    /**
     * Installs FlatLaf (dark theme) on the whole Swing interface.
     *
     * <p>Dark because AmiJa's dialogs already paint their own dark palette by hand
     * (ConfigDialog, JoystickKeysDialog, AssetDownloadDialog, ShaderParamsDialog): with the
     * system Look and Feel only the components it drew stayed light (combo boxes, spinners,
     * checkboxes, scroll bars, tooltips, JOptionPane) inside dark windows.
     *
     * <p>The reason it is FlatLaf and not any theme is its <em>user scaling</em>: it scales
     * the interface by a factor that may be fractional, independently of Java's HiDPI
     * scaling, which is off here because the emulated window must get exact physical pixels.
     * See {@link #applyUiScale}.
     *
     * <p>If it cannot be installed for any reason, the system Look and Feel is used: the
     * interface stays usable, only not scaled.
     */
    private static void applyLookAndFeel() {
        try {
            UIManager.setLookAndFeel(new FlatDarkLaf());
            return;
        } catch (UnsupportedLookAndFeelException e) {
            System.err.println("[UI] FlatLaf non disponibile, ripiego sul Look and Feel "
                    + "di sistema (l'interfaccia non sara' scalata): " + e.getMessage());
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException | UnsupportedLookAndFeelException e) {
            System.err.println("[UI] Look and Feel di sistema non disponibile: " + e.getMessage());
        }
    }

    // =========================================================================
    // Life cycle
    // =========================================================================

    public void start() {
        if (VERBOSE) {
            System.out.println("========================================");
            System.out.println("      AmiJa Emulator - Boot             ");
            System.out.println("========================================");
        }

        config = AmigaConfig.load();
        Messages.setLanguage(config.language()); // before creating any UI
        // The state saved when there was a single slot goes to Slot 1: once, here.
        SaveStateManager.migrateLegacySlot();
        boolean overscan = config.overscanEnabled();
        int fw = config.videoMode().displayWidth(overscan);
        int fh = config.videoMode().displayHeight(overscan);
        display = new AmigaDisplay(fw, fh, config.windowMode(), config.renderer(),
                config.integerScaling(), config.monitorIndex());
        frame = display.getFrame();
        inputComponent = display.getInputComponent();
        // A disk dropped on a drive updates the config in memory, so the F12 popup shows the
        // path in that drive (and saves it if the user confirms with OK).
        display.setDiskDropListener(this::rememberDroppedDisk);
        display.setShaderPreset(resolveShaderPath(config.shader()));
        installConfigHotkey();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdownRemotePlay();
            // The router port must really be withdrawn, not just ordered to: the thread that
            // withdraws it is a daemon and would be killed halfway at JVM exit (section 7.1).
            awaitRouterPortClosed();
            shutdownMachine();
            joystickService.shutdown(); // terminate GLFW (only when the process exits)
        }, "amiga-shutdown"));

        RemotePlaySettings remotePlay = readRemotePlaySettings();
        if (remotePlay.mode() == RemotePlaySettings.Mode.GUEST) {
            // The guest does NOT emulate: no Kickstart, no ROM, no MasterClock (section 3).
            // The window already exists; from here on the network fills it instead of Denise.
            joinRemoteHost(remotePlay);
            return;
        }

        if (validateRom(config)) {
            bootMachine();
            startHosting(remotePlay);
        } else {
            // ROM missing or wrong at the first start: open the popup so the user can set
            // the Kickstart path and the other parameters.
            SwingUtilities.invokeLater(this::openConfig);
        }
    }

    // =========================================================================
    // Remote play (docs/remote-play-plan.md section 12)
    // =========================================================================

    private RemotePlaySettings readRemotePlaySettings() {
        try {
            return RemotePlaySettings.fromSystemProperties();
        } catch (IllegalArgumentException e) {
            System.err.println("[NET] configurazione del gioco a distanza non valida: "
                    + e.getMessage());
            return RemotePlaySettings.off();
        }
    }

    /**
     * Opens the host-side session and attaches the video to {@code VirtualCRT}.
     *
     * <p>A failure here is <b>not</b> fatal: the machine is already running and must stay
     * playable locally. What went wrong is reported and the run goes on without a guest.
     */
    private void startHosting(RemotePlaySettings settings) {
        if (settings.mode() != RemotePlaySettings.Mode.HOST || virtualCRT == null) {
            return;
        }
        try {
            remotePlayHost = new RemotePlayHost(settings.port(), settings.bindAddress(),
                    settings.joinCode(), VirtualCRT.RAW_WIDTH, VirtualCRT.RAW_HEIGHT,
                    NetImpairment.fromSystemProperties(),
                    settings.guestGamePort(), settings.guestKeyboard(),
                    settings.audioFormat(), config.remotePlayLanOnly());
            remotePlaySettings = settings;
            remotePlayHost.start();
            attachRemoteInput();
            attachRemotePlayLed();
            openRouterPort(remotePlayHost.port());
            virtualCRT.setRemotePlayHost(remotePlayHost);
        } catch (IOException e) {
            System.err.println("[NET] impossibile ospitare sulla porta " + settings.port()
                    + ": " + e.getMessage());
            remotePlayHost = null;
        }
    }

    /**
     * Asks the router to forward the port, if the user wants it (section 7.1).
     *
     * <p>Not with <b>local network only</b> on, and not as a formality: that option says
     * "nobody gets in from outside", and whoever came in through the newly opened port would
     * be refused by the host an instant later. It would be a port open on the home router
     * for a connection that cannot work, the opposite of what both options promise.
     */
    private void openRouterPort(int port) {
        closeRouterPort();
        if (!config.remotePlayUpnp() || config.remotePlayLanOnly()) {
            return;
        }
        portMapper = new PortMapper(port);
        portMapper.open();
    }

    /** Withdraws the mapping. Called by every path that stops hosting. */
    private void closeRouterPort() {
        PortMapper mapper = portMapper;
        if (mapper == null) {
            return;
        }
        portMapper = null;
        // On its own thread: the removal talks to the router and can take seconds, and the
        // one closing the listener is almost always the EDT inside the popup.
        routerCloser = new Thread(mapper::close, "amija-remoteplay-upnp-close");
        routerCloser.setDaemon(true);
        routerCloser.start();
    }

    /**
     * Waits until the mapping has really been withdrawn. <b>Only for the shutdown hook.</b>
     *
     * <p>A daemon thread talking to the router is killed when the JVM exits, and the port
     * would stay open: this gives it time to finish. Not much, though: if the router does
     * not answer, a clean exit is better than a close that looks stuck, and the port closes
     * by itself when the lease expires, which is why there is a lease.
     */
    private void awaitRouterPortClosed() {
        Thread closer = routerCloser;
        if (closer == null) {
            return;
        }
        try {
            closer.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Connects to a host and draws what arrives.
     *
     * <p>The frame that arrives is the <b>raw</b> 912x312 raster, exactly what
     * {@code AmigaDisplay.renderFrame} receives from the emulation: crop, integer scaling,
     * overscan, shaders and fullscreen stay choices of this machine and work without
     * knowing anything about it.
     */
    private void joinRemoteHost(RemotePlaySettings settings) {
        try {
            remotePlayGuest = RemotePlayGuest.connect(settings.address(), settings.port(),
                    settings.joinCode(), NetImpairment.fromSystemProperties(),
                    guestFrameSink());
        } catch (IOException e) {
            String message = Messages.t("msg.remoteplay.joinfailed",
                    settings.address() + ":" + settings.port(), joinFailureCause(e));
            remotePlayLastError = message;
            reportRemotePlayProblem(message);
            // Our own machine is better than an empty window: from there the user can retry
            // from the popup, which is also where what went wrong can now be read.
            if (validateRom(config)) {
                bootMachine();
            }
            return;
        }
        remotePlaySettings = settings;
        guestMode = true;
        installGuestInput();
        attachRemotePlayLed();
        startGuestWatchdog(remotePlayGuest);
    }

    /**
     * Where the frames from the host go: into the <b>same</b> {@code renderFrame} that
     * receives the raster from the emulation.
     *
     * <p>That is all it takes for crop, integer scaling, overscan, shaders, LEDs, fullscreen
     * and screenshots to work on the guest without one line of that code knowing about the
     * network.
     */
    private VideoReceiver.FrameSink guestFrameSink() {
        return (pixels, w, h, info, frameId) -> display.renderFrame(pixels, w, h, info);
    }

    /**
     * Watches the session and, if it ends by itself, tells the user.
     *
     * <p>A light going off does not warn someone watching the game, and the last frozen image
     * looks too much like a hung emulator: when the connection drops a message must appear,
     * with the reason. If we closed the session ourselves
     * ({@link RemotePlayGuest.EndCause#LOCAL}) there is nothing to announce: the user has
     * just pressed the button that closes it.
     */
    private void startGuestWatchdog(RemotePlayGuest guest) {
        Thread watchdog = new Thread(() -> {
            while (guest.isAlive()) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (guest.endCause() == RemotePlayGuest.EndCause.LOCAL) {
                return;
            }
            SwingUtilities.invokeLater(() -> onRemoteSessionLost(guest));
        }, "amija-remoteplay-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** On the EDT: the session dropped by itself. Say why, and bring the machine back up. */
    private void onRemoteSessionLost(RemotePlayGuest guest) {
        if (remotePlayGuest != guest) {
            return;   // already replaced or closed meanwhile: nothing to announce
        }
        String message = Messages.t("msg.remoteplay.lost", lostCause(guest));
        boolean wasGuestMode = guestMode;
        // Must be read BEFORE opening our own message, which is modal too.
        boolean popupOpen = modalDialogShowing();
        shutdownGuestSession();
        remotePlayLastError = message;
        reportRemotePlayProblem(message);
        if (wasGuestMode && !popupOpen) {
            // Without a machine the window would be dead. With the popup open nothing is
            // rebuilt from here: settleRemotePlayAfterConfig does it when it closes, outside
            // the modal dialog, which is the rule throughout.
            leaveGuestMode();
        }
    }

    /** The reason for the drop, in the user's language. */
    private String lostCause(RemotePlayGuest guest) {
        String detail = guest.endDetail();
        return switch (guest.endCause()) {
            case HOST_CLOSED -> Messages.t("msg.remoteplay.lost.hostclosed");
            case TIMEOUT -> Messages.t("msg.remoteplay.lost.timeout");
            default -> detail == null || detail.isBlank()
                    ? Messages.t("msg.remoteplay.lost.link")
                    : Messages.t("msg.remoteplay.lost.error", detail);
        };
    }

    /**
     * Why a connection failed, in the user's language.
     *
     * <p>"Unable to connect" alone sends to check the router even someone who merely
     * mistyped the code. The four REJECT reasons are known to the host, which sends them on
     * purpose (section 3): throwing them away here would waste the one piece of information
     * that tells a network problem from a typo.
     */
    private String joinFailureCause(IOException e) {
        if (e instanceof Handshake.RejectedException rejected) {
            return switch (rejected.reason()) {
                case RemotePlayProtocol.REJECT_AUTH -> Messages.t("msg.remoteplay.reject.auth");
                case RemotePlayProtocol.REJECT_BUSY -> Messages.t("msg.remoteplay.reject.busy");
                case RemotePlayProtocol.REJECT_PROTOCOL ->
                        Messages.t("msg.remoteplay.reject.protocol");
                case RemotePlayProtocol.REJECT_SHUTDOWN ->
                        Messages.t("msg.remoteplay.reject.shutdown");
                case RemotePlayProtocol.REJECT_LAN_ONLY ->
                        Messages.t("msg.remoteplay.reject.lanonly");
                default -> String.valueOf(rejected.getMessage());
            };
        }
        if (e instanceof java.net.UnknownHostException) {
            return Messages.t("msg.remoteplay.cause.unknownhost");
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return Messages.t("msg.remoteplay.cause.timeout");
        }
        if (e instanceof java.net.ConnectException) {
            return Messages.t("msg.remoteplay.cause.refused");
        }
        return String.valueOf(e.getMessage());
    }

    /**
     * Shows a remote play failure, from whatever thread it was found on.
     *
     * <p>The parent window is not always the same, and getting it wrong is costly: the F12
     * popup is <b>modal</b>, so a message parented to the game window while it is open would
     * be blocked by Swing (it would appear and could not be closed). It is therefore
     * parented to the active window, which is the popup when there is one and the game
     * window otherwise.
     */
    private void reportRemotePlayProblem(String message) {
        System.err.println("[NET] " + message);
        Runnable show = () -> JOptionPane.showMessageDialog(activeWindow(), message,
                Messages.t("error.title"), JOptionPane.ERROR_MESSAGE);
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            SwingUtilities.invokeLater(show);
        }
    }

    /** The window to parent a message to so it is not blocked by a modal dialog. */
    private java.awt.Window activeWindow() {
        java.awt.Window active = java.awt.KeyboardFocusManager
                .getCurrentKeyboardFocusManager().getActiveWindow();
        if (active != null && active.isShowing()) {
            return active;
        }
        // Application in the background: no window is active, but if a modal dialog is open
        // the message must still attach to it.
        java.awt.Window modal = modalDialog();
        return modal != null ? modal : frame;
    }

    private boolean modalDialogShowing() {
        return modalDialog() != null;
    }

    private java.awt.Window modalDialog() {
        java.awt.Window found = null;
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w instanceof java.awt.Dialog d && d.isModal() && d.isShowing()) {
                found = d;   // the last one opened is the one that blocks the others
            }
        }
        return found;
    }

    /**
     * Guest side: attaches the real input adapters to a {@link RemoteInputSender} instead of
     * a machine.
     *
     * <p>They are the <b>same</b> adapters that drive the emulation (with the key
     * translation, the pointer capture, the keyboard joystick map inside) and notice
     * nothing: they write to {@code GamePortControls} and {@code AmigaKeyTarget}, and behind
     * those there is the network instead of the registers. Play is exactly as local, mouse
     * capture included.
     *
     * <p><b>Which port, and what is plugged into it, are two different decisions.</b> The
     * host assigns the port (it is its machine, and it must be able to keep the one it plays
     * on), but the guest chooses the device, in the drop-down of <i>that</i> port of its own
     * F12 popup. That is what makes the normal case sensible: whoever connects plays with
     * their own joystick, or with their own keys if the joystick is emulated on the
     * keyboard. The keys are theirs, read from their {@code amiga.properties}; the host's
     * have nothing to do with it and never cross the network. The actual wiring is in
     * {@link #applyGuestPortAssignment()}, which every confirmation of the popup calls too,
     * so changing device in the middle of a game works.
     *
     * <p>F12 does not get this far: {@code installConfigHotkey} consumes it first, so it
     * opens the popup of <b>this</b> machine and does not touch the host's.
     */
    private void installGuestInput() {
        remoteInputSender = new RemoteInputSender();
        attachGuestInput();
    }

    /**
     * Attaches the guest's adapters to the current input component <b>without</b> touching
     * the {@link RemoteInputSender}.
     *
     * <p>Separate from {@link #installGuestInput()} for a precise reason: recreating the
     * sender resets the <b>cumulative mouse counters</b>, and the host applies their
     * difference. Since the session generation does not change, the host cannot notice that
     * the origin changed and would compute a delta as large as all the movement made so far:
     * the pointer on the Amiga would jump. When only the window changes, which is why this
     * method exists, the counters must carry on.
     */
    private void attachGuestInput() {
        remotePlayGuest.setInputSource(remoteInputSender::snapshot);

        joystickAdapter = new AmigaJoystickKeyAdapter();
        keyAdapter = new AmigaKeyAdapter(remoteInputSender, frame);
        mouseAdapter = new AmigaMouseAdapter(remoteInputSender, frame,
                display != null ? display.getVideoScale() : 1);
        applyGuestPortAssignment();

        if (inputComponent != null) {
            // Same order as the emulation: the joystick first, because it consumes the keys
            // mapped to directions and fire, which must not also reach the Amiga keyboard.
            inputComponent.addKeyListener(joystickAdapter);
            inputComponent.addKeyListener(keyAdapter);
            inputComponent.addMouseListener(mouseAdapter);
            inputComponent.addMouseMotionListener(mouseAdapter);
            inputComponent.setFocusTraversalKeysEnabled(false);
            inputComponent.requestFocusInWindow();
        }
    }

    /**
     * The "cold restart" of a guest: the window is rebuilt, and that is all.
     *
     * <p>{@link #coldRestart()} does not fit here: it ends in {@code bootMachine()}, which
     * would build a local Amiga under the video arriving from the host. Of the settings that
     * require it, only the window's concern a guest (overscan, zoom, window mode, monitor,
     * renderer), and for those this is exactly what is needed: a new display, the adapters
     * reattached to the new component (the old one was thrown away with the window), the
     * network light reconnected because its sources live on the display.
     */
    private void recreateGuestDisplay() {
        // Before throwing the window away: the adapters must be stopped, not just forgotten;
        // they contain a keyboard poller and a focus listener.
        detachInputAdapters();
        recreateDisplay();
        attachGuestInput();
        attachRemotePlayLed();
    }

    /**
     * Connects the guest's controls to the machine: the game port the host assigned to it
     * and, if granted, the keyboard.
     *
     * <p>Must be redone at every cold restart, because the previous game port and keyboard
     * no longer exist. If there is no machine yet, it only detaches: the guest keeps seeing
     * the video without touching anything.
     */
    private void attachRemoteInput() {
        if (remotePlayHost == null) return;
        // Port 0 dell'utente = gamePort1 (mouse/JOY0DAT), Port 1 = gamePort2 (joystick).
        GamePort target = remotePlaySettings.guestGamePort() == 0 ? gamePort1 : gamePort2;
        if (target == null) {
            remotePlayHost.setInputTarget(null);
            return;
        }
        remotePlayHost.setInputTarget(new RemoteInputApplier(target,
                remotePlaySettings.guestKeyboard() ? keyboard : null,
                remotePlaySettings.guestAllowReset()));
        // That port now belongs to the guest: the local adapters pointing at it must be
        // removed, or the host's mouse and joystick would keep writing to it.
        applyPortAssignments();

        // The audio must be taken from the new Paula: the old one no longer exists.
        if (paula != null) {
            RemotePlayHost host = remotePlayHost;
            paula.setPcmTap(remotePlaySettings.audioFormat() == 0
                    ? null : host::submitAudio);
        }
    }

    // =========================================================================
    // RemotePlayControl: what the F12 popup controls (section 9)
    // =========================================================================

    @Override
    public boolean isHosting() {
        return remotePlayHost != null;
    }

    @Override
    public boolean hasGuest() {
        return remotePlayHost != null && remotePlayHost.hasGuest();
    }

    @Override
    public boolean isGuest() {
        RemotePlayGuest guest = remotePlayGuest;
        return guest != null && guest.isAlive();
    }

    @Override
    public int remoteGamePort() {
        RemotePlayGuest guest = remotePlayGuest;
        if (guest != null) {
            // As a guest what counts is what the host said in the WELCOME, not the own
            // configuration: that tells a host which port it would give away, which does
            // not apply here. Mixing them would show the user the wrong drop-down.
            return guest.gamePort();
        }
        return hostedGuestPort();
    }

    @Override
    public boolean isJoining() {
        return joining;
    }

    @Override
    public String joinCode() {
        return remotePlayHost == null ? null : remotePlayHost.joinCode();
    }

    @Override
    public String lanAddress() {
        if (remotePlayHost == null) return null;
        return localAddress() + ":" + remotePlayHost.port();
    }

    @Override
    public String internetAddress() {
        // The router says it, it is not guessed: a made-up address sends the user looking for
        // the problem where it is not. Until UPnP has answered, say nothing.
        PortMapper mapper = portMapper;
        return mapper == null || mapper.state() != PortMapper.State.MAPPED
                ? null : mapper.externalAddress();
    }

    /**
     * How far the port opening on the router has got, as a readable line.
     *
     * <p>{@code null} when there is nothing to say, i.e. when UPnP was not requested: a
     * "not requested" line would be noise in a popup that already has many.
     */
    @Override
    public String routerStatus() {
        PortMapper mapper = portMapper;
        if (mapper == null) {
            // With local network only on, the port is deliberately not requested, and it is
            // worth saying: it is the difference between "UPnP failed" and "I did not ask".
            return isHosting() && config.remotePlayUpnp() && config.remotePlayLanOnly()
                    ? Messages.t("msg.remoteplay.upnp.lanonly") : null;
        }
        return switch (mapper.state()) {
            case SEARCHING -> Messages.t("msg.remoteplay.upnp.searching");
            case MAPPED -> Messages.t("msg.remoteplay.upnp.mapped", mapper.externalAddress());
            case NO_GATEWAY -> Messages.t("msg.remoteplay.upnp.nogateway");
            case REFUSED -> Messages.t("msg.remoteplay.upnp.refused",
                    String.valueOf(mapper.detail()));
            case CARRIER_NAT -> Messages.t("msg.remoteplay.upnp.cgnat",
                    String.valueOf(mapper.externalIp()));
            case OFF -> null;
        };
    }

    @Override
    public String status() {
        if (joining) {
            return Messages.t("msg.remoteplay.connecting", joiningAddress);
        }
        RemotePlayGuest guest = remotePlayGuest;
        if (guest != null) {
            if (!guest.isAlive()) return Messages.t("msg.remoteplay.disconnected");
            if (guest.hostPaused()) return Messages.t("msg.remoteplay.paused");
            var receiver = guest.receiver();
            return Messages.t("msg.remoteplay.guestconnected",
                    receiver.framesDelivered(), receiver.droppedIncomplete());
        }
        if (remotePlayHost != null) {
            if (!remotePlayHost.hasGuest()) return Messages.t("msg.remoteplay.waiting");
            var sender = remotePlayHost.videoSender();
            long frames = sender == null ? 0 : sender.framesSent();
            return Messages.t("msg.remoteplay.hostconnected",
                    frames, remotePlayHost.framesDroppedBusy());
        }
        // The negative outcome stays written here even after the user has closed the
        // message: the popup is the only place where it can be read again.
        String error = remotePlayLastError;
        return error != null ? error : Messages.t("msg.remoteplay.off");
    }

    @Override
    public String startHosting() {
        if (remotePlayHost != null) return null;
        try {
            remotePlaySettings = new RemotePlaySettings(RemotePlaySettings.Mode.HOST, null,
                    config.remotePlayPort(), null, config.remotePlayBind(),
                    config.remotePlayGuestPort(), config.remotePlayGuestKeyboard(),
                    config.remotePlayGuestAllowReset(), audioFormatFromConfig());
            remotePlayHost = new RemotePlayHost(remotePlaySettings.port(),
                    remotePlaySettings.bindAddress(), null,
                    VirtualCRT.RAW_WIDTH, VirtualCRT.RAW_HEIGHT,
                    NetImpairment.fromSystemProperties(),
                    remotePlaySettings.guestGamePort(), remotePlaySettings.guestKeyboard(),
                    remotePlaySettings.audioFormat(), config.remotePlayLanOnly());
            remotePlayHost.start();
            attachRemoteInput();
            openRouterPort(remotePlayHost.port());
            if (virtualCRT != null) virtualCRT.setRemotePlayHost(remotePlayHost);
            return null;
        } catch (IOException e) {
            remotePlayHost = null;
            return Messages.t("msg.remoteplay.hostfailed", config.remotePlayPort(),
                    e.getMessage());
        }
    }

    @Override
    public void stopHosting() {
        shutdownHostSession();
    }

    @Override
    public String requestJoin(String address, String code) {
        if (joining || isGuest()) {
            return null;   // already in progress or already connected: the popup realigns by itself
        }
        if (address == null || address.isBlank()) {
            return Messages.t("msg.remoteplay.noaddress");
        }
        if (!JoinCode.isWellFormed(code)) {
            return Messages.t("msg.remoteplay.badcode");
        }
        final String host = address.trim();
        final String joinCode = code.trim();
        synchronized (this) {
            joinCancelled = false;
        }
        joiningAddress = host;
        remotePlayLastError = null;
        joining = true;
        Thread connect = new Thread(() -> connectToHost(host, joinCode),
                "amija-remoteplay-connect");
        connect.setDaemon(true);
        connect.start();
        return null;
    }

    /**
     * The connection attempt, on its own thread.
     *
     * <p>Off the EDT because the handshake can take seconds (TCP connection, PBKDF2 with 120k
     * iterations, up to ten seconds of waiting on each read), and the EDT is exactly the
     * thread that during those seconds must keep drawing the popup and saying "connecting".
     *
     * <p><b>The local machine is not touched.</b> It stays mounted and paused: if the
     * connection fails there is nothing to rebuild, and if it succeeds the video arrives
     * while the popup is still open, the most direct way to show that it works. Unmounting
     * it happens only with the user's OK.
     */
    private void connectToHost(String address, String joinCode) {
        int colon = address.lastIndexOf(':');
        String hostPart = colon > 0 ? address.substring(0, colon) : address;
        int port = colon > 0 ? parsePortOrDefault(address.substring(colon + 1))
                             : config.remotePlayPort();
        RemotePlaySettings settings = new RemotePlaySettings(
                RemotePlaySettings.Mode.GUEST, hostPart, port, joinCode,
                config.remotePlayBind(), config.remotePlayGuestPort(),
                config.remotePlayGuestKeyboard(), config.remotePlayGuestAllowReset(),
                audioFormatFromConfig());

        RemotePlayGuest guest = null;
        String failure = null;
        try {
            guest = RemotePlayGuest.connect(hostPart, port, joinCode,
                    NetImpairment.fromSystemProperties(), guestFrameSink());
        } catch (IOException e) {
            failure = Messages.t("msg.remoteplay.joinfailed", address, joinFailureCause(e));
        }

        synchronized (this) {
            if (guest != null && joinCancelled) {
                // The user cancelled while we were in flight (popup closed without OK, or
                // "Disconnect" pressed at once): the session just opened is closed and that is
                // the end of it. Otherwise a session would stay alive that nobody can see or
                // stop any more.
                guest.close();
                guest = null;
                failure = null;
            }
            if (guest != null) {
                // The session is published LAST: it is the volatile field the EDT
                // synchronises on, so everything it will read after seeing it set, starting
                // with the session parameters, must already be written.
                remotePlaySettings = settings;
                config.setRemotePlayLastHost(address);
                remotePlayGuest = guest;
            }
            remotePlayLastError = failure;
            joining = false;
        }

        if (guest != null) {
            attachRemotePlayLed();
            startGuestWatchdog(guest);
        } else if (failure != null) {
            reportRemotePlayProblem(failure);
        }
    }

    @Override
    public boolean requestLeave() {
        if (!guestMode) {
            // The local machine was never unmounted: closing the session is enough, and the
            // popup can stay open as it was.
            cancelGuestSession();
            return false;
        }
        pendingLeave = true;
        return true;
    }

    /** Closes the session to the host, including one still being opened. */
    private void cancelGuestSession() {
        synchronized (this) {
            joinCancelled = true;
        }
        shutdownGuestSession();
        remotePlayLastError = null;
    }

    /**
     * Hooks the "NET" light of the overscan to the state of the session.
     *
     * <p>Must be redone at every re-creation of the display, because the sources live on the
     * display and the previous one was thrown away.
     */
    private void attachRemotePlayLed() {
        if (display == null) return;
        display.setRemotePlayLedSource(
                () -> isHosting() || isGuest() || joining,
                () -> {
                    if (hasGuest()) return true;
                    // A single read of the field: between "there is a session" and "it is
                    // paused" it may have been closed, and a second read would be null.
                    RemotePlayGuest guest = remotePlayGuest;
                    return guest != null && guest.isAlive() && !guest.hostPaused();
                });
    }

    /** Audio format from the configuration, in the form the protocol uses. */
    private int audioFormatFromConfig() {
        return switch (config.remotePlayAudioMode()) {
            case "pcm16", "pcm" -> AudioBlockHeader.FORMAT_PCM16;
            case "off" -> 0;
            default -> AudioBlockHeader.FORMAT_ADPCM;
        };
    }

    /**
     * The address of this machine on the local network.
     *
     * <p>The trick of {@code connect} on an external address (section 7.2): it sends not a
     * single byte (UDP establishes nothing) but forces the system to choose the interface it
     * would go out through, which is the right one to show.
     * {@code InetAddress.getLocalHost()} on a machine with several adapters often returns
     * the wrong one.
     */
    private String localAddress() {
        try (java.net.DatagramSocket probe = new java.net.DatagramSocket()) {
            probe.connect(java.net.InetAddress.getByName("8.8.8.8"), 53);
            return probe.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /**
     * Performs the transition to guest (or the return to machine) decided in the popup.
     *
     * <p>Outside the dialog, and not out of pedantry: a guest emulates nothing, so joining
     * means <b>unmounting the machine</b>, and doing that on the EDT inside a modal window
     * while the emulation thread runs is a way to collect intermittent failures.
     */
    private boolean applyPendingRemotePlayTransition() {
        if (pendingLeave) {
            pendingLeave = false;
            leaveGuestMode();
            return true;
        }
        if (guestMode || !isGuest()) {
            // Nothing to unmount: either it already is, or the session dropped between the OK
            // and now, and then the local machine, never unmounted, must restart.
            return false;
        }
        // The session is already open and already drawing (connectToHost). What is left is
        // the one thing that could not be done from inside the popup: unmounting the machine.
        shutdownMachine();
        guestMode = true;
        installGuestInput();
        attachRemotePlayLed();
        return true;
    }

    /**
     * Becomes a normal machine again after having been a guest.
     *
     * <p>{@link #shutdownMachine()} must be called even if there is no machine: the input
     * adapter fields hold the guest's adapters, which are attached to the input component
     * and must be detached. Otherwise the boot would add a second set on top of the first
     * and every key would be delivered twice.
     */
    private void leaveGuestMode() {
        shutdownGuestSession();
        shutdownMachine();
        guestMode = false;
        if (validateRom(config)) {
            bootMachine();
        }
    }

    /**
     * Realigns machine and session after the popup closes, for every case that is not the
     * confirmed transition.
     *
     * @return {@code true} if it has already brought the machine back up and the caller has
     *         nothing more to do
     */
    private boolean settleRemotePlayAfterConfig(ConfigDialog.Result result) {
        if (result == ConfigDialog.Result.REMOTE_PLAY_TRANSITION) {
            return false;   // handled by the dedicated branch of the switch
        }
        if (guestMode) {
            if (!isGuest()) {
                // The session dropped while the popup was open: the user has already seen the
                // warning (onRemoteSessionLost), but the machine must be rebuilt here,
                // outside the modal dialog.
                leaveGuestMode();
                return true;
            }
            return false;
        }
        if (joining || isGuest()) {
            // Connected from the popup but not confirmed with OK. Confirming is the act that
            // unmounts the local machine: without it the session must be closed, or local
            // emulation and the host's video would share the same screen.
            cancelGuestSession();
        }
        return false;
    }

    private int parsePortOrDefault(String text) {
        try {
            int p = Integer.parseInt(text.trim());
            return p > 0 && p <= 65535 ? p : config.remotePlayPort();
        } catch (NumberFormatException e) {
            return config.remotePlayPort();
        }
    }

    /** Closes the remote play session, whichever side it is held from. */
    private void shutdownRemotePlay() {
        shutdownHostSession();
        shutdownGuestSession();
    }

    /** Stops hosting and detaches video and audio from the network. */
    private void shutdownHostSession() {
        closeRouterPort();
        if (remotePlayHost == null) return;
        if (virtualCRT != null) {
            virtualCRT.setRemotePlayHost(null);
        }
        if (paula != null) {
            paula.setPcmTap(null);
        }
        remotePlayHost.close();
        remotePlayHost = null;
        // The port given to the guest goes back to the machine's owner, with the device its
        // configuration assigns: otherwise it would stay empty, and whoever has just stopped
        // hosting would find a dead game port.
        applyPortAssignments();
    }

    /**
     * Closes the session to the host, and <b>only</b> that: the two roles are switched off
     * separately because cancelling a connection must not stop hosting.
     */
    private void shutdownGuestSession() {
        RemotePlayGuest guest = remotePlayGuest;
        if (guest == null) return;
        // The field is cleared BEFORE closing: the watcher compares the session it saw die
        // with the current one, and must find it already replaced to know that we closed it
        // and there is nothing to announce.
        remotePlayGuest = null;
        guest.close();
    }

    /** Registers the global F12 hotkey (opens the configuration popup). */
    private void installConfigHotkey() {
        if (inputComponent == null) return;
        // Added BEFORE the machine's adapters: it consumes F12 before it reaches the Amiga
        // keyboard (where it would be HELP). It goes on the input component (the GL canvas
        // in OpenGL): the heavyweight canvas's key events do not bubble up to the frame.
        inputComponent.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_F12) {
                    e.consume();
                    openConfig();
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                // The release must be consumed too: otherwise the Amiga keyboard would get
                // only the key-up of HELP (F12 is mapped there) without the press.
                if (e.getKeyCode() == KeyEvent.VK_F12) {
                    e.consume();
                }
            }
        });
    }

    // =========================================================================
    // Configuration popup (F12)
    // =========================================================================

    /**
     * Pauses the emulation, releases the mouse and shows the popup. Depending on the user's
     * choice it saves/restarts, cold restarts, performs a soft reset or resumes; finally it
     * captures the mouse again.
     */
    private void openConfig() {
        boolean wasRunning = clock != null && clock.isRunning();
        if (clock != null) clock.pause();
        // The popup is modal and pauses: without being told, the guest would see a frozen
        // screen and think the connection was lost (section 9).
        if (remotePlayHost != null) remotePlayHost.notifyPaused(true);
        if (mouseAdapter != null) mouseAdapter.release();
        boolean exclusiveFullScreenSuspended = display != null && display.suspendExclusiveFullScreen();

        // State of the floppies before the change: used to compute the diff to apply live
        // (insert/replace/eject) when the user confirms with OK.
        String[] oldDisks = new String[4];
        boolean[] oldEnabled = new boolean[4];
        boolean[] oldReadOnly = new boolean[4];
        for (int i = 0; i < 4; i++) {
            oldDisks[i] = config.driveDisk(i);
            oldEnabled[i] = config.driveEnabled(i);
            oldReadOnly[i] = config.driveReadOnly(i);
        }
        boolean oldOverscan = config.overscanEnabled();
        int oldIntegerScaling = config.integerScaling();
        // Interface scale: the only setting left that requires relaunching the process,
        // because flatlaf.uiScale is read when the LAF initialises.
        String oldUiScale = config.uiScaleValue();
        AmigaWindowMode oldWindowMode = config.windowMode();
        int oldMonitorIndex = config.monitorIndex();
        VideoMode oldVideoMode = config.videoMode();
        Chipset oldChipset = config.chipset();
        io.github.zolfrik.amija.cpu.CpuModel oldCpuModel = config.cpuModel();
        RendererType oldRenderer = config.renderer();
        String oldShader = config.shader();
        // Machine parameters that take effect only at construction: if they change, the
        // emulator cold restarts by itself. It matters above all in Auto mode, where they are
        // changed not by the user but by ticking a hard disk or WHDLoad: expecting the user
        // to press "Restart" would be a trap.
        String oldKickstart = config.kickstartPath();
        int oldChipRam = config.chipRamKb();
        int oldSlowRam = config.slowRamKb();
        int oldFastRam = config.fastRamKb();

        boolean oldAuto = config.autoEnabled();

        ConfigDialog.Result result = ConfigDialog.show(frame, config,
                this::takeScreenshotFile, this::updateShaderParam,
                this::saveState, this::loadState, this, activeHardware);

        // While the popup was open a remote play session may have opened (the "Connect"
        // button acts at once) or dropped. Mounting and unmounting machines belongs here,
        // not inside a modal dialog.
        if (settleRemotePlayAfterConfig(result)) {
            if (exclusiveFullScreenSuspended && display != null) {
                display.resumeExclusiveFullScreen();
            }
            recaptureMouse();
            return;
        }
        boolean displayRecreated = false;
        switch (result) {
            case OK -> {
                // OK applies input/floppies live; the display and machine settings require
                // re-creating the window and components, hence a cold restart.
                saveConfig();
                if (!oldUiScale.equals(config.uiScaleValue())) {
                    relaunchApplication(); // does not return if the relaunch succeeds
                }
                applyRuntimeInputConfig();
                boolean windowChanged =
                        displayConfigChanged(oldOverscan, oldIntegerScaling, oldWindowMode,
                                oldMonitorIndex)
                        || oldVideoMode != config.videoMode()
                        || oldRenderer != config.renderer();
                // Outside Expert the machine parameters are not chosen by the user: the
                // emulator derives them from the media list. Changing them at once would mean
                // switching the Amiga off and on because a drive was added, i.e. throwing
                // away the game in progress for a change the user thought harmless. They are
                // therefore written to the configuration and take effect at the next start;
                // the list marks them with an asterisk so it is clear they are waiting. This
                // holds while the mode stays the same: switching Expert on or off redefines
                // the machine from scratch, and there the restart is the point.
                boolean deferHardware = oldAuto && config.autoEnabled();
                boolean needsColdRestart = windowChanged
                        || (!deferHardware
                            && (oldChipset != config.chipset()
                                || oldCpuModel != config.cpuModel()
                                || !oldKickstart.equals(config.kickstartPath())
                                || oldChipRam != config.chipRamKb()
                                || oldSlowRam != config.slowRamKb()
                                || oldFastRam != config.fastRamKb()));
                if (guestMode) {
                    // A guest has no machine to restart: of the parameters above only the
                    // window's concern it, and that is the only thing to redo. The rest
                    // (Kickstart, memory, chipset) describes a machine that does not exist
                    // here, and restarting it to apply them would mean building one while
                    // watching someone else's.
                    if (windowChanged) {
                        recreateGuestDisplay();
                        displayRecreated = true;
                    } else if (config.renderer() == RendererType.OPENGL
                            && !oldShader.equals(config.shader())) {
                        display.setShaderPreset(resolveShaderPath(config.shader()));
                    }
                } else if (needsColdRestart) {
                    // No JVM relaunch: scaling is applied by AmigaDisplay, not by
                    // sun.java2d.uiScale, so integer scaling and renderer are applied by
                    // re-creating window and machine, which stay in the same process (faster).
                    coldRestart();
                    displayRecreated = true;
                } else {
                    // No cold restart: if only the shader changed (OpenGL renderer), apply it
                    // live to the GL surface.
                    if (config.renderer() == RendererType.OPENGL
                            && !oldShader.equals(config.shader())) {
                        display.setShaderPreset(resolveShaderPath(config.shader()));
                    }
                    if (wasRunning) {
                        clock.resume();
                        applyFloppyChanges(oldDisks, oldEnabled, oldReadOnly, -1, deferHardware);
                    } else {
                        resumeOrStart();
                    }
                }
            }
            case RESTART -> {
                saveConfig();
                // The interface scale is the only setting that requires relaunching the
                // process: flatlaf.uiScale is read when the Look and Feel initialises and
                // cannot be changed live. Integer scaling instead is applied by AmigaDisplay
                // at presentation, so a cold restart is enough: same process, much faster.
                if (!oldUiScale.equals(config.uiScaleValue())) {
                    relaunchApplication(); // does not return if the relaunch succeeds
                }
                // As a guest the "Restart" button does not even exist (there is no machine to
                // restart); should we get here anyway, only the window is redone: building an
                // Amiga here would put it under the host's video.
                if (guestMode) {
                    recreateGuestDisplay();
                } else {
                    coldRestart();
                }
                displayRecreated = true;
            }
            case SOFT_RESET -> {
                saveConfig();
                applyRuntimeInputConfig();
                if (processor != null) processor.reset();
                resumeOrStart();
            }
            case STATE_RESTORE -> {
                // "Load State" on a state taken with another machine: it is rebuilt as it was
                // and the load retried. The popup closed without applying its own changes,
                // so there is no applyConfig to do here.
                String msg = restoreStateAfterRebuild();
                displayRecreated = true; // coldRestart has already redone window and display
                resumeOrStart();
                JOptionPane.showMessageDialog(frame, msg, Messages.t("app.title"),
                        JOptionPane.INFORMATION_MESSAGE);
            }
            case REMOTE_PLAY_TRANSITION -> {
                // Confirming a connection unmounts the machine; leaving the session mounts it
                // again. The popup does neither: this is the right place, outside the modal
                // dialog and on the same path as the cold restart.
                saveConfig();
                if (!applyPendingRemotePlayTransition()) {
                    // Nothing left to apply (the session dropped between the OK and now): the
                    // local machine is still there, paused, and must resume.
                    resumeOrStart();
                }
                if (exclusiveFullScreenSuspended && display != null) {
                    display.resumeExclusiveFullScreen();
                }
                recaptureMouse();
                return;   // nothing else: the machine is either absent or just started
            }
            case CANCEL -> resumeOrStart();
            case QUIT -> { System.exit(0); return; } // the shutdown hook switches the machine off
        }
        if (remotePlayHost != null) remotePlayHost.notifyPaused(false);
        if (exclusiveFullScreenSuspended && !displayRecreated && display != null) {
            display.resumeExclusiveFullScreen();
        }
        recaptureMouse();
    }

    /**
     * Applies live the floppy changes decided in the popup, comparing the state saved in
     * {@link #config} with the one before the popup opened. For each drive: connects or
     * disconnects it, ejects the disk removed and inserts the one added. On a replacement it
     * ejects, waits 1500 ms (as drag-and-drop does, so the Kickstart notices the eject) and
     * then inserts the new disk. All on a daemon thread so the EDT is not blocked, while the
     * machine is already running.
     */
    private void applyFloppyChanges(String[] oldDisks, boolean[] oldEnabled, boolean[] oldReadOnly) {
        applyFloppyChanges(oldDisks, oldEnabled, oldReadOnly, -1, false);
    }

    /**
     * @param skipIndex drive already aligned by the caller (e.g. the one that just received
     *                  a drag-and-drop), to be left out of the diff; -1 = none
     */
    private void applyFloppyChanges(String[] oldDisks, boolean[] oldEnabled,
                                    boolean[] oldReadOnly, int skipIndex) {
        applyFloppyChanges(oldDisks, oldEnabled, oldReadOnly, skipIndex, false);
    }

    /**
     * @param deferHardware connecting and disconnecting a drive is a hardware change: with
     *                      {@code true} it is left alone, and the drives stay those the
     *                      machine was started with until the next start. The disk inside a
     *                      connected drive still changes live, including the drive the
     *                      configuration now considers detached: there the disk is ejected
     *                      and the drive stays, empty, ready for another.
     */
    private void applyFloppyChanges(String[] oldDisks, boolean[] oldEnabled,
                                    boolean[] oldReadOnly, int skipIndex,
                                    boolean deferHardware) {
        if (agnus == null) return;
        // Before the diff: spread to the other drives the disks of a multi-disk zip that may
        // have been inserted in DF0 (or remove those of the previous zip).
        //
        // In Auto mode the redistribution is forced (oldDf0 = null). There the media list is
        // rebuilt from scratch at every confirmation, so the disks extracted from the zip are
        // gone and there is no "the user ejected disk 2 by hand" state to respect: without
        // forcing, a multi-disk zip left as it was would lose every disk but the first.
        syncZipDiskSet(config.autoEnabled() ? null : oldDisks[0]);
        final FloppyDrive[] dfs = {
                agnus.getDf0(), agnus.getDf1(), agnus.getDf2(), agnus.getDf3() };
        final String[] newDisks = new String[4];
        final boolean[] newEnabled = new boolean[4];
        final boolean[] newReadOnly = new boolean[4];
        for (int i = 0; i < 4; i++) {
            newDisks[i] = config.driveDisk(i);
            newEnabled[i] = config.driveEnabled(i);
            newReadOnly[i] = config.driveReadOnly(i);
        }

        Thread t = new Thread(() -> {
            for (int i = 0; i < 4; i++) {
                FloppyDrive drive = dfs[i];
                if (drive == null || i == skipIndex) continue;
                if (!deferHardware && newEnabled[i] != oldEnabled[i]) {
                    drive.setConnected(newEnabled[i]);
                }
                applyFloppySound(drive, i);
                // When the hardware is deferred, what counts is the drive that is there, not
                // the one the configuration describes: it is still there and its disk changes.
                if (!(deferHardware ? drive.isConnected() : newEnabled[i])) {
                    // Drive disconnected: no disk inside.
                    if (drive.isDiskInserted()) {
                        System.out.println("[CONFIG] DF" + i + ": espulsione (drive scollegato)");
                        drive.ejectDisk();
                    }
                    continue;
                }
                String oldPath = norm(oldDisks[i]);
                String newPath = norm(newDisks[i]);
                boolean readOnlyChanged = oldReadOnly != null && oldReadOnly[i] != newReadOnly[i];
                if (oldPath.equals(newPath) && !readOnlyChanged) continue; // no change for this drive
                try {
                    if (drive.isDiskInserted()) {
                        System.out.println("[CONFIG] DF" + i + ": espulsione disco corrente...");
                        drive.ejectDisk();
                        Thread.sleep(1500);
                    }
                    if (!newPath.isEmpty()) {
                        System.out.println("[CONFIG] DF" + i + ": inserimento " + newPath);
                        drive.loadAdf(newPath, newReadOnly[i]);
                    }
                } catch (Exception e) {
                    System.err.println("[CONFIG] DF" + i
                            + ": errore applicazione disco: " + e.getMessage());
                }
            }
        }, "config-floppy-apply");
        t.setDaemon(true);
        t.start();
    }

    /** Normalises a disk path: null/blank -> empty string. */
    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * Spreads over DF1..DF3 the disks of a multi-disk zip inserted in DF0.
     *
     * <p>A zip containing several .adf/.ipf is a disk set: the <b>boot</b> disk goes in DF0
     * and the others, in the order they appear in the archive, in the following drives, but
     * only if connected and empty. The order is the one {@link DiskImage#zipDiskOrder} gives:
     * inside archives the names often put the data disks before the program disk, and
     * starting from the first would leave the Kickstart asking for a disk forever. The disks
     * mounted this way are identified by the path {@code <zip>!<n>}: when the zip in DF0 is
     * replaced or ejected they are removed automatically, while they can still be changed or
     * ejected one by one in the normal way.
     *
     * <p>It acts only if DF0 has <i>changed</i>: while the zip stays the same, DF1..DF3 are
     * left exactly as the user set them (a disk ejected by hand is not mounted again). It
     * updates the configuration, the source of both the live diff and the F12 popup.
     *
     * @param oldDf0 path in DF0 before the change; {@code null} to force the
     *               resynchronisation (machine start)
     */
    private void syncZipDiskSet(String oldDf0) {
        String df0 = norm(config.driveDisk(0));
        if (oldDf0 != null && norm(oldDf0).equals(df0)) return; // DF0 invariato
        String previous = norm(oldDf0);

        java.util.List<String> entries = java.util.List.of();
        int[] order = new int[0];
        if (DiskImage.isZipDisk(df0)) {
            try {
                entries = DiskImage.listZipDiskEntries(DiskImage.pathOf(df0));
                order   = DiskImage.zipDiskOrder(DiskImage.pathOf(df0));
            } catch (IOException e) {
                System.err.println("[CONFIG] ZIP non leggibile (" + df0 + "): " + e.getMessage());
            }
        }

        for (int i = 1; i < 4; i++) {
            String disk = norm(config.driveDisk(i));
            // 1. free the drives holding a disk of the zip (old or new: the reassignment below
            //    is idempotent).
            if (isEntryOf(disk, previous) || isEntryOf(disk, df0)) {
                disk = "";
                config.setDriveDisk(i, "");
            }
            // 2. the i-th disk in insertion order (DF0 took the first) goes into the i-th
            //    drive, if free and connected.
            if (i < order.length && disk.isEmpty() && config.driveEnabled(i)) {
                int entry = order[i];
                config.setDriveDisk(i, DiskImage.zipEntrySpec(df0, entry));
                config.setDriveReadOnly(i, true);
                System.out.println("[CONFIG] DF" + i + ": disco " + entry + " dello zip -> "
                        + entries.get(entry));
            }
        }
    }

    /** {@code true} if {@code disk} is a disk extracted from the zip {@code zipPath}. */
    private static boolean isEntryOf(String disk, String zipPath) {
        return !zipPath.isEmpty() && DiskImage.isZipDisk(zipPath)
                && disk.regionMatches(true, 0, zipPath, 0, zipPath.length())
                && disk.length() > zipPath.length()
                && disk.charAt(zipPath.length()) == DiskImage.ZIP_ENTRY_SEPARATOR;
    }

    private void rememberDroppedDisk(String path, int index) {
        String[] oldDisks = new String[4];
        boolean[] oldEnabled = new boolean[4];
        boolean[] oldReadOnly = new boolean[4];
        for (int i = 0; i < 4; i++) {
            oldDisks[i] = config.driveDisk(i);
            oldEnabled[i] = config.driveEnabled(i);
            oldReadOnly[i] = config.driveReadOnly(i);
        }
        config.setDriveDisk(index, path);
        config.setDriveReadOnly(index, isZipPath(path));
        // The drive that received the drop is already set: apply only the knock-on effect on
        // the others (multi-disk set mounted or removed).
        applyFloppyChanges(oldDisks, oldEnabled, oldReadOnly, index);
    }

    private static boolean isZipPath(String path) {
        return DiskImage.isZipDisk(path);
    }

    private void saveConfig() {
        try {
            config.save();
        } catch (IOException e) {
            error(Messages.t("error.save", e.getMessage()));
        }
    }

    private void applyRuntimeInputConfig() {
        if (guestMode) {
            applyGuestPortAssignment();
        } else {
            applyPortAssignments();
        }
    }

    /**
     * Applies (live too) the host devices chosen for the two game ports. For each port the
     * property {@code input.portN.device} says whether it is driven by the mouse, by a real
     * joystick (GLFW), by the keyboard joystick, or is empty. "Port 0" is game port 1
     * (mouse/JOY0DAT), "Port 1" game port 2 (joystick/JOY1DAT).
     *
     * <p>Idempotent and safe live: it redirects the mouse/keyboard adapters to the right port
     * (enabling or disabling them) and tells the GLFW service which ports use a real
     * joystick.
     *
     * <p><b>The guest's port is not the host's.</b> While hosting, the game port assigned to
     * the guest is outside this function: what is plugged into it is decided by the guest
     * from <i>its</i> popup, and arrives in the feedback packet ({@code RemoteInputApplier}).
     * The host assigns it no device, points no mouse at it and sends no joystick to it;
     * otherwise the first confirmation of the local F12 would override the guest's choice,
     * and the two would fight over the same port through configuration. The port stays
     * reserved even while the guest is absent: it is about to arrive, or has just dropped.
     */
    private void applyPortAssignments() {
        if (gamePort1 == null || gamePort2 == null) return;

        PortDevice d0 = config.portDevice(0); // "Port 0" -> game port 1 (mouse/JOY0DAT)
        PortDevice d1 = config.portDevice(1); // "Port 1" -> game port 2 (joystick/JOY1DAT)

        // The port given to the guest leaves the scene here: for the host it does not exist.
        int guestPort = hostedGuestPort();
        if (guestPort == 0) d0 = null;
        if (guestPort == 1) d1 = null;

        // Host mouse -> the port set to MOUSE ("Port 0" first if both are).
        GamePort mousePort = (d0 == PortDevice.MOUSE) ? gamePort1
                           : (d1 == PortDevice.MOUSE) ? gamePort2 : null;
        if (mouseAdapter != null) {
            mouseAdapter.setPort(mousePort);
            mouseAdapter.setEnabled(mousePort != null);
        }

        // Keyboard joystick: each port has its own keys (input.portN.joystick.*), so both
        // can be driven together from the same keyboard.
        if (joystickAdapter != null) {
            joystickAdapter.configure(0,
                    d0 == PortDevice.JOYSTICK_KEYBOARD ? gamePort1 : null,
                    config.joystickKeyMap(0));
            joystickAdapter.configure(1,
                    d1 == PortDevice.JOYSTICK_KEYBOARD ? gamePort2 : null,
                    config.joystickKeyMap(1));
        }

        // Real joystick (GLFW) -> every port set to it, in order.
        java.util.List<GamePort> realPorts = new java.util.ArrayList<>(2);
        if (d0 == PortDevice.JOYSTICK_REAL) realPorts.add(gamePort1);
        if (d1 == PortDevice.JOYSTICK_REAL) realPorts.add(gamePort2);
        joystickService.setPorts(realPorts);

        // Hardware device reported by each port (for the JOYxDAT/POTINP reads), declared
        // after the adapters above have been redirected: an adapter leaving a port releases
        // what it held on it, and every release makes the GamePort infer its own device.
        // Declared first, a switch to "Empty" would leave a stationary joystick or a mouse
        // on the port, and a switch from joystick to mouse a joystick.
        if (d0 != null) gamePort1.setDevice(d0.hardwareDevice());
        if (d1 != null) gamePort2.setDevice(d1.hardwareDevice());

        // The port given away is declared empty, and LAST. Detaching the host's adapters from
        // it makes them release what they held pressed (right and necessary), but every
        // release makes the GamePort infer a device, and a stationary joystick would remain
        // on a port with nothing plugged in.
        //
        // Only if the guest is NOT connected, though. If it is playing, that port is already
        // its own and what is in it is stated in every packet: clearing it here, because the
        // host confirmed its own popup for some other reason, would pull the joystick from
        // under the guest for one frame, in the middle of a game.
        if (!hasGuest()) {
            if (guestPort == 0) gamePort1.setDevice(GamePortDevice.NONE);
            if (guestPort == 1) gamePort2.setDevice(GamePortDevice.NONE);
        }
    }

    /**
     * Guest side: wires its own adapters to the port the host assigned to it, according to
     * the device <b>it</b> chose in that port's drop-down.
     *
     * <p>The twin of {@link #applyPortAssignments()} for a machine that emulates nothing:
     * instead of the {@code GamePort} there is {@link RemoteInputSender}, and the gestures go
     * into a packet instead of a register. Everything else is identical on purpose: the
     * keyboard joystick reads the <b>local</b> key map ({@code input.portN.joystick.*} of
     * this machine), which is the point: the keys one plays with are one's own, not the
     * host's.
     *
     * <p>The other port is unconfigured: it is the host's port, and there is no sensible way
     * to drive it from here.
     */
    private void applyGuestPortAssignment() {
        RemotePlayGuest guest = remotePlayGuest;
        if (remoteInputSender == null || guest == null) return;

        int mine = guest.gamePort();
        PortDevice device = config.portDevice(mine);

        if (mouseAdapter != null) {
            mouseAdapter.setEnabled(device == PortDevice.MOUSE);
        }
        if (joystickAdapter != null) {
            // The other port exists on the host's machine and cannot be reached from here:
            // configuring it would consume the user's keys for nothing.
            joystickAdapter.configure(1 - mine, null, config.joystickKeyMap(1 - mine));
            joystickAdapter.configure(mine,
                    device == PortDevice.JOYSTICK_KEYBOARD ? remoteInputSender : null,
                    config.joystickKeyMap(mine));
        }
        joystickService.setPorts(device == PortDevice.JOYSTICK_REAL
                ? java.util.List.of(remoteInputSender) : java.util.List.of());

        // LAST: unconfiguring the adapters above makes them release what they held pressed,
        // and every release makes the sender infer a device. If the choice were declared
        // before, a switch to "Empty" would rewrite itself as "Joystick".
        remoteInputSender.setDevice(device.hardwareDevice());
    }

    /**
     * The game port this machine has given to a guest, or {@code -1} if it is not hosting.
     *
     * <p>Deliberately independent of whether a guest is there <i>now</i>: the port is
     * assigned from the moment listening starts, and handing it back to the host between one
     * guest and the next would mean having it pulled away in the middle of a game.
     */
    private int hostedGuestPort() {
        return remotePlayHost == null ? -1 : remotePlaySettings.guestGamePort();
    }

    private void applyFloppySounds(FloppyDrive[] drives) {
        for (int i = 0; i < drives.length && i < floppySounds.length; i++) {
            applyFloppySound(drives[i], i);
        }
    }

    private void applyFloppySound(FloppyDrive drive, int index) {
        if (drive == null || index < 0 || index >= floppySounds.length) return;

        // What counts is the drive that is there, not the one the configuration describes: a
        // drive left empty survives until the next start, and while it is there it makes noise.
        boolean enabled = config.floppySoundEnabled()
                && (drive.isConnected() || config.driveEnabled(index));
        float gainDb = config.floppySoundGainDb();
        synchronized (floppySounds) {
            FloppySound sound = floppySounds[index];
            if (!enabled) {
                if (sound != null) {
                    sound.close();
                    floppySounds[index] = null;
                }
                drive.setFloppySound(null);
                return;
            }

            if (sound == null) {
                sound = new FloppySound(true, gainDb);
                floppySounds[index] = sound;
            } else if (sound.getGainDb() != gainDb) {
                sound.setGainDb(gainDb);
            }
            drive.setFloppySound(sound);
        }
    }

    private void closeFloppySounds() {
        synchronized (floppySounds) {
            for (int i = 0; i < floppySounds.length; i++) {
                if (floppySounds[i] != null) {
                    floppySounds[i].close();
                    floppySounds[i] = null;
                }
            }
        }
    }

    private boolean displayConfigChanged(boolean oldOverscan, int oldIntegerScaling,
            AmigaWindowMode oldWindowMode, int oldMonitorIndex) {
        return oldOverscan != config.overscanEnabled()
                || oldIntegerScaling != config.integerScaling()
                || oldWindowMode != config.windowMode()
                || oldMonitorIndex != config.monitorIndex();
    }

    /**
     * Restarts the whole JVM process (not just the machine's power cycle): launches a new
     * java process with the same class path and working dir, then exits. Needed for the
     * settings the toolkit fixes once at start-up and that cannot be changed live. If the
     * relaunch fails it does not exit: the caller goes on with a cold restart of the machine
     * alone.
     *
     * <p>The only client is a change of {@code ui.scale}: {@code flatlaf.uiScale} is read
     * when the Look and Feel initialises, once. The integer scaling of the emulated window
     * does not go through here: {@code AmigaDisplay} applies it at presentation, and
     * re-creating the window is enough.
     */
    private void relaunchApplication() {
        try {
            String javaBin = System.getProperty("java.home")
                    + File.separator + "bin" + File.separator + "java";
            List<String> command = new ArrayList<>();
            command.add(javaBin);
            // The home must be passed AGAIN explicitly: this relaunches "java -cp ...", not the
            // native launcher, so the --java-options set by jpackage (among them
            // -Damija.home=$ROOTDIR) would be lost and the new process would fall back on the
            // jar's folder, which inside an app-image is <root>/app.
            // inheritIO: the new process reuses the current console/stdio. The working dir is
            // inherited, so amiga.properties (already saved) is the same.
            command.add("-D" + AppHome.PROPERTY + "=" + AppHome.home());
            command.add("--enable-native-access=ALL-UNNAMED");
            command.addAll(Arrays.asList(JIT_OPTIONS));
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(Amija.class.getName());
            new ProcessBuilder(command)
                    .inheritIO()
                    .start();
            System.exit(0); // the shutdown hook switches the current machine off
        } catch (IOException ex) {
            System.err.println("[UI] Riavvio del processo non riuscito, eseguo un cold "
                    + "restart (il nuovo integer scaling sara' effettivo al prossimo "
                    + "riavvio manuale): " + ex.getMessage());
        }
    }

    /** Switches the machine off and on from scratch (power cycle) with the current config. */
    private void coldRestart() {
        shutdownMachine();
        recreateDisplay();
        if (validateRom(config)) {
            bootMachine();
        }
    }

    /** Recreates window and display, needed for the settings applied only at restart. */
    private void recreateDisplay() {
        if (display != null) {
            display.dispose();
        } else if (frame != null) {
            frame.dispose();
        }
        // Video mode read from the config (not from the videoMode field, still at the
        // previous boot: in coldRestart recreateDisplay precedes bootMachine).
        boolean overscan = config.overscanEnabled();
        int fw = config.videoMode().displayWidth(overscan);
        int fh = config.videoMode().displayHeight(overscan);
        display = new AmigaDisplay(fw, fh, config.windowMode(), config.renderer(),
                config.integerScaling(), config.monitorIndex());
        frame = display.getFrame();
        inputComponent = display.getInputComponent();
        display.setDiskDropListener(this::rememberDroppedDisk);
        display.setShaderPreset(resolveShaderPath(config.shader()));
        installConfigHotkey();
    }

    /**
     * Resolves the configured shader name to the path of the preset ({@code .slangp} or
     * {@code .glslp}) under {@code shaders/}, subfolders included.
     * {@code "none"}/empty or missing file -&gt; {@code null} (passthrough).
     */
    private static java.nio.file.Path resolveShaderPath(String name) {
        return io.github.zolfrik.amija.display.render.gl.ShaderCatalog.resolve(name);
    }

    /**
     * Saves a screenshot of the game window in {@code screenshots/} (software: Java2D
     * composition; OpenGL: {@code glReadPixels}). Returns the file saved or {@code null} on
     * error. Used by the F12 "Take Screenshot" button.
     */
    private File takeScreenshotFile() {
        if (display == null) return null;
        try {
            File dir = AppHome.file("screenshots");
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File file = new File(dir, "screenshot_" + ts + ".png");
            display.saveScreenshot(file);
            System.out.println("[SHOT] " + file.getAbsolutePath());
            return file;
        } catch (IOException e) {
            System.err.println("[SHOT] Salvataggio screenshot fallito: " + e.getMessage());
            return null;
        }
    }

    /** Applies an OpenGL shader parameter live (from the "..." popup). */
    private void updateShaderParam(String name, Float value) {
        if (display != null && value != null) {
            display.setShaderParam(name, value);
        }
    }

    /**
     * Saves the whole machine state in the active slot
     * ({@code savestate/Slot<N>/state.a5s}, N from {@code savestate.slot}). Called by the F12
     * "Save State" button with the machine paused (the popup is open). Returns the localised
     * outcome message to show the user.
     */
    private String saveState() {
        int slot = config.saveStateSlot();
        if (processor == null || agnus == null) {
            return Messages.t("savestate.save.fail", Messages.t("savestate.nomachine"));
        }
        try {
            File file = SaveStateManager.slotFile(slot);
            SaveStateManager.save(file, processor, eventBus, agnus, gamePort1, gamePort2, config);
            System.out.println("[STATE] salvato " + file.getAbsolutePath());
            saveStateScreenshot(slot);
            return Messages.t("savestate.save.ok", slot, file.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[STATE] salvataggio fallito: " + e.getMessage());
            return Messages.t("savestate.save.fail", String.valueOf(e.getMessage()));
        }
    }

    /**
     * Saves a screenshot of the screen next to the state
     * ({@code savestate/Slot<N>/state.png}), under a fixed name: every save overwrites that
     * slot's previous image. An error here does not invalidate the state already written, so
     * it is only reported on the console.
     */
    private void saveStateScreenshot(int slot) {
        if (display == null) return;
        try {
            File shot = SaveStateManager.slotScreenshot(slot);
            display.saveScreenshot(shot);
            System.out.println("[STATE] screenshot " + shot.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[STATE] screenshot dello slot fallito: " + e.getMessage());
        }
    }

    /**
     * Restores the machine state from the active slot, overwriting the current components
     * (machine paused). Called by the F12 "Load State" button.
     *
     * <p>If the state was taken on a machine different from the running one (other memory,
     * other Kickstart, other disks) it does not simply say no: the slot also holds the
     * configuration of the time, so the right machine can be rebuilt. That means switching
     * it off and on, which cannot be done from here: the popup is open and is a child of the
     * window the restart destroys. So {@link ConfigDialog.LoadStateOutcome#restart()} is
     * returned, the popup closes, and the rest is in {@link #restoreStateAfterRebuild()}.
     */
    private ConfigDialog.LoadStateOutcome loadState() {
        int slot = config.saveStateSlot();
        if (processor == null || agnus == null) {
            return ConfigDialog.LoadStateOutcome.done(
                    Messages.t("savestate.load.fail", Messages.t("savestate.nomachine")));
        }
        File file = SaveStateManager.slotFile(slot);
        if (!file.isFile()) {
            return ConfigDialog.LoadStateOutcome.done(Messages.t("savestate.load.none"));
        }
        try {
            SaveStateManager.load(file, processor, eventBus, agnus, gamePort1, gamePort2, config);
            System.out.println("[STATE] caricato " + file.getAbsolutePath());
            return ConfigDialog.LoadStateOutcome.done(
                    Messages.t("savestate.load.ok", slot));
        } catch (SaveStateManager.IncompatibleStateException e) {
            if (canRebuildMachineFor(file)) {
                System.out.println("[STATE] macchina diversa da quella dello slot ("
                        + e.getMessage() + "): la ricostruisco e ritento");
                return ConfigDialog.LoadStateOutcome.restart();
            }
            return ConfigDialog.LoadStateOutcome.done(
                    Messages.t("savestate.load.incompatible", e.getMessage()));
        } catch (Exception e) {
            System.err.println("[STATE] caricamento fallito: " + e.getMessage());
            return ConfigDialog.LoadStateOutcome.done(
                    Messages.t("savestate.load.fail", String.valueOf(e.getMessage())));
        }
    }

    /**
     * {@code true} if the slot describes a machine different from the running one, and so
     * rebuildable. False when the saved configuration is already the current one: then the
     * refusal comes from something else (format, structure) and restarting would only throw
     * away the session in progress.
     */
    private boolean canRebuildMachineFor(File slot) {
        try {
            return config.machineDiffers(SaveStateManager.readInfo(slot).config());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Second half of "Load State" when the state requires another machine: puts back in
     * force the configuration kept in the slot, rebuilds the machine and retries the load.
     * Returns the message to show the user.
     *
     * <p>The settings restored are only those that describe the machine (Kickstart, memory,
     * chipset, video mode, media): language, window, monitor, renderer and shader stay as
     * the user chose; see {@link AmigaConfig#restoreMachineSettings}.
     */
    private String restoreStateAfterRebuild() {
        // The slot is the one chosen in the popup: the choice takes effect at once, so it is
        // still in the configuration even if the popup closed without applying the rest. It
        // is read BEFORE restoreMachineSettings, which puts back only the machine keys, and
        // savestate.slot is not one of them.
        int slot = config.saveStateSlot();
        File file = SaveStateManager.slotFile(slot);
        try {
            Properties saved = SaveStateManager.readInfo(file).config();
            config.restoreMachineSettings(saved);
            saveConfig();
            System.out.println("[STATE] configurazione dello slot ripristinata, riavvio la macchina");
            coldRestart();
            if (processor == null || agnus == null) {
                return Messages.t("savestate.load.fail", Messages.t("savestate.nomachine"));
            }
            // The machine has just started and is emulating: it must really be stopped (not
            // just marked as paused) before the components are overwritten under it.
            if (clock != null) clock.pauseAndWait(500);
            SaveStateManager.load(file, processor, eventBus, agnus, gamePort1, gamePort2, config);
            System.out.println("[STATE] caricato " + file.getAbsolutePath());
            return Messages.t("savestate.load.reconfigured");
        } catch (SaveStateManager.IncompatibleStateException e) {
            return Messages.t("savestate.load.incompatible", e.getMessage());
        } catch (Exception e) {
            System.err.println("[STATE] caricamento dopo il riavvio fallito: " + e.getMessage());
            return Messages.t("savestate.load.fail", String.valueOf(e.getMessage()));
        }
    }

    /**
     * Resumes the emulation after the popup, or starts it if it never started.
     *
     * <p><b>As a guest it does nothing, and that is the part that matters.</b> A guest does
     * not emulate (section 3): it has no {@code MasterClock} to resume, and the "then start
     * it" branch would build a local machine <i>under</i> the video still arriving from the
     * host, two Amigas drawing on the same window. Every close of the popup, OK and Cancel
     * included, gets here, because for a guest {@code clock} is always {@code null}.
     */
    private void resumeOrStart() {
        if (guestMode) {
            return;
        }
        if (clock != null && clock.isRunning()) {
            clock.resume();
        } else if (validateRom(config)) {
            bootMachine();
        }
    }

    private void recaptureMouse() {
        if (mouseAdapter == null || frame == null) return;
        final AmigaMouseAdapter target = mouseAdapter;
        // Right after a modal dialog closes, the window is not yet the "active window":
        // requestFocusInWindow() is a no-op and robot.mouseMove is discarded during the focus
        // transition (the cursor ends up hidden but not centred). The capture is briefly
        // deferred, bringing the window to the front and forcing the focus first.
        javax.swing.Timer timer = new javax.swing.Timer(150, e -> {
            frame.toFront();
            frame.requestFocus();
            // In OpenGL the keyboard focus must go back to the canvas (the input is there).
            if (inputComponent != null) inputComponent.requestFocusInWindow();
            target.captureMouse();
        });
        timer.setRepeats(false);
        timer.start();
    }

    // =========================================================================
    // Building / tearing down the machine
    // =========================================================================

    /** Builds and starts a whole A500 machine from {@link #config}. */
    private void bootMachine() {
        try {
            // Apply the current language: rebuilding the display (setDrives) regenerates the
            // drag-and-drop overlay with the updated localised strings.
            Messages.setLanguage(config.language());
            // Video mode (PAL/NTSC): decides the clock, lines per frame and Agnus ID. Read
            // here so a cold restart after a change in the F12 popup applies it.
            videoMode = config.videoMode();
            final String kickstartPath = config.kickstartPath();

            // 1. Address decoding (Gary) and memory.
            Gary gary = Gary.defaultA500();
            RAM chipRam = new RAM(config.chipRamKb() * 1024);
            // The CHIP_RAM Region of defaultA500() has physicalSize fixed at 512 KB: align it
            // with the configuration, or the addresses above $07FFFF wrap onto the first
            // 512 KB and the Kickstart finds only 512 KB of chip (e.g. WHDLoad needs
            // contiguous chip up to $A0000 / 640 KB).
            gary.setChipRamSize(config.chipRamKb() * 1024);
            RAM slowRam = config.slowRamKb() > 0 ? new RAM(config.slowRamKb() * 1024) : null;

            // The image is normalised (headers dropped, tails truncated, overdumps collapsed)
            // before it reaches the socket: Gary maps by bit masks and needs a power of two.
            // See KickstartImage.
            // Without a usable Kickstart the AmiJa ROM bundled in the jar is mounted: it is
            // original firmware of this project, hence redistributable, and boots floppy
            // games without owning a Commodore ROM. See BuiltinRom for what it does NOT cover.
            ROM kickstart;
            if (usingBuiltinRom(kickstartPath)) {
                byte[] builtin = BuiltinRom.load();
                System.out.println("[ROM] " + BuiltinRom.version() + " integrata ("
                        + builtin.length + " byte)");
                kickstart = new ROM(builtin);
            } else {
                KickstartImage romImage = KickstartImage.load(Paths.get(kickstartPath));
                System.out.println("[ROM] " + romImage.describe());
                kickstart = new ROM(romImage.data());
            }

            // 2. CPU and bus.
            io.github.zolfrik.amija.cpu.CpuModel cpuModel = config.cpuModel();
            System.out.println("[CONFIG] CPU: " + cpuModel);
            io.github.zolfrik.amija.cpu.event.EventCpu eventCpu = null;
            if (cpuModel.isEventDriven()) {
                eventCpu = cpuModel.createEventCpu();
                cpu = null;
                processor = eventCpu;
            } else {
                cpu = cpuModel.create(new BusRequest(), new BusResponse());
                processor = cpu;
            }

            // 3. Custom chips. The chipset (OCS/ECS/AGA) selects the Agnus/Denise variant. The
            // user's choice is ALWAYS respected: AbstractAgnus's DMA address counter derives
            // the pointer mask from the Chip RAM size (on OCS the DMA space follows a Chip RAM
            // beyond 512 KB), so OcsAgnus addresses Chip RAM up to 2 MB. ECS is NOT forced
            // when Chip RAM exceeds 512 KB: besides being unnecessary, it would send an OCS
            // selection down the ECS + Kickstart 3.1 path (not validated yet), with a guru at
            // start-up (e.g. Agony through WHDLoad with 1 MB of Chip RAM).
            Chipset chipset = config.chipset();
            final boolean ecs = chipset.isEcs();
            System.out.println("[CONFIG] Chipset: " + chipset);
            final boolean aga = chipset.isAga();
            AbstractDenise denise = aga ? new AgaDenise() : ecs ? new EcsDenise() : new OcsDenise();
            paula = new Paula();
            paula.startAudioOutput();

            CIAPinSet ciaAPins = new CIAPinSet();
            MOS8520 ciaA = new MOS8520(ciaAPins, true);
            MOS8520 ciaB = new MOS8520(new CIAPinSet(), false);

            // Game port 1 (JOY0DAT, "Port 0") and port 2 (JOY1DAT, "Port 1"). The host device
            // of each (mouse / real joystick / keyboard joystick / empty) is decided by
            // applyPortAssignments from the configuration (input.port0.device /
            // input.port1.device, the F12 popup's drop-downs).
            GamePort port1 = new GamePort(1);
            GamePort port2 = new GamePort(2);
            gamePort1 = port1;
            gamePort2 = port2;
            port1.setCIAAPins(ciaAPins);
            port2.setCIAAPins(ciaAPins);
            denise.setGamePort1(port1);
            denise.setGamePort2(port2);
            paula.setGamePort1(port1);
            paula.setGamePort2(port2);

            agnus = aga
                    ? new AgaAgnus(processor, gary, chipRam, slowRam, kickstart, paula, denise, ciaA, ciaB, videoMode)
                    : ecs
                    ? new EcsAgnus(processor, gary, chipRam, slowRam, kickstart, paula, denise, ciaA, ciaB, videoMode)
                    : new OcsAgnus(processor, gary, chipRam, slowRam, kickstart, paula, denise, ciaA, ciaB, videoMode);

            // Fast RAM (Zorro II expansion with AutoConfig). Absent by default (0): the first
            // Zorro II RAM board is usually mapped by the Kickstart at $200000 and added to the
            // system memory, available to programs and WHDLoad. Must be connected BEFORE the
            // CPU reset.
            agnus.setFastRam(new FastRam(config.fastRamKb() * 1024));

            // Immediate blit: completes every blit at once (not cycle-exact). Only from
            // amiga.properties (blitter.immediate), default false, not from F12.
            agnus.getBlitter().setImmediateMode(config.blitterImmediate());

            // Hard disks (master/slave): every enabled unit with a valid .hdf becomes a Zorro
            // II controller with a boot ROM. The DH partitions are numbered across the units.
            // Must be connected BEFORE the CPU reset.
            setupHardDisks(agnus);

            virtualCRT = new VirtualCRT(agnus, denise, display);
            // The remote play session survives the machine's restarts: the guest just sees
            // the reboot instead of losing the connection. Its controls must be reattached,
            // though: game ports and keyboard are new.
            if (remotePlayHost != null) {
                virtualCRT.setRemotePlayHost(remotePlayHost);
            }

            Keyboard keyboard = new Keyboard(ciaA);
            agnus.setKeyboard(keyboard);
            this.keyboard = keyboard;

            // Input adapters (recreated at every start: they bind the new ports/keyboard).
            // The target port and the enabled state of mouse/keyboard joystick are set right
            // after by applyPortAssignments (likewise for the real joystick).
            joystickAdapter = new AmigaJoystickKeyAdapter();
            keyAdapter = new AmigaKeyAdapter(keyboard, frame);
            // The scale factor comes from the display, not from the config: it is the one
            // really applied to the window (already normalised to >= 1). It brings the mouse
            // deltas, which arrive in screen pixels, back to Amiga pixels.
            mouseAdapter = new AmigaMouseAdapter(port1, frame,
                    display != null ? display.getVideoScale() : 1);
            // The listeners go on the input component (the frame in software, the
            // heavyweight GL canvas in OpenGL: its events do not bubble up to the frame). The
            // adapters still receive the frame for focus/cursor/mouse recentring.
            if (inputComponent != null) {
                // The joystick adapter must come first: it consumes the keys mapped to the
                // directions and fire, which must not be forwarded to the Amiga keyboard.
                inputComponent.addKeyListener(joystickAdapter);
                inputComponent.addKeyListener(keyAdapter);
                inputComponent.addMouseListener(mouseAdapter);
                inputComponent.addMouseMotionListener(mouseAdapter);
                inputComponent.setFocusTraversalKeysEnabled(false);
                inputComponent.requestFocusInWindow();
            }

            // Assign the host devices to the two game ports (mouse / real joystick / keyboard
            // joystick / empty) according to the configuration.
            applyPortAssignments();

            // The guest's controls must be reattached to the new game ports and keyboard:
            // those of the previous machine no longer exist.
            attachRemoteInput();

            // Drives (DF0..DF3): connection and disk to insert at start-up. If DF0 holds a
            // multi-disk zip, the following disks fill the free drives.
            syncZipDiskSet(null);
            FloppyDrive df0 = agnus.getDf0();
            FloppyDrive[] dfs = { df0, agnus.getDf1(), agnus.getDf2(), agnus.getDf3() };
            for (int i = 0; i < dfs.length; i++) {
                dfs[i].setConnected(config.driveEnabled(i));
                String diskPath = config.driveDisk(i);
                if (config.driveEnabled(i) && diskPath != null) {
                    try {
                        dfs[i].loadAdf(diskPath, config.driveReadOnly(i));
                    } catch (IOException ioe) {
                        System.err.println("[CONFIG] Impossibile caricare il disco DF" + i
                                + " (" + diskPath + "): " + ioe.getMessage());
                    }
                }
            }
            display.setDrives(dfs[0], dfs[1], dfs[2], dfs[3]);
            // From here on this is the hardware in force: what the popup can no longer change
            // without a restart.
            activeHardware = captureDriveHardware();

            // Status LEDs + cosmetic floppy sounds (both from the configuration). The HD LED
            // reflects the hard disks present (WHDLoad and/or master/slave, in order).
            display.setStatusLedSource(ciaA::isPowerLedOn);
            display.setHdLedSources(hdLedSources.toArray(new java.util.function.BooleanSupplier[0]));
            attachRemotePlayLed();
            display.setStatusLedsEnabled(config.ledsVisible());

            // Optional diagnostic overlays (only from amiga.properties, not in the F12 popup).
            display.setFpsOverlayEnabled(config.fpsOverlayEnabled());
            display.setFrameCounterOverlayEnabled(config.frameCounterOverlayEnabled());
            applyFloppySounds(dfs);

            // 4. Master clock + reset + avvio.
            // An event-driven processor runs on its own bus, which advances the chipset.
            io.github.zolfrik.amija.common.ClockDriver driver = null;
            if (eventCpu != null) {
                eventBus = new io.github.zolfrik.amija.custom.EventCpuBus(agnus, cpuModel);
                eventBus.setVirtualCRT(virtualCRT);
                eventCpu.attach(eventBus);
                driver = eventBus.driver(eventCpu);
            }
            clock = new MasterClock(agnus, videoMode, virtualCRT, driver);
            // If the emulation thread dies of an exception, without this hook the display
            // would just freeze (or stay black, if the failure comes before the first frame)
            // without a word.
            clock.setFailureHandler(t -> SwingUtilities.invokeLater(
                    () -> error(Messages.t("error.boot", describeFailure(t)))));
            // Power-on: RESET cascades to CIAs and floppies; the CPU starts after a random delay.
            if (eventCpu != null) eventCpu.powerOnReset(AbstractMotorola.powerOnStartDelay());
            else cpu.powerOnReset(AbstractMotorola.powerOnStartDelay());
            clock.start();

            if (VERBOSE) {
                System.out.println("[INIT] Macchina avviata.");
            }
        } catch (IOException e) {
            error(Messages.t("error.boot", e.getMessage()));
        } catch (RuntimeException | LinkageError e) {
            // Any other failure during the boot. Without this branch the exception would
            // reach the caller: when bootMachine() starts from openConfig() that caller is the
            // EDT, where AWT only prints to stderr and carries on. The window stays alive and
            // responsive (F12 still opens the popup) but clock.start() was never reached, so
            // no frame arrives: a permanent black screen, without even the power/drive LEDs,
            // which are drawn INSIDE the emulated frame and vanish with it. The symptom is
            // indistinguishable from a video failure, and in an app launched by double click
            // nobody sees the stack trace on stderr. Better to say it on screen.
            e.printStackTrace();
            error(Messages.t("error.boot", describeFailure(e)));
        }
    }

    /**
     * Compact description of a failure for the on-screen message: the exception type (often
     * the only information, e.g. for a NullPointerException) followed by the message, when
     * there is one. The full stack trace stays on stderr.
     */
    private static String describeFailure(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.isBlank()
                ? t.getClass().getName()
                : t.getClass().getSimpleName() + ": " + msg;
    }

    /**
     * Builds the hard disk controllers from the configured units (master/slave) and connects
     * them to {@link AbstractAgnus}. Every valid {@code .hdf} file produces a controller; the
     * partitions are numbered across the units (DH0, DH1, ...).
     */
    private void setupHardDisks(AbstractAgnus agnus) {
        hardFiles.clear();
        hdLedSources.clear();
        java.util.List<HdController> controllers = new java.util.ArrayList<>();
        int partitionBase = 0;

        // WHDLoad: the .lha archive is extracted and packed into a bootable FFS hard disk
        // (see WhdLoadManager), mounted as the FIRST unit so it becomes the boot volume (DH0)
        // and runs the WHDLoad slave automatically.
        if (config.whdloadEnabled() && config.whdloadFile() != null) {
            try {
                WhdLoadManager whd = new WhdLoadManager();
                // WHDLoad.prefs generated from the whdload.prefs.* section of amiga.properties.
                whd.withPrefs(config.whdloadPrefsContent());
                // Give WHDLoad the Kickstart ROMs found next to the one in use (many OCS
                // slaves need e.g. KS 1.3): matched by CRC32.
                String ksPath = config.kickstartPath();
                if (!ksPath.isEmpty()) {
                    java.nio.file.Path ksParent = java.nio.file.Paths.get(ksPath).getParent();
                    if (ksParent != null) whd.addKickstartDirectory(ksParent);
                }
                java.nio.file.Path hdf = whd.prepare(config.whdloadFile());
                HardFile hf = HardFile.load(hdf.toString(), false);
                hardFiles.add(hf);
                HdController hd = new HdController(controllers.size(), hf, partitionBase,
                        () -> { FloppyDrive d = agnus.getDf0(); return d != null && d.isDiskInserted(); });
                controllers.add(hd);
                partitionBase += hf.numPartitions();
                System.out.println("[WHDLOAD] " + config.whdloadFile() + " -> " + hdf
                        + " (" + hf.cylinders() + "/" + hf.heads() + "/" + hf.sectors() + " C/H/S)");

                // Persistent volume for the WHDLoad saves (Saves: -> WHDSaves:), mounted as a
                // NON-bootable unit so it does not interfere with the boot.
                java.nio.file.Path savesHdf = whd.prepareSaves();
                HardFile shf = HardFile.load(savesHdf.toString(), false);
                shf.setBootable(false);
                hardFiles.add(shf);
                HdController savesHd = new HdController(controllers.size(), shf, partitionBase,
                        () -> { FloppyDrive d = agnus.getDf0(); return d != null && d.isDiskInserted(); });
                controllers.add(savesHd);
                partitionBase += shf.numPartitions();

                // A single HD LED for WHDLoad: boot and saves are the same logical unit.
                hdLedSources.add(() -> hd.ledActive() || savesHd.ledActive());
            } catch (IOException ioe) {
                System.err.println("[WHDLOAD] Avvio WHDLoad fallito (" + config.whdloadFile()
                        + "): " + ioe.getMessage());
            }
        }

        for (int unit = 0; unit < AmigaConfig.HD_UNITS.length; unit++) {
            if (!config.hdEnabled(unit)) continue;
            String diskPath = config.hdDisk(unit);
            if (diskPath == null) continue;
            try {
                HardFile hf = HardFile.load(diskPath, config.hdReadOnly(unit));
                hardFiles.add(hf);
                HdController hd = new HdController(controllers.size(), hf, partitionBase,
                        () -> { FloppyDrive d = agnus.getDf0(); return d != null && d.isDiskInserted(); });
                controllers.add(hd);
                hdLedSources.add(hd::ledActive); // master (unit 0) first, slave (unit 1) after
                partitionBase += hf.numPartitions();
                System.out.println("[HDF] " + AmigaConfig.HD_UNITS[unit] + ": montato " + diskPath
                        + " (" + hf.numPartitions() + " part., "
                        + hf.cylinders() + "/" + hf.heads() + "/" + hf.sectors() + " C/H/S"
                        + (hf.readOnly() ? ", sola lettura" : "") + ")");
            } catch (IOException ioe) {
                System.err.println("[HDF] Impossibile montare l'hard disk " + AmigaConfig.HD_UNITS[unit]
                        + " (" + diskPath + "): " + ioe.getMessage());
            }
        }
        agnus.setHdControllers(controllers);
    }

    /** Stops the current machine: clock, audio, render and input listeners. */
    private void shutdownMachine() {
        if (clock != null) {
            clock.stop();
            clock = null;
        }
        if (virtualCRT != null) {
            virtualCRT.stop();
            virtualCRT = null;
        }
        if (paula != null) {
            paula.stopAudioOutput();
            paula = null;
        }
        if (agnus != null) {
            agnus.getDf0().setFloppySound(null);
            agnus.getDf1().setFloppySound(null);
            agnus.getDf2().setFloppySound(null);
            agnus.getDf3().setFloppySound(null);
            // The disks must be EJECTED, not just forgotten. ejectDisk() writes back the track
            // being written, does the final flush and closes the DiskImage, which is also the
            // only place where its shutdown hook is removed. Otherwise every machine unmounted
            // and rebuilt in the same process (ROM change, configuration change, loading an
            // incompatible state, and the game sweep that boots one instance per file) would
            // leave up to four hooks behind, each a GC root of the whole image.
            agnus.getDf0().ejectDisk();
            agnus.getDf1().ejectDisk();
            agnus.getDf2().ejectDisk();
            agnus.getDf3().ejectDisk();
        }
        closeFloppySounds();
        // Save to disk any writes to the mounted hard disks.
        for (HardFile hf : hardFiles) {
            hf.flush();
        }
        hardFiles.clear();
        detachInputAdapters();
        gamePort1 = null;
        gamePort2 = null;
        cpu = null;
        processor = null;
        eventBus = null;
        agnus = null;
        activeHardware = null;
    }

    /**
     * Takes the media just mounted on the machine: how many floppy drives are connected, the
     * hard disk images in unit order and the WHDLoad archive running. It is the yardstick
     * with which the popup tells an immediate change (the disk inside a drive that already
     * exists) from one that needs new hardware.
     */
    private ConfigDialog.DriveHardware captureDriveHardware() {
        int floppies = 0;
        for (int i = 0; i < 4; i++) {
            if (config.driveEnabled(i)) floppies++;
        }
        java.util.List<String> hds = new java.util.ArrayList<>();
        for (int u = 0; u < AmigaConfig.HD_UNITS.length; u++) {
            if (config.hdEnabled(u)) hds.add(norm(config.hdDisk(u)));
        }
        String whd = config.whdloadEnabled() ? norm(config.whdloadFile()) : "";
        return new ConfigDialog.DriveHardware(floppies, hds, whd);
    }

    /**
     * Detaches the input adapters from the component and disposes of them.
     *
     * <p>Letting them go is not enough: {@code keyAdapter} holds a <b>physical poller</b> of
     * the keyboard and {@code mouseAdapter} a focus listener on the frame. Replacing them
     * without stopping them would leave a thread behind for every time they were remade,
     * and keys would be delivered twice.
     *
     * <p>Used by whoever unmounts the machine and by whoever redoes only the window
     * ({@code recreateGuestDisplay}): the guest has no machine to unmount, but has the same
     * adapters.
     */
    private void detachInputAdapters() {
        if (inputComponent != null) {
            if (mouseAdapter != null) {
                inputComponent.removeMouseListener(mouseAdapter);
                inputComponent.removeMouseMotionListener(mouseAdapter);
                mouseAdapter.dispose(); // also removes the window focus listener
            }
            if (keyAdapter != null) inputComponent.removeKeyListener(keyAdapter);
            if (joystickAdapter != null) inputComponent.removeKeyListener(joystickAdapter);
        }
        if (keyAdapter != null) keyAdapter.dispose(); // stops the shift poller
        // The real joystick service (GLFW) stays alive between boots (idle): detaching the
        // ports is enough, they are reattached at the next boot.
        joystickService.setPorts(java.util.List.of());
        mouseAdapter = null;
        keyAdapter = null;
        joystickAdapter = null;
    }

    // =========================================================================
    // Validation / utilities
    // =========================================================================

    /**
     * Checks that the configured Kickstart exists and is usable on an A500; if not, tells
     * the user what is wrong. The check goes beyond the file's existence because the typical
     * ROM collection is full of images that are not socket Kickstarts (CDTV extended ROMs,
     * ReKick images, freezer cartridges): without this message the only symptom would be a
     * black screen. See {@link KickstartImage}.
     */
    /**
     * True if the AmiJa ROM must be mounted instead of a Kickstart: when none was chosen, or
     * when the file named is gone.
     */
    private static boolean usingBuiltinRom(String kickstartPath) {
        if (!BuiltinRom.isAvailable()) return false;
        return kickstartPath.isEmpty() || !new File(kickstartPath).isFile();
    }

    private boolean validateRom(AmigaConfig cfg) {
        String path = cfg.kickstartPath();

        // No Kickstart, or the file is gone: start anyway with the built-in ROM. The user is
        // told once, because it is not an error but a fallback they must know they are using.
        if ((path.isEmpty() || !new File(path).isFile()) && BuiltinRom.isAvailable()) {
            if (!welcomeShown) {
                welcomeShown = true;
                info(Messages.t("welcome.title"), Messages.t("welcome.builtinrom"));
            }
            System.out.println("[CONFIG] nessun Kickstart utilizzabile: uso la ROM AmiJa integrata");
            return true;
        }

        if (path.isEmpty()) {
            // No Kickstart set: almost always the very first start. An error saying "set
            // kickstart.path" does not help someone who does not even know what a Kickstart
            // is or why the emulator has none: the first time the situation is explained in
            // full, after that a reminder of what is missing is enough.
            if (!welcomeShown) {
                welcomeShown = true;
                info(Messages.t("welcome.title"), Messages.t("welcome.rom"));
            } else if (cfg.autoEnabled()) {
                // In Auto mode the path does not come from 'kickstart.path' but from one of
                // the two boxes of the popup: saying which one is missing is the useful part.
                error(Messages.t("error.rom.missing.auto",
                        Messages.t(cfg.autoProfile().usesKickstart31()
                                ? "label.kickstart31" : "label.kickstart13").replace(":", "")));
            } else {
                error(Messages.t("error.rom.missing", AmigaConfig.FILE_NAME));
            }
            return false;
        }
        if (!new File(path).isFile()) {
            error(Messages.t("error.rom.notfound", path));
            return false;
        }
        try {
            KickstartImage image = KickstartImage.load(Paths.get(path));
            switch (image.compatibility()) {
                case EXTENDED_ROM:
                    error(Messages.t("error.rom.extended", path));
                    return false;
                case RELOCATED:
                    error(Messages.t("error.rom.relocated", path,
                            String.format("$%06X", image.resetPc())));
                    return false;
                case NOT_A_KICKSTART:
                    error(Messages.t("error.rom.invalid", path,
                            image.incompatibilityReason()));
                    return false;
                default:
                    break;
            }
        } catch (IOException e) {
            error(Messages.t("error.rom.invalid", path, String.valueOf(e.getMessage())));
            return false;
        }
        return true;
    }

    private void error(String message) {
        System.err.println("[CONFIG] " + message.replace('\n', ' '));
        if (frame != null) {
            JOptionPane.showMessageDialog(frame, message, Messages.t("error.title"), JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * On-screen information message. Unlike {@link #error} the text goes through HTML, which
     * wraps by itself within a fixed width: these messages are discursive and in a JLabel
     * would stay on a single line as wide as the screen.
     */
    private void info(String title, String message) {
        System.out.println("[CONFIG] " + message.replace('\n', ' '));
        if (frame != null) {
            String html = "<html><body style='width: 420px'>"
                    + message.replace("\n", "<br>") + "</body></html>";
            JOptionPane.showMessageDialog(frame, html, title, JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
