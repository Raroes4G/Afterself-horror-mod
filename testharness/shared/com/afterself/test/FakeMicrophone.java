package com.afterself.test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Dev-only: sends real microphone packets through Voice Chat's real client connection, every 20 ms like a real
 * microphone. Everything after the sound card runs for real. Reflection, because these are internal classes.
 */
final class FakeMicrophone {
    private FakeMicrophone() {}

    private static Object connection() throws ReflectiveOperationException {
        Object client = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager").getMethod("getClient").invoke(null);
        if (client == null) {
            return null;
        }
        return client.getClass().getMethod("getConnection").invoke(client);
    }

    /**
     * Voice Chat ignores all audio until its first-launch screen is done, and the test closes that screen.
     * The activation type stays push-to-talk and nothing presses the key, so the real microphone stays silent.
     */
    static void finishFirstLaunchScreen() {
        try {
            Class.forName("de.maxhenkel.voicechat.gui.onboarding.OnboardingManager").getMethod("finishOnboarding").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("AFTERSELFTEST INFO onboarding not finished: " + e);
        }
    }

    static boolean connected() {
        try {
            Object connection = connection();
            if (connection == null) {
                return false;
            }
            return (boolean) connection.getClass().getMethod("isInitialized").invoke(connection);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("AFTERSELFTEST INFO voice connection unavailable: " + e);
            return false;
        }
    }

    static void speak(byte[][] opusFrames) {
        Thread thread = new Thread(() -> {
            try {
                Object connection = connection();
                Class<?> micClass = Class.forName("de.maxhenkel.voicechat.voice.common.MicPacket");
                Class<?> messageClass = Class.forName("de.maxhenkel.voicechat.voice.common.NetworkMessage");
                Class<?> packetClass = Class.forName("de.maxhenkel.voicechat.voice.common.Packet");
                Constructor<?> mic = micClass.getConstructor(byte[].class, boolean.class, long.class);
                Constructor<?> message = messageClass.getConstructor(packetClass);
                Method send = connection.getClass().getMethod("sendToServer", messageClass);
                long sequence = 1L;
                for (byte[] frame : opusFrames) {
                    send.invoke(connection, message.newInstance(mic.newInstance(frame, false, sequence++)));
                    Thread.sleep(20L);
                }
            } catch (ReflectiveOperationException | InterruptedException | RuntimeException e) {
                System.out.println("AFTERSELFTEST FAIL fake_microphone | " + e);
            }
        }, "afterself-fake-microphone");
        thread.setDaemon(true);
        thread.start();
    }
}
