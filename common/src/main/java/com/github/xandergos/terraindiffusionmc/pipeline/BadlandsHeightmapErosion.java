package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.hydrology.FluvialRiverNetwork;
import com.github.xandergos.terraindiffusionmc.hydrology.HydrologyParallel;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SurfaceNoise;

/**
 * Drainage-guided incision of the diffusion model's height raster.
 *
 * <p>No object is added to the terrain. Erosion lowers existing hillsides according to
 * slope and the runoff accumulation already calculated from the neural elevation DEM.
 * A small coherent secondary rill field breaks up uniform slopes. Near biome borders
 * the height delta fades continuously to zero, preventing sheer seams.
 *
 * <p>This pass is applied after the canonical river carve and biome classification but
 * BEFORE the heightmap is compacted and sampled by Minecraft. It must never change a
 * river/lake bed beneath the established water surface. Once compacted, the same raster
 * is used by the chunk generator, biome source and map explorer.
 */
public final class BadlandsHeightmapErosion {
    private static final float RUNOFF_MIN_KM2 = 0.0015f;
    private static final float RUNOFF_MAX_KM2 = 0.085f;

    private BadlandsHeightmapErosion() {}

    /**
     * @param elevations         the canonical core elevation raster, modified in place
     * @param classified         core biome IDs, including river and lake overrides
     * @param paddedBiomes       classified biomes with a world-space margin
     * @param paddedHeights      unmodified carved DEM with same margin
     * @param coreSize           width and height of the core tile
     * @param biomePadding       number of padded pixels on each side
     * @param coreI0             world Z of the first core row
     * @param coreJ0             world X of the first core column
     * @param riverTopology      model-generated drainage and surface water fields
     * @param hydrologyHalo      offset of the core inside the analysis window
     * @param analysisWidth      width of the analysis window
     * @param metresPerBlock     terrain's native elevation unit represented by a block
     * @param worldSeed          model seed for repeatable micro-erosion
     */
    public static void erode(float[] elevations, short[] classified,
                             short[] paddedBiomes, float[] paddedHeights,
                             int coreSize, int biomePadding, int coreI0, int coreJ0,
                             FluvialRiverNetwork.RiverTopology riverTopology,
                             int hydrologyHalo, int analysisWidth,
                             float metresPerBlock, long worldSeed) {
        int paddedSize = coreSize + 2 * biomePadding;
        if (elevations == null || classified == null || paddedBiomes == null
                || paddedHeights == null || riverTopology == null
                || elevations.length != coreSize * coreSize
                || paddedBiomes.length != paddedSize * paddedSize
                || paddedHeights.length != paddedSize * paddedSize) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        var badlands = registry.byKey("minecraft:badlands");
        var eroded = registry.byKey("minecraft:eroded_badlands");
        var wooded = registry.byKey("minecraft:wooded_badlands");
        if (badlands == null || eroded == null || wooded == null) return;
        short a = badlands.index(), b = eroded.index(), c = wooded.index();
        int n = paddedSize + 1;

        // Summed-area biome mask. Each core pixel gets an exact fraction of Badlands
        // within a 40-block neighbourhood; the core and its neighbours sample
        // identical world coordinates and therefore share a seamless erosion fade.
        int[] integral = new int[n * n];
        for (int row = 0; row < paddedSize; row++) {
            int sum = 0;
            int source = row * paddedSize;
            int dest = (row + 1) * n;
            int previous = row * n;
            for (int col = 0; col < paddedSize; col++) {
                short biome = paddedBiomes[source + col];
                if (biome == a || biome == b || biome == c) sum++;
                integral[dest + col + 1] = integral[previous + col + 1] + sum;
            }
        }

        final int radius = Math.min(26, Math.max(2, biomePadding - 2));
        final int window = 2 * radius + 1;
        final float inverseArea = 1f / (window * window);
        final int elevationStep = Math.min(18, Math.max(1, biomePadding - 2));
        final float[] drainage = riverTopology.drainageAccumulation();
        final float[] valley = riverTopology.valleyInfluence();
        final float[] riverWater = riverTopology.waterSurface();
        final int riverWidth = riverTopology.width();
        final float m = Math.max(1f, metresPerBlock);

        HydrologyParallel.forEachRow(0, coreSize, coreSize, row -> {
            int coreOffset = row * coreSize;
            int pRow = row + biomePadding;
            int hRow = row + hydrologyHalo;
            int y1 = pRow - radius, y2 = pRow + radius + 1;
            for (int col = 0; col < coreSize; col++) {
                int coreIndex = coreOffset + col;
                // Never excavate Minecraft's river/frozen_river after hydrology has set
                // its water height: the two systems must use the same river bed.
                short biome = classified[coreIndex];
                if (registry.isRiver(biome) || registry.isFrozenRiver(biome)) continue;
                float elevation = elevations[coreIndex];
                if (elevation <= m * 4f) continue;

                int pCol = col + biomePadding;
                int x1 = pCol - radius, x2 = pCol + radius + 1;
                int count = integral[y2 * n + x2] - integral[y1 * n + x2]
                        - integral[y2 * n + x1] + integral[y1 * n + x1];
                if (count <= 0) continue;

                float influence = count * inverseArea;
                // Fade both sides of a Badlands transition, rather than hard-editing
                // only pixels with a matching biome ID.
                float biomeGate = smooth(influence);
                if (biomeGate < 0.002f) continue;

                int pCenter = pRow * paddedSize + pCol;
                int step = elevationStep;
                float left = paddedHeights[pCenter - step];
                float right = paddedHeights[pCenter + step];
                float up = paddedHeights[pCenter - step * paddedSize];
                float down = paddedHeights[pCenter + step * paddedSize];
                float grade = (float) Math.hypot(right - left, down - up)
                        / (2f * step * m);
                float slopeGate = smooth(clamp((grade - 0.065f) / 0.28f));

                // Relief comes from the neural model itself, sampled across 36 blocks.
                // Flat mesas and plains do not sprout isolated towers.
                float localRelief = (Math.max(Math.max(left, right), Math.max(up, down))
                        - Math.min(Math.min(left, right), Math.min(up, down))) / m;
                float reliefGate = smooth(clamp((localRelief - 1.5f) / 12f));
                float terrainGate = slopeGate * reliefGate;
                if (terrainGate < 0.002f) continue;

                int hydrologyIndex = hRow * riverWidth + col + hydrologyHalo;
                if (Float.isFinite(riverWater[hydrologyIndex])) continue;
                float riverFade = 1f - smooth(clamp(valley[hydrologyIndex] * 1.65f));
                if (riverFade < 0.02f) continue;

                // Intermittent washes are precisely the upstream part of the same
                // drainage graph used to generate rivers. Max over a small footprint
                // gives the stream a naturally widening erosional channel instead
                // of a one-block trench.
                float runoff = drainage[hydrologyIndex];
                for (int dz = -2; dz <= 2; dz++) {
                    int di = hydrologyIndex + dz * riverWidth;
                    for (int dx = -2; dx <= 2; dx++) {
                        float cell = drainage[di + dx];
                        float attenuation = 1f - 0.16f * (Math.abs(dx) + Math.abs(dz));
                        runoff = Math.max(runoff, cell * Math.max(0.1f, attenuation));
                    }
                }
                float flowGate = runoff <= RUNOFF_MIN_KM2 ? 0f
                        : smooth(clamp((float) (Math.log(runoff / RUNOFF_MIN_KM2)
                        / Math.log(RUNOFF_MAX_KM2 / RUNOFF_MIN_KM2))));

                int worldX = coreJ0 + col, worldZ = coreI0 + row;
                // Fine erosion only modulates existing slopes: it cannot construct a
                // regular cylinder, capstone or a free-standing column in flat land.
                float pattern = SurfaceNoise.valueNoise(worldSeed ^ 0xBD1034L,
                        worldX / 48f, worldZ / 48f);
                float rill = (float) Math.pow(Math.max(0f, 1f - Math.abs(pattern) * 2.4f), 4);
                float fineDepthBlocks = 0.75f + 2.1f * rill;
                float incisionBlocks = (fineDepthBlocks + 8.2f * flowGate)
                        * terrainGate * biomeGate * riverFade;

                // Hydraulic erosion is subtractive. The diffusion-generated
                // heightmap remains the single source of solid geometry.
                elevations[coreIndex] = Math.max(m, elevation - incisionBlocks * m);
            }
        });
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float smooth(float v) {
        return v * v * (3f - 2f * v);
    }
}
