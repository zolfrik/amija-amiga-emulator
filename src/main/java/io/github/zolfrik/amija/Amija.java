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
import io.github.zolfrik.amija.cpu.Motorola68000;
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
import java.util.Date;
import java.util.Properties;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

/**
 * Classe principale di AmiJa, l'emulatore Amiga 500. Istanzia e collega tutti i
 * componenti interni, avviando la simulazione cycle-exact.
 *
 * <p>Funge anche da controller del ciclo di vita della macchina: la configurazione
 * è letta da {@link AmigaConfig} ed è modificabile a runtime tramite il popup
 * raggiungibile con <b>F12</b> ({@link ConfigDialog}). All'apertura del popup
 * l'emulazione va in pausa e il mouse host viene rilasciato; alla chiusura la
 * macchina riparte (a freddo o riprendendo) e il mouse viene ricatturato.
 */
public class Amija implements RemotePlayControl {

    private static final boolean VERBOSE = false;
    // Modalita' video corrente (PAL/NTSC): letta da config a ogni avvio a freddo
    // (bootMachine). Il cambio nel popup F12 ha effetto solo dopo un cold restart.
    private VideoMode videoMode = VideoMode.PAL;
    // La spiegazione sul Kickstart mancante si dà una volta sola per avvio: se l'utente
    // chiude il popup senza impostarlo, alla seconda volta basta ricordargli cosa manca.
    private boolean welcomeShown;

    // --- Stato persistente tra i riavvii (finestra/display + configurazione) ---
    private AmigaConfig config;
    private AmigaDisplay display;
    private JFrame frame;
    // Componente a cui sono agganciati i listener di input: il frame in software, il
    // canvas GL (heavyweight) in OpenGL. Fissato a ogni (ri)creazione del display.
    private java.awt.Component inputComponent;

    // --- Componenti della macchina corrente (ricreati a ogni avvio a freddo) ---
    // I supporti montati sulla macchina accesa, fotografati alla sua costruzione;
    // null quando non c'è macchina. Sono l'hardware in vigore: il popup ci confronta
    // la lista dei supporti per sapere che cosa l'utente sta aggiungendo, e quello
    // aspetta il prossimo avvio invece di cambiare la macchina sotto al gioco.
    private ConfigDialog.DriveHardware activeHardware;
    private MasterClock clock;
    private Paula paula;
    private VirtualCRT virtualCRT;
    private Motorola68000 cpu;
    private AbstractAgnus agnus;
    private Keyboard keyboard;
    private AmigaMouseAdapter mouseAdapter;
    private AmigaKeyAdapter keyAdapter;
    private AmigaJoystickKeyAdapter joystickAdapter;
    // Game port correnti (ricreate a ogni boot): Port 0 = mouse/JOY0DAT, Port 1 = joystick/JOY1DAT.
    private GamePort gamePort1;
    private GamePort gamePort2;
    // Servizio joystick reale (GLFW): singleton, avviato pigramente solo se selezionato.
    private final GlfwJoystickService joystickService = GlfwJoystickService.getInstance();

    // --- Gioco a distanza (docs/remote-play-plan.md). Uno dei due, mai entrambi:
    // o questa macchina emula e serve un ospite, o non emula affatto e ne è l'ospite.
    // Avvio solo da -D finché la Fase 5 non porta la sezione nel popup F12.
    private RemotePlayHost remotePlayHost;
    /** Apertura della porta sul router mentre si ospita (§7.1); null se non richiesta. */
    private volatile PortMapper portMapper;
    private Thread routerCloser;
    private volatile RemotePlayGuest remotePlayGuest;
    private RemotePlaySettings remotePlaySettings = RemotePlaySettings.off();
    /** Lato ospite: raccoglie i gesti locali nel pacchetto da spedire. */
    private RemoteInputSender remoteInputSender;
    // --- Collegarsi a un host: la sessione si apre col popup ANCORA APERTO -------------
    // Il collegamento vero avviene su un thread suo (l'handshake puo' metterci secondi e
    // l'EDT non puo' aspettarlo), mentre la macchina locale resta montata: cosi' il popup
    // puo' raccontare come sta andando e un fallimento ha ancora una finestra in cui essere
    // detto. A smontare la macchina — che e' la parte che da dentro un dialog modale non si
    // fa — si arriva solo se l'utente conferma con OK (applyPendingRemotePlayTransition).
    private volatile boolean joining;
    private String joiningAddress;
    /** Chiusura chiesta mentre il tentativo era in volo. Protetto da {@code this}. */
    private boolean joinCancelled;
    /** Vero quando la macchina e' stata davvero smontata per fare l'ospite. */
    private volatile boolean guestMode;
    /** Ultimo esito negativo del gioco a distanza, che resta nella riga di stato. */
    private volatile String remotePlayLastError;
    private boolean pendingLeave;
    private final FloppySound[] floppySounds = new FloppySound[4];
    private final java.util.List<HardFile> hardFiles = new java.util.ArrayList<>();
    // Sorgenti di attività per il LED HD del display, una per unità (WHDLoad, master,
    // slave) nell'ordine in cui devono comparire da sinistra a destra.
    private final java.util.List<java.util.function.BooleanSupplier> hdLedSources =
            new java.util.ArrayList<>();

    public static void main(String[] args) {
        // PRIMA di ogni altra cosa (in particolare prima che AWT si inizializzi):
        // dichiara il processo per-monitor DPI aware. Il launcher nativo di jpackage
        // non ha manifest e partirebbe DPI-unaware, facendo fotografare ad AWT i font
        // di sistema a 96 DPI; alla prima JFileChooser quelle desktop property vengono
        // rilette alla DPI reale e i font dell'intero Look and Feel si ingrandiscono.
        // Sotto java.exe (manifest dpiAware=true/PM) e' un no-op.
        WindowsDpiAwareness.ensurePerMonitorAware();
        // Lo scaling HiDPI di Java2D e' spento SEMPRE, e va spento QUI, prima che il
        // toolkit AWT/Swing si inizializzi (la prima init avviene in
        // applyWindowsLookAndFeel): il toolkit lo fissa una volta sola all'avvio.
        //
        // Da qui in poi 1 pixel Java = 1 pixel fisico, e le due scale sono indipendenti:
        //  - la finestra emulata la ingrandisce AmiJa, di un fattore INTERO
        //    (video.integerScaling), in AmigaDisplay/DisplayPanel per il renderer software
        //    e sulla GPU per quello OpenGL;
        //  - l'interfaccia la ingrandisce il Look and Feel, di un fattore anche
        //    frazionario, senza toccare la finestra emulata.
        // Lasciando decidere Java, invece, un desktop al 150% scalerebbe TUTTO per 1,5 —
        // finestra emulata compresa, con interpolazione: l'opposto dell'integer scaling.
        // Le due property insieme non sono ridondanti: la prima e' quella che disattiva
        // lo scaling di sistema, la seconda e' la cintura di sicurezza.
        // Vedi docs/gui-scaling-flatlaf-plan.md.
        System.setProperty("sun.java2d.uiScale.enabled", "false");
        System.setProperty("sun.java2d.uiScale", "1");
        applyUiScale(AmigaConfig.load());
        applyLookAndFeel();
        new Amija().start();
    }

    /**
     * Passa a FlatLaf il fattore di scala dell'interfaccia ({@code ui.scale}). Va fatto
     * PRIMA di installare il Look and Feel: {@code flatlaf.uiScale} viene letta all'init
     * di {@code UIScale}, cioe' una volta sola, e non e' modificabile a caldo — per
     * questo il cambio nel popup F12 rilancia il processo.
     *
     * <p><b>La property si imposta sempre, anche quando vale 1.</b> Non e' ridondanza:
     * con lo scaling di sistema spento (le due righe qui sopra) FlatLaf, in assenza di un
     * valore esplicito, dedurrebbe il fattore dalla dimensione del font di sistema. Su
     * questa macchina quel font e' 12 anche con il desktop al 150%, quindi dedurrebbe 1 e
     * andrebbe bene per caso; ma sotto il launcher jpackage esiste uno stato noto in cui
     * AWT rilegge i font alla DPI reale e quel 12 diventa 18 (vedi WindowsDpiAwareness),
     * e li' la deduzione darebbe 1,5. Un valore esplicito rende il comportamento
     * indipendente da quale font AWT abbia fotografato, e da quando.
     */
    private static void applyUiScale(AmigaConfig cfg) {
        System.setProperty("flatlaf.uiScale", cfg.uiScaleValue());
    }

    /**
     * Installa FlatLaf (tema scuro) sull'intera interfaccia Swing.
     *
     * <p>Scuro perche' i dialoghi di AmiJa dipingono gia' a mano la propria palette scura
     * (ConfigDialog, JoystickKeysDialog, AssetDownloadDialog, ShaderParamsDialog): con il
     * Look and Feel di sistema restavano chiari solo i componenti che disegnava lui —
     * combo, spinner, checkbox, scrollbar, tooltip, JOptionPane — dentro finestre scure.
     *
     * <p>Ma la ragione per cui la scelta e' FlatLaf e non un tema qualunque e' il suo
     * <em>user scaling</em>: scala l'interfaccia di un fattore anche frazionario
     * indipendentemente dallo scaling HiDPI di Java, che qui e' spento perche' la finestra
     * emulata deve ricevere pixel fisici esatti. Vedi {@link #applyUiScale}.
     *
     * <p>Se per qualsiasi motivo non si installasse, si ripiega sul Look and Feel di
     * sistema: l'interfaccia resta usabile, solo non scalata.
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
    // Ciclo di vita
    // =========================================================================

    public void start() {
        if (VERBOSE) {
            System.out.println("========================================");
            System.out.println("      AmiJa Emulator - Boot             ");
            System.out.println("========================================");
        }

        config = AmigaConfig.load();
        Messages.setLanguage(config.language()); // prima di creare qualsiasi UI
        // Lo stato salvato quando lo slot era uno solo finisce nello Slot 1: una volta, qui.
        SaveStateManager.migrateLegacySlot();
        boolean overscan = config.overscanEnabled();
        int fw = config.videoMode().displayWidth(overscan);
        int fh = config.videoMode().displayHeight(overscan);
        display = new AmigaDisplay(fw, fh, config.windowMode(), config.renderer(),
                config.integerScaling(), config.monitorIndex());
        frame = display.getFrame();
        inputComponent = display.getInputComponent();
        // Un disco droppato su un drive aggiorna la config in memoria, così il popup F12
        // mostra il percorso nel drive relativo (e lo salva se l'utente conferma con OK).
        display.setDiskDropListener(this::rememberDroppedDisk);
        display.setShaderPreset(resolveShaderPath(config.shader()));
        installConfigHotkey();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdownRemotePlay();
            // La porta sul router va ritirata davvero, non solo ordinata: il thread che la
            // ritira è daemon e alla chiusura della JVM verrebbe ucciso a metà (§7.1).
            awaitRouterPortClosed();
            shutdownMachine();
            joystickService.shutdown(); // termina GLFW (solo alla chiusura del processo)
        }, "amiga-shutdown"));

        RemotePlaySettings remotePlay = readRemotePlaySettings();
        if (remotePlay.mode() == RemotePlaySettings.Mode.GUEST) {
            // L'ospite NON emula: niente Kickstart, niente ROM, niente MasterClock (§3).
            // La finestra c'è già; da qui in poi la riempie la rete al posto di Denise.
            joinRemoteHost(remotePlay);
            return;
        }

        if (validateRom(config)) {
            bootMachine();
            startHosting(remotePlay);
        } else {
            // ROM mancante/errata al primo avvio: apri il popup così l'utente può
            // impostare il percorso del Kickstart e gli altri parametri.
            SwingUtilities.invokeLater(this::openConfig);
        }
    }

    // =========================================================================
    // Gioco a distanza (docs/remote-play-plan.md §12, Fase 2: avvio via -D)
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
     * Apre la sessione lato host e attacca il video a {@code VirtualCRT}.
     *
     * <p>Un fallimento qui <b>non</b> è fatale: la macchina è già avviata e deve restare
     * giocabile in locale. Si dice cosa è andato storto e si prosegue senza ospite.
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
     * Chiede al router di inoltrare la porta, se l'utente lo vuole (§7.1).
     *
     * <p>Non con la <b>sola rete locale</b> accesa, e non è una precauzione formale: quella
     * spunta dice «da fuori non entra nessuno», e chi entrasse dalla porta appena aperta
     * verrebbe rifiutato dall'host un istante dopo. Sarebbe una porta aperta sul router di
     * casa per una connessione che non può funzionare — cioè il contrario di quel che
     * entrambe le opzioni promettono.
     */
    private void openRouterPort(int port) {
        closeRouterPort();
        if (!config.remotePlayUpnp() || config.remotePlayLanOnly()) {
            return;
        }
        portMapper = new PortMapper(port);
        portMapper.open();
    }

    /** Ritira la mappatura. Chiamata da ogni percorso che smette di ospitare. */
    private void closeRouterPort() {
        PortMapper mapper = portMapper;
        if (mapper == null) {
            return;
        }
        portMapper = null;
        // Su un thread suo: la rimozione parla col router e può metterci secondi, e chi
        // chiude l'ascolto è quasi sempre l'EDT dentro il popup.
        routerCloser = new Thread(mapper::close, "amija-remoteplay-upnp-close");
        routerCloser.setDaemon(true);
        routerCloser.start();
    }

    /**
     * Aspetta che la mappatura sia stata ritirata davvero. <b>Solo per lo shutdown hook.</b>
     *
     * <p>Un thread daemon che sta parlando col router viene ucciso quando la JVM esce, e la
     * porta resterebbe aperta: qui si concede il tempo di finire. Poco, però — se il router
     * non risponde è preferibile un'uscita pulita a una chiusura che sembra bloccata, e la
     * porta si chiude comunque da sé alla scadenza del lease, che è il motivo per cui una
     * scadenza c'è.
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
     * Si collega a un host e disegna quello che arriva.
     *
     * <p>Il frame che arriva è il <b>raster grezzo</b> 912x312, cioè esattamente ciò che
     * {@code AmigaDisplay.renderFrame} riceve dall'emulazione: crop, integer scaling,
     * overscan, shader e fullscreen restano quindi scelte locali di questa macchina e
     * funzionano senza saperne niente.
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
            // Meglio la propria macchina che una finestra vuota: da lì l'utente può
            // ritentare dal popup, che è anche dove ora si legge cosa è andato storto.
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
     * Dove finiscono i frame che arrivano dall'host: nello <b>stesso</b>
     * {@code renderFrame} che riceve il raster dall'emulazione.
     *
     * <p>È tutto quel che serve perché crop, integer scaling, overscan, shader, LED,
     * fullscreen e screenshot funzionino sull'ospite senza che una riga di quel codice
     * sappia della rete.
     */
    private VideoReceiver.FrameSink guestFrameSink() {
        return (pixels, w, h, info, frameId) -> display.renderFrame(pixels, w, h, info);
    }

    /**
     * Sorveglia la sessione e, se finisce da sé, lo dice all'utente.
     *
     * <p>Una spia che si spegne non avvisa chi sta guardando il gioco, e l'ultima immagine
     * congelata somiglia troppo a un emulatore piantato: quando il collegamento cade deve
     * comparire un messaggio, con il motivo. Se invece la sessione l'abbiamo chiusa noi
     * ({@link RemotePlayGuest.EndCause#LOCAL}) non c'è niente da annunciare — l'utente ha
     * appena premuto il pulsante che la chiude.
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

    /** Sull'EDT: la sessione è caduta da sé. Si dice perché, e si rimette in piedi la macchina. */
    private void onRemoteSessionLost(RemotePlayGuest guest) {
        if (remotePlayGuest != guest) {
            return;   // già sostituita o chiusa nel frattempo: nessun annuncio da fare
        }
        String message = Messages.t("msg.remoteplay.lost", lostCause(guest));
        boolean wasGuestMode = guestMode;
        // Va letto PRIMA di aprire il nostro messaggio, che è a sua volta modale.
        boolean popupOpen = modalDialogShowing();
        shutdownGuestSession();
        remotePlayLastError = message;
        reportRemotePlayProblem(message);
        if (wasGuestMode && !popupOpen) {
            // Senza macchina resterebbe una finestra morta. Col popup aperto invece non si
            // ricostruisce niente da qui: lo fa settleRemotePlayAfterConfig alla chiusura,
            // fuori dal dialog modale, che è la regola di tutto questo sviluppo.
            leaveGuestMode();
        }
    }

    /** Il motivo della caduta, nella lingua dell'utente. */
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
     * Perché un collegamento non è riuscito, nella lingua dell'utente.
     *
     * <p>«Impossibile collegarsi» e basta manda a controllare il router anche chi ha solo
     * sbagliato a copiare il codice. I quattro motivi di REJECT li conosce l'host e li
     * manda apposta (§3): buttarli via qui sarebbe sprecare l'unica informazione che
     * distingue un problema di rete da un errore di battitura.
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
     * Mostra un guasto del gioco a distanza, da qualunque thread lo si sia scoperto.
     *
     * <p>La finestra a cui parentarlo non è sempre la stessa, e sbagliarla si paga caro: il
     * popup F12 è <b>modale</b>, quindi un messaggio parentato alla finestra di gioco mentre
     * quello è aperto verrebbe bloccato da Swing — comparirebbe e non si potrebbe chiudere.
     * Si parenta quindi alla finestra attiva, che è il popup quando c'è e la finestra di
     * gioco quando non c'è.
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

    /** La finestra a cui parentare un messaggio perché non finisca bloccato da un modale. */
    private java.awt.Window activeWindow() {
        java.awt.Window active = java.awt.KeyboardFocusManager
                .getCurrentKeyboardFocusManager().getActiveWindow();
        if (active != null && active.isShowing()) {
            return active;
        }
        // Applicazione in secondo piano: nessuna finestra è attiva, ma se c'è un dialog
        // modale aperto il messaggio deve comunque appenderglisi.
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
                found = d;   // l'ultimo aperto è quello che blocca gli altri
            }
        }
        return found;
    }

    /**
     * Lato ospite: aggancia gli adapter di input veri a un {@link RemoteInputSender}
     * invece che a una macchina.
     *
     * <p>Sono gli <b>stessi</b> adapter che pilotano l'emulazione — quelli con dentro la
     * traduzione dei tasti, la cattura del puntatore, la mappa del joystick da tastiera —
     * e non si accorgono di niente: scrivono su {@code GamePortControls} e
     * {@code AmigaKeyTarget}, e qui dietro c'è la rete al posto dei registri. Si gioca
     * esattamente come in locale, mouse catturato compreso.
     *
     * <p><b>Quale porta, e cosa ci sta dentro, sono due decisioni diverse.</b> La porta la
     * assegna l'host — è la sua macchina, e deve poter tenere per sé quella su cui gioca —
     * ma la periferica la sceglie l'ospite, nella tendina di <i>quella</i> porta del proprio
     * popup F12. È ciò che rende sensato il caso normale: chi si collega gioca col proprio
     * joystick, o coi propri tasti se il joystick lo emula sulla tastiera. I tasti sono i
     * suoi, letti dalla sua {@code amiga.properties}; quelli dell'host non c'entrano nulla e
     * non hanno mai attraversato la rete. Il cablaggio vero sta in
     * {@link #applyGuestPortAssignment()}, che è anche ciò che ogni conferma del popup
     * richiama — così cambiare periferica a partita in corso funziona.
     *
     * <p>F12 non arriva fin qui: {@code installConfigHotkey} lo consuma prima, quindi apre
     * il popup <b>di questa</b> macchina e non tocca quella dell'host.
     */
    private void installGuestInput() {
        remoteInputSender = new RemoteInputSender();
        attachGuestInput();
    }

    /**
     * Aggancia gli adapter dell'ospite al componente di input corrente, <b>senza</b> toccare
     * il {@link RemoteInputSender}.
     *
     * <p>Separata da {@link #installGuestInput()} per una ragione precisa: rifare il sender
     * vuol dire riportare a zero i <b>contatori cumulativi del mouse</b>, e l'host applica la
     * loro differenza. Siccome la generazione di sessione non cambia, l'host non ha modo di
     * accorgersi che l'origine è cambiata e calcolerebbe un delta grande quanto tutto il
     * movimento fatto fin lì: il puntatore sull'Amiga farebbe un salto. Quando a cambiare è
     * solo la finestra — ed è per quello che questa esiste — i contatori devono proseguire.
     */
    private void attachGuestInput() {
        remotePlayGuest.setInputSource(remoteInputSender::snapshot);

        joystickAdapter = new AmigaJoystickKeyAdapter();
        keyAdapter = new AmigaKeyAdapter(remoteInputSender, frame);
        mouseAdapter = new AmigaMouseAdapter(remoteInputSender, frame,
                display != null ? display.getVideoScale() : 1);
        applyGuestPortAssignment();

        if (inputComponent != null) {
            // Stesso ordine dell'emulazione: il joystick prima, perché consuma i tasti
            // mappati su direzioni e fuoco, che non vanno anche alla tastiera Amiga.
            inputComponent.addKeyListener(joystickAdapter);
            inputComponent.addKeyListener(keyAdapter);
            inputComponent.addMouseListener(mouseAdapter);
            inputComponent.addMouseMotionListener(mouseAdapter);
            inputComponent.setFocusTraversalKeysEnabled(false);
            inputComponent.requestFocusInWindow();
        }
    }

    /**
     * Il «riavvio a freddo» di un ospite: si rifà la finestra, e basta.
     *
     * <p>{@link #coldRestart()} non va bene qui, e non per poco: finisce in
     * {@code bootMachine()}, cioè costruirebbe un'Amiga locale sotto il video che sta
     * arrivando dall'host. Delle impostazioni che lo richiedono, a un ospite ne riguardano
     * solo quelle della finestra — overscan, zoom, modo finestra, monitor, renderer — e per
     * quelle serve esattamente questo: display nuovo, adapter riagganciati al componente
     * nuovo (quello di prima è stato buttato via con la finestra), spia di rete ricollegata
     * perché le sue sorgenti stanno sul display.
     */
    private void recreateGuestDisplay() {
        // Prima di buttare la finestra: gli adapter vanno fermati, non solo dimenticati —
        // dentro ci sono un poller di tastiera e un listener di focus.
        detachInputAdapters();
        recreateDisplay();
        attachGuestInput();
        attachRemotePlayLed();
    }

    /**
     * Collega i controlli dell'ospite alla macchina: la game port che l'host gli ha
     * assegnato e, se concessa, la tastiera.
     *
     * <p>Va rifatto a ogni riavvio a freddo, perché la game port e la tastiera di prima non
     * esistono più. Se la macchina non c'è ancora, si stacca e basta: l'ospite continua a
     * vedere il video senza toccare niente.
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
        // Quella porta ora è dell'ospite: gli adapter locali che ci puntavano vanno tolti
        // di lì, o mouse e joystick dell'host continuerebbero a scriverci sopra.
        applyPortAssignments();

        // L'audio va derivato dal nuovo Paula: quello di prima non esiste più.
        if (paula != null) {
            RemotePlayHost host = remotePlayHost;
            paula.setPcmTap(remotePlaySettings.audioFormat() == 0
                    ? null : host::submitAudio);
        }
    }

    // =========================================================================
    // RemotePlayControl: quello che il popup F12 comanda (§9)
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
            // Da ospite vale quel che ha detto l'host nel WELCOME, non la propria
            // configurazione: quella dice a chi ospita quale porta cederebbe, che qui non
            // c'entra. Confonderle vorrebbe dire mostrare all'utente la tendina sbagliata.
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
        // Lo dice il router, non lo si indovina: un indirizzo inventato manda l'utente a
        // cercare il problema dove non c'è. Finché UPnP non ha risposto, si tace.
        PortMapper mapper = portMapper;
        return mapper == null || mapper.state() != PortMapper.State.MAPPED
                ? null : mapper.externalAddress();
    }

    /**
     * A che punto è l'apertura della porta sul router, in una riga leggibile.
     *
     * <p>{@code null} quando non c'è niente da dire, cioè quando UPnP non è stato chiesto:
     * una riga «non richiesto» sarebbe rumore in un popup che ne ha già tante.
     */
    @Override
    public String routerStatus() {
        PortMapper mapper = portMapper;
        if (mapper == null) {
            // Con la sola rete locale accesa la porta non si chiede apposta, e vale la pena
            // dirlo: è la differenza fra «UPnP non ha funzionato» e «non gliel'ho chiesto».
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
        // L'esito negativo resta scritto qui anche dopo che l'utente ha chiuso il messaggio:
        // il popup è l'unico posto in cui può rileggere perché non è andata.
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
            return null;   // già in corso o già collegati: il popup si riallinea da sé
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
     * Il tentativo di collegamento, su un thread suo.
     *
     * <p>Fuori dall'EDT perché l'handshake può metterci secondi — connessione TCP, PBKDF2 a
     * 120k iterazioni, e fino a dieci secondi di attesa su ogni lettura — e l'EDT è
     * esattamente il thread che in quei secondi deve continuare a disegnare il popup e a
     * dire «connessione in corso».
     *
     * <p><b>La macchina locale non viene toccata.</b> Resta montata e in pausa: se il
     * collegamento fallisce non c'è niente da ricostruire, e se riesce il video arriva già
     * mentre il popup è ancora aperto — che è il modo più diretto di far vedere che la cosa
     * funziona. A smontarla si arriva solo con l'OK dell'utente.
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
                // L'utente ha annullato mentre eravamo in volo (popup chiuso senza OK, o
                // «Disconnetti» premuto subito): la sessione appena aperta si chiude e non
                // se ne parla più. Senza questo resterebbe viva una sessione che nessuno
                // ha più modo di vedere né di fermare.
                guest.close();
                guest = null;
                failure = null;
            }
            if (guest != null) {
                // La sessione si pubblica per ULTIMA: è il campo volatile su cui l'EDT si
                // sincronizza, quindi tutto ciò che dovrà leggere dopo averlo visto pieno
                // — a partire dai parametri di sessione — dev'essere già stato scritto.
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
            // La macchina locale non è mai stata smontata: basta chiudere la sessione, e il
            // popup può restare aperto com'era.
            cancelGuestSession();
            return false;
        }
        pendingLeave = true;
        return true;
    }

    /** Chiude la sessione verso l'host, compresa quella che si sta ancora aprendo. */
    private void cancelGuestSession() {
        synchronized (this) {
            joinCancelled = true;
        }
        shutdownGuestSession();
        remotePlayLastError = null;
    }

    /**
     * Aggancia la spia "NET" dell'overscan allo stato della sessione.
     *
     * <p>Va rifatto a ogni ricreazione del display, perché le sorgenti stanno sul display
     * e quello di prima è stato buttato via.
     */
    private void attachRemotePlayLed() {
        if (display == null) return;
        display.setRemotePlayLedSource(
                () -> isHosting() || isGuest() || joining,
                () -> {
                    if (hasGuest()) return true;
                    // Una lettura sola del campo: fra "c'è una sessione" e "è in pausa" può
                    // essercene stata una chiusura, e la seconda lettura sarebbe un null.
                    RemotePlayGuest guest = remotePlayGuest;
                    return guest != null && guest.isAlive() && !guest.hostPaused();
                });
    }

    /** Formato audio dalla configurazione, nella forma che il protocollo usa. */
    private int audioFormatFromConfig() {
        return switch (config.remotePlayAudioMode()) {
            case "pcm16", "pcm" -> AudioBlockHeader.FORMAT_PCM16;
            case "off" -> 0;
            default -> AudioBlockHeader.FORMAT_ADPCM;
        };
    }

    /**
     * L'indirizzo di questa macchina sulla rete locale.
     *
     * <p>Il trucco della {@code connect} su un indirizzo esterno (§7.2): non manda un solo
     * byte — UDP non stabilisce niente — ma costringe il sistema a scegliere l'interfaccia
     * con cui uscirebbe, che è quella giusta da mostrare. {@code InetAddress.getLocalHost()}
     * su una macchina con più schede restituisce spesso quella sbagliata.
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
     * Esegue la transizione a ospite (o il ritorno a macchina) decisa nel popup.
     *
     * <p>Fuori dal dialog, e non per pignoleria: un ospite non emula niente, quindi unirsi
     * vuol dire <b>smontare la macchina</b>, e farlo sull'EDT dentro una finestra modale
     * mentre il thread di emulazione gira è il modo di collezionare guasti intermittenti.
     */
    private boolean applyPendingRemotePlayTransition() {
        if (pendingLeave) {
            pendingLeave = false;
            leaveGuestMode();
            return true;
        }
        if (guestMode || !isGuest()) {
            // Niente da smontare: o lo si è già, oppure la sessione è caduta fra l'OK e
            // adesso, e allora la macchina locale — mai smontata — deve ripartire.
            return false;
        }
        // La sessione è già aperta e sta già disegnando (connectToHost). Qui resta la sola
        // cosa che da dentro il popup non si poteva fare: smontare la macchina.
        shutdownMachine();
        guestMode = true;
        installGuestInput();
        attachRemotePlayLed();
        return true;
    }

    /**
     * Torna a essere una macchina normale dopo essere stato un ospite.
     *
     * <p>{@link #shutdownMachine()} va chiamato anche se di macchina non ce n'è: i campi
     * degli adapter di input sono occupati da quelli dell'ospite, che sono agganciati al
     * componente di input e vanno staccati. Senza, il boot ne aggiungerebbe una seconda
     * serie sopra la prima e ogni tasto verrebbe consegnato due volte.
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
     * Riallinea macchina e sessione dopo la chiusura del popup, per tutti i casi che non
     * sono la transizione confermata.
     *
     * @return {@code true} se ha già rimesso in piedi la macchina e il chiamante non deve
     *         fare altro
     */
    private boolean settleRemotePlayAfterConfig(ConfigDialog.Result result) {
        if (result == ConfigDialog.Result.REMOTE_PLAY_TRANSITION) {
            return false;   // se ne occupa il ramo dedicato dello switch
        }
        if (guestMode) {
            if (!isGuest()) {
                // La sessione è caduta mentre il popup era aperto: l'avviso l'utente l'ha
                // già visto (onRemoteSessionLost), ma la macchina va ricostruita qui, che è
                // fuori dal dialog modale.
                leaveGuestMode();
                return true;
            }
            return false;
        }
        if (joining || isGuest()) {
            // Ci si è collegati dal popup ma non si è confermato con OK. Confermare è
            // l'atto che smonta la macchina locale: senza, la sessione va chiusa, o si
            // resterebbe con l'emulazione locale e il video dell'host sullo stesso schermo.
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

    /** Chiude la sessione di gioco a distanza, da qualunque lato la si stia tenendo. */
    private void shutdownRemotePlay() {
        shutdownHostSession();
        shutdownGuestSession();
    }

    /** Smette di ospitare e stacca video e audio dalla rete. */
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
        // La porta ceduta all'ospite torna a chi possiede la macchina, con la periferica
        // che la sua configurazione le assegna: senza questo resterebbe vuota, e chi ha
        // appena smesso di ospitare si ritroverebbe una game port morta.
        applyPortAssignments();
    }

    /**
     * Chiude la sessione verso l'host, e <b>solo</b> quella: i due ruoli si spengono
     * separatamente perché annullare un collegamento non deve smettere di ospitare.
     */
    private void shutdownGuestSession() {
        RemotePlayGuest guest = remotePlayGuest;
        if (guest == null) return;
        // Il campo si azzera PRIMA della chiusura: il sorvegliante confronta la sessione che
        // ha visto morire con quella corrente, e deve trovarla già sostituita per sapere che
        // a chiuderla siamo stati noi e che non c'è niente da annunciare.
        remotePlayGuest = null;
        guest.close();
    }

    /** Registra la hotkey globale F12 (apre il popup di configurazione). */
    private void installConfigHotkey() {
        if (inputComponent == null) return;
        // Aggiunto PRIMA degli adapter di macchina: consuma F12 prima che raggiunga
        // la tastiera Amiga (dove sarebbe HELP). Va sul componente di input (canvas GL in
        // OpenGL): gli eventi tastiera del canvas heavyweight non risalgono al frame.
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
                // Va consumato anche il rilascio: senza, alla tastiera Amiga arriverebbe
                // il solo key-up di HELP (F12 è mappato lì) senza la pressione.
                if (e.getKeyCode() == KeyEvent.VK_F12) {
                    e.consume();
                }
            }
        });
    }

    // =========================================================================
    // Popup di configurazione (F12)
    // =========================================================================

    /**
     * Mette in pausa l'emulazione, rilascia il mouse e mostra il popup. In base alla
     * scelta dell'utente salva/riavvia, riavvia a freddo, esegue un soft reset o
     * riprende l'esecuzione; infine ricattura il mouse.
     */
    private void openConfig() {
        boolean wasRunning = clock != null && clock.isRunning();
        if (clock != null) clock.pause();
        // Il popup è modale e mette in pausa: senza avvisarlo, l'ospite vedrebbe lo
        // schermo fermo e crederebbe di aver perso il collegamento (§9).
        if (remotePlayHost != null) remotePlayHost.notifyPaused(true);
        if (mouseAdapter != null) mouseAdapter.release();
        boolean exclusiveFullScreenSuspended = display != null && display.suspendExclusiveFullScreen();

        // Stato dei floppy prima della modifica: serve a calcolare il diff da applicare
        // a caldo (inserimento/sostituzione/espulsione) quando l'utente conferma con OK.
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
        // Scala dell'interfaccia: e' l'unica impostazione rimasta che richiede di
        // rilanciare il processo, perche' flatlaf.uiScale si legge all'init del LAF.
        String oldUiScale = config.uiScaleValue();
        AmigaWindowMode oldWindowMode = config.windowMode();
        int oldMonitorIndex = config.monitorIndex();
        VideoMode oldVideoMode = config.videoMode();
        Chipset oldChipset = config.chipset();
        RendererType oldRenderer = config.renderer();
        String oldShader = config.shader();
        // Parametri della macchina che hanno effetto solo alla costruzione: se cambiano
        // il riavvio a freddo lo fa l'emulatore da sé. Serve soprattutto alla modalità
        // Auto, dove a cambiarli non è l'utente ma la spunta di un hard disk o di
        // WHDLoad: pretendere che sia lui a premere "Riavvia" sarebbe un tranello.
        String oldKickstart = config.kickstartPath();
        int oldChipRam = config.chipRamKb();
        int oldSlowRam = config.slowRamKb();
        int oldFastRam = config.fastRamKb();

        boolean oldAuto = config.autoEnabled();

        ConfigDialog.Result result = ConfigDialog.show(frame, config,
                this::takeScreenshotFile, this::updateShaderParam,
                this::saveState, this::loadState, this, activeHardware);

        // Mentre il popup era aperto una sessione di gioco a distanza può essersi aperta
        // (il pulsante «Collega» agisce subito) o essere caduta. Montare e smontare
        // macchine è roba da qui, non da dentro un dialog modale.
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
                // OK applica a caldo input/floppy; le impostazioni display e quelle
                // della macchina richiedono la ricreazione di finestra e componenti,
                // quindi un cold restart.
                saveConfig();
                if (!oldUiScale.equals(config.uiScaleValue())) {
                    relaunchApplication(); // non ritorna se il rilancio riesce
                }
                applyRuntimeInputConfig();
                boolean windowChanged =
                        displayConfigChanged(oldOverscan, oldIntegerScaling, oldWindowMode,
                                oldMonitorIndex)
                        || oldVideoMode != config.videoMode()
                        || oldRenderer != config.renderer();
                // Fuori da Expert i parametri della macchina non li sceglie l'utente: li
                // deduce l'emulatore dalla lista dei supporti. Cambiarli subito
                // vorrebbe dire spegnere e riaccendere l'Amiga perché è stato aggiunto
                // un drive, cioè buttare via la partita in corso per una modifica che
                // l'utente credeva innocua. Restano quindi scritti in configurazione e
                // diventano effettivi al prossimo avvio; la lista li marca con un
                // asterisco perché si sappia che stanno aspettando. Il ragionamento vale
                // finché si resta nella stessa modalità: accendere o spegnere Expert
                // ridefinisce la macchina da capo, e lì il riavvio è il punto.
                boolean deferHardware = oldAuto && config.autoEnabled();
                boolean needsColdRestart = windowChanged
                        || (!deferHardware
                            && (oldChipset != config.chipset()
                                || !oldKickstart.equals(config.kickstartPath())
                                || oldChipRam != config.chipRamKb()
                                || oldSlowRam != config.slowRamKb()
                                || oldFastRam != config.fastRamKb()));
                if (guestMode) {
                    // Un ospite non ha macchina da riavviare: dei parametri qui sopra gli
                    // riguardano solo quelli della finestra, e la sola cosa che va rifatta
                    // è quella. Il resto — Kickstart, memorie, chipset — descrive una
                    // macchina che qui non esiste, e riavviarla per applicarli vorrebbe
                    // dire costruirla mentre si sta guardando quella di qualcun altro.
                    if (windowChanged) {
                        recreateGuestDisplay();
                        displayRecreated = true;
                    } else if (config.renderer() == RendererType.OPENGL
                            && !oldShader.equals(config.shader())) {
                        display.setShaderPreset(resolveShaderPath(config.shader()));
                    }
                } else if (needsColdRestart) {
                    // Nessun rilancio della JVM: da quando lo scaling e' applicato da
                    // AmigaDisplay e non da sun.java2d.uiScale, integer scaling e
                    // renderer si applicano ricreando finestra e macchina, che resta
                    // dentro lo stesso processo (piu' rapido).
                    coldRestart();
                    displayRecreated = true;
                } else {
                    // Nessun cold restart: se è cambiato solo lo shader (renderer OpenGL),
                    // applicalo a caldo alla superficie GL.
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
                // La scala dell'interfaccia e' l'unica impostazione che richiede di
                // rilanciare il processo: flatlaf.uiScale si legge all'init del Look and
                // Feel e non e' modificabile a caldo. L'integer scaling invece lo applica
                // AmigaDisplay alla presentazione, quindi gli basta il cold restart —
                // stesso processo, molto piu' rapido.
                if (!oldUiScale.equals(config.uiScaleValue())) {
                    relaunchApplication(); // non ritorna se il rilancio riesce
                }
                // Da ospite il pulsante «Riavvia» non c'è nemmeno (non c'è la macchina che
                // riavvierebbe); se ci si arrivasse lo stesso, si rifà la sola finestra —
                // costruire un'Amiga qui vorrebbe dire metterla sotto il video dell'host.
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
                if (cpu != null) cpu.reset();
                resumeOrStart();
            }
            case STATE_RESTORE -> {
                // "Load State" su uno stato preso con un'altra macchina: la si ricostruisce
                // com'era e si ritenta. Il popup si è chiuso senza applicare le proprie
                // modifiche, quindi qui non c'è nessun applyConfig da fare.
                String msg = restoreStateAfterRebuild();
                displayRecreated = true; // coldRestart ha già rifatto finestra e display
                resumeOrStart();
                JOptionPane.showMessageDialog(frame, msg, Messages.t("app.title"),
                        JOptionPane.INFORMATION_MESSAGE);
            }
            case REMOTE_PLAY_TRANSITION -> {
                // Confermare un collegamento smonta la macchina; lasciare la sessione la
                // rimonta. Il popup non fa né l'una né l'altra: qui c'è il posto giusto,
                // fuori dal dialog modale e sullo stesso percorso del riavvio a freddo.
                saveConfig();
                if (!applyPendingRemotePlayTransition()) {
                    // Non c'era più niente da applicare (la sessione è caduta fra l'OK e
                    // adesso): la macchina locale è ancora lì, in pausa, e va ripresa.
                    resumeOrStart();
                }
                if (exclusiveFullScreenSuspended && display != null) {
                    display.resumeExclusiveFullScreen();
                }
                recaptureMouse();
                return;   // niente altro: la macchina o non c'è o è appena partita
            }
            case CANCEL -> resumeOrStart();
            case QUIT -> { System.exit(0); return; } // lo shutdown hook spegne la macchina
        }
        if (remotePlayHost != null) remotePlayHost.notifyPaused(false);
        if (exclusiveFullScreenSuspended && !displayRecreated && display != null) {
            display.resumeExclusiveFullScreen();
        }
        recaptureMouse();
    }

    /**
     * Applica a caldo le modifiche ai floppy decise nel popup, confrontando lo stato
     * salvato in {@link #config} con quello precedente all'apertura. Per ogni drive:
     * collega/scollega, espelle il disco rimosso e inserisce quello aggiunto. In caso
     * di sostituzione espelle, attende 1500 ms (come nel drag-and-drop, così il
     * Kickstart rileva l'espulsione) e poi inserisce il nuovo disco. Tutto su un thread
     * daemon per non bloccare l'EDT, mentre la macchina è già in esecuzione.
     */
    private void applyFloppyChanges(String[] oldDisks, boolean[] oldEnabled, boolean[] oldReadOnly) {
        applyFloppyChanges(oldDisks, oldEnabled, oldReadOnly, -1, false);
    }

    /**
     * @param skipIndex drive già allineato dal chiamante (es. quello appena ricevuto
     *                  via drag-and-drop), da escludere dal diff; -1 = nessuno
     */
    private void applyFloppyChanges(String[] oldDisks, boolean[] oldEnabled,
                                    boolean[] oldReadOnly, int skipIndex) {
        applyFloppyChanges(oldDisks, oldEnabled, oldReadOnly, skipIndex, false);
    }

    /**
     * @param deferHardware collegare e scollegare un drive è una modifica all'hardware:
     *                      con {@code true} non si tocca, e i drive restano quelli con
     *                      cui la macchina è stata accesa fino al prossimo avvio. Il
     *                      disco dentro un drive collegato continua a cambiare a caldo,
     *                      compreso il drive che la configurazione considera ormai
     *                      staccato: lì il disco viene espulso e il lettore resta,
     *                      vuoto, pronto a riceverne un altro.
     */
    private void applyFloppyChanges(String[] oldDisks, boolean[] oldEnabled,
                                    boolean[] oldReadOnly, int skipIndex,
                                    boolean deferHardware) {
        if (agnus == null) return;
        // Prima del diff: propaga agli altri drive i dischi dello zip multi-disco
        // eventualmente inserito in DF0 (o rimuove quelli dello zip precedente).
        //
        // In modalità Auto la ridistribuzione va forzata (oldDf0 = null). Lì la lista dei
        // supporti è ricostruita da capo a ogni conferma, quindi i dischi estratti dallo
        // zip non ci sono più e non esiste uno stato "l'utente ha espulso il disco 2 a
        // mano" da rispettare: senza la forzatura, uno zip multi-disco lasciato com'era
        // perderebbe tutti i dischi tranne il primo.
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
                // Rimandando l'hardware conta il drive che c'è, non quello che la
                // configurazione descrive: è ancora lì e il suo disco si cambia.
                if (!(deferHardware ? drive.isConnected() : newEnabled[i])) {
                    // Drive scollegato: niente disco dentro.
                    if (drive.isDiskInserted()) {
                        System.out.println("[CONFIG] DF" + i + ": espulsione (drive scollegato)");
                        drive.ejectDisk();
                    }
                    continue;
                }
                String oldPath = norm(oldDisks[i]);
                String newPath = norm(newDisks[i]);
                boolean readOnlyChanged = oldReadOnly != null && oldReadOnly[i] != newReadOnly[i];
                if (oldPath.equals(newPath) && !readOnlyChanged) continue; // nessuna modifica per questo drive
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

    /** Normalizza un percorso disco: null/spazi -> stringa vuota. */
    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * Distribuisce su DF1..DF3 i dischi di uno zip multi-disco inserito in DF0.
     *
     * <p>Uno zip che contiene più .adf/.ipf è un set di dischi: in DF0 va quello di
     * <b>avvio</b> e gli altri, nell'ordine in cui compaiono nell'archivio, nei
     * drive seguenti — ma solo se collegati e vuoti. L'ordine è quello che dà
     * {@link DiskImage#zipDiskOrder}: dentro l'archivio i nomi mettono spesso i
     * dischi dati prima del disco programma, e partire dal primo lascerebbe il
     * Kickstart a chiedere un disco per sempre. I dischi
     * così montati sono identificati dal percorso {@code <zip>!<n>}: quando lo zip
     * di DF0 viene sostituito o espulso vengono rimossi automaticamente, mentre
     * restano modificabili/espellibili uno a uno con la procedura normale.
     *
     * <p>Agisce solo se DF0 è <i>cambiato</i>: finché lo zip resta lo stesso, DF1..DF3
     * sono lasciati esattamente come l'utente li ha impostati (un disco espulso a mano
     * non viene rimontato). Aggiorna la configurazione, che è la sorgente sia del diff
     * a caldo sia del popup F12.
     *
     * @param oldDf0 percorso presente in DF0 prima della modifica;
     *               {@code null} per forzare la risincronizzazione (avvio macchina)
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
            // 1. libera i drive che tenevano un disco dello zip (vecchio o nuovo: la
            //    riassegnazione qui sotto è idempotente).
            if (isEntryOf(disk, previous) || isEntryOf(disk, df0)) {
                disk = "";
                config.setDriveDisk(i, "");
            }
            // 2. l'i-esimo disco dell'ordine di inserimento (DF0 ha preso il primo)
            //    va nell'i-esimo drive, se libero e collegato.
            if (i < order.length && disk.isEmpty() && config.driveEnabled(i)) {
                int entry = order[i];
                config.setDriveDisk(i, DiskImage.zipEntrySpec(df0, entry));
                config.setDriveReadOnly(i, true);
                System.out.println("[CONFIG] DF" + i + ": disco " + entry + " dello zip -> "
                        + entries.get(entry));
            }
        }
    }

    /** {@code true} se {@code disk} è un disco estratto dallo zip {@code zipPath}. */
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
        // Il drive che ha ricevuto il drop è già a posto: applica solo l'effetto a
        // catena sugli altri (set multi-disco montato o rimosso).
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
     * Applica (anche a caldo) le periferiche host scelte per le due game port. Per ogni
     * porta la property {@code input.portN.device} indica se è pilotata dal mouse, da un
     * joystick reale (GLFW), dal joystick su tastiera o se è vuota. "Port 0" corrisponde
     * alla game port 1 (mouse/JOY0DAT), "Port 1" alla game port 2 (joystick/JOY1DAT).
     *
     * <p>Idempotente e sicuro a caldo: reindirizza gli adapter mouse/tastiera sulla porta
     * corretta (abilitandoli/disabilitandoli) e comunica al servizio GLFW l'elenco delle
     * porte che usano un joystick reale.
     *
     * <p><b>La porta dell'ospite non è dell'host.</b> Mentre si ospita, la game port
     * assegnata all'ospite è fuori da questa funzione: cosa ci sia collegato lo decide lui
     * dal <i>suo</i> popup, e arriva nel pacchetto di feedback ({@code RemoteInputApplier}).
     * L'host non le assegna una periferica, non ci punta il mouse e non ci manda il joystick
     * — altrimenti la prima conferma dell'F12 locale scavalcherebbe la scelta dell'ospite,
     * e i due si contenderebbero la stessa porta a colpi di configurazione. La porta resta
     * riservata anche mentre l'ospite non c'è: sta per arrivare, o è appena caduto.
     */
    private void applyPortAssignments() {
        if (gamePort1 == null || gamePort2 == null) return;

        PortDevice d0 = config.portDevice(0); // "Port 0" -> game port 1 (mouse/JOY0DAT)
        PortDevice d1 = config.portDevice(1); // "Port 1" -> game port 2 (joystick/JOY1DAT)

        // La porta ceduta all'ospite esce di scena qui: per l'host è come se non ci fosse.
        int guestPort = hostedGuestPort();
        if (guestPort == 0) d0 = null;
        if (guestPort == 1) d1 = null;

        // Periferica hardware riportata da ciascuna porta (per le letture JOYxDAT/POTINP).
        if (d0 != null) gamePort1.setDevice(d0.hardwareDevice());
        if (d1 != null) gamePort2.setDevice(d1.hardwareDevice());

        // Mouse host -> porta impostata su MOUSE (priorità a "Port 0" in caso di doppia).
        GamePort mousePort = (d0 == PortDevice.MOUSE) ? gamePort1
                           : (d1 == PortDevice.MOUSE) ? gamePort2 : null;
        if (mouseAdapter != null) {
            mouseAdapter.setPort(mousePort);
            mouseAdapter.setEnabled(mousePort != null);
        }

        // Joystick da tastiera: ciascuna porta ha i propri tasti (input.portN.joystick.*),
        // quindi entrambe possono essere pilotate insieme dalla stessa tastiera.
        if (joystickAdapter != null) {
            joystickAdapter.configure(0,
                    d0 == PortDevice.JOYSTICK_KEYBOARD ? gamePort1 : null,
                    config.joystickKeyMap(0));
            joystickAdapter.configure(1,
                    d1 == PortDevice.JOYSTICK_KEYBOARD ? gamePort2 : null,
                    config.joystickKeyMap(1));
        }

        // Joystick reale (GLFW) -> tutte le porte impostate su di esso, in ordine.
        java.util.List<GamePort> realPorts = new java.util.ArrayList<>(2);
        if (d0 == PortDevice.JOYSTICK_REAL) realPorts.add(gamePort1);
        if (d1 == PortDevice.JOYSTICK_REAL) realPorts.add(gamePort2);
        joystickService.setPorts(realPorts);

        // La porta ceduta si dichiara vuota, e per ULTIMA. Staccare da lì gli adapter
        // dell'host li fa rilasciare quel che tenevano premuto — cosa giusta e necessaria —
        // ma ogni rilascio fa dedurre alla GamePort una periferica, e resterebbe un
        // joystick fermo su una porta in cui non c'è collegato niente.
        //
        // Solo però se l'ospite NON è collegato. Se sta giocando, quella porta è già sua e
        // ciò che c'è dentro lo dice lui a ogni pacchetto: azzerarla qui — perché l'host ha
        // confermato il proprio popup per tutt'altro motivo — gli toglierebbe il joystick di
        // sotto per il tempo di un fotogramma, in mezzo a una partita.
        if (!hasGuest()) {
            if (guestPort == 0) gamePort1.setDevice(GamePortDevice.NONE);
            if (guestPort == 1) gamePort2.setDevice(GamePortDevice.NONE);
        }
    }

    /**
     * Lato ospite: cabla i propri adapter alla porta che l'host gli ha assegnato, secondo
     * la periferica che <b>lui</b> ha scelto nella tendina di quella porta.
     *
     * <p>È il gemello di {@link #applyPortAssignments()} per una macchina che non emula
     * niente: al posto della {@code GamePort} c'è {@link RemoteInputSender}, e i gesti
     * finiscono in un pacchetto invece che in un registro. Tutto il resto è identico, e
     * volutamente — il joystick da tastiera legge la mappa tasti <b>locale</b>
     * ({@code input.portN.joystick.*} di questa macchina), che è il punto di tutta la
     * faccenda: i tasti con cui si gioca sono i propri, non quelli di chi ospita.
     *
     * <p>L'altra porta viene sconfigurata: è la porta dell'host, e non c'è nessun modo
     * sensato di pilotarla da qui.
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
            // L'altra porta esiste sulla macchina dell'host e non è raggiungibile da qui:
            // configurarla vorrebbe dire consumare i tasti dell'utente per niente.
            joystickAdapter.configure(1 - mine, null, config.joystickKeyMap(1 - mine));
            joystickAdapter.configure(mine,
                    device == PortDevice.JOYSTICK_KEYBOARD ? remoteInputSender : null,
                    config.joystickKeyMap(mine));
        }
        joystickService.setPorts(device == PortDevice.JOYSTICK_REAL
                ? java.util.List.of(remoteInputSender) : java.util.List.of());

        // Per ULTIMA: sconfigurare gli adapter qui sopra li fa rilasciare quel che tenevano
        // premuto, e ogni rilascio fa dedurre al sender una periferica. Se la scelta la
        // dichiarasse prima, un passaggio a «Vuota» si riscriverebbe da sé in «Joystick».
        remoteInputSender.setDevice(device.hardwareDevice());
    }

    /**
     * La game port che questa macchina ha ceduto a un ospite, o {@code -1} se non ospita.
     *
     * <p>Volutamente indipendente dal fatto che un ospite ci sia <i>adesso</i>: la porta è
     * assegnata dal momento in cui si comincia ad ascoltare, e restituirla all'host fra un
     * ospite e il successivo vorrebbe dire vedersela sfilare da sotto a metà partita.
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

        boolean enabled = config.floppySoundEnabled() && config.driveEnabled(index);
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
     * Riavvia l'intero processo JVM (non il solo power-cycle della macchina): rilancia
     * un nuovo processo java con lo stesso classpath e working dir, poi esce. Serve per
     * le impostazioni che il toolkit fissa una sola volta all'avvio e che non sono
     * modificabili a caldo. Se il rilancio fallisce non esce: il chiamante prosegue con
     * un cold restart della sola macchina.
     *
     * <p>L'unico cliente e' il cambio di {@code ui.scale}: {@code flatlaf.uiScale} viene
     * letta all'init del Look and Feel, una volta sola. L'integer scaling della finestra
     * emulata NON passa piu' di qui — lo applica {@code AmigaDisplay} alla presentazione,
     * e gli basta ricreare la finestra.
     */
    private void relaunchApplication() {
        try {
            String javaBin = System.getProperty("java.home")
                    + File.separator + "bin" + File.separator + "java";
            // La home va RIPASSATA esplicitamente: qui si rilancia "java -cp ...", non il
            // launcher nativo, quindi le --java-options impostate da jpackage (fra cui
            // -Damija.home=$ROOTDIR) andrebbero perse e il nuovo processo ripiegherebbe
            // sulla cartella del jar, che dentro un'app-image e' <root>/app.
            // inheritIO: il nuovo processo riusa console/stdio correnti. La working dir
            // viene ereditata, quindi amiga.properties (gia' salvata) e' la stessa.
            new ProcessBuilder(javaBin,
                    "-D" + AppHome.PROPERTY + "=" + AppHome.home(),
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp", System.getProperty("java.class.path"),
                    Amija.class.getName())
                    .inheritIO()
                    .start();
            System.exit(0); // lo shutdown hook spegne la macchina corrente
        } catch (IOException ex) {
            System.err.println("[UI] Riavvio del processo non riuscito, eseguo un cold "
                    + "restart (il nuovo integer scaling sara' effettivo al prossimo "
                    + "riavvio manuale): " + ex.getMessage());
        }
    }

    /** Spegne e riaccende la macchina da zero (power-cycle) con la config corrente. */
    private void coldRestart() {
        shutdownMachine();
        recreateDisplay();
        if (validateRom(config)) {
            bootMachine();
        }
    }

    /** Ricrea finestra e display, necessario per impostazioni applicate solo al riavvio. */
    private void recreateDisplay() {
        if (display != null) {
            display.dispose();
        } else if (frame != null) {
            frame.dispose();
        }
        // Modalità video letta da config (non dal campo videoMode, ancora fermo al boot
        // precedente: in coldRestart recreateDisplay precede bootMachine).
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
     * Risolve il nome dello shader configurato nel percorso del preset ({@code .slangp} o
     * {@code .glslp}) sotto {@code shaders/}, anche in sottocartelle.
     * {@code "none"}/vuoto o file assente -&gt; {@code null} (passthrough).
     */
    private static java.nio.file.Path resolveShaderPath(String name) {
        return io.github.zolfrik.amija.display.render.gl.ShaderCatalog.resolve(name);
    }

    /**
     * Salva uno screenshot della finestra di gioco in {@code screenshots/} (software:
     * composizione Java2D; OpenGL: {@code glReadPixels}). Ritorna il file salvato o
     * {@code null} in caso di errore. Usato dal pulsante F12 "Take Screenshot".
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

    /** Applica a caldo un parametro dello shader OpenGL (dal popup "..."). */
    private void updateShaderParam(String name, Float value) {
        if (display != null && value != null) {
            display.setShaderParam(name, value);
        }
    }

    /**
     * Salva l'intero stato della macchina nello slot attivo
     * ({@code savestate/Slot<N>/state.a5s}, con N da {@code savestate.slot}). Invocato dal
     * pulsante F12 "Save State" a macchina in pausa (il popup è aperto). Ritorna il
     * messaggio di esito localizzato da mostrare all'utente.
     */
    private String saveState() {
        int slot = config.saveStateSlot();
        if (cpu == null || agnus == null) {
            return Messages.t("savestate.save.fail", Messages.t("savestate.nomachine"));
        }
        try {
            File file = SaveStateManager.slotFile(slot);
            SaveStateManager.save(file, cpu, agnus, gamePort1, gamePort2, config);
            System.out.println("[STATE] salvato " + file.getAbsolutePath());
            saveStateScreenshot(slot);
            return Messages.t("savestate.save.ok", slot, file.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[STATE] salvataggio fallito: " + e.getMessage());
            return Messages.t("savestate.save.fail", String.valueOf(e.getMessage()));
        }
    }

    /**
     * Salva accanto allo stato uno screenshot dello schermo
     * ({@code savestate/Slot<N>/state.png}), a nome fisso: ogni salvataggio sovrascrive
     * l'immagine precedente di quello slot. Un errore qui non invalida lo stato già
     * scritto, quindi viene solo segnalato a console.
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
     * Ripristina lo stato della macchina dallo slot attivo, sovrascrivendo i componenti
     * correnti (macchina in pausa). Invocato dal pulsante F12 "Load State".
     *
     * <p>Se lo stato è stato preso su una macchina diversa da quella accesa — altra
     * memoria, altro Kickstart, altri dischi — non si dice semplicemente di no: dentro
     * lo slot c'è anche la configurazione di allora, quindi la macchina giusta si può
     * ricostruire. Quello però vuol dire spegnerla e riaccenderla, e non si può fare da
     * qui: il popup è aperto ed è figlio della finestra che il riavvio distrugge. Si
     * ritorna allora {@link ConfigDialog.LoadStateOutcome#restart()}, il popup si chiude
     * e il seguito è in {@link #restoreStateAfterRebuild()}.
     */
    private ConfigDialog.LoadStateOutcome loadState() {
        int slot = config.saveStateSlot();
        if (cpu == null || agnus == null) {
            return ConfigDialog.LoadStateOutcome.done(
                    Messages.t("savestate.load.fail", Messages.t("savestate.nomachine")));
        }
        File file = SaveStateManager.slotFile(slot);
        if (!file.isFile()) {
            return ConfigDialog.LoadStateOutcome.done(Messages.t("savestate.load.none"));
        }
        try {
            SaveStateManager.load(file, cpu, agnus, gamePort1, gamePort2, config);
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
     * {@code true} se lo slot descrive una macchina diversa da quella accesa e quindi
     * ricostruibile. Falso quando la configurazione salvata è già quella corrente: in
     * quel caso il rifiuto viene da altro (formato, struttura) e riavviare non
     * servirebbe a nulla se non a buttare via la sessione in corso.
     */
    private boolean canRebuildMachineFor(File slot) {
        try {
            return config.machineDiffers(SaveStateManager.readInfo(slot).config());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Seconda metà di "Load State" quando lo stato pretende un'altra macchina: rimette
     * in vigore la configurazione conservata nello slot, ricostruisce la macchina e
     * ritenta il caricamento. Ritorna il messaggio da mostrare all'utente.
     *
     * <p>Le impostazioni ripristinate sono le sole che descrivono la macchina (Kickstart,
     * memorie, chipset, modo video, supporti): lingua, finestra, monitor, renderer e
     * shader restano quelli scelti dall'utente — vedi
     * {@link AmigaConfig#restoreMachineSettings}.
     */
    private String restoreStateAfterRebuild() {
        // Lo slot è quello scelto nel popup: la scelta ha effetto subito, quindi è ancora
        // in configurazione anche se il popup si è chiuso senza applicare il resto. Lo si
        // legge PRIMA di restoreMachineSettings — che rimette in vigore le sole chiavi di
        // macchina, e savestate.slot non è una di quelle.
        int slot = config.saveStateSlot();
        File file = SaveStateManager.slotFile(slot);
        try {
            Properties saved = SaveStateManager.readInfo(file).config();
            config.restoreMachineSettings(saved);
            saveConfig();
            System.out.println("[STATE] configurazione dello slot ripristinata, riavvio la macchina");
            coldRestart();
            if (cpu == null || agnus == null) {
                return Messages.t("savestate.load.fail", Messages.t("savestate.nomachine"));
            }
            // La macchina è appena partita e sta emulando: va fermata davvero (non solo
            // segnata come in pausa) prima di sovrascriverle sotto i componenti.
            if (clock != null) clock.pauseAndWait(500);
            SaveStateManager.load(file, cpu, agnus, gamePort1, gamePort2, config);
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
     * Riprende l'emulazione dopo il popup, o la avvia se non era mai partita.
     *
     * <p><b>Da ospite non fa niente, ed è la parte che conta.</b> Un ospite non emula (§3):
     * non ha un {@code MasterClock} da riprendere, e il ramo «allora avviala» costruirebbe
     * una macchina locale <i>sotto</i> il video che continua ad arrivare dall'host — due
     * Amiga che disegnano sulla stessa finestra. Ci si arrivava da ogni chiusura del popup,
     * OK e Annulla comprese, perché per un ospite {@code clock} è sempre {@code null}. È
     * diventato un percorso di tutti i giorni da quando l'ospite ha nel popup qualcosa che
     * gli serve davvero: la tendina della propria game port.
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
        // Subito dopo la chiusura di un dialog modale la finestra non è ancora la
        // "active window": requestFocusInWindow() è un no-op e il robot.mouseMove
        // viene scartato durante la transizione di focus (il cursore risulta nascosto
        // ma non agganciato al centro). Rinviamo brevemente la cattura, portando prima
        // la finestra in primo piano e forzando il focus.
        javax.swing.Timer timer = new javax.swing.Timer(150, e -> {
            frame.toFront();
            frame.requestFocus();
            // In OpenGL il fuoco tastiera deve tornare sul canvas (l'input è lì).
            if (inputComponent != null) inputComponent.requestFocusInWindow();
            target.captureMouse();
        });
        timer.setRepeats(false);
        timer.start();
    }

    // =========================================================================
    // Costruzione / distruzione della macchina
    // =========================================================================

    /** Costruisce e avvia un'intera macchina A500 a partire da {@link #config}. */
    private void bootMachine() {
        try {
            // Applica la lingua corrente: il rebuild del display (setDrives) rigenera
            // l'overlay drag-and-drop con le stringhe localizzate aggiornate.
            Messages.setLanguage(config.language());
            // Modalita' video (PAL/NTSC): determina clock, righe per frame e ID Agnus.
            // Letta qui così un cold restart dopo il cambio nel popup F12 la applica.
            videoMode = config.videoMode();
            final String kickstartPath = config.kickstartPath();

            // 1. Decodifica indirizzi (Gary) e memoria.
            Gary gary = Gary.defaultA500();
            RAM chipRam = new RAM(config.chipRamKb() * 1024);
            // La Region CHIP_RAM di defaultA500() ha physicalSize cablato a 512 KB:
            // allineala alla configurazione, altrimenti gli indirizzi sopra $07FFFF
            // wrappano sui primi 512 KB e il Kickstart rileva solo 512 KB di chip
            // (es. WHDLoad richiede chip contigua fino a $A0000 / 640 KB).
            gary.setChipRamSize(config.chipRamKb() * 1024);
            RAM slowRam = config.slowRamKb() > 0 ? new RAM(config.slowRamKb() * 1024) : null;

            // L'immagine viene normalizzata (header scartati, code troncate, overdump
            // collassati) prima di finire nel socket: Gary mappa per maschere di bit e
            // pretende una potenza di due. Vedi KickstartImage.
            // Senza un Kickstart utilizzabile si monta la ROM AmiJa inclusa nel jar:
            // e' firmware originale di questo progetto, quindi ridistribuibile, e
            // permette di avviare i giochi su floppy senza possedere una ROM
            // Commodore. Vedi BuiltinRom per cosa NON copre.
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

            // 2. CPU e bus.
            cpu = new Motorola68000(new BusRequest(), new BusResponse());

            // 3. Custom chips. Il chipset (OCS/ECS) seleziona la variante di Agnus/Denise.
            // Si rispetta SEMPRE la scelta dell'utente: l'address counter DMA di
            // AbstractAgnus deriva la maschera dei puntatori dalla dimensione della Chip
            // RAM (dmaPtrHiMask = chipMask & 0x1F0000), quindi OcsAgnus indirizza già la
            // Chip RAM fino a 2 MB. NON si forza più ECS quando la Chip RAM supera i
            // 512 KB: oltre a non essere necessario, manderebbe la selezione OCS sul path
            // ECS+Kickstart 3.1 (non ancora validato) provocando un guru all'avvio
            // (regressione: p.es. Agony via WHDLoad con Chip RAM 1 MB).
            Chipset chipset = config.chipset();
            final boolean ecs = chipset.isEcs();
            System.out.println("[CONFIG] Chipset: " + chipset);
            AbstractDenise denise = ecs ? new EcsDenise() : new OcsDenise();
            paula = new Paula();
            paula.startAudioOutput();

            CIAPinSet ciaAPins = new CIAPinSet();
            MOS8520 ciaA = new MOS8520(ciaAPins, true);
            MOS8520 ciaB = new MOS8520(new CIAPinSet(), false);

            // Game port 1 (JOY0DAT, "Port 0") e port 2 (JOY1DAT, "Port 1"). La periferica
            // host di ciascuna (mouse / joystick reale / joystick da tastiera / vuota) è
            // decisa da applyPortAssignments in base alla configurazione (property
            // input.port0.device / input.port1.device, tendine del popup F12).
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

            agnus = ecs
                    ? new EcsAgnus(cpu, gary, chipRam, slowRam, kickstart, paula, denise, ciaA, ciaB, videoMode)
                    : new OcsAgnus(cpu, gary, chipRam, slowRam, kickstart, paula, denise, ciaA, ciaB, videoMode);

            // Fast RAM (espansione Zorro II con AutoConfig). Assente di default (0):
            // la prima scheda di RAM Zorro II viene mappata dal Kickstart tipicamente
            // a $200000 e aggiunta alla memoria di sistema, rendendola disponibile a
            // programmi e WHDLoad. Va collegata PRIMA del reset della CPU.
            agnus.setFastRam(new FastRam(config.fastRamKb() * 1024));

            // Immediate blit: completa ogni blit istantaneamente (non cycle-exact).
            // Solo da amiga.properties (blitter.immediate), default false, non da F12.
            agnus.getBlitter().setImmediateMode(config.blitterImmediate());

            // Hard disk (master/slave): ogni unità abilitata con un .hdf valido diventa
            // un controller Zorro II con boot ROM. La numerazione delle partizioni DH
            // è progressiva fra le unità. Va collegato PRIMA del reset della CPU.
            setupHardDisks(agnus);

            virtualCRT = new VirtualCRT(agnus, denise, display);
            // La sessione di gioco a distanza sopravvive ai riavvii della macchina:
            // l'ospite vede semplicemente il reboot, invece di perdere il collegamento.
            // I suoi controlli vanno però riagganciati: game port e tastiera sono nuove.
            if (remotePlayHost != null) {
                virtualCRT.setRemotePlayHost(remotePlayHost);
            }

            Keyboard keyboard = new Keyboard(ciaA);
            agnus.setKeyboard(keyboard);
            this.keyboard = keyboard;

            // Adapter di input (ricreati a ogni avvio: legano i nuovi port/keyboard).
            // La porta di destinazione e lo stato abilitato di mouse/joystick-tastiera
            // sono impostati subito dopo da applyPortAssignments (idem per il joystick reale).
            joystickAdapter = new AmigaJoystickKeyAdapter();
            keyAdapter = new AmigaKeyAdapter(keyboard, frame);
            // Il fattore di scala viene dal display, non dalla config: è quello davvero
            // applicato alla finestra (già normalizzato a >= 1). Serve a riportare i
            // delta del mouse, che arrivano in pixel dello schermo, a pixel Amiga.
            mouseAdapter = new AmigaMouseAdapter(port1, frame,
                    display != null ? display.getVideoScale() : 1);
            // I listener vanno sul componente di input (il frame in software, il canvas GL
            // heavyweight in OpenGL: i suoi eventi non risalgono al frame). Gli adapter
            // continuano a ricevere il frame per fuoco/cursore/ricentraggio del mouse.
            if (inputComponent != null) {
                // Il joystick adapter deve venire prima: consuma i tasti mappati sulle
                // direzioni e sul fuoco, che non vanno inoltrati alla tastiera Amiga.
                inputComponent.addKeyListener(joystickAdapter);
                inputComponent.addKeyListener(keyAdapter);
                inputComponent.addMouseListener(mouseAdapter);
                inputComponent.addMouseMotionListener(mouseAdapter);
                inputComponent.setFocusTraversalKeysEnabled(false);
                inputComponent.requestFocusInWindow();
            }

            // Assegna le periferiche host alle due game port (mouse / joystick reale /
            // joystick da tastiera / vuota) secondo la configurazione.
            applyPortAssignments();

            // I controlli dell'ospite vanno riagganciati alle nuove game port e tastiera:
            // quelle della macchina precedente non esistono più.
            attachRemoteInput();

            // Drive (DF0..DF3): collegamento e disco da inserire all'avvio. Se DF0 ha
            // uno zip multi-disco, i dischi successivi popolano i drive liberi.
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
            // Da qui in poi questo è l'hardware in vigore: quello che il popup non può
            // più cambiare senza un riavvio.
            activeHardware = captureDriveHardware();

            // LED di stato + suoni estetici del floppy (entrambi da configurazione).
            // Il LED HD riflette gli hard disk presenti (WHDLoad e/o master/slave, in ordine).
            display.setStatusLedSource(ciaA::isPowerLedOn);
            display.setHdLedSources(hdLedSources.toArray(new java.util.function.BooleanSupplier[0]));
            attachRemotePlayLed();
            display.setStatusLedsEnabled(config.ledsVisible());

            // Overlay diagnostici opzionali (solo da amiga.properties, non nel popup F12).
            display.setFpsOverlayEnabled(config.fpsOverlayEnabled());
            display.setFrameCounterOverlayEnabled(config.frameCounterOverlayEnabled());
            applyFloppySounds(dfs);

            // 4. Master clock + reset + avvio.
            clock = new MasterClock(agnus, videoMode, virtualCRT);
            // Se il thread di emulazione muore per un'eccezione, senza questo aggancio
            // il display si limiterebbe a congelarsi (o a restare nero, se il guasto
            // arriva prima del primo frame) senza dire una parola.
            clock.setFailureHandler(t -> SwingUtilities.invokeLater(
                    () -> error(Messages.t("error.boot", describeFailure(t)))));
            cpu.reset(); // il reset asserta RESET: Agnus resetta a cascata CIA e floppy.
            clock.start();

            if (VERBOSE) {
                System.out.println("[INIT] Macchina avviata.");
            }
        } catch (IOException e) {
            error(Messages.t("error.boot", e.getMessage()));
        } catch (RuntimeException | LinkageError e) {
            // Qualunque altro guasto durante il boot. Senza questo ramo l'eccezione
            // risalirebbe al chiamante: quando bootMachine() parte da openConfig() quel
            // chiamante e' l'EDT, dove AWT si limita a stampare su stderr e prosegue.
            // La finestra resta viva e reattiva (F12 continua ad aprire il popup) ma
            // clock.start() non e' mai stato raggiunto, quindi non arriva alcun frame:
            // schermo nero permanente, senza nemmeno i LED power/drive — che sono
            // disegnati DENTRO il frame emulato e spariscono con esso. Il sintomo e'
            // indistinguibile da un guasto video, e in una app lanciata col doppio clic
            // lo stack trace su stderr non lo vede nessuno. Meglio dirlo a schermo.
            e.printStackTrace();
            error(Messages.t("error.boot", describeFailure(e)));
        }
    }

    /**
     * Descrizione compatta di un guasto per il messaggio a video: tipo dell'eccezione
     * (spesso l'unica informazione presente, p.es. per una NullPointerException) seguito
     * dal messaggio, quando c'è. Lo stack trace completo resta su stderr.
     */
    private static String describeFailure(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.isBlank()
                ? t.getClass().getName()
                : t.getClass().getSimpleName() + ": " + msg;
    }

    /**
     * Costruisce i controller hard disk dalle unità configurate (master/slave) e li
     * collega ad {@link AbstractAgnus}. Ogni file {@code .hdf} valido produce un controller;
     * le partizioni sono numerate progressivamente (DH0, DH1, …) fra le unità.
     */
    private void setupHardDisks(AbstractAgnus agnus) {
        hardFiles.clear();
        hdLedSources.clear();
        java.util.List<HdController> controllers = new java.util.ArrayList<>();
        int partitionBase = 0;

        // WHDLoad: l'archivio .lha viene estratto e impacchettato in un hard disk FFS
        // avviabile (vedi WhdLoadManager), montato come PRIMA unità così da diventare
        // il volume di boot (DH0) ed eseguire automaticamente lo slave WHDLoad.
        if (config.whdloadEnabled() && config.whdloadFile() != null) {
            try {
                WhdLoadManager whd = new WhdLoadManager();
                // WHDLoad.prefs generata dalla sezione whdload.prefs.* di amiga.properties.
                whd.withPrefs(config.whdloadPrefsContent());
                // Fornisci a WHDLoad i ROM Kickstart presenti accanto a quello in uso
                // (molti slave OCS richiedono p.es. KS 1.3): associati per CRC32.
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

                // Volume persistente dei salvataggi WHDLoad (Saves: → WHDSaves:),
                // montato come unità NON avviabile così non interferisce col boot.
                java.nio.file.Path savesHdf = whd.prepareSaves();
                HardFile shf = HardFile.load(savesHdf.toString(), false);
                shf.setBootable(false);
                hardFiles.add(shf);
                HdController savesHd = new HdController(controllers.size(), shf, partitionBase,
                        () -> { FloppyDrive d = agnus.getDf0(); return d != null && d.isDiskInserted(); });
                controllers.add(savesHd);
                partitionBase += shf.numPartitions();

                // Un solo LED HD per WHDLoad: boot + salvataggi sono la stessa unità logica.
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
                hdLedSources.add(hd::ledActive); // master (unit 0) prima, slave (unit 1) dopo
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

    /** Ferma la macchina corrente: clock, audio, render e listener di input. */
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
            // ⛔ I dischi vanno ESPULSI, non solo dimenticati. ejectDisk() riversa la
            // traccia in corso di scrittura, fa il flush finale e chiude la DiskImage —
            // che è anche l'unico punto in cui il suo shutdown hook viene rimosso.
            // Senza questo, ogni macchina smontata e ricostruita nello stesso processo
            // (cambio ROM, cambio configurazione, load di uno stato incompatibile, e il
            // game sweep che boota un'istanza per file) lasciava dietro fino a quattro
            // hook, ciascuno radice GC dell'intera immagine.
            agnus.getDf0().ejectDisk();
            agnus.getDf1().ejectDisk();
            agnus.getDf2().ejectDisk();
            agnus.getDf3().ejectDisk();
        }
        closeFloppySounds();
        // Salva su disco le eventuali scritture agli hard disk montati.
        for (HardFile hf : hardFiles) {
            hf.flush();
        }
        hardFiles.clear();
        detachInputAdapters();
        gamePort1 = null;
        gamePort2 = null;
        cpu = null;
        agnus = null;
        activeHardware = null;
    }

    /**
     * Fotografa i supporti appena montati sulla macchina: quanti drive per floppy sono
     * collegati, le immagini degli hard disk in ordine di unità e l'archivio WHDLoad in
     * esecuzione. È il metro con cui il popup distingue una modifica immediata (il disco
     * dentro un drive che c'è già) da una che pretende hardware nuovo.
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
     * Stacca gli adapter di input dal componente e li smonta.
     *
     * <p>Non basta lasciarli andare: {@code keyAdapter} tiene un <b>poller fisico</b> della
     * tastiera e {@code mouseAdapter} un listener di focus sul frame. Rimpiazzarli senza
     * fermarli lascerebbe in giro un thread per ogni volta che li si è rifatti, e i tasti
     * verrebbero consegnati due volte.
     *
     * <p>Serve a chi smonta la macchina e a chi rifà la sola finestra ({@code recreateGuestDisplay}):
     * l'ospite non ha macchina da smontare, ma ha gli stessi adapter.
     */
    private void detachInputAdapters() {
        if (inputComponent != null) {
            if (mouseAdapter != null) {
                inputComponent.removeMouseListener(mouseAdapter);
                inputComponent.removeMouseMotionListener(mouseAdapter);
                mouseAdapter.dispose(); // rimuove anche il window focus listener
            }
            if (keyAdapter != null) inputComponent.removeKeyListener(keyAdapter);
            if (joystickAdapter != null) inputComponent.removeKeyListener(joystickAdapter);
        }
        if (keyAdapter != null) keyAdapter.dispose(); // ferma il poller shift
        // Il servizio joystick reale (GLFW) resta vivo tra un boot e l'altro (idle):
        // basta staccare le porte, che verranno ricollegate al prossimo boot.
        joystickService.setPorts(java.util.List.of());
        mouseAdapter = null;
        keyAdapter = null;
        joystickAdapter = null;
    }

    // =========================================================================
    // Validazione / utilità
    // =========================================================================

    /**
     * Verifica che il Kickstart configurato esista e sia utilizzabile su un A500; in
     * caso negativo avvisa l'utente dicendo cosa c'è che non va. Il controllo va oltre
     * l'esistenza del file perché la collezione tipica di ROM è piena di immagini che
     * non sono Kickstart da socket (extended ROM del CDTV, immagini per ReKick,
     * cartucce freezer): senza questo messaggio l'unico sintomo sarebbe uno schermo
     * nero. Vedi {@link KickstartImage}.
     */
    /**
     * Vero se si deve montare la ROM AmiJa invece di un Kickstart: quando non
     * ne e' stato scelto nessuno, o quando il file indicato non c'e' piu'.
     */
    private static boolean usingBuiltinRom(String kickstartPath) {
        if (!BuiltinRom.isAvailable()) return false;
        return kickstartPath.isEmpty() || !new File(kickstartPath).isFile();
    }

    private boolean validateRom(AmigaConfig cfg) {
        String path = cfg.kickstartPath();

        // Nessun Kickstart, o file sparito: si parte lo stesso con la ROM
        // integrata. Si avvisa una volta sola, perche' non e' un errore ma una
        // scelta di ripiego che l'utente deve sapere di star usando.
        if ((path.isEmpty() || !new File(path).isFile()) && BuiltinRom.isAvailable()) {
            if (!welcomeShown) {
                welcomeShown = true;
                info(Messages.t("welcome.title"), Messages.t("welcome.builtinrom"));
            }
            System.out.println("[CONFIG] nessun Kickstart utilizzabile: uso la ROM AmiJa integrata");
            return true;
        }

        if (path.isEmpty()) {
            // Nessun Kickstart impostato: quasi sempre è il primo avvio in assoluto.
            // Un errore che dice "imposta kickstart.path" non aiuta chi non sa nemmeno
            // cos'è un Kickstart né perché l'emulatore non ne abbia uno: la prima volta
            // si spiega la situazione per intero, poi basta ricordare cosa manca.
            if (!welcomeShown) {
                welcomeShown = true;
                info(Messages.t("welcome.title"), Messages.t("welcome.rom"));
            } else if (cfg.autoEnabled()) {
                // Con la modalità Auto il percorso non viene da 'kickstart.path' ma da
                // una delle due caselle del popup: dire quale manca è l'unica cosa utile.
                error(Messages.t("error.rom.missing.auto",
                        Messages.t(cfg.autoProfile().expanded()
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
     * Messaggio informativo a schermo. A differenza di {@link #error} il testo passa per
     * l'HTML, che manda a capo da sé entro una larghezza fissa: questi messaggi sono
     * discorsivi e in un JLabel resterebbero su un'unica riga larga quanto lo schermo.
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
