package com.afterself.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.afterself.entity.SoulEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer;
import software.bernie.geckolib.renderer.layer.ItemArmorGeoLayer;

/** The dead player's skin, cold and see-through, animated by GeckoLib. */
public class SoulRenderer extends GeoEntityRenderer<SoulEntity> {
    private static final int TINT = FastColor.ARGB32.colorFromFloat(0.65f, 0.55f, 0.7f, 0.95f);

    public SoulRenderer(EntityRendererProvider.Context context) {
        super(context, new SoulModel());
        this.shadowRadius = 0.3f;
        addRenderLayer(new BlockAndItemGeoLayer<>(this) {
            @Override
            protected ItemStack getStackForBone(GeoBone bone, SoulEntity soul) {
                return switch (bone.getName()) {
                    case "rightItem" -> soul.getMainHandItem();
                    case "leftItem" -> soul.getOffhandItem();
                    default -> null;
                };
            }

            @Override
            protected ItemDisplayContext getTransformTypeForStack(GeoBone bone, ItemStack stack, SoulEntity soul) {
                return bone.getName().equals("leftItem") ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
            }

            @Override
            protected void renderStackForBone(PoseStack pose, GeoBone bone, ItemStack stack, SoulEntity soul,
                    MultiBufferSource buffers, float partialTick, int light, int overlay) {
                // The game's own placement of a held item, written for a model that is not drawn upside down like
                // the game's: a quarter turn only. Half a turn more and an axe shows its back, a shield its inside.
                pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90f));
                pose.translate(0f, 0.0625f, 0f);
                // Not through GeckoLib: it draws every item as if held in the right hand, which throws a shield in
                // the left hand far off to the side. The game mirrors left-hand items itself when told which hand.
                boolean leftHand = bone.getName().equals("leftItem");
                net.minecraft.client.Minecraft.getInstance().getItemRenderer().renderStatic(soul, stack,
                        getTransformTypeForStack(bone, stack, soul), leftHand, pose, buffers, soul.level(), light, overlay, soul.getId());
            }
        });
        addRenderLayer(new ItemArmorGeoLayer<>(this) {
            @Override
            protected ItemStack getArmorItemForBone(GeoBone bone, SoulEntity soul) {
                return switch (bone.getName()) {
                    case "head" -> soul.getItemBySlot(EquipmentSlot.HEAD);
                    case "body", "rightArm", "leftArm" -> soul.getItemBySlot(EquipmentSlot.CHEST);
                    case "rightLeg", "leftLeg" -> legs(soul);
                    default -> null;
                };
            }

            private ItemStack legs(SoulEntity soul) {
                ItemStack leggings = soul.getItemBySlot(EquipmentSlot.LEGS);
                return leggings.isEmpty() ? soul.getItemBySlot(EquipmentSlot.FEET) : leggings;
            }

            @Override
            protected EquipmentSlot getEquipmentSlotForBone(GeoBone bone, ItemStack stack, SoulEntity soul) {
                return switch (bone.getName()) {
                    case "head" -> EquipmentSlot.HEAD;
                    case "body", "rightArm", "leftArm" -> EquipmentSlot.CHEST;
                    default -> soul.getItemBySlot(EquipmentSlot.LEGS).isEmpty() ? EquipmentSlot.FEET : EquipmentSlot.LEGS;
                };
            }

            @Override
            protected ModelPart getModelPartForBone(GeoBone bone, EquipmentSlot slot, ItemStack stack, SoulEntity soul, HumanoidModel<?> model) {
                return switch (bone.getName()) {
                    case "head" -> model.head;
                    case "rightArm" -> model.rightArm;
                    case "leftArm" -> model.leftArm;
                    case "rightLeg" -> model.rightLeg;
                    case "leftLeg" -> model.leftLeg;
                    default -> model.body;
                };
            }
        });
    }

    @Override
    public RenderType getRenderType(SoulEntity soul, ResourceLocation texture, MultiBufferSource buffers, float partialTick) {
        return RenderType.entityTranslucent(texture);
    }

    @Override
    public void actuallyRender(PoseStack pose, SoulEntity soul, BakedGeoModel model, RenderType type, MultiBufferSource buffers,
            VertexConsumer consumer, boolean isReRender, float partialTick, int light, int overlay, int colour) {
        // In disguise it looks alive: no cold tint, nothing to see through.
        super.actuallyRender(pose, soul, model, type, buffers, consumer, isReRender, partialTick, light, overlay,
                soul.isDisguised() ? colour : TINT);
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
}
