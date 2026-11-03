package com.github.xandergos.terraindiffusionmc.world.surface;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SiteGrid;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.List;

/**
 * Entry point for procedural surface structures (boulders, hoodoos, arches, ...), called from the
 * same {@code applyBiomeDecoration} mixin hook as {@code TerrainDiffusionRiverDecorator} -- after
 * it, so features can see where rivers/lakes ended up rather than placing blind.
 *
 * <p>Fetches one heightmap window wide enough to cover every registered {@link
 * SurfaceFeaturePlacer}'s {@link SurfaceFeaturePlacer#maxReachBlocks()}, enumerates that placer's
 * candidate sites over the window via {@link SiteGrid}, and lets each placer decide (and clip)
 * what it actually writes into this chunk. To add a new feature, write a class implementing
 * {@link SurfaceFeaturePlacer} and add it to {@link SurfaceFeatureRegistry#PLACERS} -- nothing
 * here needs to change.</p>
 */
public final class SurfaceFeatureDecorator {
    private SurfaceFeatureDecorator() {}

    public static void decorate(ChunkAccess chunk) {
        // Geological relief is generated into the Terrain Diffusion height raster,
        // not stamped here as free-standing rock sculptures. Existing biome-neutral
        // surface features remain available in other biomes.
        List<SurfaceFeaturePlacer> placers = TerrainDiffusionConfig.surfaceFeaturesEnabled()
                ? SurfaceFeatureRegistry.PLACERS : List.of();

        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int maxReach = 0;
        for (SurfaceFeaturePlacer placer : placers) {
            maxReach = Math.max(maxReach, placer.maxReachBlocks());
        }

        int dataOriginX = startX - maxReach;
        int dataOriginZ = startZ - maxReach;
        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(
                dataOriginZ, dataOriginX, startZ + 16 + maxReach, startX + 16 + maxReach);
        if (data == null) return;

        boolean badlandsChunk = false;
        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        for (int z = 0; z < 16 && !badlandsChunk; z++) {
            int row = startZ + z - dataOriginZ;
            for (int x = 0; x < 16; x++) {
                int col = startX + x - dataOriginX;
                if (row >= 0 && row < data.height && col >= 0 && col < data.width) {
                    String key = registry.keyForIndex(data.biomeIndexes[row][col]);
                    if (key != null && key.contains("badlands")) {
                        badlandsChunk = true;
                        break;
                    }
                }
            }
        }

        // Skip every standalone geological feature inside Badlands chunks, including
        // arches, hoodoos, buttes, rock piles and dune crests. Their shapes must
        // come from the underlying height field. Vegetation/world decorations from
        // normal Minecraft biome generation are unaffected.
        long worldSeed = LocalTerrainProvider.getSeed();
        if (!badlandsChunk) for (SurfaceFeaturePlacer placer : placers) {
            int reach = placer.maxReachBlocks();
            List<SiteGrid.Site> sites = SiteGrid.sitesOverlapping(worldSeed, placer.salt(),
                    placer.cellSizeBlocks(), Math.min(placer.cellSizeBlocks() / 2, reach),
                    startX - reach, startZ - reach, startX + 16 + reach, startZ + 16 + reach);
            for (SiteGrid.Site site : sites) {
                placer.place(chunk, data, dataOriginX, dataOriginZ, site, worldSeed);
            }
        }
        // One continuous geology pass after all features, not random isolated banded patches.
        BadlandsStrataDecorator.decorate(chunk, data, dataOriginX, dataOriginZ);
    }
}
