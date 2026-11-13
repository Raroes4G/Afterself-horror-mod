package com.afterself;

import com.afterself.entity.SoulEntity;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/** Shared state filled in by whichever loader entry point runs. */
public final class Afterself {
    public static final String MOD_ID = "afterself";

    /** Set by the loader entry before anything uses it. */
    public static Supplier<EntityType<SoulEntity>> soulType;
    public static Supplier<SoundEvent> screamerSound;

    /** Set on the client only: keeps client classes out of the dedicated server. */
    public static ClientEvents clientEvents = (soul, id) -> {};

    /** Set by the Voice Chat plugin when its server starts; plays a clip from any entity. */
    public static VoiceOutput voiceOutput;

    public interface VoiceOutput {
        void play(Entity speaker, List<byte[]> opusFrames, float pitch);
    }

    public interface ClientEvents {
        void onSoulEvent(SoulEntity soul, byte id);
    }

    private Afterself() {}

    public static void onServerVoiceStopped() {
        voiceOutput = null;
    }
}
