package com.afterself.test;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import java.util.UUID;

/** Dev-only: observes the same voice events the mod uses, at both ends of the network. */
@ForgeVoicechatPlugin
public class AfterselfTestVoice implements VoicechatPlugin {
    public static volatile VoicechatServerApi api;
    public static volatile Throwable lastError = new IllegalStateException("clip was never played");

    /** Microphone packets that really arrived at the voice server. */
    public static volatile int micPackets;

    /** Audio the client really received from one entity. */
    public static volatile UUID listenTo;
    public static volatile int framesFromEntity;
    public static volatile int loudestSample;
    /** Where audio stops, when it stops: sent by the server, then seen by the client. */
    public static volatile int serverEntityFrames;
    public static volatile int clientEvents;

    /** Old Voice Chat builds cannot name the entity. Only the soul talks during the test, so any entity sound counts. */
    private static boolean isFromListenedEntity(ClientReceiveSoundEvent.EntitySound event) {
        try {
            return event.getEntityId().equals(listenTo);
        } catch (NoSuchMethodError e) {
            return listenTo != null;
        }
    }

    @Override
    public String getPluginId() {
        return "afterselftest";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> api = event.getVoicechat());
        registration.registerEvent(MicrophonePacketEvent.class, event -> {
            if (event.getPacket().getOpusEncodedData().length > 0) {
                micPackets++;
            }
        });
        registration.registerEvent(EntitySoundPacketEvent.class, event -> serverEntityFrames++);
        registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, event -> {
            if (isFromListenedEntity(event)) {
                clientEvents++;
            }
            // Empty packets only mark where a stream starts or ends.
            if (isFromListenedEntity(event) && event.getRawAudio() != null && event.getRawAudio().length > 0) {
                framesFromEntity++;
                for (short sample : event.getRawAudio()) {
                    loudestSample = Math.max(loudestSample, Math.abs((int) sample));
                }
                // Measured, then silenced: the test tone must not reach the speakers.
                if (System.getenv("AFTERSELF_TEST_KEEP_AUDIO") == null) {
                    event.setRawAudio(new short[event.getRawAudio().length]);
                }
            }
        });
    }
}
