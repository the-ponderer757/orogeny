package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fumarole caves (Nov 2): after the features step, every lava block inside a fumarole cave turns to magma, so the
 * volcanic heat shows as glowing rock without lava flowing over the ice and melting it. Done for the chunk being
 * decorated and its eight neighbours, because cave lakes of lava cross chunk edges. Direct section writes; magma and
 * lava are both solid-for-heightmap purposes here (cave interiors), so heightmaps don't change.
 */
public final class FumaroleCaves {
    private static final Logger LOG = LoggerFactory.getLogger(FumaroleCaves.class);
    private static final ResourceKey<Biome> FUMAROLE = ResourceKey.create(Registries.BIOME,
            Identifier.fromNamespaceAndPath("orogeny", "fumarole_caves"));
    private static final BlockState MAGMA = Blocks.MAGMA_BLOCK.defaultBlockState();

    private FumaroleCaves() {
    }

    public static void finish(WorldGenLevel level, ChunkAccess chunk) {
        if (!TerrainDiffusionConfig.caveBiomes()) return;
        try {
            ChunkPos cp = chunk.getPos();
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    ChunkAccess c = dx == 0 && dz == 0 ? chunk : level.getChunk(cp.x() + dx, cp.z() + dz);
                    if (c != null) convert(c);
                }
        } catch (Throwable t) {
            LOG.error("Orogeny: fumarole pass failed for chunk {}", chunk.getPos(), t);
        }
    }

    private static void convert(ChunkAccess chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (LevelChunkSection sec : sections) {
            if (sec == null || sec.hasOnlyAir()) continue;
            if (!sec.getBiomes().maybeHas(h -> h.is(FUMAROLE))) continue;
            if (!sec.getStates().maybeHas(s -> s.is(Blocks.LAVA))) continue;
            boolean[] cell = new boolean[64];
            for (int qy = 0; qy < 4; qy++)
                for (int qz = 0; qz < 4; qz++)
                    for (int qx = 0; qx < 4; qx++) {
                        Holder<Biome> h = sec.getNoiseBiome(qx, qy, qz);
                        cell[(qy * 4 + qz) * 4 + qx] = h.is(FUMAROLE);
                    }
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        if (!cell[((y >> 2) * 4 + (z >> 2)) * 4 + (x >> 2)]) continue;
                        if (sec.getBlockState(x, y, z).is(Blocks.LAVA)) sec.setBlockState(x, y, z, MAGMA, false);
                    }
        }
    }
}
