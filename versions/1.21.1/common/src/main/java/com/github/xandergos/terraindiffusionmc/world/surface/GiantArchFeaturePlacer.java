package com.github.xandergos.terraindiffusionmc.world.surface;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SiteGrid;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SurfaceNoise;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.TerrainSampling;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Monument-scale free-standing arch -- the Delicate Arch / Arches National Park landmark for
 * badlands country. Unlike {@link ArchFeaturePlacer} (which spans an <em>existing</em> gap and
 * therefore never fires on flat mesa tops), this placer erects the whole structure: two thick
 * tapered legs rising well above ground and a banded red-rock arc between them. It deliberately
 * works on flat and rolling ground -- the only terrain requirement is that both leg sites are
 * on land at roughly equal elevation.
 *
 * <p>Geometry is derived entirely from the diffusion raster + site seed (the
 * {@link ArchFeaturePlacer} pattern), so every chunk the footprint touches computes the same
 * shape and contributes only its own columns. Gated to badlands-family biomes, which the
 * region-based biome catalog concentrates into one province -- so these read as a coherent
 * "arch country" rather than scattered one-offs.</p>
 */
public final class GiantArchFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x61A47C11E5L;
    private static final int CELL_SIZE = 230;
    private static final float SPAWN_CHANCE = 0.35f;

    private static final int MIN_SPAN = 29;
    private static final int MAX_SPAN = 76;
    private static final int RING_SAMPLES = 8;

    /** Worst-case leg radius, for {@link #maxReachBlocks()}; the real radius scales with span. */
    private static final int MAX_LEG_RADIUS = 7;

    @Override
    public String id() {
        return "giant_arch";
    }

    @Override
    public int cellSizeBlocks() {
        return LandformScale.blocks(CELL_SIZE);
    }

    @Override
    public int maxReachBlocks() {
        return LandformScale.blocks(MAX_SPAN + MAX_LEG_RADIUS + 4);
    }

    @Override
    public long salt() {
        return SALT;
    }

    @Override
    public void place(ChunkAccess chunk, HeightmapData data, int dataOriginX, int dataOriginZ,
                       SiteGrid.Site site, long worldSeed) {
        if (SurfaceNoise.unitHash(worldSeed ^ SALT, site.worldX(), site.worldZ()) >= SPAWN_CHANCE) return;

        int rowA = site.worldZ() - dataOriginZ;
        int colA = site.worldX() - dataOriginX;
        if (!TerrainSampling.inBounds(data, rowA, colA)) return;
        float elevA = TerrainSampling.elevationAt(data, rowA, colA);
        if (elevA <= 0f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, rowA, colA);
        if (registry.isRiver(biomeIndex) || registry.isFrozenRiver(biomeIndex)) return;
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null) return;
        // Arch country: the badlands family (one province in the region catalog) plus the
        // BoP dry-rock biomes that border it there.
        boolean archBiome = biomeKey.contains("badlands")
                || biomeKey.endsWith("wasteland_steppe") || biomeKey.endsWith("dryland");
        if (!archBiome) return;

        Candidate best = findOpposingAnchor(data, dataOriginX, dataOriginZ, site, elevA);
        if (best == null) return;

        buildArch(chunk, site.seed(), site.worldX(), site.worldZ(), elevA,
                best.worldX(), best.worldZ(), best.elevation());
    }

    private record Candidate(int worldX, int worldZ, float elevation) {}

    /**
     * Unlike the gap-spanning template there is no "must find a saddle" rejection -- we only
     * need a second on-land anchor at similar elevation whose midpoint isn't a hill that would
     * bury the arc. Most directions on rolling badlands qualify; the seed picks one.
     *
     * <p>The span roll is squared, so most arches stay modest while the occasional site lands a
     * true monument near {@link #MAX_SPAN} -- a wide size variety rather than a uniform blur.
     * Longer spans also get a proportionally looser anchor-elevation tolerance, since demanding
     * near-equal ground 48 blocks apart would filter the big ones out of existence.</p>
     */
    private Candidate findOpposingAnchor(HeightmapData data, int dataOriginX, int dataOriginZ,
                                          SiteGrid.Site site, float elevA) {
        double ringOffset = SurfaceNoise.unitHash(site.seed(), 0, 0) * Math.PI * 2;
        for (int i = 0; i < RING_SAMPLES; i++) {
            double angle = ringOffset + (Math.PI * 2 * i) / RING_SAMPLES;
            float roll = SurfaceNoise.unitHash(site.seed(), i, 7);
            int span = LandformScale.blocks(MIN_SPAN
                    + Math.round((float) Math.pow(roll, 1.25) * (MAX_SPAN - MIN_SPAN)));
            int bx = site.worldX() + Math.round((float) Math.cos(angle) * span);
            int bz = site.worldZ() + Math.round((float) Math.sin(angle) * span);
            int rowB = bz - dataOriginZ;
            int colB = bx - dataOriginX;
            if (!TerrainSampling.inBounds(data, rowB, colB)) continue;
            float elevB = TerrainSampling.elevationAt(data, rowB, colB);
            if (elevB <= 0f) continue;
            float toleranceBlocks = 4f + span / 12f;
            if (Math.abs(elevB - elevA) > SurfaceStamp.blocksToElevation(toleranceBlocks)) continue;

            int mx = (site.worldX() + bx) / 2;
            int mz = (site.worldZ() + bz) / 2;
            int rowM = mz - dataOriginZ;
            int colM = mx - dataOriginX;
            if (!TerrainSampling.inBounds(data, rowM, colM)) continue;
            float elevM = TerrainSampling.elevationAt(data, rowM, colM);
            // A rise between the legs is fine (the arc clears it); a big hill is not.
            if (elevM - Math.max(elevA, elevB) > SurfaceStamp.blocksToElevation(3f)) continue;

            return new Candidate(bx, bz, elevB);
        }
        return null;
    }

    private void buildArch(ChunkAccess chunk, long siteSeed, int ax, int az, float elevA,
                            int bx, int bz, float elevB) {
        int span = (int) Math.round(Math.hypot(bx - ax, bz - az));
        // Everything scales with the span, so a 48-block monument is proportionally as massive
        // as a 14-block arch is delicate -- not the same slender rib stretched four times wider.
        int legHeight = Math.max(12, Math.min(65, Math.round(span * 0.54f)));
        int apexRise = Math.max(6, Math.min(35, Math.round(span * 0.30f)));
        int legRadius = Math.min(LandformScale.blocks(MAX_LEG_RADIUS), 2 + span / 14);
        int archHalfWidth = Math.min(7, 2 + span / 17);
        int archThickness = Math.min(10, 3 + span / 15);

        int groundYA = HeightConverter.convertToMinecraftHeight((short) elevA) - 1;
        int groundYB = HeightConverter.convertToMinecraftHeight((short) elevB) - 1;
        int springYA = groundYA + legHeight;
        int springYB = groundYB + legHeight;

        double dx = bx - ax, dz = bz - az;
        double length = Math.max(1e-3, Math.hypot(dx, dz));
        double perpX = -dz / length, perpZ = dx / length;

        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);

        placeLeg(chunk, worldSurface, motionBlocking, siteSeed, ax, az, groundYA, springYA, legRadius);
        placeLeg(chunk, worldSurface, motionBlocking, siteSeed, bx, bz, groundYB, springYB, legRadius);

        int steps = Math.max(24, span * 3);
        for (int t = 0; t <= steps; t++) {
            float frac = t / (float) steps;
            double centerX = ax + dx * frac;
            double centerZ = az + dz * frac;
            float baseline = SurfaceNoise.lerp(springYA, springYB, frac);
            float arcY = baseline + apexRise * 4f * frac * (1f - frac);

            // Taper: full width near the springs, slimmer at the apex, like a real arch rib.
            int halfWidth = frac > 0.25f && frac < 0.75f ? archHalfWidth - 1 : archHalfWidth;
            for (int w = -halfWidth; w <= halfWidth; w++) {
                int wx = (int) Math.round(centerX + perpX * w);
                int wz = (int) Math.round(centerZ + perpZ * w);
                for (int thick = 0; thick < archThickness; thick++) {
                    int wy = Math.round(arcY) - thick;
                    placeIfAir(chunk, worldSurface, motionBlocking, siteSeed, wx, wy, wz);
                }
            }
        }
    }

    private void placeLeg(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking, long siteSeed,
                           int worldX, int worldZ, int groundY, int springY, int legRadius) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        // Root the legs a few blocks below the raster ground so they meet real terrain even
        // where the raster over-estimates it.
        int loY = Math.max(groundY - 3, chunk.getMinBuildHeight());
        int hiY = Math.min(springY, chunk.getMaxBuildHeight() - 1);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = -legRadius; dz <= legRadius; dz++) {
            int wz = worldZ + dz;
            int lz = wz - minZ;
            if (lz < 0 || lz > 15) continue;
            for (int dx = -legRadius; dx <= legRadius; dx++) {
                int wx = worldX + dx;
                int lx = wx - minX;
                if (lx < 0 || lx > 15) continue;
                for (int wy = loY; wy <= hiY; wy++) {
                    // Taper the leg: full radius at ground, radius-1 above two-thirds height.
                    float rise = (wy - loY) / (float) Math.max(1, hiY - loY);
                    int radius = rise > 0.66f ? legRadius - 1 : legRadius;
                    if (dx * dx + dz * dz > radius * radius + 1) continue;
                    pos.set(wx, wy, wz);
                    BlockState block = blockFor(siteSeed, wx, wy, wz);
                    chunk.setBlockState(pos, block, false);
                    worldSurface.update(lx, wy, lz, block);
                    motionBlocking.update(lx, wy, lz, block);
                }
            }
        }
    }

    private void placeIfAir(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking, long siteSeed,
                             int worldX, int worldY, int worldZ) {
        if (worldY < chunk.getMinBuildHeight() || worldY > chunk.getMaxBuildHeight() - 1) return;
        int localX = worldX - chunk.getPos().getMinBlockX();
        int localZ = worldZ - chunk.getPos().getMinBlockZ();
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(worldX, worldY, worldZ);
        if (!chunk.getBlockState(pos).isAir()) return;

        BlockState block = blockFor(siteSeed, worldX, worldY, worldZ);
        chunk.setBlockState(pos, block, false);
        worldSurface.update(localX, worldY, localZ, block);
        motionBlocking.update(localX, worldY, localZ, block);
    }

    /** Badlands strata: horizontal bands of red rock, keyed to absolute Y so the bands line up
     *  across both legs and the arc no matter which chunk places them. */
    private static BlockState blockFor(long siteSeed, int x, int y, int z) {
        return BadlandsStrataDecorator.strataFor(x, y, z);
    }
}
