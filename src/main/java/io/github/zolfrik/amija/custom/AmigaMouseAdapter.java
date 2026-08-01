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

package io.github.zolfrik.amija.custom;

import io.github.zolfrik.amija.input.GamePort;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;

/**
 * Traduce gli eventi AWT del mouse host nei comandi della game port Amiga.
 *
 * <h3>Due modalità operative</h3>
 *
 * <p><b>Libera</b> (prima della cattura): il cursore host è visibile e si muove
 * liberamente. Il mouse Amiga riceve movimento e click solo mentre il puntatore
 * host è dentro al JFrame. Lo spostamento è il delta tra eventi consecutivi.
 *
 * <p><b>Catturata</b> (dopo il primo click sinistro): il cursore host viene reso
 * invisibile e {@link Robot} lo riporta al centro della finestra a ogni evento.
 * Il movimento Amiga è il delta rispetto al centro. Questo è il classico
 * "mouse-look": il puntatore non raggiunge mai i bordi dello schermo, quindi non
 * si perdono spostamenti quando l'utente muove molto il mouse fisico (causa
 * tipica dello "stallo" del cursore agli estremi del monitor). Il delta misurato
 * resta corretto anche sotto carico elevato: AWT fonde (coalesce) gli eventi di
 * movimento, ma il delta è sempre calcolato come (posizione corrente − centro),
 * quindi cattura lo spostamento netto a prescindere da quanti eventi intermedi
 * vengono persi.
 *
 * <p>La cattura si rilascia con il <b>tasto centrale</b> (o quando la finestra
 * perde il focus, per non intrappolare il puntatore durante un alt-tab).
 *
 * <h3>Mappatura pulsanti (in modalità catturata)</h3>
 * <pre>
 *   Sinistro → pulsante sinistro Amiga (CIA-A bit 6, active-LOW)
 *   Destro   → pulsante destro   Amiga (POTINP bit 10/14)
 *   Centrale → RILASCIA la cattura (non inoltrato all'Amiga)
 * </pre>
 */
public class AmigaMouseAdapter extends MouseAdapter {

    private volatile GamePort port;
    private volatile boolean enabled = true;
    private final JFrame frame;

    /** Robot per il ricentraggio del puntatore; null se non disponibile (headless). */
    private final Robot robot;
    /** Cursore trasparente usato per nascondere il puntatore mentre è catturato. */
    private final Cursor blankCursor;
    /** Cursore di default ripristinato al rilascio. */
    private final Cursor defaultCursor;

    /** Listener di perdita-focus registrato sul frame; rimosso da {@link #dispose()}. */
    private final WindowAdapter focusListener;

    /** true quando il mouse host è catturato (invisibile + ricentrato). */
    private volatile boolean captured = false;

    // --- Stato modalità libera (delta tra eventi, coordinate componente) ---
    private boolean insideFrame = false;
    private int lastX = Integer.MIN_VALUE;
    private int lastY = Integer.MIN_VALUE;

    // --- Stato modalità catturata (delta rispetto al centro, coordinate schermo) ---
    private int centerX, centerY;

    // Il click sinistro che attiva la cattura viene consumato (non inoltrato): questo
    // flag fa scartare il relativo rilascio per non generare un release spurio.
    private boolean swallowNextLeftRelease = false;

    public AmigaMouseAdapter(GamePort port, JFrame frame) {
        this.port = port;
        this.frame = frame;

        Robot r = null;
        try {
            r = new Robot();
            r.setAutoWaitForIdle(false);
        } catch (AWTException | SecurityException | java.awt.HeadlessException ex) {
            // Cattura non disponibile (es. headless): resta solo la modalità libera.
        }
        this.robot = r;

        // Cursore trasparente 1x1: nasconde il puntatore senza alterarne la posizione.
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        this.blankCursor = Toolkit.getDefaultToolkit()
                .createCustomCursor(img, new Point(0, 0), "blank");
        this.defaultCursor = Cursor.getDefaultCursor();

        // Rilascia la cattura se la finestra perde il focus (evita di intrappolare
        // il puntatore durante alt-tab o quando un'altra app prende il fuoco).
        this.focusListener = new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                if (captured) release();
            }
        };
        if (frame != null) {
            frame.addWindowFocusListener(focusListener);
        }
    }

    public boolean isCaptured() {
        return captured;
    }

    /**
     * Collega il mouse host a un'altra game port (a caldo, dal popup F12): serve quando
     * la periferica "Mouse" viene spostata da una porta all'altra. {@code null} scollega.
     */
    public void setPort(GamePort port) {
        GamePort old = this.port;
        if (old != null && old != port) {
            old.pressMouseLeft(false);
            old.pressMouseRight(false);
            old.pressMouseMiddle(false);
        }
        this.port = port;
    }

    /**
     * Abilita/disabilita il mouse host. Da disabilitato non muove né clicca la porta e
     * rilascia l'eventuale cattura del puntatore.
     */
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        if (!enabled) {
            if (captured) release();
            GamePort p = port;
            if (p != null) {
                p.pressMouseLeft(false);
                p.pressMouseRight(false);
                p.pressMouseMiddle(false);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Rimuove i listener registrati sul frame. Da chiamare quando l'adapter viene
     * dismesso (es. riavvio a freddo della macchina) per non accumulare listener
     * di focus a ogni ricostruzione.
     */
    public void dispose() {
        if (frame != null) {
            frame.removeWindowFocusListener(focusListener);
        }
    }

    // -------------------------------------------------------------------------
    // Cattura / rilascio
    // -------------------------------------------------------------------------

    /**
     * Cattura il mouse host (nasconde il cursore e lo ricentra). Esposto per
     * ripristinare la cattura dopo la chiusura del popup di configurazione (F12),
     * dove il puntatore viene rilasciato all'apertura del dialog.
     */
    public void captureMouse() {
        capture();
    }

    /** Attiva la cattura: nasconde il cursore e lo riporta al centro. */
    private void capture() {
        if (!enabled || robot == null || frame == null || captured) return;
        captured = true;
        setCursor(blankCursor);
        recenter();
    }

    /** Disattiva la cattura: ripristina il cursore visibile e il movimento libero. */
    public void release() {
        if (!captured) return;
        captured = false;
        setCursor(defaultCursor);
        // Reset dello stato della modalità libera così il primo movimento successivo
        // non produce un delta gigante.
        lastX = lastY = Integer.MIN_VALUE;
    }

    private void setCursor(Cursor c) {
        // Impostato sul frame e sul content pane: il puntatore sta sopra il content
        // pane (la JLabel), che eredita il cursore solo se non ne ha uno proprio.
        SwingUtilities.invokeLater(() -> {
            frame.setCursor(c);
            if (frame.getContentPane() != null) {
                frame.getContentPane().setCursor(c);
            }
        });
    }

    /** Calcola il centro della finestra (coordinate schermo) e vi sposta il puntatore. */
    private void recenter() {
        if (robot == null || !frame.isShowing()) return;
        Point loc = frame.getLocationOnScreen();
        centerX = loc.x + frame.getWidth() / 2;
        centerY = loc.y + frame.getHeight() / 2;
        robot.mouseMove(centerX, centerY);
    }

    // -------------------------------------------------------------------------
    // Entrata / uscita dalla finestra (solo modalità libera)
    // -------------------------------------------------------------------------

    @Override
    public void mouseEntered(MouseEvent e) {
        insideFrame = true;
        lastX = e.getX();
        lastY = e.getY();
    }

    @Override
    public void mouseExited(MouseEvent e) {
        if (captured) {
            // Rete di sicurezza: con un movimento ampio/veloce il puntatore può uscire
            // dai bordi del frame prima che il ricentraggio per-evento lo agganci. Fuori
            // dal frame NON arrivano più eventi di movimento, quindi la cattura si
            // "perderebbe" (cursore di nuovo visibile). Riportiamolo subito al centro.
            recenter();
            return;
        }
        insideFrame = false;
        lastX = Integer.MIN_VALUE;
        lastY = Integer.MIN_VALUE;
    }

    // -------------------------------------------------------------------------
    // Movimento
    // -------------------------------------------------------------------------

    @Override
    public void mouseMoved(MouseEvent e) {
        handleMove(e);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        handleMove(e);
    }

    private void handleMove(MouseEvent e) {
        if (!enabled || port == null) return;
        if (captured) {
            handleMoveCaptured(e);
        } else {
            handleMoveFree(e);
        }
    }

    /** Modalità catturata: delta rispetto al centro, poi ricentra il puntatore. */
    private void handleMoveCaptured(MouseEvent e) {
        int dx = e.getXOnScreen() - centerX;
        int dy = e.getYOnScreen() - centerY;
        if (dx != 0 || dy != 0) {
            port.moveMouse(dx, dy);
            // Riporta il puntatore al centro: l'evento di ricentraggio cade esattamente
            // sul centro (dx=dy=0) e viene quindi naturalmente ignorato.
            recenter();
        }
    }

    /** Modalità libera: delta tra eventi consecutivi, solo se dentro al frame. */
    private void handleMoveFree(MouseEvent e) {
        if (!insideFrame) return;
        int x = e.getX();
        int y = e.getY();
        if (lastX != Integer.MIN_VALUE) {
            int dx = x - lastX;
            int dy = y - lastY;
            if (dx != 0 || dy != 0) {
                // Con sun.java2d.uiScale i delta del mouse arrivano gia' in coordinate
                // logiche (native), quindi nessuna conversione di scala e' necessaria.
                port.moveMouse(dx, dy);
            }
        }
        lastX = x;
        lastY = y;
    }

    // -------------------------------------------------------------------------
    // Pulsanti
    // -------------------------------------------------------------------------

    @Override
    public void mousePressed(MouseEvent e) {
        if (!enabled || port == null) return;
        // Tasto centrale: rilascia la cattura (se attiva) e non inoltrare all'Amiga.
        if (e.getButton() == MouseEvent.BUTTON2) {
            if (captured) {
                release();
                return;
            }
            // Cattura non attiva: comportamento normale del tasto centrale.
            port.pressMouseMiddle(true);
            return;
        }

        // Primo click sinistro fuori dalla cattura: cattura il mouse e consuma il click.
        if (!captured && e.getButton() == MouseEvent.BUTTON1 && robot != null) {
            capture();
            swallowNextLeftRelease = true;
            return;
        }

        if (!captured && !insideFrame) return;

        switch (e.getButton()) {
            case MouseEvent.BUTTON1 -> port.pressMouseLeft(true);
            case MouseEvent.BUTTON3 -> port.pressMouseRight(true);
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (!enabled || port == null) return;
        switch (e.getButton()) {
            case MouseEvent.BUTTON1 -> {
                if (swallowNextLeftRelease) {
                    swallowNextLeftRelease = false; // rilascio del click di cattura: scartato
                } else {
                    port.pressMouseLeft(false);
                }
            }
            case MouseEvent.BUTTON3 -> port.pressMouseRight(false);
            case MouseEvent.BUTTON2 -> {
                if (!captured) port.pressMouseMiddle(false);
            }
        }
    }
}
