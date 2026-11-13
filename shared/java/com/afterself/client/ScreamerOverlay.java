package com.afterself.client;

import com.afterself.AfterselfConfig;
import com.afterself.entity.SoulEntity;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;

/**
 * The dead player's own face, wrong: eyes hollowed out, jaw dropped far too low, lunging out of a black screen.
 * It is built to stay frightening: the server sends it rarely, there are six of them, each with a scream made for
 * it, one is picked at random and never the same twice in a row, and the scream is pitched a little differently
 * each time.
 */
public final class ScreamerOverlay {
    /**
     * The screamers. The number is the moment of the lunge, in milliseconds: each sound is cut so that its hit falls
     * exactly there. The name is the sound in sounds.json, and the last number the face size the lunge starts from.
     */
    public enum Variant {
        /** Black, a small face far away, and it is on you. */
        LUNGE(190, "screamer", 0.36f),
        /** Far, gone, closer, gone, on you: three frames of something walking up, a footstep on each. */
        STUTTER(490, "screamer_steps", 0.62f),
        /** Two points of light in the dark, coming closer under a whisper. They blink once. Then the rest. */
        EYES(620, "screamer_eyes", 0.36f),
        /** It hangs upside down from the top of the screen, and drops. The scream falls with it. */
        FALL(250, "screamer_fall", 0.3f),
        /** One face in the dark, then another, then nine. Then all of them scream at once. */
        CROWD(760, "screamer_crowd", 0.3f),
        /** It comes out of the dark so slowly that it might not be there, over a heartbeat. Then it is. */
        SLOW(1200, "screamer_slow", 0.8f);

        final int lungeAt;
        final String sound;
        final float from;

        Variant(int lungeAt, String sound, float from) {
            this.lungeAt = lungeAt;
            this.sound = sound;
            this.from = from;
        }

        public int lungeAt() {
            return lungeAt;
        }

        public String sound() {
            return sound;
        }
    }

    private static final long AFTER_LUNGE_MS = 1560L;
    private static final long FADE_MS = 450L;
    private static final long CALM_MS = 1300L;
    private static final long FLASH_MS = 160L;
    /** Face size as a part of the smaller side of the screen: where the lunge ends up, and how far it goes first. */
    private static final float SETTLED = 1.0f;
    private static final float OVERSHOOT = 1.3f;
    private static final int CROWD_SIZE = 9;
    private static final Random RANDOM = new Random();

    private static long startedAt = -1L;
    private static long durationMs;
    private static boolean silentFlash;
    private static Variant variant = Variant.LUNGE;
    private static Variant lastVariant;
    private static float pitch = 1f;
    private static ResourceLocation skin;
    /** Where the faces of the crowd stand, as parts of the screen, in the order they appear. */
    private static final float[] crowdX = new float[CROWD_SIZE];
    private static final float[] crowdY = new float[CROWD_SIZE];
    /** Set while a face is drawn upside down. */
    private static boolean flipped;
    private static int flipTop;
    private static int flipHeight;
    /** How many flashes and screamers were shown since the game started. Read by the tests. */
    public static int flashesShown;
    public static int screamsShown;
    /** Tests set this to see one screamer on purpose. */
    public static Variant forcedVariant;

    /** Draws with whichever API this Minecraft version has. */
    public interface Canvas {
        void rect(int x, int y, int width, int height, int argb);

        /** Whole rows of the face (0 is the top of the head, 7 the chin), stretched into the rectangle. */
        void part(ResourceLocation skin, int x, int y, int width, int height, int row, int rows,
                float red, float green, float blue, float alpha);
    }

    private ScreamerOverlay() {}

    public static void trigger(SoulEntity soul) {
        if (!AfterselfConfig.screamer) {
            return;
        }
        skin = ClientCompat.skinOf(soul.getOwnerId());
        silentFlash = false;
        float volume;
        if (AfterselfConfig.screamerIntense) {
            variant = forcedVariant != null ? forcedVariant : pickVariant();
            durationMs = variant.lungeAt + AFTER_LUNGE_MS;
            // Never quite the same scream twice. The picture runs at the speed of the sound, so they stay together.
            pitch = 0.88f + RANDOM.nextFloat() * 0.2f;
            volume = 1f;
            placeCrowd();
        } else {
            variant = Variant.LUNGE;
            durationMs = CALM_MS;
            pitch = 1f;
            volume = 0.4f;
        }
        lastVariant = variant;
        screamsShown++;
        startedAt = System.currentTimeMillis();
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(ClientCompat.uiSound(variant.sound), pitch, volume));
    }

    private static Variant pickVariant() {
        Variant[] all = Variant.values();
        Variant picked = all[RANDOM.nextInt(all.length)];
        while (picked == lastVariant) {
            picked = all[RANDOM.nextInt(all.length)];
        }
        return picked;
    }

    /** Nine places on a loose grid, shuffled: the crowd never stands the same way twice. */
    private static void placeCrowd() {
        int[] order = new int[CROWD_SIZE];
        for (int i = 0; i < CROWD_SIZE; i++) {
            order[i] = i;
        }
        for (int i = CROWD_SIZE - 1; i > 0; i--) {
            int other = RANDOM.nextInt(i + 1);
            int kept = order[i];
            order[i] = order[other];
            order[other] = kept;
        }
        for (int i = 0; i < CROWD_SIZE; i++) {
            int cell = order[i];
            crowdX[i] = (cell % 3 + 0.5f) / 3f + (RANDOM.nextFloat() - 0.5f) * 0.12f;
            crowdY[i] = (cell / 3 + 0.5f) / 3f + (RANDOM.nextFloat() - 0.5f) * 0.12f;
        }
    }

    /** The face, for a blink, with no sound: was it there? Never interrupts a real screamer. */
    public static void flash(SoulEntity soul) {
        if (!AfterselfConfig.screamer || !AfterselfConfig.screamerIntense || startedAt >= 0L) {
            return;
        }
        skin = ClientCompat.skinOf(soul.getOwnerId());
        durationMs = FLASH_MS;
        silentFlash = true;
        pitch = 1f;
        flashesShown++;
        startedAt = System.currentTimeMillis();
    }

    public static boolean isFlash() {
        return startedAt >= 0L && silentFlash;
    }

    public static boolean isActive() {
        return startedAt >= 0L;
    }

    public static Variant currentVariant() {
        return variant;
    }

    public static void render(Canvas canvas, int width, int height) {
        if (startedAt < 0L) {
            return;
        }
        // In the time of the sound: a scream pitched lower also plays slower, and the picture slows with it.
        long elapsed = (long) ((System.currentTimeMillis() - startedAt) * pitch);
        if (elapsed >= durationMs) {
            startedAt = -1L;
            return;
        }
        flipped = false;
        float smaller = Math.min(width, height);
        if (silentFlash) {
            // Dim, still, and gone before the eye is sure.
            canvas.rect(0, 0, width, height, 0x99000000);
            face(canvas, width / 2, height / 2, smaller * 0.9f, 0f, 0.5f, 0.5f, 0.55f, 1f, false, Features.ALL);
            return;
        }
        if (!AfterselfConfig.screamerIntense) {
            calm(canvas, width, height, smaller, elapsed);
            return;
        }
        long lunge = variant.lungeAt;
        long fadeFrom = durationMs - FADE_MS;
        int backdrop = elapsed < fadeFrom ? 255 : (int) (255f * (1f - (elapsed - fadeFrom) / (float) FADE_MS));
        canvas.rect(0, 0, width, height, backdrop << 24);
        flipped = variant == Variant.FALL;
        if (elapsed < lunge) {
            prelude(canvas, width, height, smaller, elapsed);
            flipped = false;
            return;
        }
        if (elapsed >= fadeFrom) {
            // It is gone. Twice more it is there again, for a frame or two, while the dark lifts.
            long since = elapsed - fadeFrom;
            if ((since >= 80L && since < 130L) || (since >= 230L && since < 270L)) {
                face(canvas, width / 2, height / 2, smaller * SETTLED, 0.4f, 0.5f, 0.12f, 0.12f, 0.85f, false, Features.ALL);
            }
            flipped = false;
            return;
        }

        long since = elapsed - lunge;
        float progress = since / (float) (fadeFrom - lunge);
        float scale;
        float arrived = 1f;
        if (since < 140L) {
            // Too fast to follow, and past where it will stop.
            float rush = since / 140f;
            arrived = 1f - (1f - rush) * (1f - rush) * (1f - rush);
            scale = variant.from + (OVERSHOOT - variant.from) * arrived;
        } else if (since < 300L) {
            scale = OVERSHOOT + (SETTLED - OVERSHOOT) * ((since - 140L) / 160f);
        } else {
            scale = SETTLED + 0.03f * (float) Math.sin(elapsed / 38.0);
        }
        float jaw = Math.min(1f, since / 260f) * 0.4f;

        int shake = (int) (height * 0.035f * (1f - 0.5f * progress));
        int x = width / 2 + RANDOM.nextInt(shake * 2 + 1) - shake;
        int y = height / 2 + RANDOM.nextInt(shake * 2 + 1) - shake;
        if (variant == Variant.FALL) {
            // It was hanging at the top of the screen: it comes down as it comes closer.
            y -= (int) (height * 0.34f * (1f - arrived));
        }
        float size = smaller * scale;
        // Slow enough to stay under three flashes a second.
        boolean red = (since / 170L) % 2L == 0L;
        if (variant == Variant.CROWD) {
            crowd(canvas, width, height, smaller, CROWD_SIZE, jaw, red, shake);
        }
        int split = (int) (size * 0.03f);
        face(canvas, x - split, y, size, jaw, 1f, 0.08f, 0.08f, 0.35f, true, Features.NONE);
        face(canvas, x + split, y, size, jaw, 0.08f, 0.85f, 1f, 0.3f, true, Features.NONE);
        if (red) {
            face(canvas, x, y, size, jaw, 1f, 0.3f, 0.28f, 1f, true, Features.ALL);
        } else {
            face(canvas, x, y, size, jaw, 0.8f, 0.78f, 0.74f, 1f, true, Features.ALL);
        }
        flipped = false;
    }

    /** What is seen before the lunge. Every moment here has its sound in the file of the same screamer. */
    private static void prelude(Canvas canvas, int width, int height, float smaller, long elapsed) {
        switch (variant) {
            case LUNGE -> {
                if (elapsed >= 110L) {
                    face(canvas, width / 2, height / 2, smaller * 0.36f, 0f, 0.3f, 0.3f, 0.32f, 1f, false, Features.ALL);
                }
            }
            case STUTTER -> {
                // A footstep lands with each of the two.
                if (elapsed >= 90L && elapsed < 240L) {
                    face(canvas, width / 2, height / 2, smaller * 0.26f, 0f, 0.3f, 0.3f, 0.32f, 1f, false, Features.ALL);
                } else if (elapsed >= 320L && elapsed < 430L) {
                    face(canvas, width / 2, height / 2, smaller * 0.62f, 0.1f, 0.45f, 0.43f, 0.43f, 1f, false, Features.ALL);
                }
            }
            case EYES -> {
                // Nothing but two points of light in the dark, coming closer. They blink once, with a click.
                boolean blink = elapsed >= 330L && elapsed < 400L;
                if (elapsed >= 90L && elapsed < 560L && !blink) {
                    float size = smaller * (0.3f + 0.22f * (elapsed - 90L) / 470f);
                    face(canvas, width / 2, height / 2, size, 0f, 1f, 1f, 1f, 1f, false, Features.EYES_ONLY);
                }
            }
            case FALL -> {
                // Hanging from the top of the screen, head down, swaying a little.
                if (elapsed >= 100L) {
                    int sway = (int) (Math.sin(elapsed / 55.0) * width * 0.012);
                    face(canvas, width / 2 + sway, (int) (height * 0.16f), smaller * 0.3f, 0f, 0.36f, 0.35f, 0.37f, 1f, false, Features.ALL);
                }
            }
            case CROWD -> {
                // One more every 70 ms, each with its little breath.
                int shown = elapsed < 100L ? 0 : Math.min(CROWD_SIZE, (int) ((elapsed - 100L) / 70L) + 1);
                crowd(canvas, width, height, smaller, shown, 0f, false, 0);
            }
            case SLOW -> {
                // Out of the black so slowly that it might not be there. It swells with each heartbeat, and the
                // screen goes fully dark for the last instant.
                if (elapsed >= 100L && elapsed < 1185L) {
                    float emerged = (elapsed - 100L) / 1085f;
                    float beat = pulse(elapsed - 300L) + pulse(elapsed - 760L);
                    face(canvas, width / 2, height / 2, smaller * (0.8f + 0.025f * beat), 0f, 0.55f, 0.55f, 0.6f,
                            0.75f * (float) Math.pow(emerged, 1.5), false, Features.ALL);
                }
            }
        }
    }

    private static float pulse(long sinceBeat) {
        return sinceBeat < 0L ? 0f : (float) Math.exp(-sinceBeat / 110.0);
    }

    /** The small faces of the crowd. Dim while they gather, and screaming with the big one once it lunges. */
    private static void crowd(Canvas canvas, int width, int height, float smaller, int shown, float jaw, boolean red, int shake) {
        for (int i = 0; i < shown; i++) {
            int x = (int) (crowdX[i] * width) + (shake > 0 ? RANDOM.nextInt(shake + 1) - shake / 2 : 0);
            int y = (int) (crowdY[i] * height) + (shake > 0 ? RANDOM.nextInt(shake + 1) - shake / 2 : 0);
            // The newest one is a little brighter: the eye goes to it.
            float light = jaw > 0f ? 0.7f : i == shown - 1 ? 0.5f : 0.33f;
            face(canvas, x, y, smaller * 0.2f, jaw, light, red ? light * 0.3f : light * 0.97f, red ? light * 0.28f : light * 0.95f,
                    1f, false, Features.ALL);
        }
    }

    /** For players who turned the intense version off: no flashing, no shaking, no glitch, and a quieter scream. */
    private static void calm(Canvas canvas, int width, int height, float smaller, long elapsed) {
        float t = elapsed / (float) durationMs;
        float visibility = t < 0.75f ? 1f : (1f - t) / 0.25f;
        canvas.rect(0, 0, width, height, (int) (visibility * 235f) << 24);
        face(canvas, width / 2, height / 2, smaller * (0.8f + 0.12f * t), 0.25f * Math.min(1f, t * 3f),
                0.75f * visibility, 0.7f * visibility, 0.7f * visibility, 1f, false, Features.ALL);
    }

    /** What is drawn over the skin: nothing (the coloured ghosts), everything, or only the eyes in the dark. */
    private enum Features { NONE, ALL, EYES_ONLY }

    /**
     * One face, centred on a point. The jaw value opens the mouth by stretching the mouth row downward, as a part of
     * the face size. Glitch slides some rows sideways. The features are the hollow eyes with what runs down from
     * them, and the open mouth with its teeth. While flipped is set, all of it is drawn head down.
     */
    private static void face(Canvas canvas, int centerX, int centerY, float size, float jaw,
            float red, float green, float blue, float alpha, boolean glitch, Features features) {
        int row = Math.max(1, (int) (size / 8f));
        int side = row * 8;
        int gap = (int) (jaw * side);
        int left = centerX - side / 2;
        int top = centerY - (side + gap) / 2;
        flipTop = top;
        flipHeight = side + gap;
        int[] slide = new int[8];
        if (glitch) {
            for (int i = 0; i < 8; i++) {
                if (RANDOM.nextInt(9) < 2) {
                    slide[i] = (int) ((RANDOM.nextFloat() - 0.5f) * side * 0.14f);
                }
            }
        }
        if (features != Features.EYES_ONLY) {
            for (int i = 0; i < 6; i++) {
                part(canvas, left + slide[i], top + i * row, side, row, i, red, green, blue, alpha);
            }
            part(canvas, left + slide[6], top + 6 * row, side, row + gap, 6, red, green, blue, alpha);
            part(canvas, left + slide[7], top + 7 * row + gap, side, row, 7, red, green, blue, alpha);
        }
        if (features == Features.NONE) {
            return;
        }
        int black = (int) (alpha * 255f) << 24;
        // Eyes: two holes where the eyes were, something dark running down from each, and a point of light that
        // never holds still.
        int socketY = top + (int) (3.8f * row);
        int socketWidth = (int) (2.2f * row);
        int socketHeight = (int) (1.25f * row);
        int point = Math.max(2, features == Features.EYES_ONLY ? row / 2 : row / 4);
        int wander = features == Features.EYES_ONLY ? 0 : Math.max(1, row / 7);
        int streak = Math.max(1, row / 6);
        for (float column : new float[] {0.9f, 4.9f}) {
            int socketX = left + slide[4] + (int) (column * row);
            if (features == Features.ALL) {
                rect(canvas, socketX, socketY, socketWidth, socketHeight, black);
                rect(canvas, socketX + socketWidth / 5, socketY + socketHeight, streak, (int) (1.5f * row), black | 0x200000);
                rect(canvas, socketX + socketWidth / 2, socketY + socketHeight, streak, (int) (2.4f * row), black | 0x200000);
                rect(canvas, socketX + socketWidth * 4 / 5, socketY + socketHeight, streak, (int) (0.9f * row), black | 0x200000);
            }
            rect(canvas, socketX + socketWidth / 2 - point / 2 + (wander > 0 ? RANDOM.nextInt(wander * 2 + 1) - wander : 0),
                    socketY + socketHeight / 2 - point / 2 + (wander > 0 ? RANDOM.nextInt(wander * 2 + 1) - wander : 0),
                    point, point, black | 0xFFE8E0);
        }
        if (features == Features.EYES_ONLY) {
            return;
        }
        // Mouth: open, far longer than a mouth can be, teeth at both ends and something darker than black inside.
        int mouthX = left + slide[6] + (int) (2.3f * row);
        int mouthWidth = (int) (3.4f * row);
        int mouthY = top + 6 * row + row / 8;
        int mouthHeight = row * 3 / 4 + gap;
        rect(canvas, mouthX, mouthY, mouthWidth, mouthHeight, black);
        rect(canvas, mouthX + mouthWidth / 5, mouthY + mouthHeight / 3, mouthWidth * 3 / 5, mouthHeight / 2, black | 0x2A0000);
        int tooth = Math.max(1, mouthWidth * 3 / 20);
        for (int i = 0; i < 4; i++) {
            int toothX = mouthX + mouthWidth * (2 + i * 5) / 22;
            int toothHeight = Math.max(1, i % 2 == 0 ? row / 4 : row / 3);
            rect(canvas, toothX, mouthY, tooth, toothHeight, black | 0xD6CCB0);
            rect(canvas, toothX, mouthY + mouthHeight - toothHeight, tooth, toothHeight, black | 0xD6CCB0);
        }
    }

    /** Where a piece that starts at y and is this tall goes once the face is turned head down. */
    private static int placed(int y, int height) {
        return flipped ? flipTop + flipHeight - (y - flipTop) - height : y;
    }

    private static void part(Canvas canvas, int x, int y, int width, int height, int row,
            float red, float green, float blue, float alpha) {
        canvas.part(skin, x, placed(y, height), width, height, row, 1, red, green, blue, alpha);
    }

    private static void rect(Canvas canvas, int x, int y, int width, int height, int argb) {
        canvas.rect(x, placed(y, height), width, height, argb);
    }
}
