package com.afterself.client;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

/** Client calls that differ in Minecraft 1.20.1. */
public final class ClientCompat {
    private static final UUID NOBODY = new UUID(0L, 0L);

    /** This version tells the client who hurt a player. */
    public static final boolean KNOWS_ATTACKER = true;

    private ClientCompat() {}

    public static ResourceLocation skinOf(UUID owner) {
        UUID id = owner == null ? NOBODY : owner;
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(id);
        return info != null ? info.getSkinLocation() : DefaultPlayerSkin.getDefaultSkin(id);
    }

    /**
     * A sound of the mod by name, for the client's own use. Only sounds.json has to know it: nothing is registered,
     * so a server never needs to agree on the list.
     */
    public static net.minecraft.sounds.SoundEvent uiSound(String name) {
        return net.minecraft.sounds.SoundEvent.createVariableRangeEvent(com.afterself.Compat.id(name));
    }

    public static boolean isSlim(UUID owner) {
        UUID id = owner == null ? NOBODY : owner;
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(id);
        return "slim".equals(info != null ? info.getModelName() : DefaultPlayerSkin.getSkinModelName(id));
    }
}
