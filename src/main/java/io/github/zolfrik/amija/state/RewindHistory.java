/*
 * AmiJa - a cycle-exact Amiga emulator in Java.
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

package io.github.zolfrik.amija.state;

import io.github.zolfrik.amija.common.MasterClock;
import io.github.zolfrik.amija.common.VirtualCRT;
import io.github.zolfrik.amija.cpu.Processor;
import io.github.zolfrik.amija.custom.AbstractAgnus;
import io.github.zolfrik.amija.memory.RAM;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The rewind history: one state of the machine per second of play, kept compressed in
 * memory, any of which can be put back.
 *
 * <p>A sample is taken on the clock thread at the start of a frame ({@link MasterClock.Sampler})
 * with a {@link MachineSnapshot}. The snapshot copies only the memory pages written since its
 * previous capture, and those pages are exactly what a sample keeps: most samples hold the
 * pages that changed in their second, the rest of the state whole, and a small picture of the
 * screen. Every {@link #KEY_INTERVAL}-th sample (a key sample) holds the memories whole, so a
 * state is rebuilt from the key before it plus at most {@code KEY_INTERVAL - 1} deltas.
 * Compression runs on a worker thread; the clock thread only copies.
 *
 * <p>The history keeps the seconds asked for, and less when the memory budget (a fraction of
 * the heap) runs out first. Whole groups (a key and its deltas) leave from the oldest end.
 *
 * <p>Everything but sampling is done with the machine paused (the F12 popup is open).
 */
public final class RewindHistory implements MasterClock.Sampler {

    /** One key sample every this many samples. */
    public static final int KEY_INTERVAL = 30;

    /** Share of the maximum heap the history may use. */
    private static final double BUDGET_FRACTION = 0.10;

    /** Cost of a second assumed before any sample has been measured. */
    private static final long DEFAULT_BYTES_PER_SECOND = 64 * 1024;

    // Picture: the overscan area of the raw frame, one column out of two.
    private static final int CROP_X = VirtualCRT.REGRESSION_CROP_X;
    private static final int CROP_Y = VirtualCRT.REGRESSION_CROP_Y;
    public static final int THUMB_W = VirtualCRT.REGRESSION_CROP_W / 2;
    public static final int THUMB_H = VirtualCRT.REGRESSION_CROP_H;

    /** A state in the history. */
    public static final class Entry {
        private final boolean key;
        private final int frame;
        private volatile boolean ready;
        // Compressed parts, written by the worker before ready.
        private byte[] state;
        private byte[][] memories;
        private byte[] thumb;
        private long bytes;
        // Uncompressed parts, from the clock thread to the worker.
        private byte[] rawState;
        private byte[][] rawMemories;
        private byte[] rawThumb;

        private Entry(boolean key, int frame) {
            this.key = key;
            this.frame = frame;
        }

        /** Emulated seconds between this state and frame {@code nowFrame}. */
        public double secondsBefore(int nowFrame, int framesPerSecond) {
            return Math.max(0, nowFrame - frame) / (double) framesPerSecond;
        }

        /** The screen at this state, {@link #THUMB_W} x {@link #THUMB_H}. */
        public BufferedImage thumbnail() {
            return picture(inflate(thumb, THUMB_W * THUMB_H * 3));
        }
    }

    private final MachineSnapshot snapshot;
    private final AbstractAgnus agnus;
    private final Processor cpu;
    private final boolean eventDriven;
    private final Supplier<int[]> frameSource;
    private final int framesPerSecond;
    private final long budget;
    private final int[] memorySizes;

    private final List<Entry> entries = new ArrayList<>();
    private long totalBytes;
    private final ExecutorService worker;

    private volatile int seconds;
    private int lastSampleFrame;
    private int sinceKey = -1;

    /**
     * @param frameSource     the raw frame ({@link VirtualCRT#RAW_WIDTH} x
     *                        {@link VirtualCRT#RAW_HEIGHT}), complete at a frame boundary
     * @param eventDriven     {@code true} for an event-driven processor, which is stepped a
     *                        whole instruction at a time
     * @param framesPerSecond frames in a second of the video mode
     */
    public RewindHistory(MachineSnapshot snapshot, AbstractAgnus agnus, Processor cpu,
            boolean eventDriven, Supplier<int[]> frameSource, int framesPerSecond, int seconds) {
        this.snapshot = snapshot;
        this.agnus = agnus;
        this.cpu = cpu;
        this.eventDriven = eventDriven;
        this.frameSource = frameSource;
        this.framesPerSecond = Math.max(1, framesPerSecond);
        this.budget = (long) (Runtime.getRuntime().maxMemory() * BUDGET_FRACTION);
        this.memorySizes = new int[snapshot.ramCount()];
        for (int i = 0; i < memorySizes.length; i++) memorySizes[i] = snapshot.ramCopy(i).length;
        this.seconds = Math.max(0, seconds);
        this.lastSampleFrame = agnus.getFrameCount();
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rewind-history");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    // =========================================================================
    // Settings
    // =========================================================================

    /** Seconds of history asked for; 0 = off. */
    public int seconds() {
        return seconds;
    }

    /** Changes the seconds kept; 0 empties the history and stops sampling. */
    public void setSeconds(int seconds) {
        this.seconds = Math.max(0, seconds);
        if (this.seconds == 0) {
            clear();
        } else {
            worker.execute(this::trim);
        }
    }

    public int framesPerSecond() {
        return framesPerSecond;
    }

    /** Bytes the history may use. */
    public long budgetBytes() {
        return budget;
    }

    /** Bytes the history uses now. */
    public synchronized long usedBytes() {
        return totalBytes;
    }

    /**
     * Seconds of history that fit in the budget, at the cost per second measured so far (a
     * guess before the first samples).
     */
    public long maxSeconds() {
        long perSecond;
        synchronized (this) {
            long ready = 0, bytes = 0;
            for (Entry e : entries) {
                if (e.ready) {
                    ready++;
                    bytes += e.bytes;
                }
            }
            perSecond = ready >= 2 ? Math.max(1, bytes / ready) : DEFAULT_BYTES_PER_SECOND;
        }
        return budget / perSecond;
    }

    // =========================================================================
    // Sampling, on the clock thread
    // =========================================================================

    @Override
    public boolean due(long ranClocks) {
        if (seconds == 0) return false;
        int frames = agnus.getFrameCount() - lastSampleFrame;
        if (frames < 0) {
            // The frame counter went back (a state was loaded): count from here.
            lastSampleFrame = agnus.getFrameCount();
            return false;
        }
        return frames >= framesPerSecond;
    }

    @Override
    public boolean atBoundary() {
        return agnus.getVpos() == 0 && (eventDriven || cpu.isAtInstructionBoundary());
    }

    @Override
    public void sample() {
        take(true);
    }

    /**
     * Takes a sample now, with the machine paused, and returns it. The oldest states are not
     * dropped to make room: the popup may be showing them.
     */
    public Entry take() {
        return take(false);
    }

    private Entry take(boolean trimAfter) {
        snapshot.capture();
        int frame = agnus.getFrameCount();
        lastSampleFrame = frame;
        boolean key = sinceKey < 0 || sinceKey >= KEY_INTERVAL - 1;
        sinceKey = key ? 0 : sinceKey + 1;
        Entry e = new Entry(key, frame);
        e.rawState = snapshot.exportState();
        e.rawMemories = new byte[memorySizes.length][];
        for (int i = 0; i < memorySizes.length; i++) {
            e.rawMemories[i] = key ? snapshot.ramCopy(i).clone()
                    : packPages(snapshot.ramCopy(i), snapshot.ramChanged(i));
        }
        e.rawThumb = thumbnailBytes(frameSource.get());
        synchronized (this) {
            entries.add(e);
        }
        worker.execute(() -> {
            compress(e);
            if (trimAfter) trim();
        });
        return e;
    }

    /** The screen now, from the raw frame. */
    public BufferedImage currentThumbnail() {
        return picture(thumbnailBytes(frameSource.get()));
    }

    // =========================================================================
    // Reading and restoring, machine paused
    // =========================================================================

    /** The states held, oldest first, at most the seconds asked for (plus one). */
    public List<Entry> entries() {
        flush();
        synchronized (this) {
            int n = entries.size();
            int from = Math.max(0, n - (seconds + 1));
            return List.copyOf(entries.subList(from, n));
        }
    }

    /**
     * Puts the machine back in state {@code target}. With {@code truncate} the states after
     * it are dropped: the history goes on from there.
     */
    public void restore(Entry target, boolean truncate) {
        flush();
        List<Entry> chain = new ArrayList<>();
        synchronized (this) {
            int k = entries.indexOf(target);
            if (k < 0) throw new IllegalArgumentException("rewind: state no longer held");
            int j = k;
            while (!entries.get(j).key) j--;
            chain.addAll(entries.subList(j, k + 1));
        }
        byte[][] memories = new byte[memorySizes.length][];
        Entry keyEntry = chain.get(0);
        for (int i = 0; i < memorySizes.length; i++) {
            memories[i] = inflate(keyEntry.memories[i], memorySizes[i]);
        }
        for (int m = 1; m < chain.size(); m++) {
            Entry d = chain.get(m);
            for (int i = 0; i < memorySizes.length; i++) {
                applyPages(memories[i], inflateUnknown(d.memories[i]));
            }
        }
        snapshot.importState(inflateUnknown(target.state), memories);
        snapshot.restore();
        lastSampleFrame = agnus.getFrameCount();
        if (truncate) {
            synchronized (this) {
                int k = entries.indexOf(target);
                while (entries.size() > k + 1) {
                    totalBytes -= entries.remove(entries.size() - 1).bytes;
                }
                sinceKey = 0;
                for (int i = entries.size() - 1; i >= 0 && !entries.get(i).key; i--) sinceKey++;
            }
        }
    }

    /** Drops every state: the next sample starts a new history. */
    public void clear() {
        flush();
        synchronized (this) {
            entries.clear();
            totalBytes = 0;
            sinceKey = -1;
        }
        lastSampleFrame = agnus.getFrameCount();
    }

    /** Stops the worker; the history is not used any more. */
    public void close() {
        worker.shutdownNow();
        synchronized (this) {
            entries.clear();
            totalBytes = 0;
        }
    }

    /** Waits until every sample taken so far is compressed. */
    private void flush() {
        if (worker.isShutdown()) return;
        try {
            worker.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("rewind: history worker not answering", e);
        }
    }

    // =========================================================================
    // Worker
    // =========================================================================

    private void compress(Entry e) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            e.state = deflate(deflater, e.rawState);
            e.memories = new byte[e.rawMemories.length][];
            long bytes = e.state.length;
            for (int i = 0; i < e.rawMemories.length; i++) {
                e.memories[i] = deflate(deflater, e.rawMemories[i]);
                bytes += e.memories[i].length;
            }
            e.thumb = deflate(deflater, e.rawThumb);
            bytes += e.thumb.length;
            e.bytes = bytes;
        } finally {
            deflater.end();
        }
        e.rawState = null;
        e.rawMemories = null;
        e.rawThumb = null;
        synchronized (this) {
            if (entries.contains(e)) totalBytes += e.bytes;
            e.ready = true;
        }
    }

    /**
     * Drops the oldest groups while the history is longer than asked or over budget, always
     * keeping the newest group (which holds the key its deltas need).
     */
    private synchronized void trim() {
        int keep = seconds + 1;
        while (!entries.isEmpty()) {
            int group = 1;
            while (group < entries.size() && !entries.get(group).key) group++;
            if (group >= entries.size()) return;
            boolean tooLong = entries.size() - group >= keep;
            boolean tooBig = totalBytes > budget;
            if (!tooLong && !tooBig) return;
            for (int i = 0; i < group; i++) totalBytes -= entries.remove(0).bytes;
        }
    }

    // =========================================================================
    // Encodings
    // =========================================================================

    /** The changed pages: count, page numbers, then the pages. */
    private static byte[] packPages(byte[] memory, byte[] changed) {
        int pageSize = RAM.pageSize();
        int count = 0;
        for (byte c : changed) if (c != 0) count++;
        ByteBuffer out = ByteBuffer.allocate(4 + 4 * count + count * pageSize);
        out.putInt(count);
        for (int p = 0; p < changed.length; p++) if (changed[p] != 0) out.putInt(p);
        for (int p = 0; p < changed.length; p++) {
            if (changed[p] != 0) {
                int start = p * pageSize;
                int len = Math.min(pageSize, memory.length - start);
                out.put(memory, start, len);
                if (len < pageSize) out.position(out.position() + pageSize - len);
            }
        }
        return out.array();
    }

    private static void applyPages(byte[] memory, byte[] packed) {
        int pageSize = RAM.pageSize();
        ByteBuffer in = ByteBuffer.wrap(packed);
        int count = in.getInt();
        int[] pages = new int[count];
        for (int i = 0; i < count; i++) pages[i] = in.getInt();
        int data = in.position();
        for (int i = 0; i < count; i++) {
            int start = pages[i] * pageSize;
            int len = Math.min(pageSize, memory.length - start);
            System.arraycopy(packed, data + i * pageSize, memory, start, len);
        }
    }

    private static byte[] thumbnailBytes(int[] raw) {
        byte[] out = new byte[THUMB_W * THUMB_H * 3];
        int o = 0;
        for (int y = 0; y < THUMB_H; y++) {
            int row = (CROP_Y + y) * VirtualCRT.RAW_WIDTH + CROP_X;
            for (int x = 0; x < THUMB_W; x++) {
                int rgb = raw[row + 2 * x];
                out[o++] = (byte) (rgb >> 16);
                out[o++] = (byte) (rgb >> 8);
                out[o++] = (byte) rgb;
            }
        }
        return out;
    }

    private static BufferedImage picture(byte[] rgb) {
        BufferedImage img = new BufferedImage(THUMB_W, THUMB_H, BufferedImage.TYPE_INT_RGB);
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        for (int i = 0, o = 0; i < px.length; i++, o += 3) {
            px[i] = (rgb[o] & 0xFF) << 16 | (rgb[o + 1] & 0xFF) << 8 | (rgb[o + 2] & 0xFF);
        }
        return img;
    }

    private static byte[] deflate(Deflater deflater, byte[] data) {
        deflater.reset();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 8));
        byte[] buf = new byte[64 * 1024];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] data, int size) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            byte[] out = new byte[size];
            int n = 0;
            while (n < size && !inflater.finished()) {
                int r = inflater.inflate(out, n, size - n);
                if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                n += r;
            }
            if (n != size) throw new IllegalStateException("rewind: damaged sample");
            return out;
        } catch (DataFormatException e) {
            throw new IllegalStateException("rewind: damaged sample", e);
        } finally {
            inflater.end();
        }
    }

    private static byte[] inflateUnknown(byte[] data) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
            byte[] buf = new byte[64 * 1024];
            while (!inflater.finished()) {
                int r = inflater.inflate(buf);
                if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IllegalStateException("rewind: damaged sample");
                }
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IllegalStateException("rewind: damaged sample", e);
        } finally {
            inflater.end();
        }
    }
}
