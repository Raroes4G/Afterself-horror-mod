package com.afterself;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Phrases of each consenting player's voice, kept on disk in the world folder so a soul can bring back something
 * said long ago. A phrase is whatever the player said between two pauses: nothing is transcribed.
 * Called from the voice thread and from the server thread; disk work runs on its own thread.
 */
public final class VoiceMemory {
    /** Voice Chat sends one Opus frame every 20 ms. */
    private static final int MIN_FRAMES = 20;
    private static final int MAX_FRAMES = 300;
    private static final long PAUSE_MS = 600L;
    private static final int FILE_VERSION = 1;

    private static final Map<UUID, Recording> OPEN = new HashMap<>();
    private static final Map<UUID, List<Clip>> CLIPS = new HashMap<>();
    private static final Set<UUID> LOADED = new HashSet<>();
    /** Players whose folder is waiting to be deleted: nothing may be read back from it. */
    private static final Set<UUID> ERASING = new HashSet<>();
    private static final Random RANDOM = new Random();
    private static Path root;
    private static ExecutorService disk;

    private static final class Recording {
        final List<byte[]> frames = new ArrayList<>();
        long lastFrameAt;
    }

    private static final class Clip {
        final String fileName;
        final List<byte[]> frames;

        Clip(String fileName, List<byte[]> frames) {
            this.fileName = fileName;
            this.frames = frames;
        }
    }

    private VoiceMemory() {}

    public static synchronized void init(MinecraftServer server) {
        root = server.getWorldPath(LevelResource.ROOT).resolve("afterself_voice");
        disk = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "afterself-voice-disk");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Stops using this world's folder. What was written stays on disk. */
    public static synchronized void shutdown() {
        OPEN.clear();
        CLIPS.clear();
        LOADED.clear();
        ERASING.clear();
        root = null;
        if (disk != null) {
            disk.shutdown();
            disk = null;
        }
    }

    /** Adds one frame; the caller has already checked consent. */
    public static synchronized void frame(UUID player, byte[] opus, long nowMs) {
        Recording rec = OPEN.get(player);
        if (rec != null && nowMs - rec.lastFrameAt > PAUSE_MS) {
            close(player);
            rec = null;
        }
        if (rec == null) {
            rec = new Recording();
            OPEN.put(player, rec);
        }
        rec.frames.add(opus);
        rec.lastFrameAt = nowMs;
        if (rec.frames.size() >= MAX_FRAMES) {
            close(player);
        }
    }

    /** The player stopped talking: Voice Chat sends an empty packet. */
    public static synchronized void endPhrase(UUID player) {
        close(player);
    }

    /** A random phrase from this player, however old, or null. */
    public static synchronized List<byte[]> pick(UUID player) {
        flushStale(player);
        ensureLoaded(player);
        List<Clip> clips = CLIPS.get(player);
        if (clips == null || clips.isEmpty()) {
            return null;
        }
        return clips.get(RANDOM.nextInt(clips.size())).frames;
    }

    public static synchronized int count(UUID player) {
        flushStale(player);
        ensureLoaded(player);
        List<Clip> clips = CLIPS.get(player);
        return clips == null ? 0 : clips.size();
    }

    /** Reads this player's clips from disk. Only done for players who accepted. */
    public static synchronized void loadPlayer(UUID player) {
        if (LOADED.contains(player) || root == null) {
            return;
        }
        LOADED.add(player);
        if (ERASING.contains(player)) {
            // They refused and accepted again before the disk caught up: what is still there is being erased,
            // and must not come back.
            return;
        }
        Path folder = root.resolve(player.toString());
        if (!Files.isDirectory(folder)) {
            return;
        }
        List<Clip> clips = CLIPS.computeIfAbsent(player, id -> new ArrayList<>());
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(path -> path.getFileName().toString().endsWith(".voice"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        List<byte[]> frames = readClip(path);
                        if (frames != null) {
                            clips.add(new Clip(path.getFileName().toString(), frames));
                        }
                    });
        } catch (IOException | UncheckedIOException ignored) {
            // An unreadable folder only means fewer clips.
        }
    }

    /** Erases a player's voice, from memory and from disk. Used when they refuse or ask for it. */
    public static synchronized void forget(UUID player) {
        OPEN.remove(player);
        CLIPS.remove(player);
        LOADED.remove(player);
        if (root != null && disk != null) {
            Path folder = root.resolve(player.toString());
            ERASING.add(player);
            runOnDisk(() -> {
                deleteFolder(folder);
                synchronized (VoiceMemory.class) {
                    ERASING.remove(player);
                }
            });
        }
    }

    /** Waits until pending writes and deletions are done. */
    public static void flushDisk() {
        ExecutorService current;
        synchronized (VoiceMemory.class) {
            current = disk;
        }
        if (current == null) {
            return;
        }
        try {
            current.submit(() -> {}).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException ignored) {
            // Nothing to report: the marker task cannot fail.
        }
    }

    /** Test helper: drops what is in memory, so the next read comes from disk. */
    public static synchronized void forgetMemoryOnly(UUID player) {
        OPEN.remove(player);
        CLIPS.remove(player);
        LOADED.remove(player);
    }

    private static void ensureLoaded(UUID player) {
        if (!LOADED.contains(player) && ConsentStore.accepted(player)) {
            loadPlayer(player);
        }
    }

    private static void flushStale(UUID player) {
        Recording rec = OPEN.get(player);
        if (rec != null && System.currentTimeMillis() - rec.lastFrameAt > PAUSE_MS) {
            close(player);
        }
    }

    private static void close(UUID player) {
        Recording rec = OPEN.remove(player);
        if (rec == null || rec.frames.size() < MIN_FRAMES) {
            return;
        }
        ensureLoaded(player);
        List<Clip> clips = CLIPS.computeIfAbsent(player, id -> new ArrayList<>());
        String name = System.currentTimeMillis() + "_" + Integer.toHexString(RANDOM.nextInt()) + ".voice";
        clips.add(new Clip(name, rec.frames));
        if (root != null) {
            Path file = root.resolve(player.toString()).resolve(name);
            List<byte[]> frames = rec.frames;
            runOnDisk(() -> writeClip(file, frames));
        }
        // A random old clip makes room, never the one just said, so very old phrases can survive for a long time.
        while (clips.size() > AfterselfConfig.voiceMaxClips) {
            Clip removed = clips.remove(RANDOM.nextInt(clips.size() - 1));
            if (root != null) {
                Path file = root.resolve(player.toString()).resolve(removed.fileName);
                runOnDisk(() -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                        // Stays on disk until the next successful cleanup.
                    }
                });
            }
        }
    }

    private static void runOnDisk(Runnable task) {
        if (disk != null) {
            disk.execute(task);
        }
    }

    private static void writeClip(Path file, List<byte[]> frames) {
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(file)))) {
                out.writeInt(FILE_VERSION);
                out.writeInt(frames.size());
                for (byte[] frame : frames) {
                    out.writeInt(frame.length);
                    out.write(frame);
                }
            }
        } catch (IOException ignored) {
            // The clip still exists in memory until the server stops.
        }
    }

    private static List<byte[]> readClip(Path file) {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != FILE_VERSION) {
                return null;
            }
            int count = in.readInt();
            if (count <= 0 || count > MAX_FRAMES) {
                return null;
            }
            List<byte[]> frames = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int length = in.readInt();
                if (length < 0 || length > 4096) {
                    return null;
                }
                byte[] frame = new byte[length];
                in.readFully(frame);
                frames.add(frame);
            }
            return frames;
        } catch (IOException e) {
            return null;
        }
    }

    private static void deleteFolder(Path folder) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> files = Files.list(folder)) {
            files.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Left for the next attempt.
                }
            });
            Files.deleteIfExists(folder);
        } catch (IOException ignored) {
            // Left for the next attempt.
        }
    }
}
