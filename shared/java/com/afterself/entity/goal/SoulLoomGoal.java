package com.afterself.entity.goal;

import com.afterself.entity.SoulEntity;
import java.util.EnumSet;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

/**
 * Right behind you, not moving, looking at the back of your head. What happens when you turn around is decided in
 * SoulEntity: this only keeps it perfectly still.
 */
public class SoulLoomGoal extends Goal {
    private final SoulEntity soul;

    public SoulLoomGoal(SoulEntity soul) {
        this.soul = soul;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return soul.getMode() == SoulEntity.Mode.BEHIND;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        soul.getNavigation().stop();
        soul.setSprinting(false);
    }

    @Override
    public void tick() {
        soul.getNavigation().stop();
        Player player = soul.getStalkedPlayer();
        if (player != null) {
            soul.getLookControl().setLookAt(player.getX(), player.getEyeY(), player.getZ(), 60f, 60f);
        }
    }
}
