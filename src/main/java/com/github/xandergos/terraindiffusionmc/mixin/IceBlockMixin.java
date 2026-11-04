package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.NaturalIceMelt;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Natural lake ice in a biome where water doesn't freeze melts on its random tick ({@link NaturalIceMelt}). */
@Mixin(IceBlock.class)
public abstract class IceBlockMixin {

    @Shadow
    protected abstract void melt(BlockState state, Level level, BlockPos pos);

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void orogeny$meltNaturalIce(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
                                        CallbackInfo ci) {
        try {
            if (NaturalIceMelt.shouldMelt(level, pos)) {
                melt(state, level, pos);
                ci.cancel();
            }
        } catch (RuntimeException ignored) {
            // never let this break block ticking
        }
    }

    @Inject(method = "playerDestroy", at = @At("HEAD"))
    private void orogeny$forgetPlacedIce(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state,
                                         BlockEntity blockEntity, ItemStack tool, CallbackInfo ci) {
        NaturalIceMelt.removed(level, pos);
    }
}
