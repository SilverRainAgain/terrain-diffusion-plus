package com.github.xandergos.terraindiffusionmc.world.surface;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SiteGrid;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SurfaceNoise;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.TerrainSampling;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Larger-scale raised badlands landforms, complementing the smaller hoodoo clusters.
 *
 * <p>Creates broad, stratified buttes with irregular steep sides and eroded ledges.
 * All decisions use world coordinates and the shared terrain raster; the resulting
 * shape is identical across chunk borders. This is a surface-landform pass, NOT a
 * replacement for valley-scale erosion in the underlying neural-network model.</p>
 */
public final class BadlandsButteFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0xBADD1A5DL;
    private static final int CELL_SIZE = 160;
    private static final int MAX_RADIUS = 36;
    private static final int MIN_RADIUS = 23;
    private static final int MIN_HEIGHT = 18;
    private static final int MAX_HEIGHT = 39;

    private static final BlockState[] STRATA = {
            Blocks.TERRACOTTA.defaultBlockState(),
            Blocks.ORANGE_TERRACOTTA.defaultBlockState(),
            Blocks.TERRACOTTA.defaultBlockState(),
            Blocks.YELLOW_TERRACOTTA.defaultBlockState(),
            Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState(),
            Blocks.TERRACOTTA.defaultBlockState(),
            Blocks.RED_TERRACOTTA.defaultBlockState(),
            Blocks.ORANGE_TERRACOTTA.defaultBlockState()
    };

    @Override public String id() { return "badlands_butte"; }
    @Override public int cellSizeBlocks() { return CELL_SIZE; }
    @Override public int maxReachBlocks() { return MAX_RADIUS + 2; }
    @Override public long salt() { return SALT; }

    @Override
    public void place(ChunkAccess chunk, HeightmapData data, int originX, int originZ,
                      SiteGrid.Site site, long worldSeed) {
        if (!TerrainDiffusionConfig.badlandsButteFeaturesEnabled()) return;
        if (SurfaceNoise.unitHash(site.seed(), 20, 17)
                >= TerrainDiffusionConfig.badlandsButteDensity()) return;

        int centerRow = site.worldZ() - originZ;
        int centerCol = site.worldX() - originX;
        if (!TerrainSampling.inBounds(data, centerRow, centerCol)) return;
        if (!badlands(data, centerRow, centerCol)) return;
        if (TerrainSampling.elevationAt(data, centerRow, centerCol) < SurfaceStamp.blocksToElevation(3f)) return;
        // A rocky tableland needs a coherent foot, not a tower cantilevered from a cliff.
        if (TerrainSampling.slopeAt(data, centerRow, centerCol, 5)
                > SurfaceStamp.slopeFromBlocks(0.25f)) return;

        int radius = SurfaceStamp.randRange(site.seed(), 91, 20, MIN_RADIUS, MAX_RADIUS);
        int height = SurfaceStamp.randRange(site.seed(), 27, 43, MIN_HEIGHT, MAX_HEIGHT);
        int siteBaseY = com.github.xandergos.terraindiffusionmc.world.HeightConverter.convertToMinecraftHeight(
                (short) TerrainSampling.elevationAt(data, centerRow, centerCol)) - 1;
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        Heightmap surfaceMap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionMap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);

        for (int lz = 0; lz < 16; lz++) {
            int worldZ = minZ + lz;
            int row = worldZ - originZ;
            for (int lx = 0; lx < 16; lx++) {
                int worldX = minX + lx;
                int col = worldX - originX;
                if (!TerrainSampling.inBounds(data, row, col) || !badlands(data, row, col)) continue;

                float dx = worldX - site.worldX();
                float dz = worldZ - site.worldZ();
                // Low-frequency edge distortion avoids a perfect circular mesa footprint.
                float edgeWarp = SurfaceNoise.valueNoise(worldSeed ^ SALT,
                        worldX / 19f, worldZ / 19f) * 3.5f;
                float distance = (float) Math.sqrt(dx * dx + dz * dz) + edgeWarp;
                if (distance >= radius) continue;

                float fraction = Math.max(0f, distance / radius);
                // Broad, nearly level cap; an eroded stepped shoulder and a steep lower rim.
                float shoulder = SurfaceNoise.clamp01((1.0f - fraction) / 0.48f);
                float stepped = (float) Math.floor(shoulder * 5f) / 5f;
                float envelope = shoulder * 0.75f + stepped * 0.25f;
                int topY = siteBaseY + Math.round(height * envelope);
                if (topY <= siteBaseY + 2) continue;

                int surfaceY = SurfaceStamp.surfaceY(chunk, lx, lz);
                if (surfaceY <= chunk.getMinBuildHeight()) continue;
                BlockState ground = SurfaceStamp.stateAt(chunk, worldX, surfaceY, worldZ);
                if (!SurfaceStamp.isSolidGround(ground)) continue;
                if (surfaceY > topY) continue;

                // Only fill air above the already-generated terrain; never hollow existing
                // caves, overwrite water, or leave unsupported columns hovering in air.
                int maxY = Math.min(topY, chunk.getMaxBuildHeight() - 1);
                for (int y = surfaceY + 1; y <= maxY; y++) {
                    SurfaceStamp.placeIfAir(chunk, surfaceMap, motionMap, worldX, y, worldZ,
                            strataForY(y));
                }
            }
        }
    }

    private static boolean badlands(HeightmapData data, int row, int col) {
        if (data.riverWater != null && data.riverWater[row][col] != 0) return false;
        TerrainBiomeRegistry biomes = TerrainBiomeRegistry.instance();
        short index = TerrainSampling.biomeIndexAt(data, row, col);
        if (biomes.isRiver(index) || biomes.isFrozenRiver(index)) return false;
        String key = biomes.keyForIndex(index);
        return "minecraft:badlands".equals(key) || "minecraft:eroded_badlands".equals(key);
    }

    private static BlockState strataForY(int y) {
        // Absolute Y (not site random) keeps bands horizontal across neighbouring formations.
        return STRATA[Math.floorMod(Math.floorDiv(y, 4), STRATA.length)];
    }
}
