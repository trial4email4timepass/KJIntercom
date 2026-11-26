package com.khalsa.intercom.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire format for AWAY (internet) voice messages.
 *
 * A message is recorded in full on the sending phone, compressed with IMA ADPCM, split
 * into parts small enough for any public MQTT relay, and played on the receiving phone
 * only after every part has arrived. Playback never depends on network timing, which is
 * what broke the old live-streaming design on mobile data.
 *
 * These bodies travel inside AwayProtocol.wrapFresh + encrypt, so they are already
 * authenticated, encrypted and replay-limited. Pure Java so it can be unit tested.
 */
public final class ClipProtocol {
    private ClipProtocol() {
    }

    public static final byte KIND_PART = 'C';
    public static final byte KIND_ACK = 'A';

    public static final byte ACK_RECEIVED = 1;
    public static final byte ACK_PLAYED = 2;

    /** Audio bytes per part. Keeps every relay packet well under 64 KB. */
    public static final int PART_BYTES = 24 * 1024;
    public static final int MAX_PARTS = 64;
    public static final int PART_HEADER = 1 + 4 + 4 + 4 + 2 + 2 + 4 + 4;

    public static final class Part {
        public final int senderId;
        public final int targetId;
        public final int clipId;
        public final int index;
        public final int total;
        public final int durationMs;
        public final int sampleRate;
        public final byte[] data;

        Part(int senderId, int targetId, int clipId, int index, int total, int durationMs, int sampleRate, byte[] data) {
            this.senderId = senderId;
            this.targetId = targetId;
            this.clipId = clipId;
            this.index = index;
            this.total = total;
            this.durationMs = durationMs;
            this.sampleRate = sampleRate;
            this.data = data;
        }
    }

    public static final class Ack {
        public final int senderId;
        public final int targetId;
        public final int clipId;
        public final byte status;

        Ack(int senderId, int targetId, int clipId, byte status) {
            this.senderId = senderId;
            this.targetId = targetId;
            this.clipId = clipId;
            this.status = status;
        }
    }

    /** Splits one encoded clip into part bodies, in order. */
    public static List<byte[]> encodeParts(int senderId, int targetId, int clipId, int durationMs, int sampleRate, byte[] encoded) {
        int total = Math.max(1, (encoded.length + PART_BYTES - 1) / PART_BYTES);
        if (total > MAX_PARTS) {
            throw new IllegalArgumentException("clip too long: " + total + " parts");
        }
        List<byte[]> out = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int from = i * PART_BYTES;
            int len = Math.min(PART_BYTES, encoded.length - from);
            ByteBuffer b = ByteBuffer.allocate(PART_HEADER + Math.max(0, len)).order(ByteOrder.BIG_ENDIAN);
            b.put(KIND_PART);
            b.putInt(senderId);
            b.putInt(targetId);
            b.putInt(clipId);
            b.putShort((short) i);
            b.putShort((short) total);
            b.putInt(durationMs);
            b.putInt(sampleRate);
            if (len > 0) {
                b.put(encoded, from, len);
            }
            out.add(b.array());
        }
        return out;
    }

    public static byte[] encodeAck(int senderId, int targetId, int clipId, byte status) {
        ByteBuffer b = ByteBuffer.allocate(1 + 4 + 4 + 4 + 1).order(ByteOrder.BIG_ENDIAN);
        b.put(KIND_ACK);
        b.putInt(senderId);
        b.putInt(targetId);
        b.putInt(clipId);
        b.put(status);
        return b.array();
    }

    public static Part decodePart(byte[] body) {
        if (body == null || body.length < PART_HEADER || body[0] != KIND_PART) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
        b.get();
        int sender = b.getInt();
        int target = b.getInt();
        int clip = b.getInt();
        int index = b.getShort() & 0xFFFF;
        int total = b.getShort() & 0xFFFF;
        int duration = b.getInt();
        int rate = b.getInt();
        if (total < 1 || total > MAX_PARTS || index >= total || duration < 0 || duration > 10 * 60 * 1000) {
            return null;
        }
        if (rate != 8000 && rate != 16000) {
            return null;
        }
        byte[] data = new byte[body.length - PART_HEADER];
        b.get(data);
        return new Part(sender, target, clip, index, total, duration, rate, data);
    }

    public static Ack decodeAck(byte[] body) {
        if (body == null || body.length != 14 || body[0] != KIND_ACK) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
        b.get();
        return new Ack(b.getInt(), b.getInt(), b.getInt(), b.get());
    }

    /** A clip whose parts have all arrived. */
    public static final class Clip {
        public final int senderId;
        public final int clipId;
        public final int durationMs;
        public final int sampleRate;
        public final byte[] encoded;

        Clip(int senderId, int clipId, int durationMs, int sampleRate, byte[] encoded) {
            this.senderId = senderId;
            this.clipId = clipId;
            this.durationMs = durationMs;
            this.sampleRate = sampleRate;
            this.encoded = encoded;
        }
    }

    /**
     * Collects parts into complete clips. The same part may arrive twice (the app sends on
     * two relays); duplicates and parts of clips that already completed are ignored.
     * Not thread safe: callers synchronize.
     */
    public static final class Assembler {
        private static final int MAX_PENDING = 8;
        private static final int MAX_DONE = 128;

        private static final class Pending {
            final byte[][] parts;
            final int durationMs;
            final int sampleRate;
            final long firstMs;
            int have;

            Pending(int total, int durationMs, int sampleRate, long firstMs) {
                this.parts = new byte[total][];
                this.durationMs = durationMs;
                this.sampleRate = sampleRate;
                this.firstMs = firstMs;
            }
        }

        private final long expireMs;
        private final LinkedHashMap<Long, Pending> pending = new LinkedHashMap<>();
        private final LinkedHashMap<Long, Boolean> done = new LinkedHashMap<Long, Boolean>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                return size() > MAX_DONE;
            }
        };

        public Assembler(long expireMs) {
            this.expireMs = expireMs;
        }

        private static long key(int sender, int clip) {
            return ((long) sender << 32) | (clip & 0xFFFFFFFFL);
        }

        /** Returns the finished clip when this part completes it, otherwise null. */
        public Clip offer(Part p, long nowMs) {
            expire(nowMs);
            long k = key(p.senderId, p.clipId);
            if (done.containsKey(k)) {
                return null;
            }
            Pending pend = pending.get(k);
            if (pend == null) {
                if (pending.size() >= MAX_PENDING) {
                    Iterator<Long> it = pending.keySet().iterator();
                    it.next();
                    it.remove();
                }
                pend = new Pending(p.total, p.durationMs, p.sampleRate, nowMs);
                pending.put(k, pend);
            }
            if (pend.parts.length != p.total || pend.parts[p.index] != null) {
                return null;
            }
            pend.parts[p.index] = p.data;
            pend.have++;
            if (pend.have < pend.parts.length) {
                return null;
            }
            pending.remove(k);
            done.put(k, Boolean.TRUE);
            int size = 0;
            for (byte[] part : pend.parts) {
                size += part.length;
            }
            byte[] all = new byte[size];
            int o = 0;
            for (byte[] part : pend.parts) {
                System.arraycopy(part, 0, all, o, part.length);
                o += part.length;
            }
            return new Clip(p.senderId, p.clipId, pend.durationMs, pend.sampleRate, all);
        }

        /** True if any part of a clip from this sender is still arriving. */
        public boolean isReceivingFrom(int sender) {
            for (Long k : pending.keySet()) {
                if ((int) (k >> 32) == sender) {
                    return true;
                }
            }
            return false;
        }

        private void expire(long nowMs) {
            Iterator<Map.Entry<Long, Pending>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                if (nowMs - it.next().getValue().firstMs > expireMs) {
                    it.remove();
                }
            }
        }
    }
}
