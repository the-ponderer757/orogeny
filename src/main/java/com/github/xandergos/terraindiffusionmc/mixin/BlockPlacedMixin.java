package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.NaturalIceMelt;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remembers ice blocks placed by players, so {@link NaturalIceMelt} never melts them. */
@Mixin(Block.class)
public abstract class BlockPlacedMixin {

    @Inject(method = "setPlacedBy", at = @At("HEAD"))
    private void orogeny$rememberPlacedIce(Level level, BlockPos pos, BlockState state, LivingEntity placer,
                                           ItemStack stack, CallbackInfo ci) {
        if (state.is(Blocks.ICE) && !level.isClientSide()) {
            try {
                NaturalIceMelt.placed(level, pos);
            } catch (RuntimeException ignored) {
                // never let this break block placement
            }
        }
    }
}
