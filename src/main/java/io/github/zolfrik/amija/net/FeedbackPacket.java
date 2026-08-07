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

package io.github.zolfrik.amija.net;

import java.nio.ByteBuffer;

/**
 * Il pacchetto che l'ospite manda all'host, 200 volte al secondo
 * ({@code docs/remote-play-plan.md} §4.3).
 *
 * <h2>Stato completo, mai eventi</h2>
 * Ogni pacchetto porta lo <b>stato intero</b> dei controlli. Un pacchetto perso non ha
 * conseguenze: il successivo riporta la verita' completa, e nessun rilascio puo' restare
 * appeso. Il movimento del mouse, che per natura sarebbe un delta, e' riportato a stato con
 * due <b>contatori cumulativi</b> che non si azzerano mai: l'host applica la differenza
 * signed, e un pacchetto perso viene recuperato dal successivo senza che nessuno debba
 * chiedere niente. Combacia anche col modello hardware, dove {@code AmigaMouse} e' gia' una
 * coppia di contatori di quadratura che avvolgono.
 *
 * <h2>Ci viaggia sopra anche l'ACK video</h2>
 * {@code ackVideoFrameId} e' l'ultimo frame video ricevuto <b>per intero</b>: e' il perno del
 * §4.1, cioe' cio' che permette all'host di comprimere sempre contro qualcosa che l'ospite ha
 * davvero. Sta qui invece che in un pacchetto suo perche' questo flusso c'e' gia', e' il piu'
 * frequente dei due, e cosi' l'ACK non costa un solo byte in piu' di quanti ne costerebbe il
 * suo header.
 *
 * <pre>
 *    0  u32 seq                monotono; l'host scarta tutto cio' che e' &lt;= all'ultimo applicato
 *    4  u32 sessionGen         cambia a ogni riconnessione: azzera i baseline dei contatori
 *    8  u32 ackVideoFrameId    ultimo frame video ricevuto INTERO
 *   12  u32 mouseCounterX      cumulativo, mai azzerato
 *   16  u32 mouseCounterY      cumulativo, mai azzerato
 *   20  u16 audioFillMs        telemetria per il governatore di banda
 *   22  i8  joyX               -1 / 0 / +1
 *   23  i8  joyY               -1 / 0 / +1
 *   24  u8  buttons            fire, b2, b3, mouseL, mouseR, mouseM
 *   25  u8  portDevice         cosa l'ospite ha collegato: NONE | MOUSE | JOYSTICK
 *   26  u16 riservato          deve essere 0
 *   28  u8  keys[16]           bitmap dei 128 codici raw Amiga, con latch appiccicoso
 *   44  = SIZE
 * </pre>
 *
 * <p><b>Sono 44 byte, non i 48 stimati nel piano.</b> La differenza e' irrilevante per la
 * banda (44 x 200 x 8 = 70 kbit/s contro i 77 stimati) ed e' la conseguenza di aver
 * disposto i campi <b>allineati</b> invece che nell'ordine in cui il §4.3 li elenca: i due
 * contatori del mouse sono saliti accanto agli altri interi. Il campo riservato che resta
 * esiste per lasciare spazio, ed e' <b>verificato a zero</b> apposta — un campo di riserva
 * che il ricevente ignora non e' spazio per il futuro, e' un canale silenzioso.
 *
 * <h2>Perche' la periferica sta qui e non nell'handshake</h2>
 * L'host assegna all'ospite <b>una</b> game port; cosa infilarci dentro — mouse, joystick,
 * niente — lo decide l'ospite, ed e' una cosa che puo' cambiare <b>a partita in corso</b>
 * (il popup F12 dell'ospite e' aperto proprio per quello). Un parametro d'handshake sarebbe
 * fissato al momento sbagliato, e un messaggio di controllo apposta andrebbe scritto,
 * ordinato e ripetuto alla riconnessione. Qui invece costa <b>zero byte</b> — il posto
 * c'era gia' — arriva 200 volte al secondo, e soprattutto <b>e' stato come tutto il resto
 * del pacchetto</b>: se si perde quello che porta il cambio, il successivo lo riporta
 * uguale. La regola del §4.3 vale anche per questo campo, senza eccezioni da ricordare.
 *
 * <p>I campi di input restano a zero fino alla Fase 3: qui serve gia' tutto il resto — seq,
 * sessionGen, ACK — e definire il formato una volta sola evita di doverlo cambiare quando
 * l'input arrivera' davvero.
 */
public final class FeedbackPacket {

    public static final int SIZE = 44;

    /** Bitmap dei 128 codici raw della tastiera Amiga: 16 byte. */
    public static final int KEY_BYTES = 16;

    public static final int BUTTON_FIRE = 0x01;
    public static final int BUTTON_2 = 0x02;
    public static final int BUTTON_3 = 0x04;
    public static final int BUTTON_MOUSE_LEFT = 0x08;
    public static final int BUTTON_MOUSE_RIGHT = 0x10;
    public static final int BUTTON_MOUSE_MIDDLE = 0x20;
    private static final int BUTTON_MASK = 0x3F;

    /** Porta vuota: l'ospite non ha collegato niente, e {@code JOYxDAT} deve leggere 0. */
    public static final int DEVICE_NONE = 0;
    public static final int DEVICE_MOUSE = 1;
    public static final int DEVICE_JOYSTICK = 2;
    private static final int DEVICE_MAX = DEVICE_JOYSTICK;

    public int seq;
    public int sessionGen;
    public long ackVideoFrameId;
    public int mouseCounterX;
    public int mouseCounterY;
    public int audioFillMs;
    public int joyX;
    public int joyY;
    public int buttons;
    /** Cosa l'ospite ha collegato alla porta che l'host gli ha assegnato. */
    public int portDevice;
    public final byte[] keys = new byte[KEY_BYTES];

    /** Scrive il pacchetto in {@code dst}, che deve avere almeno {@link #SIZE} byte. */
    public void writeTo(byte[] dst) {
        ByteBuffer b = RemotePlayProtocol.wrap(dst, 0, SIZE);
        b.putInt(seq);
        b.putInt(sessionGen);
        b.putInt((int) ackVideoFrameId);
        b.putInt(mouseCounterX);
        b.putInt(mouseCounterY);
        b.putShort((short) audioFillMs);
        b.put((byte) joyX);
        b.put((byte) joyY);
        b.put((byte) buttons);
        b.put((byte) portDevice);
        b.putShort((short) 0);
        b.put(keys, 0, KEY_BYTES);
    }

    /**
     * Legge e <b>valida</b> un pacchetto ricevuto.
     *
     * <p>Sono byte che arrivano dalla rete e finiscono a pilotare una macchina: qui non si
     * accetta niente che non sia esattamente cio' che il formato prevede. Gli assi fuori da
     * [-1, +1] e i bit di pulsante non definiti sono rifiutati invece che ritagliati —
     * ritagliare nasconderebbe un mittente che non parla questo protocollo.
     *
     * @throws ProtocolException se la lunghezza o un campo non tornano
     */
    public void readFrom(byte[] src, int length) throws ProtocolException {
        if (length != SIZE) {
            throw new ProtocolException("pacchetto di feedback di " + length
                    + " byte, attesi " + SIZE);
        }
        ByteBuffer b = RemotePlayProtocol.wrap(src, 0, SIZE);
        seq = b.getInt();
        sessionGen = b.getInt();
        ackVideoFrameId = b.getInt() & 0xFFFFFFFFL;
        mouseCounterX = b.getInt();
        mouseCounterY = b.getInt();
        audioFillMs = b.getShort() & 0xFFFF;
        joyX = b.get();
        joyY = b.get();
        buttons = b.get() & 0xFF;
        portDevice = b.get() & 0xFF;
        int reserved16 = b.getShort() & 0xFFFF;
        b.get(keys, 0, KEY_BYTES);

        if (joyX < -1 || joyX > 1 || joyY < -1 || joyY > 1) {
            throw new ProtocolException("assi del joystick fuori da [-1, +1]: "
                    + joyX + ", " + joyY);
        }
        if ((buttons & ~BUTTON_MASK) != 0) {
            throw new ProtocolException("bit di pulsante non definiti: 0x"
                    + Integer.toHexString(buttons));
        }
        if (portDevice > DEVICE_MAX) {
            throw new ProtocolException("periferica di porta sconosciuta: " + portDevice);
        }
        if (reserved16 != 0) {
            throw new ProtocolException("campo riservato non nullo");
        }
    }

    /** Azzera tutto: nuova sessione, nessuno stato ereditato da quella precedente. */
    public void clear() {
        seq = 0;
        sessionGen = 0;
        ackVideoFrameId = RemotePlayProtocol.NO_REFERENCE;
        mouseCounterX = 0;
        mouseCounterY = 0;
        audioFillMs = 0;
        joyX = 0;
        joyY = 0;
        buttons = 0;
        portDevice = DEVICE_NONE;
        java.util.Arrays.fill(keys, (byte) 0);
    }

    /** Copia i campi in {@code other}, senza allocare. */
    public void copyTo(FeedbackPacket other) {
        other.seq = seq;
        other.sessionGen = sessionGen;
        other.ackVideoFrameId = ackVideoFrameId;
        other.mouseCounterX = mouseCounterX;
        other.mouseCounterY = mouseCounterY;
        other.audioFillMs = audioFillMs;
        other.joyX = joyX;
        other.joyY = joyY;
        other.buttons = buttons;
        other.portDevice = portDevice;
        System.arraycopy(keys, 0, other.keys, 0, KEY_BYTES);
    }

    /** L'ACK vale solo se parla di un frame vero: {@link RemotePlayProtocol#NO_REFERENCE} no. */
    public boolean hasAck() {
        return ackVideoFrameId != RemotePlayProtocol.NO_REFERENCE;
    }
}
