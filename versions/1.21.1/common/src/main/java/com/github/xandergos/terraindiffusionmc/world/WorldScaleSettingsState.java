package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.biome.BiomeWeightSettings;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/** Persisted per-world Terrain Diffusion settings. */
public final class WorldScaleSettingsState extends SavedData {
    public static final String DATA_NAME = "terrain_diffusion_world_settings";
    private static final String SCALE_KEY = "scale";
    private static final String EXPLICIT_KEY = "explicit_settings";
    private static final String LEGACY_EXPLICIT_KEY = "explicit_scale";
    private static final String SOURCE_LIMIT_KEY = "block_sources_below_775m";
    private static final String CANYONS_KEY = "vanilla_canyons_enabled";
    private static final String WEIGHTS_KEY = "biome_weight_multipliers";

    public static final SavedData.Factory<WorldScaleSettingsState> FACTORY = new SavedData.Factory<>(
            WorldScaleSettingsState::createDefault, WorldScaleSettingsState::load, null);

    private int scale;
    private boolean explicitSettings;
    private boolean blockLowAltitudeSources;
    private boolean vanillaCanyonsEnabled;
    private Map<String, Float> biomeWeights;

    private WorldScaleSettingsState(int scale, boolean explicitSettings, boolean sourceLimit,
                                    boolean vanillaCanyonsEnabled, Map<String, Float> biomeWeights) {
        this.scale = WorldScaleManager.clampScale(scale);
        this.explicitSettings = explicitSettings;
        this.blockLowAltitudeSources = sourceLimit;
        this.vanillaCanyonsEnabled = vanillaCanyonsEnabled;
        this.biomeWeights = WorldScaleSelectionState.sanitizedWeights(biomeWeights);
    }

    public static WorldScaleSettingsState createDefault() {
        return new WorldScaleSettingsState(WorldScaleManager.DEFAULT_SCALE, false,
                WorldScaleManager.DEFAULT_BLOCK_LOW_ALTITUDE_SOURCES, false, Map.of());
    }

    private static WorldScaleSettingsState load(CompoundTag tag, HolderLookup.Provider provider) {
        int scale = tag.contains(SCALE_KEY) ? tag.getInt(SCALE_KEY) : WorldScaleManager.DEFAULT_SCALE;
        boolean explicit = tag.contains(EXPLICIT_KEY) ? tag.getBoolean(EXPLICIT_KEY)
                : tag.contains(LEGACY_EXPLICIT_KEY) && tag.getBoolean(LEGACY_EXPLICIT_KEY);
        boolean sourceLimit = tag.contains(SOURCE_LIMIT_KEY) && tag.getBoolean(SOURCE_LIMIT_KEY);

        // Keep vanilla canyon behaviour in worlds created before this setting existed;
        // changing carving midway through an existing world would create abrupt chunk boundaries.
        boolean canyons = tag.contains(CANYONS_KEY) ? tag.getBoolean(CANYONS_KEY) : explicit;
        Map<String, Float> weights = new HashMap<>();
        if (tag.contains(WEIGHTS_KEY)) {
            CompoundTag stored = tag.getCompound(WEIGHTS_KEY);
            for (String key : stored.getAllKeys()) {
                float value = stored.getFloat(key);
                if (Float.isFinite(value) && value >= 0f && value <= BiomeWeightSettings.MAX_WEIGHT) {
                    weights.put(key, value);
                }
            }
        }
        return new WorldScaleSettingsState(scale, explicit, sourceLimit, canyons, weights);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt(SCALE_KEY, scale);
        tag.putBoolean(EXPLICIT_KEY, explicitSettings);
        tag.putBoolean(LEGACY_EXPLICIT_KEY, explicitSettings);
        tag.putBoolean(SOURCE_LIMIT_KEY, blockLowAltitudeSources);
        tag.putBoolean(CANYONS_KEY, vanillaCanyonsEnabled);
        CompoundTag weights = new CompoundTag();
        biomeWeights.forEach(weights::putFloat);
        tag.put(WEIGHTS_KEY, weights);
        return tag;
    }

    public int getScale() { return scale; }
    public boolean hasExplicitSettings() { return explicitSettings; }
    public boolean hasExplicitScale() { return explicitSettings; }
    public boolean shouldBlockLowAltitudeSources() { return blockLowAltitudeSources; }
    public boolean vanillaCanyonsEnabled() { return vanillaCanyonsEnabled; }
    public Map<String, Float> biomeWeights() { return biomeWeights; }

    public void setSettings(int configuredScale, boolean sourceLimit) {
        setSettings(configuredScale, sourceLimit, vanillaCanyonsEnabled, biomeWeights);
    }

    public void setSettings(int configuredScale, boolean sourceLimit,
                            boolean canyonsEnabled, Map<String, Float> weights) {
        scale = WorldScaleManager.clampScale(configuredScale);
        blockLowAltitudeSources = sourceLimit;
        vanillaCanyonsEnabled = canyonsEnabled;
        biomeWeights = WorldScaleSelectionState.sanitizedWeights(weights);
        explicitSettings = true;
        setDirty();
    }

    public void setScale(int configuredScale) { setSettings(configuredScale, blockLowAltitudeSources); }
}
