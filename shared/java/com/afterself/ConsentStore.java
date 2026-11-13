package com.afterself;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Remembers, per world, who accepted voice clips. No answer means no recording: capture is opt-in.
 * The file name is versioned on purpose: answers given when clips stayed in memory do not cover clips on disk,
 * so everyone is asked again. Bump it whenever what players agree to changes.
 */
public final class ConsentStore {
    private static final String FILE_NAME = "afterself_voice_consent_v2.txt";
    private static final Map<UUID, Boolean> ANSWERS = new ConcurrentHashMap<>();
    private static Path file;

    private ConsentStore() {}

    public static void load(MinecraftServer server) {
        ANSWERS.clear();
        file = server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split("=");
                if (parts.length == 2) {
                    try {
                        ANSWERS.put(UUID.fromString(parts[0]), Boolean.parseBoolean(parts[1]));
                    } catch (IllegalArgumentException ignored) {
                        // Skips a line that is not a player id.
                    }
                }
            }
        } catch (IOException ignored) {
            // An unreadable file means nobody accepted, the safe answer.
            ANSWERS.clear();
        }
    }

    public static void unload() {
        ANSWERS.clear();
        file = null;
    }

    public static boolean hasAnswered(UUID player) {
        return ANSWERS.containsKey(player);
    }

    public static boolean accepted(UUID player) {
        return Boolean.TRUE.equals(ANSWERS.get(player));
    }

    public static void set(UUID player, boolean accepted) {
        ANSWERS.put(player, accepted);
        if (accepted) {
            VoiceMemory.loadPlayer(player);
        } else {
            VoiceMemory.forget(player);
        }
        if (file == null) {
            return;
        }
        Set<String> lines = new HashSet<>();
        ANSWERS.forEach((id, answer) -> lines.add(id + "=" + answer));
        try {
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The answer still applies until the server stops.
        }
    }
}
