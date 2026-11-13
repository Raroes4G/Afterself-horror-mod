package com.afterself.entity;

import java.util.HashMap;
import java.util.Map;
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
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/** Synced data and GeckoLib animations, whose code changes across Minecraft versions. */
public abstract class SoulBase extends Monster implements GeoEntity {
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
    private static final Map<String, RawAnimation> LOOPS = new HashMap<>();
    private static final RawAnimation ATTACK = RawAnimation.begin().thenPlay(SoulAnimations.ATTACK);
    private static final RawAnimation GUARD = RawAnimation.begin().thenLoop(SoulAnimations.GUARD);

    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);

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
        return this.onGround();
    }

    public float walkAnimationSpeed() {
        return this.walkAnimation.speed();
    }

    /** The Blockbench animation to loop right now. */
    protected abstract String animationName();

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 4, state -> {
            state.getController().setAnimation(LOOPS.computeIfAbsent(animationName(), name -> RawAnimation.begin().thenLoop(name)));
            return PlayState.CONTINUE;
        }));
        controllers.add(new AnimationController<>(this, "attack", 1, state -> {
            if (this.swinging) {
                state.getController().setAnimation(ATTACK);
                return PlayState.CONTINUE;
            }
            state.getController().forceAnimationReset();
            return PlayState.STOP;
        }));
        // After "main", so the raised shield arm wins over the walk's swinging arm.
        controllers.add(new AnimationController<>(this, "guard", 3, state -> {
            if (SoulAnimations.isGuarding(this)) {
                state.getController().setAnimation(GUARD);
                return PlayState.CONTINUE;
            }
            state.getController().forceAnimationReset();
            return PlayState.STOP;
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animationCache;
    }
}
