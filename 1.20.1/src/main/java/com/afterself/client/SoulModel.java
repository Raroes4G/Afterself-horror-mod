package com.afterself.client;

import com.afterself.Compat;
import com.afterself.entity.SoulEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

/** Files in geo/entity and animations/entity. The skin always comes from the dead player. */
public class SoulModel extends DefaultedEntityGeoModel<SoulEntity> {
    private static final ResourceLocation WIDE = Compat.id("geo/entity/soul.geo.json");
    private static final ResourceLocation SLIM = Compat.id("geo/entity/soul_slim.geo.json");

    public SoulModel() {
        super(Compat.id("soul"), true);
    }

    @Override
    public ResourceLocation getModelResource(SoulEntity soul) {
        return ClientCompat.isSlim(soul.getOwnerId()) ? SLIM : WIDE;
    }

    @Override
    public ResourceLocation getTextureResource(SoulEntity soul) {
        return ClientCompat.skinOf(soul.getOwnerId());
    }
}
