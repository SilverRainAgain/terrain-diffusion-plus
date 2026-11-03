package com.github.xandergos.terraindiffusionmc.biome;

import java.util.HashMap;
import java.util.Map;

/**
 * World-scoped relative multipliers applied to the catalog's biome rarity weights.
 * The map is replaced when a Terrain Diffusion world loads, before tiles are generated.
 * Missing biomes use 1.0; zero disables the biome as a candidate.
 */
public final class BiomeWeightSettings {
    public static final float DEFAULT_WEIGHT = 1.0f;
    public static final float MAX_WEIGHT = 10.0f;

    private static volatile Map<String, Float> active = Map.of();

    private BiomeWeightSettings() {}

    public static Map<String, Float> current() {
        return active;
    }

    public static float multiplier(String biomeKey) {
        return active.getOrDefault(biomeKey, DEFAULT_WEIGHT);
    }

    public static float normalized(float value) {
        if (!Float.isFinite(value)) return DEFAULT_WEIGHT;
        return Math.max(0f, Math.min(MAX_WEIGHT, value));
    }

    /** Replace atomically: worker threads may read the active map concurrently. */
    public static void activate(Map<String, Float> weights) {
        Map<String, Float> filtered = new HashMap<>();
        if (weights != null) {
            for (Map.Entry<String, Float> entry : weights.entrySet()) {
                String key = entry.getKey();
                Float value = entry.getValue();
                if (key == null || key.isBlank() || value == null) continue;
                float normalized = normalized(value);
                if (normalized != DEFAULT_WEIGHT) filtered.put(key, normalized);
            }
        }
        active = Map.copyOf(filtered);
    }
}
