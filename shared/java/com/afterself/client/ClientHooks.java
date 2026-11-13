package com.afterself.client;

import com.afterself.Possession;
import com.afterself.Afterself;
import com.afterself.entity.SoulEntity;

/** Connects the soul's events to the client code. Only loaded on the client. */
public final class ClientHooks {
    private ClientHooks() {}

    public static void init() {
        Afterself.clientEvents = (soul, id) -> {
            if (id == SoulEntity.EVENT_SCREAMER) {
                ScreamerOverlay.trigger(soul);
            } else if (id == SoulEntity.EVENT_FLASH) {
                ScreamerOverlay.flash(soul);
            } else if (id == Possession.EVENT_POSSESS) {
                PossessionClient.onPossessed();
            } else if (id == Possession.EVENT_RELEASE) {
                PossessionClient.onReleased();
            }
        };
    }
}
