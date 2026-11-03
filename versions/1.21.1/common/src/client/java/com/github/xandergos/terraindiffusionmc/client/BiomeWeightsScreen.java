package com.github.xandergos.terraindiffusionmc.client;

import com.github.xandergos.terraindiffusionmc.biome.BiomeWeightSettings;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeSettlement;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A searchable, per-biome relative-weight editor for the world creation flow.
 * Values affect competition between climate-eligible biomes; they cannot force
 * a desert biome to spawn in a snowy climate. 1.0 means unmodified catalog weight.
 */
public final class BiomeWeightsScreen extends Screen {
    private final WorldScaleSettingsScreen parent;
    private final Map<String, Float> weights;
    private final List<String> keys;
    private int selected = 0;
    private EditBox searchField;
    private EditBox weightField;
    private StringWidget selectedLabel;
    private StringWidget validationLabel;

    public BiomeWeightsScreen(WorldScaleSettingsScreen parent, Map<String, Float> selectedWeights) {
        super(Component.literal("Terrain Diffusion - Biome Weights"));
        this.parent = parent;
        this.weights = new HashMap<>(selectedWeights);
        this.keys = new ArrayList<>();
        for (TerrainBiomeSettlement biome : TerrainBiomeRegistry.instance().all()) {
            if (biome.canGenerateOverworld()) keys.add(biome.key());
        }
        keys.sort(Comparator.naturalOrder());
    }

    @Override
    protected void init() {
        int x = width / 2;
        int y = height / 2;
        addRenderableWidget(new StringWidget(0, 18, width, 12, title, font));
        addRenderableWidget(new StringWidget(0, y - 85, width, 12,
                Component.literal("Search by biome ID (e.g. badlands)"), font));
        searchField = new EditBox(font, x - 140, y - 67, 280, 20, Component.literal("Search biomes"));
        addRenderableWidget(searchField);

        selectedLabel = new StringWidget(x - 145, y - 38, 290, 14, Component.empty(), font);
        addRenderableWidget(selectedLabel);
        addRenderableWidget(new StringWidget(x - 90, y - 15, 80, 16, Component.literal("Weight (0 - 10)"), font));
        weightField = new EditBox(font, x + 10, y - 16, 80, 20, Component.literal("Biome weight"));
        addRenderableWidget(weightField);

        addRenderableWidget(Button.builder(Component.literal("< Previous"), b -> navigate(-1))
                .bounds(x - 140, y + 12, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Next >"), b -> navigate(1))
                .bounds(x + 50, y + 12, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset to 1.0"), b -> resetSelected())
                .bounds(x - 55, y + 39, 110, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> saveAndClose())
                .bounds(x - 95, y + 66, 90, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(x + 5, y + 66, 90, 20).build());

        validationLabel = new StringWidget(0, y + 93, width, 12, Component.empty(), font);
        addRenderableWidget(validationLabel);
        showSelection();
        searchField.setResponder(search -> {
            if (search.isBlank()) return;
            for (int i = 0; i < keys.size(); i++) {
                if (keys.get(i).toLowerCase(java.util.Locale.ROOT)
                        .contains(search.toLowerCase(java.util.Locale.ROOT))) {
                    if (i != selected && commitWeight()) {
                        selected = i;
                        showSelection();
                    }
                    break;
                }
            }
        });
    }

    private void showSelection() {
        if (keys.isEmpty()) {
            selectedLabel.setMessage(Component.literal("No available overworld biomes"));
            weightField.setValue("1.0");
            return;
        }
        String key = keys.get(selected);
        selectedLabel.setMessage(Component.literal((selected + 1) + "/" + keys.size() + "   " + key));
        weightField.setValue(Float.toString(weights.getOrDefault(key, BiomeWeightSettings.DEFAULT_WEIGHT)));
        validationLabel.setMessage(Component.empty());
    }

    private boolean commitWeight() {
        if (keys.isEmpty()) return true;
        String value = weightField.getValue().trim();
        try {
            float parsed = Float.parseFloat(value);
            if (!Float.isFinite(parsed) || parsed < 0 || parsed > BiomeWeightSettings.MAX_WEIGHT) {
                throw new NumberFormatException(value);
            }
            String key = keys.get(selected);
            if (parsed == BiomeWeightSettings.DEFAULT_WEIGHT) weights.remove(key);
            else weights.put(key, parsed);
            validationLabel.setMessage(Component.empty());
            return true;
        } catch (NumberFormatException ex) {
            validationLabel.setMessage(Component.literal("Enter a weight from 0 to 10")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
    }

    private void navigate(int delta) {
        if (!commitWeight() || keys.isEmpty()) return;
        selected = Math.floorMod(selected + delta, keys.size());
        showSelection();
    }

    private void resetSelected() {
        if (keys.isEmpty()) return;
        weights.remove(keys.get(selected));
        showSelection();
    }

    private void saveAndClose() {
        if (!commitWeight()) return;
        parent.acceptBiomeWeights(weights);
        onClose();
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
