package com.afterself.entity;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.util.GeckoLibUtil;

/** Synced data and GeckoLib 3 animations: 1.19.2 has the older GeckoLib API. Same animation files as the others. */
public abstract class SoulBase extends Monster implements IAnimatable {
    private static final EntityDataAccessor<Optional<UUID>> OWNER_ID =
            SynchedEntityData.defineId(SoulBase.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<String> OWNER_NAME =
            SynchedEntityData.defineId(SoulBase.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Byte> MODE =
            SynchedEntityData.defineId(SoulBase.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> LURE =
            SynchedEntityData.defineId(SoulBase.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> SCALE =
            SynchedEntityData.defineId(SoulBase.class, EntityDataSerializers.FLOAT);

    private final AnimationFactory animationFactory = GeckoLibUtil.createFactory(this);

    protected SoulBase(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(OWNER_ID, Optional.empty());
        this.entityData.define(OWNER_NAME, "");
        this.entityData.define(MODE, (byte) 0);
        this.entityData.define(LURE, (byte) 0);
        this.entityData.define(SCALE, 1f);
    }

    public UUID getOwnerId() {
        return this.entityData.get(OWNER_ID).orElse(null);
    }

    public String getOwnerName() {
        return this.entityData.get(OWNER_NAME);
    }

    protected void setOwnerData(UUID id, String name) {
        this.entityData.set(OWNER_ID, Optional.ofNullable(id));
        this.entityData.set(OWNER_NAME, name == null ? "" : name);
    }

    protected int getModeData() {
        return this.entityData.get(MODE);
    }

    protected void setModeData(int mode) {
        this.entityData.set(MODE, (byte) mode);
    }

    protected int getLureData() {
        return this.entityData.get(LURE);
    }

    protected void setLureData(int scene) {
        this.entityData.set(LURE, (byte) scene);
    }

    public float getSoulScale() {
        return this.entityData.get(SCALE);
    }

    protected void setSoulScale(float scale) {
        this.entityData.set(SCALE, scale);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (SCALE.equals(key)) {
            this.refreshDimensions();
        }
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return super.getDimensions(pose).scale(getSoulScale());
    }

    public boolean onGroundCompat() {
        return this.isOnGround();
    }

    public float walkAnimationSpeed() {
        return this.animationSpeed;
    }

    /** The Blockbench animation to loop right now. */
    protected abstract String animationName();

    /**
     * GeckoLib 3 keeps its controllers in a hash map and plays them in the order of that map, not in the order they
     * were added, and the last one to move a bone wins. These three names come out as "state", "attack", "guard":
     * the blow and the raised shield are played over the walk. Renaming one can change the order.
     */
    @Override
    public void registerControllers(AnimationData data) {
        data.addAnimationController(new AnimationController<>(this, "state", 4, event -> {
            event.getController().setAnimation(new AnimationBuilder().addAnimation(animationName(), ILoopType.EDefaultLoopTypes.LOOP));
            return PlayState.CONTINUE;
        }));
        data.addAnimationController(new AnimationController<>(this, "attack", 1, event -> {
            if (this.swinging) {
                event.getController().setAnimation(new AnimationBuilder().addAnimation(SoulAnimations.ATTACK, ILoopType.EDefaultLoopTypes.PLAY_ONCE));
                return PlayState.CONTINUE;
            }
            event.getController().markNeedsReload();
            return PlayState.STOP;
        }));
        data.addAnimationController(new AnimationController<>(this, "guard", 3, event -> {
            if (SoulAnimations.isGuarding(this)) {
                event.getController().setAnimation(new AnimationBuilder().addAnimation(SoulAnimations.GUARD, ILoopType.EDefaultLoopTypes.LOOP));
                return PlayState.CONTINUE;
            }
            event.getController().markNeedsReload();
            return PlayState.STOP;
        }));
    }

    @Override
    public AnimationFactory getFactory() {
        return animationFactory;
    }
}
