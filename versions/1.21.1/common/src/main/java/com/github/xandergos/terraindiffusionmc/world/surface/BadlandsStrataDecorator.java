package com.github.xandergos.terraindiffusionmc.world.surface;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SurfaceNoise;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.TerrainSampling;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Continuous badlands geology, not a collection of randomly placed red disks.
 *
 * <p>All exposed steep native terrain and surface-feature stone in a badlands biome
 * get the same strata. Bands are horizontal and tied to absolute Y, with a gentle
 * low-frequency geological flexure. The pass covers every column in a chunk
 * regardless of whether the chunk intersects a mesa-terracing feature site.
 *
 * <p>Runs AFTER surface features: spires, arches and buttes use the same material
 * bands and no longer sit on unrelated grey stone while surrounding hills are red.
 */
public final class BadlandsStrataDecorator {
    private static final int CRUST_DEPTH = 80;

    private static final BlockState[] GEOLOGY = {
            Blocks.RED_TERRACOTTA.defaultBlockState(),
            Blocks.RED_TERRACOTTA.defaultBlockState(),
            Blocks.ORANGE_TERRACOTTA.defaultBlockState(),
            Blocks.TERRACOTTA.defaultBlockState(),
            Blocks.TERRACOTTA.defaultBlockState(),
            Blocks.YELLOW_TERRACOTTA.defaultBlockState(),
            Blocks.TERRACOTTA.defaultBlockState(),
            Blocks.BROWN_TERRACOTTA.defaultBlockState(),
            Blocks.TERRACOTTA.defaultBlockState(),
            Blocks.WHITE_TERRACOTTA.defaultBlockState(),
            Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState(),
            Blocks.TERRACOTTA.defaultBlockState()
    };

    private BadlandsStrataDecorator() {}

    public static BlockState strataFor(int x, int y, int z) {
        // Very gentle bending of coherent strata, rather than spotty per-column bands.
        float flexure = SurfaceNoise.valueNoise(0xBADD1A5DL, x / 115f, z / 115f) * 1.4f;
        int band = Math.floorDiv((int) Math.floor(y + flexure), 4);
        return GEOLOGY[Math.floorMod(band, GEOLOGY.length)];
    }

    public static void decorate(ChunkAccess chunk, HeightmapData data, int originX, int originZ) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        TerrainBiomeRegistry biomes = TerrainBiomeRegistry.instance();
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);

        for (int lz = 0; lz < 16; lz++) {
            int z = startZ + lz, row = z - originZ;
            for (int lx = 0; lx < 16; lx++) {
                int x = startX + lx, col = x - originX;
                if (!TerrainSampling.inBounds(data, row, col)) continue;
                short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
                if (biomes.isRiver(biomeIndex) || biomes.isFrozenRiver(biomeIndex)) continue;
                String key = biomes.keyForIndex(biomeIndex);
                if (key == null || !key.contains("badlands")) continue;
                int top = SurfaceStamp.surfaceY(chunk, lx, lz);
                if (top <= chunk.getMinBuildHeight() || top >= chunk.getMaxBuildHeight()) continue;
                boolean exposedCliff = TerrainSampling.slopeAt(data, row, col, 3)
                        >= SurfaceStamp.slopeFromBlocks(0.40f);
                int minimum = Math.max(chunk.getMinBuildHeight(), top - CRUST_DEPTH);

                for (int y = top; y >= minimum; y--) {
                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (!isRock(state)) continue;
                    // Keep red sand/soil on level ground and mesa caps, but give
                    // cliff faces coherent sedimentary geology across all heights.
                    if (!exposedCliff && y >= top - 2
                            && (state.is(Blocks.RED_SAND) || state.is(Blocks.SAND)
                                || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT))) continue;
                    // No internal cave re-carving, no fluids, no foliage, no structures.
                    BlockState strata = strataFor(x, y, z);
                    if (state.equals(strata)) continue;
                    chunk.setBlockState(pos, strata, false);
                    worldSurface.update(lx, y, lz, strata);
                    motionBlocking.update(lx, y, lz, strata);
                }
            }
        }
    }

    private static boolean isRock(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE)
                || state.is(Blocks.TUFF) || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(Blocks.RED_SANDSTONE) || state.is(Blocks.SMOOTH_RED_SANDSTONE)
                || state.is(Blocks.RED_SAND) || state.is(Blocks.SAND)
                || state.is(Blocks.TERRACOTTA) || state.is(Blocks.RED_TERRACOTTA)
                || state.is(Blocks.ORANGE_TERRACOTTA) || state.is(Blocks.YELLOW_TERRACOTTA)
                || state.is(Blocks.WHITE_TERRACOTTA) || state.is(Blocks.LIGHT_GRAY_TERRACOTTA)
                || state.is(Blocks.BROWN_TERRACOTTA) || state.is(Blocks.GRAY_TERRACOTTA)
                || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT);
    }
}
