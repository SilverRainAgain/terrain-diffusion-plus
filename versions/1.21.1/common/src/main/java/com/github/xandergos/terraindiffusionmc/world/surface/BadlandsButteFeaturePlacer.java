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
    private static final int CELL_SIZE = 260;
    private static final int MAX_RADIUS = 58;
    private static final int MIN_RADIUS = 28;
    private static final int MIN_HEIGHT = 20;
    private static final int MAX_HEIGHT = 58;

    @Override public String id() { return "badlands_butte"; }
    @Override public int cellSizeBlocks() { return LandformScale.blocks(CELL_SIZE); }
    @Override public int maxReachBlocks() { return LandformScale.blocks(MAX_RADIUS * 2); }
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

        int radius = LandformScale.blocks(SurfaceStamp.randRange(site.seed(), 91, 20, MIN_RADIUS, MAX_RADIUS));
        int height = LandformScale.blocks(SurfaceStamp.randRange(site.seed(), 27, 43, MIN_HEIGHT, MAX_HEIGHT));
        float angle = (float) (SurfaceNoise.unitHash(site.seed(), 51, 14) * Math.PI * 2.0);
        float cosine = (float) Math.cos(angle);
        float sine = (float) Math.sin(angle);
        float aspect = 0.70f + SurfaceNoise.unitHash(site.seed(), 19, 88) * 0.65f;
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
                // Rotated, asymmetric footprint with erosion on multiple wavelengths.
                float along = dx * cosine + dz * sine;
                float across = -dx * sine + dz * cosine;
                float elongation = (float) Math.sqrt(
                        (along / (radius * aspect)) * (along / (radius * aspect))
                        + (across * aspect / radius) * (across * aspect / radius));
                float broadErosion = SurfaceNoise.valueNoise(
                        site.seed() ^ 0xE20510AAL, worldX / 33f, worldZ / 33f);
                float fineErosion = SurfaceNoise.valueNoise(
                        site.seed() ^ 0xA52E450L, worldX / 11f, worldZ / 11f);
                float distance = elongation + 0.15f * broadErosion + 0.04f * fineErosion;
                if (distance >= 1f) continue;

                float shoulder = SurfaceNoise.clamp01((1f - distance) / 0.53f);
                float envelope = shoulder * shoulder * (3f - 2f * shoulder);
                float ledge = distance > 0.38f && distance < 0.82f
                        ? Math.max(0f, fineErosion) * 0.085f : 0f;
                float summit = 0.93f + 0.07f * SurfaceNoise.valueNoise(
                        site.seed() ^ 0x9C10FFL, worldX / 26f, worldZ / 26f);
                int topY = siteBaseY + Math.round(height * Math.max(0f,
                        envelope * summit - ledge));
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
                            BadlandsStrataDecorator.strataFor(worldX, y, worldZ));
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

}
