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

package io.github.zolfrik.amija.net.codec;

import java.io.IOException;

/**
 * Payload video rifiutato da {@link FrameDecoder}: troncato, incoerente o ostile.
 *
 * <p>E' una {@link IOException} <b>controllata</b> di proposito. I byte che arrivano qui
 * vengono dalla rete e possono essere qualunque cosa; il compilatore deve costringere il
 * trasporto a decidere cosa farne — che nel gioco a distanza e' sempre la stessa cosa,
 * "scarta il frame e non ACKarlo" (piano §4.1), mai propagare.
 */
public class CodecException extends IOException {

    private static final long serialVersionUID = 1L;

    public CodecException(String message) {
        super(message);
    }

    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
