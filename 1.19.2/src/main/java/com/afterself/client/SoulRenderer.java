package com.afterself.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Vector3f;
import com.afterself.entity.SoulEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.core.util.Color;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.renderers.geo.ExtendedGeoEntityRenderer;

/** The dead player's skin, cold and see-through, animated by GeckoLib 3. */
public class SoulRenderer extends ExtendedGeoEntityRenderer<SoulEntity> {
    private static final Color TINT = Color.ofRGBA(0.55f, 0.7f, 0.95f, 0.65f);

    public SoulRenderer(EntityRendererProvider.Context context) {
        super(context, new SoulModel());
        this.shadowRadius = 0.3f;
    }

    @Override
    public RenderType getRenderType(SoulEntity soul, float partialTick, PoseStack pose, MultiBufferSource buffers,
            VertexConsumer consumer, int light, ResourceLocation texture) {
        return RenderType.entityTranslucent(texture);
    }

    @Override
    public Color getRenderColor(SoulEntity soul, float partialTick, PoseStack pose, MultiBufferSource buffers,
            VertexConsumer consumer, int light) {
        // In disguise it looks alive: no cold tint, nothing to see through.
        return soul.isDisguised() ? Color.ofRGBA(1f, 1f, 1f, 1f) : TINT;
    }

    @Override
    public void render(SoulEntity soul, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        float scale = soul.getSoulScale();
        pose.pushPose();
        pose.scale(scale, scale, scale);
        super.render(soul, yaw, partialTick, pose, buffers, light);
        pose.popPose();
    }

    /** Inside a possessed player, it is only a marker: nothing to draw. */
    @Override
    public boolean shouldRender(SoulEntity soul, Frustum frustum, double x, double y, double z) {
        return !soul.isPossessing() && super.shouldRender(soul, frustum, x, y, z);
    }

    @Override
    public boolean shouldShowName(SoulEntity soul) {
        return soul.isDisguised();
    }

    @Override
    protected boolean isArmorBone(GeoBone bone) {
        return switch (bone.getName()) {
            case "head", "body", "rightArm", "leftArm", "rightLeg", "leftLeg" -> true;
            default -> false;
        };
    }

    @Override
    protected ResourceLocation getTextureForBone(String bone, SoulEntity soul) {
        return null;
    }

    @Override
    protected ItemStack getHeldItemForBone(String bone, SoulEntity soul) {
        return switch (bone) {
            case "rightItem" -> soul.getMainHandItem();
            case "leftItem" -> soul.getOffhandItem();
            default -> null;
        };
    }

    @Override
    protected ItemTransforms.TransformType getCameraTransformForItemAtBone(ItemStack stack, String bone) {
        return bone.equals("leftItem") ? ItemTransforms.TransformType.THIRD_PERSON_LEFT_HAND : ItemTransforms.TransformType.THIRD_PERSON_RIGHT_HAND;
    }

    @Override
    protected BlockState getHeldBlockForBone(String bone, SoulEntity soul) {
        return null;
    }

    @Override
    protected void preRenderItem(PoseStack pose, ItemStack stack, String bone, SoulEntity soul, IBone boneObject) {
        // The game's own placement of a held item, written for a model that is not drawn upside down like the
        // game's: a quarter turn only. Half a turn more and an axe shows its back, a shield its inside.
        pose.mulPose(Vector3f.XP.rotationDegrees(-90f));
        pose.translate(0f, 0.0625f, 0f);
    }

    /**
     * GeckoLib draws every item as if held in the right hand, which throws a shield in the left hand far off to the
     * side. The game mirrors left-hand items itself when told which hand.
     */
    @Override
    protected void renderItemStack(PoseStack pose, MultiBufferSource buffers, int light, ItemStack stack, String bone) {
        SoulEntity soul = this.currentEntityBeingRendered;
        net.minecraft.client.Minecraft.getInstance().getItemRenderer().renderStatic(soul, stack,
                getCameraTransformForItemAtBone(stack, bone), bone.equals("leftItem"), pose, buffers, soul.level, light,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, soul.getId());
    }

    @Override
    protected void preRenderBlock(PoseStack pose, BlockState state, String bone, SoulEntity soul) {
    }

    @Override
    protected void postRenderItem(PoseStack pose, ItemStack stack, String bone, SoulEntity soul, IBone boneObject) {
    }

    @Override
    protected void postRenderBlock(PoseStack pose, BlockState state, String bone, SoulEntity soul) {
    }

    @Override
    protected ItemStack getArmorForBone(String bone, SoulEntity soul) {
        return switch (bone) {
            case "head" -> soul.getItemBySlot(EquipmentSlot.HEAD);
            case "body", "rightArm", "leftArm" -> soul.getItemBySlot(EquipmentSlot.CHEST);
            case "rightLeg", "leftLeg" -> {
                ItemStack leggings = soul.getItemBySlot(EquipmentSlot.LEGS);
                yield leggings.isEmpty() ? soul.getItemBySlot(EquipmentSlot.FEET) : leggings;
            }
            default -> null;
        };
    }

    @Override
    protected EquipmentSlot getEquipmentSlotForArmorBone(String bone, SoulEntity soul) {
        return switch (bone) {
            case "head" -> EquipmentSlot.HEAD;
            case "body", "rightArm", "leftArm" -> EquipmentSlot.CHEST;
            case "rightLeg", "leftLeg" -> soul.getItemBySlot(EquipmentSlot.LEGS).isEmpty() ? EquipmentSlot.FEET : EquipmentSlot.LEGS;
            default -> null;
        };
    }

    @Override
    protected ModelPart getArmorPartForBone(String bone, HumanoidModel<?> model) {
        return switch (bone) {
            case "head" -> model.head;
            case "body" -> model.body;
            case "rightArm" -> model.rightArm;
            case "leftArm" -> model.leftArm;
            case "rightLeg" -> model.rightLeg;
            case "leftLeg" -> model.leftLeg;
            default -> null;
        };
    }
}
