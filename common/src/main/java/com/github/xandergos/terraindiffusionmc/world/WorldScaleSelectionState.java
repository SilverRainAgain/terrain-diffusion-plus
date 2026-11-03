package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.biome.BiomeWeightSettings;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory handoff for terrain settings selected in the single-player world creation UI. */
public final class WorldScaleSelectionState {
    private static final AtomicReference<PendingSettings> PENDING_SETTINGS = new AtomicReference<>();

    private WorldScaleSelectionState() {}

    public static void setPendingSettings(int selectedScale, boolean blockLowAltitudeSources) {
        setPendingSettings(selectedScale, blockLowAltitudeSources, true, Map.of());
    }

    public static void setPendingSettings(int selectedScale, boolean blockLowAltitudeSources,
                                          boolean vanillaCanyonsEnabled, Map<String, Float> biomeWeights) {
        PENDING_SETTINGS.set(new PendingSettings(
                WorldScaleManager.clampScale(selectedScale), blockLowAltitudeSources,
                vanillaCanyonsEnabled, sanitizedWeights(biomeWeights)));
    }

    public static Map<String, Float> sanitizedWeights(Map<String, Float> weights) {
        Map<String, Float> copy = new HashMap<>();
        if (weights != null) {
            for (var e : weights.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) continue;
                float weight = BiomeWeightSettings.normalized(e.getValue());
                if (weight != BiomeWeightSettings.DEFAULT_WEIGHT) copy.put(e.getKey(), weight);
            }
        }
        return Map.copyOf(copy);
    }

    /** Compatibility helper for callers that only update scale. */
    public static void setPendingScale(int selectedScale) {
        PendingSettings previous = PENDING_SETTINGS.get();
        setPendingSettings(selectedScale, getPendingBlockLowAltitudeSourcesOrDefault(),
                getPendingVanillaCanyonsEnabledOrDefault(), getPendingBiomeWeightsOrDefault());
    }

    public static PendingSettings consumePendingSettings() {
        return PENDING_SETTINGS.getAndSet(null);
    }

    public static Integer consumePendingScale() {
        PendingSettings settings = consumePendingSettings();
        return settings == null ? null : settings.scale();
    }

    public static int getPendingScaleOrDefault() {
        PendingSettings settings = PENDING_SETTINGS.get();
        return settings == null ? WorldScaleManager.DEFAULT_SCALE : settings.scale();
    }

    public static boolean getPendingBlockLowAltitudeSourcesOrDefault() {
        PendingSettings settings = PENDING_SETTINGS.get();
        return settings == null
                ? WorldScaleManager.DEFAULT_BLOCK_LOW_ALTITUDE_SOURCES
                : settings.blockLowAltitudeSources();
    }

    public static boolean getPendingVanillaCanyonsEnabledOrDefault() {
        PendingSettings settings = PENDING_SETTINGS.get();
        return settings == null ? false : settings.vanillaCanyonsEnabled();
    }

    public static Map<String, Float> getPendingBiomeWeightsOrDefault() {
        PendingSettings settings = PENDING_SETTINGS.get();
        return settings == null ? Map.of() : settings.biomeWeights();
    }

    public record PendingSettings(int scale, boolean blockLowAltitudeSources,
                                  boolean vanillaCanyonsEnabled, Map<String, Float> biomeWeights) {}
}
