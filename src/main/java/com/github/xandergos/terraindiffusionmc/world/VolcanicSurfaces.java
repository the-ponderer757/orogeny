package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.Landforms;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.pipeline.RegionalBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Surfaces of the landform stage's volcanoes (biomes plan v1.4 round A), right after the terrain pass, from the tile's
 * surface tags ({@link Landforms#TAG_LAVA} and the others). The volcano and lava field biomes already give the slopes
 * their ash and rock through their surface rules; this lays the exact shapes on top:
 * <ul>
 *   <li><b>Lava flows</b>: fresh rock (blackstone, basalt, a little smooth basalt) three blocks deep, with the odd
 *   magma block in a hollow.</li>
 *   <li><b>Live lava</b> along the middle of an active volcano's freshest flows, as a continuous stream sunk below its
 *   banks that pours down the flow's steps, and in its crater where the ground is level or a hollow; only where every
 *   column within three blocks is bare lava rock (nothing to set alight). Magma under it.</li>
 *   <li><b>Craters</b>: blackstone and tuff, with obsidian near the vent.</li>
 * </ul>
 * Snowy ground keeps its snow (a snow-capped volcano), except where live lava lies.
 */
public final class VolcanicSurfaces {
    private static final Logger LOG = LoggerFactory.getLogger(VolcanicSurfaces.class);

    /** Blocks of fresh rock under a flow's surface. */
    private static final int ROCK_DEPTH = 3;
    /** Share of level spots on a cooled flow with a magma block. */
    private static final float MAGMA_CHANCE = 0.05f;
    /** Live lava needs this many blocks of bare lava rock around it in every direction. */
    private static final int CLEARANCE = 3;

    private static final FastNoiseLite PATCHES = noise(77301, 1f / 9f);
    private static final FastNoiseLite STRATA = noise(77302, 1f / 6f);

    private static volatile boolean loggedFirst;

    private VolcanicSurfaces() {
    }

    private static FastNoiseLite noise(int seed, float freq) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freq);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFractalOctaves(2);
        return n;
    }

    public static void place(ChunkAccess chunk) {
        try {
            if (!TerrainDiffusionConfig.volcanoes()) return;
            placeUnchecked(chunk);
        } catch (Throwable t) {
            LOG.error("Orogeny: volcanic surfaces failed for chunk {}", chunk.getPos(), t);
        }
    }

    private static void placeUnchecked(ChunkAccess chunk) {
        ChunkPos cp = chunk.getPos();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        int tileSize = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(tileSize);
        int startX = (x0 >> shift) << shift, startZ = (z0 >> shift) << shift;
        HeightmapData data = LocalTerrainProvider.peekHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
        if (data == null || data.oceanTags == null) return;
        int scale = WorldScaleManager.clampScale(WorldScaleManager.getCurrentScale());
        long seed = LocalTerrainProvider.getSeed();
        int minY = chunk.getMinY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = x0 + dx, z = z0 + dz, lx = x - startX, lz = z - startZ;
                byte tag = data.oceanTags[lz][lx];
                if (tag != Landforms.TAG_LAVA && tag != Landforms.TAG_LAVA_CHANNEL && tag != Landforms.TAG_LAVA_LAKE
                        && tag != Landforms.TAG_CRATER && tag != Landforms.TAG_HOT_CRATER) continue;
                if (!loggedFirst) {
                    loggedFirst = true;
                    LOG.info("Orogeny: first chunk with volcanic surfaces at {}", cp);
                }
                long h = mix(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ 0x7A1AL);
                column(chunk, data, pos, x, z, lx, lz, h, tag, minY, scale);
            }
        }
    }

    private static void column(ChunkAccess chunk, HeightmapData data, BlockPos.MutableBlockPos pos, int x, int z,
                               int lx, int lz, long h, byte tag, int minY, int scale) {
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15);
        while (y > minY) {
            BlockState st = chunk.getBlockState(pos.set(x, y, z));
            if (!st.isAir() && st.getFluidState().isEmpty() && !st.is(Blocks.SNOW)) break;
            y--;
        }
        if (y <= minY) return;
        if (!chunk.getBlockState(pos.set(x, y + 1, z)).getFluidState().isEmpty()) return;   // under water: leave it
        boolean snowy = com.github.xandergos.terraindiffusionmc.pipeline.CoastShaper.isSnowy(data.biomeIds[lz][lx]);
        boolean liveTag = tag == Landforms.TAG_LAVA_CHANNEL || tag == Landforms.TAG_LAVA_LAKE;
        if (snowy && !liveTag) return;
        for (int yy = y; yy >= Math.max(minY + 1, y - ROCK_DEPTH); yy--) {
            BlockState st = chunk.getBlockState(pos.set(x, yy, z));
            if (!isGround(st)) continue;
            boolean crater = tag == Landforms.TAG_CRATER || tag == Landforms.TAG_HOT_CRATER;
            chunk.setBlockState(pos, crater ? craterRockAt(x, yy, z, h) : lavaRockAt(x, yy, z), 0);
        }
        BlockState above = chunk.getBlockState(pos.set(x, y + 1, z));
        if (above.is(Blocks.SNOW)) chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), 0);
        else if (!above.isAir()) return;
        int startX = x - lx, startZ = z - lz;
        int own = tileTop(data, startX, startZ, x, z, scale);
        // Live channels run down the flow as one stream: each column's lava sits in a trough no higher than the banks
        // beside it (the columns off the channel), so it can only run on along the channel, and steps between columns
        // spill as lava falls. (Only level spots used to get lava, which left a downhill channel as stripes, Calvin,
        // Nov 1, X 105902 Z 43784.)
        if (tag == Landforms.TAG_LAVA_CHANNEL && own == y && clearOfFuel(data, startX, startZ, x, z)) {
            // Lava can't climb: where the channel runs over a rise in the ground (a bump of the flow's rough surface),
            // it is cut down through it (an opening of the channel's profile), instead of the lava heaping up over the
            // hump and pouring down its front (Calvin, Nov 1, X -42012 Z -55073).
            int level = Math.min(own, openedTop(data, startX, startZ, x, z, scale));
            boolean step = false;
            int[] ox = {1, -1, 0, 0}, oz = {0, 0, 1, -1};
            for (int k = 0; k < 4; k++) {
                int nx = x + ox[k], nz = z + oz[k];
                int nTop = tileTop(data, startX, startZ, nx, nz, scale);
                if (nTop == Integer.MIN_VALUE) return;
                if (isChannel(data, startX, startZ, nx, nz)) {
                    if (nTop < own) step = true;   // the channel goes on downhill: let it pour
                } else {
                    level = Math.min(level, nTop);
                }
            }
            if (own - level > TROUGH_MAX) return;
            for (int yy = own; yy > level; yy--) chunk.setBlockState(pos.set(x, yy, z), Blocks.AIR.defaultBlockState(), 0);
            if (chunk.getBlockState(pos.set(x, level + 1, z)).is(Blocks.SNOW)) chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), 0);
            chunk.setBlockState(pos.set(x, level, z), Blocks.LAVA.defaultBlockState(), 0);
            chunk.setBlockState(pos.set(x, level - 1, z), Blocks.MAGMA_BLOCK.defaultBlockState(), 0);
            if (step || level < own) chunk.markPosForPostProcessing(pos.set(x, level, z));
            return;
        }
        // Lava lakes and magma only where the ground is level or a hollow, so nothing can run out.
        if (own != y || !levelOrHollow(data, startX, startZ, x, z, scale, own)) return;
        if (liveTag && clearOfFuel(data, startX, startZ, x, z)) {
            chunk.setBlockState(pos.set(x, y, z), Blocks.LAVA.defaultBlockState(), 0);
            chunk.setBlockState(pos.set(x, y - 1, z), Blocks.MAGMA_BLOCK.defaultBlockState(), 0);
        } else if (tag == Landforms.TAG_LAVA && unit(mix(h + 9)) < MAGMA_CHANCE) {
            chunk.setBlockState(pos.set(x, y, z), Blocks.MAGMA_BLOCK.defaultBlockState(), 0);
        }
    }

    /** Deepest a live channel is sunk below its own ground to sit under its banks. */
    private static final int TROUGH_MAX = 6;
    /** Half-width (blocks) of the window that cuts bumps out of a channel's profile: rises up to about twice this long. */
    private static final int BUMP_R = 3;

    /**
     * The channel's profile at (x, z) with bumps cut out: the highest, over the channel columns within {@link #BUMP_R},
     * of the lowest channel ground within {@link #BUMP_R} of each (a morphological opening). Slopes and dips are kept;
     * a rise narrower than the window is levelled. From the tiles only, so every chunk agrees.
     */
    private static int openedTop(HeightmapData data, int startX, int startZ, int x, int z, int scale) {
        int best = Integer.MIN_VALUE;
        for (int dz = -BUMP_R; dz <= BUMP_R; dz++) {
            for (int dx = -BUMP_R; dx <= BUMP_R; dx++) {
                int ax = x + dx, az = z + dz;
                if (!isChannel(data, startX, startZ, ax, az)) continue;
                int low = Integer.MAX_VALUE;
                for (int ez = -BUMP_R; ez <= BUMP_R && low > best; ez++) {
                    for (int ex = -BUMP_R; ex <= BUMP_R; ex++) {
                        int bx = ax + ex, bz = az + ez;
                        if (!isChannel(data, startX, startZ, bx, bz)) continue;
                        int t = tileTop(data, startX, startZ, bx, bz, scale);
                        if (t != Integer.MIN_VALUE && t < low) low = t;
                    }
                }
                if (low != Integer.MAX_VALUE && low > best) best = low;
            }
        }
        return best == Integer.MIN_VALUE ? Integer.MAX_VALUE : best;
    }

    /** Whether block column (x, z) is a live lava channel or lava lake. */
    private static boolean isChannel(HeightmapData data, int startX, int startZ, int x, int z) {
        HeightmapData d = tileAt(data, startX, startZ, x, z);
        if (d == null || d.oceanTags == null) return false;
        int size = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(size);
        byte v = d.oceanTags[z - ((z >> shift) << shift)][x - ((x >> shift) << shift)];
        return v == Landforms.TAG_LAVA_CHANNEL || v == Landforms.TAG_LAVA_LAKE;
    }

    /** Fresh lava rock: blackstone and basalt in patches, a little smooth basalt. */
    private static BlockState lavaRockAt(int x, int y, int z) {
        float p = PATCHES.GetNoise(x * 1.3f + 1500, z * 1.3f);
        float q = STRATA.GetNoise(x * 0.8f - 900, y * 2f, z * 0.8f);
        if (q > 0.55f) return Blocks.SMOOTH_BASALT.defaultBlockState();
        return p > 0.1f ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.BASALT.defaultBlockState();
    }

    /** Crater floors and walls: blackstone and tuff, the odd obsidian. */
    private static BlockState craterRockAt(int x, int y, int z, long h) {
        if (unit(mix(h + y)) < 0.04) return Blocks.OBSIDIAN.defaultBlockState();
        float p = PATCHES.GetNoise(x * 1.7f - 400, z * 1.7f + 90);
        return p > 0f ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.TUFF.defaultBlockState();
    }

    private static boolean isGround(BlockState st) {
        return st.is(Blocks.STONE) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT) || st.is(Blocks.COARSE_DIRT)
                || st.is(Blocks.PODZOL) || st.is(Blocks.GRAVEL) || st.is(Blocks.SAND) || st.is(Blocks.RED_SAND)
                || st.is(Blocks.ANDESITE) || st.is(Blocks.TUFF) || st.is(Blocks.BASALT) || st.is(Blocks.BLACKSTONE)
                || st.is(Blocks.SMOOTH_BASALT) || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.POWDER_SNOW)
                || st.is(Blocks.MUD) || st.is(Blocks.CLAY) || st.is(Blocks.DIORITE) || st.is(Blocks.GRANITE)
                || st.is(Blocks.CALCITE) || st.is(Blocks.MOSS_BLOCK) || st.is(Blocks.ROOTED_DIRT)
                || st.is(Blocks.SANDSTONE) || st.is(Blocks.TERRACOTTA) || st.is(Blocks.PACKED_ICE);
    }

    /**
     * The tile holding block column (x, z): {@code data} if it is that tile, else the neighbouring tile (cached, or built:
     * a lava lake or flow running over a tile's edge needs both sides, or the checks below failed along every tile edge
     * and left lines of rock across lava lakes, Calvin, Oct 25).
     */
    static HeightmapData tileAt(HeightmapData data, int startX, int startZ, int x, int z) {
        int size = TerrainDiffusionConfig.tileSize();
        if (x >= startX && x < startX + size && z >= startZ && z < startZ + size) return data;
        int shift = Integer.numberOfTrailingZeros(size);
        int sx = (x >> shift) << shift, sz = (z >> shift) << shift;
        return LocalTerrainProvider.getInstance().fetchHeightmap(sz, sx, sz + size, sx + size);
    }

    /** Top solid block Y of block column (x, z) from its tile (rivers included). */
    static int tileTop(HeightmapData data, int startX, int startZ, int x, int z, int scale) {
        HeightmapData d = tileAt(data, startX, startZ, x, z);
        if (d == null) return Integer.MIN_VALUE;
        int size = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(size);
        int lx = x - ((x >> shift) << shift), lz = z - ((z >> shift) << shift);
        if (d.surfaceY != null && d.surfaceY[lz][lx] != com.github.xandergos.terraindiffusionmc.hydrology.RiverCarver.NONE) {
            return d.surfaceY[lz][lx];
        }
        return HeightConverter.convertToMinecraftHeight(d.heightmap[lz][lx], scale) - 1;
    }

    /** Whether all eight neighbours of block column (x, z) stand at least as high as {@code own}. */
    private static boolean levelOrHollow(HeightmapData data, int startX, int startZ, int x, int z, int scale, int own) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dz != 0) && tileTop(data, startX, startZ, x + dx, z + dz, scale) < own) return false;
            }
        }
        return true;
    }

    /** Whether every column within {@link #CLEARANCE} blocks is bare lava rock or crater (nothing to set alight). */
    private static boolean clearOfFuel(HeightmapData data, int startX, int startZ, int x, int z) {
        int size = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(size);
        for (int dz = -CLEARANCE; dz <= CLEARANCE; dz++) {
            for (int dx = -CLEARANCE; dx <= CLEARANCE; dx++) {
                HeightmapData d = tileAt(data, startX, startZ, x + dx, z + dz);
                if (d == null || d.oceanTags == null) return false;
                int lx = x + dx - (((x + dx) >> shift) << shift), lz = z + dz - (((z + dz) >> shift) << shift);
                byte v = d.oceanTags[lz][lx];
                if (v != Landforms.TAG_LAVA && v != Landforms.TAG_LAVA_CHANNEL && v != Landforms.TAG_LAVA_LAKE
                        && v != Landforms.TAG_CRATER && v != Landforms.TAG_HOT_CRATER) return false;
                short b = d.biomeIds[lz][lx];
                if (b != RegionalBiomes.LAVA_FIELDS && b != RegionalBiomes.VOLCANO
                        && !com.github.xandergos.terraindiffusionmc.pipeline.CoastShaper.isSnowy(b)) return false;
            }
        }
        return true;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
