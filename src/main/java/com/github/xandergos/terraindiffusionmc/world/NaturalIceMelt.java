package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.core.Direction;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Lake ice that generated in a biome where water doesn't freeze melts back into water (Calvin, Nov 4). Worlds made
 * before 1.0.5 froze lakes by climate, so cold meadows and grasslands have frozen lakes; from 1.0.5 only snowy biomes
 * freeze theirs (see {@code RiverWaterPlacer}). Ice a player placed never melts this way: placements are remembered
 * in the world's saved data.
 *
 * <p>Runs from the ice block's random tick ({@code IceBlockMixin}), so a frozen lake thaws over a few minutes while
 * players are near it, like vanilla ice melting by a torch.
 */
public final class NaturalIceMelt {
    /** Biome votes are taken this far round the ice, every {@link #VOTE_STEP} blocks. */
    private static final int VOTE_RADIUS = 8, VOTE_STEP = 4;
    /** Sky light just above the ice at least this: open sky, or under a tree or a snow layer; not in a cave. */
    private static final int SKY_LIGHT_MIN = 8;

    private NaturalIceMelt() {
    }

    /** Whether this natural ice block at {@code pos} should melt now. */
    public static boolean shouldMelt(ServerLevel level, BlockPos pos) {
        if (!TerrainDiffusionConfig.meltNaturalIce() || TerrainDiffusionConfig.riverFreezeEnabled()) return false;
        if (level.dimension() != Level.OVERWORLD) return false;
        if (!(level.getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)) return false;
        // Inland lake ice only: above the sea (sea ice stays), open to the sky (cave ice stays; a snow layer or leaves
        // overhead don't count), not in an ocean biome (icebergs stay), and on a lake: water under it, or, where the
        // lake is a single block deep at its shore, next to more lake water or ice.
        if (pos.getY() <= level.getSeaLevel()) return false;
        if (level.getBrightness(LightLayer.SKY, pos.above()) < SKY_LIGHT_MIN) return false;
        if (level.getBiome(pos).is(BiomeTags.IS_OCEAN)) return false;
        if (!onLake(level, pos)) return false;
        if (snowyHere(level, pos)) return false;
        return !PlacedIce.get(level).contains(pos);
    }

    /**
     * Water under the ice, or (where the lake is one block deep) water beside it, or ice with water under it: an ice
     * floor on dry land, such as an old ice road, doesn't count.
     */
    private static boolean onLake(ServerLevel level, BlockPos pos) {
        if (level.getFluidState(pos.below()).is(FluidTags.WATER)) return true;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = pos.relative(d);
            if (level.getFluidState(n).is(FluidTags.WATER)) return true;
            if (level.getBlockState(n).is(Blocks.ICE) && level.getFluidState(n.below()).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    /**
     * Whether most of the biomes round the ice are snowy, as the lake freezing decides it at generation: snowy biomes
     * vote for, other climate biomes against, and bare rock and river biomes don't vote. No votes: snowy (keep the ice).
     */
    private static boolean snowyHere(ServerLevel level, BlockPos pos) {
        int snowy = 0, other = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dz = -VOTE_RADIUS; dz <= VOTE_RADIUS; dz += VOTE_STEP) {
            for (int dx = -VOTE_RADIUS; dx <= VOTE_RADIUS; dx += VOTE_STEP) {
                p.set(pos.getX() + dx, pos.getY(), pos.getZ() + dz);
                if (!level.hasChunkAt(p)) continue;
                Holder<Biome> b = level.getBiome(p);
                if (b.is(Biomes.STONY_PEAKS) || b.is(Biomes.RIVER) || b.is(Biomes.STONY_SHORE)) continue;
                if (isSnowy(b.value())) snowy++;
                else other++;
            }
        }
        return snowy + other == 0 || snowy > other;
    }

    /** Snows rather than rains, with snow on the ground: precipitation on and a base temperature under 0.15. */
    private static boolean isSnowy(Biome biome) {
        return biome.hasPrecipitation() && biome.getBaseTemperature() < 0.15f;
    }

    /** A player placed ice here (called when any entity places an ice block). */
    public static void placed(Level level, BlockPos pos) {
        if (level instanceof ServerLevel server && server.dimension() == Level.OVERWORLD) PlacedIce.get(server).add(pos);
    }

    /** The ice here is gone (broken, or melted). */
    public static void removed(Level level, BlockPos pos) {
        if (level instanceof ServerLevel server && server.dimension() == Level.OVERWORLD) PlacedIce.get(server).remove(pos);
    }

    /** Positions of ice blocks placed by players, saved with the world ({@code data/orogeny_placed_ice.dat}). */
    public static final class PlacedIce extends SavedData {
        public static final Codec<PlacedIce> CODEC = Codec.LONG_STREAM.xmap(
                s -> {
                    PlacedIce d = new PlacedIce();
                    s.forEach(d.positions::add);
                    return d;
                },
                d -> java.util.Arrays.stream(d.positions.toLongArray()));
        public static final SavedDataType<PlacedIce> TYPE = new SavedDataType<>(
                Identifier.fromNamespaceAndPath("orogeny", "placed_ice"), PlacedIce::new, CODEC,
                DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

        private final LongOpenHashSet positions = new LongOpenHashSet();

        public PlacedIce() {
        }

        static PlacedIce get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(TYPE);
        }

        synchronized boolean contains(BlockPos pos) {
            return positions.contains(pos.asLong());
        }

        synchronized void add(BlockPos pos) {
            if (positions.add(pos.asLong())) setDirty();
        }

        synchronized void remove(BlockPos pos) {
            if (positions.remove(pos.asLong())) setDirty();
        }
    }
}
