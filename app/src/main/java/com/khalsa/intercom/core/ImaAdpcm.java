package com.khalsa.intercom.core;

/**
 * IMA ADPCM codec for mono 16-bit PCM (little endian).
 *
 * Four bits per sample, so 16 kHz speech costs 8 KB per second: half the size of
 * 16 kHz mu-law and with twice the audio bandwidth of 8 kHz mu-law. Pure Java with no
 * Android dependencies so it can be unit tested on a desktop JVM.
 *
 * Stream layout: [int16 initial predictor LE][uint8 initial step index][uint8 reserved]
 * then two samples per byte, low nibble first.
 */
public final class ImaAdpcm {
    private ImaAdpcm() {
    }

    private static final int[] INDEX_TABLE = {
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    };

    private static final int[] STEP_TABLE = {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };

    public static final int HEADER_BYTES = 4;

    /** Encodes {@code length} bytes of 16-bit LE mono PCM. */
    public static byte[] encode(byte[] pcm, int length) {
        int samples = Math.max(0, length / 2);
        byte[] out = new byte[HEADER_BYTES + (samples + 1) / 2];
        int predictor = samples > 0 ? sample(pcm, 0) : 0;
        int index = 0;
        out[0] = (byte) (predictor & 0xFF);
        out[1] = (byte) ((predictor >> 8) & 0xFF);
        out[2] = (byte) index;
        out[3] = 0;
        int o = HEADER_BYTES;
        for (int i = 0; i < samples; i++) {
            int s = sample(pcm, i * 2);
            int step = STEP_TABLE[index];
            int diff = s - predictor;
            int code = 0;
            if (diff < 0) {
                code = 8;
                diff = -diff;
            }
            int delta = step >> 3;
            if (diff >= step) {
                code |= 4;
                diff -= step;
                delta += step;
            }
            step >>= 1;
            if (diff >= step) {
                code |= 2;
                diff -= step;
                delta += step;
            }
            step >>= 1;
            if (diff >= step) {
                code |= 1;
                delta += step;
            }
            predictor = (code & 8) != 0 ? predictor - delta : predictor + delta;
            predictor = clamp16(predictor);
            index = clampIndex(index + INDEX_TABLE[code]);
            if ((i & 1) == 0) {
                out[o] = (byte) code;
            } else {
                out[o] = (byte) ((out[o] & 0x0F) | (code << 4));
                o++;
            }
        }
        return out;
    }

    /** Decodes to 16-bit LE mono PCM. Returns an empty array for malformed input. */
    public static byte[] decode(byte[] adpcm) {
        if (adpcm == null || adpcm.length < HEADER_BYTES) {
            return new byte[0];
        }
        int predictor = (short) ((adpcm[0] & 0xFF) | ((adpcm[1] & 0xFF) << 8));
        int index = adpcm[2] & 0xFF;
        if (index > 88) {
            return new byte[0];
        }
        int samples = (adpcm.length - HEADER_BYTES) * 2;
        byte[] out = new byte[samples * 2];
        int w = 0;
        for (int i = HEADER_BYTES; i < adpcm.length; i++) {
            int b = adpcm[i] & 0xFF;
            for (int half = 0; half < 2; half++) {
                int code = half == 0 ? (b & 0x0F) : (b >> 4);
                int step = STEP_TABLE[index];
                int delta = step >> 3;
                if ((code & 4) != 0) delta += step;
                if ((code & 2) != 0) delta += step >> 1;
                if ((code & 1) != 0) delta += step >> 2;
                predictor = (code & 8) != 0 ? predictor - delta : predictor + delta;
                predictor = clamp16(predictor);
                index = clampIndex(index + INDEX_TABLE[code]);
                out[w++] = (byte) (predictor & 0xFF);
                out[w++] = (byte) ((predictor >> 8) & 0xFF);
            }
        }
        return out;
    }

    private static int sample(byte[] pcm, int off) {
        return (short) ((pcm[off] & 0xFF) | (pcm[off + 1] << 8));
    }

    private static int clamp16(int v) {
        return v > 32767 ? 32767 : (v < -32768 ? -32768 : v);
    }

    private static int clampIndex(int v) {
        return v < 0 ? 0 : (v > 88 ? 88 : v);
    }
}
