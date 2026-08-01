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

/**
 * Metadati di frame passati da VirtualCRT ad AmigaDisplay insieme al framebuffer raw.
 *
 * <p>Classe mutabile e riusabile: viene aggiornata a ogni frame per evitare una
 * allocazione fissa nel path di presentazione.
 */
public final class FrameInfo {
    private boolean hires;
    private boolean interlaced;
    private boolean evenField;
    private int visibleX;
    private int visibleY;
    private int visibleWidth;
    private int visibleHeight;
    // Contatore dei fotogrammi emulati (PAL), indipendente dalla velocità di emulazione:
    // serve come riferimento temporale riproducibile per l'overlay "frame counter".
    private long frameCount;

    public FrameInfo() {
    }

    public FrameInfo(boolean hires, boolean interlaced, boolean evenField,
                     int visibleX, int visibleY, int visibleWidth, int visibleHeight) {
        set(hires, interlaced, evenField, visibleX, visibleY, visibleWidth, visibleHeight);
    }

    public void set(boolean hires, boolean interlaced, boolean evenField,
                    int visibleX, int visibleY, int visibleWidth, int visibleHeight) {
        this.hires = hires;
        this.interlaced = interlaced;
        this.evenField = evenField;
        this.visibleX = visibleX;
        this.visibleY = visibleY;
        this.visibleWidth = visibleWidth;
        this.visibleHeight = visibleHeight;
    }

    public void setFrameCount(long frameCount) {
        this.frameCount = frameCount;
    }

    public void copyFrom(FrameInfo other) {
        set(other.hires, other.interlaced, other.evenField,
                other.visibleX, other.visibleY, other.visibleWidth, other.visibleHeight);
        this.frameCount = other.frameCount;
    }

    public boolean hires() { return hires; }
    public boolean interlaced() { return interlaced; }
    public boolean evenField() { return evenField; }
    public int visibleX() { return visibleX; }
    public int visibleY() { return visibleY; }
    public int visibleWidth() { return visibleWidth; }
    public int visibleHeight() { return visibleHeight; }
    public long frameCount() { return frameCount; }
}
