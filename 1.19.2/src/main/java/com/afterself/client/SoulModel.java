package com.afterself.client;

import com.afterself.Compat;
import com.afterself.entity.SoulEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.model.AnimatedGeoModel;
import software.bernie.geckolib3.model.provider.data.EntityModelData;

/** Same files as the other versions. The skin always comes from the dead player. */
public class SoulModel extends AnimatedGeoModel<SoulEntity> {
    private static final ResourceLocation WIDE = Compat.id("geo/entity/soul.geo.json");
    private static final ResourceLocation SLIM = Compat.id("geo/entity/soul_slim.geo.json");
    private static final ResourceLocation ANIMATIONS = Compat.id("animations/entity/soul.animation.json");

    @Override
    public ResourceLocation getModelResource(SoulEntity soul) {
        return ClientCompat.isSlim(soul.getOwnerId()) ? SLIM : WIDE;
    }

    @Override
    public ResourceLocation getTextureResource(SoulEntity soul) {
        return ClientCompat.skinOf(soul.getOwnerId());
    }

    @Override
    public ResourceLocation getAnimationResource(SoulEntity soul) {
        return ANIMATIONS;
    }

    /** GeckoLib 3 has no built-in head look. Rare scenes keep the pose from their own animation. */
    @Override
    public void setCustomAnimations(SoulEntity soul, int instanceId, AnimationEvent event) {
        super.setCustomAnimations(soul, instanceId, event);
        IBone head = getAnimationProcessor().getBone("head");
        Object data = event == null ? null : event.getExtraDataOfType(EntityModelData.class).stream().findFirst().orElse(null);
        if (head != null && data instanceof EntityModelData model && soul.getLureScene() == SoulEntity.LureScene.NONE) {
            head.setRotationX(head.getRotationX() + model.headPitch * Mth.DEG_TO_RAD);
            head.setRotationY(head.getRotationY() + model.netHeadYaw * Mth.DEG_TO_RAD);
        }
    }
}
