package com.afterself;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Plain properties file, so the same code runs on every Minecraft version and loader. */
public final class AfterselfConfig {
    /** Turns soul creation at death on or off. */
    public static boolean soulsEnabled = true;
    /** Beyond this many souls for one player, the two weakest merge into a bigger one. */
    public static int maxSouls = 10;
    /** Souls exist in the world at night only. By day they wait, with their items, for the next night. */
    public static boolean soulsOnlyAtNight = true;
    /** Souls may break blocks, also requires the mobGriefing game rule. */
    public static boolean soulsBreakBlocks = true;
    /** Souls place blocks to bridge and climb toward a player, also requires the mobGriefing game rule. */
    public static boolean soulsPlaceBlocks = true;
    /** Souls frighten the player they follow: footsteps, doors, chests, torches knocked down, standing behind them. */
    public static boolean soulsHaunt = true;
    /** A player who has souls cannot sleep the night away: one comes to stand over the bed. */
    public static boolean soulsHauntSleep = true;
    /** A heartbeat and a moment of darkness warn a player that a soul is very close or coming. */
    public static boolean dreadCues = true;
    /** Tells every player, each time they join, what dying costs here. */
    public static boolean loginWarning = true;
    /** Souls take useful items from chests and barrels. */
    public static boolean soulsLootContainers = true;
    /** Souls sneak up and take items from players' inventories. */
    public static boolean soulsStealFromPlayers = true;
    /** A soul that kills a player through a trap takes control of the body instead. */
    public static boolean possessionEnabled = true;
    /** Safety net: longest possession in seconds, so a lone player can never be stuck forever. 0 means no limit. */
    public static int possessionMaxSeconds = 600;
    /** Master switch for storing voice clips. Each player must also accept. */
    public static boolean voiceClips = true;
    /** Most voice clips kept on disk per player. Old ones are kept on purpose: forgotten phrases are the scary ones. */
    public static int voiceMaxClips = 64;
    /** Below 1 makes replayed voices slower and deeper. */
    public static float voicePitch = 0.86f;
    /**
     * Shortest wait between two screamers for the same player, in seconds. It is the wait after their first one:
     * each screamer they get makes the next wait longer, up to three times this.
     */
    public static int screamerCooldownSeconds = 900;
    /** Chances out of 100 that a soul surprised at close range screams. One standing behind you always does. */
    public static int screamerSurpriseChance = 35;
    /** Client: show the screamer at all. */
    public static boolean screamer = true;
    /** Client: false removes the flash and shake, and lowers the sound. */
    public static boolean screamerIntense = true;

    private AfterselfConfig() {}

    public static void load(Path gameDir) {
        Path file = gameDir.resolve("config").resolve("afterself.properties");
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException | IllegalArgumentException ignored) {
                // A broken file falls back to the defaults.
            }
        }
        // Replaced by maxSouls: its old default of 3 would merge far too early.
        p.remove("maxSoulsPerPlayer");

        soulsEnabled = bool(p, "soulsEnabled", soulsEnabled);
        maxSouls = Math.max(1, integer(p, "maxSouls", maxSouls));
        soulsOnlyAtNight = bool(p, "soulsOnlyAtNight", soulsOnlyAtNight);
        soulsBreakBlocks = bool(p, "soulsBreakBlocks", soulsBreakBlocks);
        soulsPlaceBlocks = bool(p, "soulsPlaceBlocks", soulsPlaceBlocks);
        soulsHaunt = bool(p, "soulsHaunt", soulsHaunt);
        soulsHauntSleep = bool(p, "soulsHauntSleep", soulsHauntSleep);
        dreadCues = bool(p, "dreadCues", dreadCues);
        loginWarning = bool(p, "loginWarning", loginWarning);
        soulsLootContainers = bool(p, "soulsLootContainers", soulsLootContainers);
        soulsStealFromPlayers = bool(p, "soulsStealFromPlayers", soulsStealFromPlayers);
        possessionEnabled = bool(p, "possessionEnabled", possessionEnabled);
        possessionMaxSeconds = Math.max(0, integer(p, "possessionMaxSeconds", possessionMaxSeconds));
        voiceClips = bool(p, "voiceClips", voiceClips);
        voiceMaxClips = Math.max(1, integer(p, "voiceMaxClips", voiceMaxClips));
        voicePitch = Math.max(0.5f, Math.min(1.5f, decimal(p, "voicePitch", voicePitch)));
        screamerCooldownSeconds = Math.max(0, integer(p, "screamerCooldownSeconds", screamerCooldownSeconds));
        screamerSurpriseChance = Math.max(0, Math.min(100, integer(p, "screamerSurpriseChance", screamerSurpriseChance)));
        screamer = bool(p, "screamer", screamer);
        screamerIntense = bool(p, "screamerIntense", screamerIntense);

        p.setProperty("soulsEnabled", String.valueOf(soulsEnabled));
        p.setProperty("maxSouls", String.valueOf(maxSouls));
        p.setProperty("soulsOnlyAtNight", String.valueOf(soulsOnlyAtNight));
        p.setProperty("soulsBreakBlocks", String.valueOf(soulsBreakBlocks));
        p.setProperty("soulsPlaceBlocks", String.valueOf(soulsPlaceBlocks));
        p.setProperty("soulsHaunt", String.valueOf(soulsHaunt));
        p.setProperty("soulsHauntSleep", String.valueOf(soulsHauntSleep));
        p.setProperty("dreadCues", String.valueOf(dreadCues));
        p.setProperty("loginWarning", String.valueOf(loginWarning));
        p.setProperty("soulsLootContainers", String.valueOf(soulsLootContainers));
        p.setProperty("soulsStealFromPlayers", String.valueOf(soulsStealFromPlayers));
        p.setProperty("possessionEnabled", String.valueOf(possessionEnabled));
        p.setProperty("possessionMaxSeconds", String.valueOf(possessionMaxSeconds));
        p.setProperty("voiceClips", String.valueOf(voiceClips));
        p.setProperty("voiceMaxClips", String.valueOf(voiceMaxClips));
        p.setProperty("voicePitch", String.valueOf(voicePitch));
        p.setProperty("screamerCooldownSeconds", String.valueOf(screamerCooldownSeconds));
        p.setProperty("screamerSurpriseChance", String.valueOf(screamerSurpriseChance));
        p.setProperty("screamer", String.valueOf(screamer));
        p.setProperty("screamerIntense", String.valueOf(screamerIntense));
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(w, "Afterself settings");
            }
        } catch (IOException ignored) {
            // Read-only folders keep working with the values in memory.
        }
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }

    private static int integer(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float decimal(Properties p, String key, float fallback) {
        try {
            return Float.parseFloat(p.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
