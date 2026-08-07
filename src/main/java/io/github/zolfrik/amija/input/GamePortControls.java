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

package io.github.zolfrik.amija.input;

/**
 * Cio' che si puo' <b>fare</b> a una game port: muovere il mouse, premere i suoi pulsanti,
 * spingere il joystick, premere i suoi.
 *
 * <p>E' la superficie che gli adapter di input pilotano, estratta da {@link GamePort} per il
 * gioco a distanza: sull'ospite non c'e' nessuna game port da pilotare — non c'e' nessuna
 * macchina — e gli stessi gesti devono finire in un pacchetto invece che in un registro.
 * {@code GamePort} la implementa cosi' com'era; gli adapter non si sono accorti di niente.
 *
 * <p>Nota su {@link #moveMouse}: e' l'unico metodo <b>incrementale</b> di questa interfaccia,
 * e non e' un caso — il mouse Amiga e' una coppia di contatori di quadratura, non una
 * posizione. E' anche la ragione per cui sul filo il movimento viaggia come contatore
 * cumulativo e non come delta: un delta perso e' perso per sempre, un contatore si recupera
 * da solo al pacchetto dopo (piano §4.3).
 */
public interface GamePortControls {

    /**
     * Dichiara <b>cosa risulta collegato</b> alla porta: e' quel che decide se le letture di
     * {@code JOYxDAT} e delle linee POT parlano di un mouse, di un joystick o di niente.
     *
     * <p>Sta in questa interfaccia — e non nella sola {@link GamePort} — perche' nel gioco a
     * distanza la porta la riempie l'<b>ospite</b>, e la sua scelta deve poter arrivare fino
     * alla macchina dell'host. {@code RemoteInputSender} la implementa mettendola nel
     * pacchetto; {@code RemoteInputApplier} la rilegge di la' e la posa sulla porta vera.
     *
     * <p>Chi la implementa deve <b>anche</b> dedurla dai gesti, come fa {@link GamePort}: un
     * {@link #moveMouse} dice mouse, un {@link #setJoystickAxes} dice joystick. Le due strade
     * non si contraddicono, perche' i gesti possono arrivare solo dalla periferica che e'
     * stata dichiarata; questo metodo serve a rendere vero anche il caso in cui di gesti non
     * ne arriva nessuno — porta vuota, o appena cambiata.
     */
    void setDevice(GamePortDevice device);

    void moveMouse(int dx, int dy);

    void pressMouseLeft(boolean pressed);

    void pressMouseRight(boolean pressed);

    void pressMouseMiddle(boolean pressed);

    /** @param x -1 sinistra, 0 centro, +1 destra; {@code y} idem su su/giu' */
    void setJoystickAxes(int x, int y);

    void pressJoystickFire(boolean pressed);

    void pressJoystickButton2(boolean pressed);

    void pressJoystickButton3(boolean pressed);
}
