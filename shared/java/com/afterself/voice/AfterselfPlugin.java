package com.afterself.voice;

import com.afterself.ConsentStore;
import com.afterself.Possession;
import com.afterself.Afterself;
import com.afterself.AfterselfConfig;
import com.afterself.VoiceMemory;
import com.afterself.entity.SoulEntity;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** The only class that talks to Simple Voice Chat. Found by Voice Chat through its annotation on both loaders. */
@ForgeVoicechatPlugin
public class AfterselfPlugin implements VoicechatPlugin {
    private static final int VOICE_RANGE = 24;
    private static final long NOISE_PERIOD_MS = 200L;
    /** Frame loudness below this is breathing or room noise. */
    private static final int QUIET = 350;
    private static final int NORMAL = 1800;
    private static final int LOUD = 7000;

    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastNoise = new ConcurrentHashMap<>();
    /** Who is talking right now, and since when: one voice at a time per speaker. */
    private final Map<UUID, Long> speaking = new ConcurrentHashMap<>();
    private static final long LONGEST_CLIP_MS = 60_000L;
    private VoicechatServerApi api;

    @Override
    public String getPluginId() {
        return Afterself.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        // Nothing to prepare before the voice server starts.
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onStarted);
        registration.registerEvent(VoicechatServerStoppedEvent.class, this::onStopped);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::onDisconnected);
    }

    private void onStarted(VoicechatServerStartedEvent event) {
        api = event.getVoicechat();
        Afterself.voiceOutput = this::play;
    }

    private void onStopped(VoicechatServerStoppedEvent event) {
        decoders.values().forEach(OpusDecoder::close);
        decoders.clear();
        lastNoise.clear();
        speaking.clear();
        api = null;
        Afterself.onServerVoiceStopped();
    }

    private void onDisconnected(PlayerDisconnectedEvent event) {
        UUID id = event.getPlayerUuid();
        OpusDecoder decoder = decoders.remove(id);
        if (decoder != null) {
            decoder.close();
        }
        lastNoise.remove(id);
        VoiceMemory.endPhrase(id);
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        VoicechatConnection connection = event.getSenderConnection();
        if (connection == null || !(connection.getPlayer().getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        UUID id = player.getUUID();
        // A possessed player cannot warn anyone: their microphone goes nowhere.
        if (Possession.isPossessed(id)) {
            event.cancel();
            return;
        }
        byte[] opus = event.getPacket().getOpusEncodedData();
        if (opus == null || opus.length == 0) {
            OpusDecoder decoder = decoders.get(id);
            if (decoder != null) {
                decoder.resetState();
            }
            VoiceMemory.endPhrase(id);
            return;
        }
        OpusDecoder decoder = decoders.computeIfAbsent(id, unused -> event.getVoicechat().createDecoder());
        handleFrame(player, opus, event.getPacket().isWhispering(), decoder);
    }

    /** Separate from the event so the same path can be exercised without a real microphone. */
    public void handleFrame(ServerPlayer player, byte[] opus, boolean whispering, OpusDecoder decoder) {
        int loudness = AudioFx.rms(decoder.decode(opus));
        UUID id = player.getUUID();
        if (AfterselfConfig.voiceClips && ConsentStore.accepted(id)) {
            VoiceMemory.frame(id, opus, System.currentTimeMillis());
        }
        int radius = noiseRadius(loudness, whispering);
        if (radius <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastNoise.get(id);
        if (last != null && now - last < NOISE_PERIOD_MS) {
            return;
        }
        lastNoise.put(id, now);
        MinecraftServer server = player.getServer();
        if (server != null) {
            server.execute(() -> SoulEntity.broadcastNoise(player, radius));
        }
    }

    static int noiseRadius(int loudness, boolean whispering) {
        if (loudness < QUIET) {
            return 0;
        }
        if (whispering) {
            return 5;
        }
        if (loudness < NORMAL) {
            return 10;
        }
        return loudness < LOUD ? 24 : 48;
    }

    private void play(Entity speaker, List<byte[]> frames, float pitch) {
        VoicechatServerApi current = api;
        if (current == null) {
            return;
        }
        UUID id = speaker.getUUID();
        long now = System.currentTimeMillis();
        Long since = speaking.get(id);
        if (since != null && now - since < LONGEST_CLIP_MS) {
            // Still saying the last thing: two streams on one channel would cut each other off.
            return;
        }
        short[] joined = decodeAll(current, frames);
        // The channel carries the speaker's own id. Voice Chat before 2.6 (the only one for Forge 1.21, and older
        // builds on 1.20.1) finds the speaker on the client by the channel's id: any other id and nothing is heard.
        EntityAudioChannel channel = current.createEntityAudioChannel(id, current.fromEntity(speaker));
        if (channel == null) {
            return;
        }
        channel.setDistance(VOICE_RANGE);
        // An empty packet first: the client forgets where an earlier stream on this id stopped, such as the
        // player's own voice cut off mid-sentence by a possession, and plays this one from its first packet.
        channel.flush();
        OpusEncoder encoder = current.createEncoder();
        AudioPlayer player = current.createAudioPlayer(channel, encoder, AudioFx.haunt(joined, pitch));
        speaking.put(id, now);
        player.setOnStopped(() -> {
            encoder.close();
            speaking.remove(id, now);
        });
        player.startPlaying();
    }

    private static short[] decodeAll(VoicechatServerApi api, List<byte[]> frames) {
        OpusDecoder decoder = api.createDecoder();
        try {
            short[][] parts = new short[frames.size()][];
            int total = 0;
            for (int i = 0; i < parts.length; i++) {
                parts[i] = decoder.decode(frames.get(i));
                total += parts[i].length;
            }
            short[] joined = new short[total];
            int at = 0;
            for (short[] part : parts) {
                System.arraycopy(part, 0, joined, at, part.length);
                at += part.length;
            }
            return joined;
        } finally {
            decoder.close();
        }
    }
}
