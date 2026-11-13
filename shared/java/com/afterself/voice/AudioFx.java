package com.afterself.voice;

/** Makes a recorded voice sound wrong: slower, deeper, with a thin echo. */
public final class AudioFx {
    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME = 960;

    private AudioFx() {}

    /** Loudness of one frame, 0 to 32767. */
    public static int rms(short[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return 0;
        }
        long sum = 0;
        for (short s : pcm) {
            sum += (long) s * s;
        }
        return (int) Math.sqrt((double) sum / pcm.length);
    }

    public static short[] haunt(short[] input, float pitch) {
        return padToFrame(echo(resample(input, pitch)));
    }

    /** Plays the samples back at another speed; below 1 is slower and deeper. */
    static short[] resample(short[] input, float rate) {
        if (input.length == 0) {
            return input;
        }
        int length = (int) (input.length / rate);
        short[] out = new short[length];
        for (int i = 0; i < length; i++) {
            float pos = i * rate;
            int a = Math.min((int) pos, input.length - 1);
            int b = Math.min(a + 1, input.length - 1);
            float t = pos - a;
            out[i] = (short) (input[a] * (1f - t) + input[b] * t);
        }
        return out;
    }

    static short[] echo(short[] input) {
        int delay = SAMPLE_RATE / 6;
        short[] out = new short[input.length + delay];
        for (int i = 0; i < out.length; i++) {
            int dry = i < input.length ? input[i] : 0;
            int wet = i >= delay ? out[i - delay] : 0;
            int mixed = (int) (dry * 0.8f + wet * 0.35f);
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixed));
        }
        return out;
    }

    /** The Opus encoder takes whole 20 ms frames. */
    static short[] padToFrame(short[] input) {
        int rest = input.length % FRAME;
        if (rest == 0) {
            return input;
        }
        short[] out = new short[input.length + FRAME - rest];
        System.arraycopy(input, 0, out, 0, input.length);
        return out;
    }
}
