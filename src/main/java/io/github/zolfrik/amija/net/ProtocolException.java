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

import java.io.IOException;

/**
 * Dati di rete rifiutati: malformati, incoerenti, troppo grandi o non autenticati.
 *
 * <p>Come {@code CodecException} per il codec, e' <b>controllata</b> di proposito: i byte
 * che la provocano vengono da fuori, e il compilatore deve costringere chi legge a decidere
 * cosa farne. Nel gioco a distanza la risposta e' sempre la stessa — scarta e continua — ma
 * dev'essere una decisione scritta, non un'omissione.
 */
public class ProtocolException extends IOException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
