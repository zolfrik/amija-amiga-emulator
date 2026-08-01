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

/**
 * Scheda di espansione Zorro II che partecipa alla sequenza di AutoConfig.
 *
 * <p>Al boot l'{@code expansion.library} del Kickstart interroga lo spazio di
 * configurazione $E80000–$E8FFFF: la prima scheda ancora <i>unconfigured</i> della
 * catena risponde con il proprio descrittore, riceve un indirizzo base e poi si
 * ritira (CONFIGURED o SHUTUP), lasciando rispondere la scheda successiva. Questo
 * modella la "daisy chain" hardware in cui il CONFIG_OUT di una scheda pilota il
 * CONFIG_IN della seguente.
 *
 * <p>{@link Agnus} mantiene una catena ordinata di queste schede (es. Fast RAM +
 * controller hard disk) e instrada gli accessi a $E8xxxx alla prima ancora in
 * attesa. Implementazioni: {@link FastRam}, {@link HdController}.
 */
public interface AutoConfigDevice {

    /** {@code true} finché la scheda risponde nello spazio di AutoConfig (UNCONFIGURED). */
    boolean autoconfiguring();

    /**
     * Lettura di un byte del descrittore di configurazione (spazio $E8xxxx).
     * Restituisce $FF quando la scheda non è più in fase di AutoConfig.
     */
    int peekConfigByte(int addr);

    /**
     * Scrittura nello spazio di AutoConfig: il Kickstart assegna l'indirizzo base
     * ($48/$4A) o ordina lo "shut up" ($4C).
     *
     * @return {@code true} se la scrittura ha completato la configurazione (base
     *         assegnato): il chiamante deve mappare la scheda all'indirizzo
     *         restituito da {@link #configuredBaseAddr()}.
     */
    boolean pokeConfigByte(int addr, int value);

    /** Indirizzo base assegnato dal Kickstart (valido dopo {@link #pokeConfigByte} = true). */
    int configuredBaseAddr();

    /** Reset di sistema: la scheda torna in attesa di AutoConfig. */
    void reset();
}
