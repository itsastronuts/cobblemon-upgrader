package dev.itsastronuts.cobbleupgrader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings, saved to config/cobbleupgrader.json. Edit the file and run /upgrader reload.
 */
public final class UpgraderConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("cobbleupgrader.json");

    private static UpgraderConfig instance = new UpgraderConfig();

    // ---- Odds ----------------------------------------------------------------------------
    /** Multiplier on (bet value / target value). 1.0 = fair, 0.9 = the house keeps 10% on average. */
    public double houseEdge = 0.9;
    /** Upgrades whose chance would be below this are refused (instead of being rounded up, which would be exploitable). */
    public double minChance = 0.001;
    /** Highest possible chance, so betting something better than the target is never a free win. */
    public double maxChance = 0.9;
    /** How long the wheel spins in the custom screen, in ticks (20 ticks = 1 second). */
    public int spinTicks = 100;
    /** Always use the chest screen, even for players who have the mod installed. */
    public boolean alwaysUseChestScreen = false;
    /** Seconds a player must wait between spins. */
    public int cooldownSeconds = 3;
    /** Ask for a second click before a Pokémon is put at risk. */
    public boolean confirmPokemonBets = true;

    // ---- What is allowed -----------------------------------------------------------------
    /** Permission level needed for /upgrader (0 = everyone, 2 = operators). */
    public int commandPermissionLevel = 0;
    public boolean allowPokemonBets = true;
    /** Let Pokémon from the PC be bet too (chest screen only; the custom screen is party only). */
    public boolean allowPcBets = false;
    /** Let items from your inventory and hotbar be bet. */
    public boolean allowInventoryBets = true;
    public boolean allowPokemonTargets = true;
    public boolean allowItemTargets = true;
    public boolean allowShinyTargets = true;
    public boolean allowLegendaryTargets = true;
    public int minTargetLevel = 1;
    public int maxTargetLevel = 100;
    /** Species ids that can't be bet or won, e.g. "cobblemon:mewtwo". */
    public List<String> blacklistedSpecies = new ArrayList<>();
    /** Item ids that can't be bet or won. Spawn eggs are always blocked. */
    public List<String> blacklistedItems = new ArrayList<>(List.of(
            "minecraft:bedrock", "minecraft:barrier", "minecraft:command_block", "minecraft:chain_command_block",
            "minecraft:repeating_command_block", "minecraft:command_block_minecart", "minecraft:structure_block",
            "minecraft:structure_void", "minecraft:jigsaw", "minecraft:light", "minecraft:debug_stick",
            "minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault", "minecraft:knowledge_book",
            "minecraft:end_portal_frame", "minecraft:reinforced_deepslate", "minecraft:shulker_box",
            "minecraft:bundle", "cobblemon:pokemon_model"));

    // ---- Pokémon value -------------------------------------------------------------------
    /** Overall scale of Pokémon prices compared to item prices. */
    public double pokemonValueScale = 10.0;
    /** Value multiplier per level: value x (1 + level x levelWeight). Level 100 = x5 by default. */
    public double levelWeight = 0.04;
    public double shinyMultiplier = 10.0;
    public double legendaryMultiplier = 8.0;
    public double mythicalMultiplier = 10.0;
    public double ultraBeastMultiplier = 5.0;
    public double paradoxMultiplier = 4.0;
    /** IVs scale value from x(1 - ivSpread/2) at 0 IVs to x(1 + ivSpread/2) at perfect IVs. */
    public double ivSpread = 0.5;
    /** Extra multiplier for a perfect 6x31 IV Pokémon. */
    public double perfectIvBonus = 1.5;
    /** Fixed base value for a species (before level / shiny / IV multipliers), e.g. "cobblemon:eevee": 120. */
    public Map<String, Double> speciesValueOverrides = new LinkedHashMap<>();

    // ---- Item value ----------------------------------------------------------------------
    /** Price of one item that has no entry in itemPrices, before the rarity multiplier. */
    public double defaultItemValue = 1.0;
    public double uncommonItemMultiplier = 4.0;
    public double rareItemMultiplier = 16.0;
    public double epicItemMultiplier = 64.0;
    /** Fixed price per item. Every item listed here can also be picked as an upgrade target. */
    public Map<String, Double> itemPrices = defaultItemPrices();

    public static UpgraderConfig get() {
        return instance;
    }

    public static synchronized void load() {
        UpgraderConfig loaded = null;
        if (Files.isRegularFile(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE)) {
                loaded = GSON.fromJson(reader, UpgraderConfig.class);
            } catch (Exception e) {
                CobbleUpgrader.LOGGER.error("Could not read {}, using defaults", FILE, e);
            }
        }
        if (loaded == null) {
            loaded = new UpgraderConfig();
        }
        loaded.sanitize();
        instance = loaded;
        save();
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(instance, writer);
            }
        } catch (Exception e) {
            CobbleUpgrader.LOGGER.error("Could not write {}", FILE, e);
        }
    }

    private void sanitize() {
        houseEdge = clamp(houseEdge, 0.01, 1.0);
        minChance = clamp(minChance, 0.0, 1.0);
        maxChance = clamp(maxChance, 0.01, 1.0);
        if (minChance > maxChance) {
            minChance = maxChance;
        }
        cooldownSeconds = Math.max(0, cooldownSeconds);
        spinTicks = Math.max(20, Math.min(600, spinTicks));
        commandPermissionLevel = Math.max(0, Math.min(4, commandPermissionLevel));
        minTargetLevel = Math.max(1, Math.min(100, minTargetLevel));
        maxTargetLevel = Math.max(minTargetLevel, Math.min(100, maxTargetLevel));
        pokemonValueScale = Math.max(0.0001, pokemonValueScale);
        levelWeight = Math.max(0, levelWeight);
        ivSpread = clamp(ivSpread, 0, 1.9);
        defaultItemValue = Math.max(0, defaultItemValue);
        if (blacklistedSpecies == null) blacklistedSpecies = new ArrayList<>();
        if (blacklistedItems == null) blacklistedItems = new ArrayList<>();
        if (speciesValueOverrides == null) speciesValueOverrides = new LinkedHashMap<>();
        if (itemPrices == null) itemPrices = defaultItemPrices();
        itemPrices.values().removeIf(v -> v == null || !(v > 0));
        speciesValueOverrides.values().removeIf(v -> v == null || !(v > 0));
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static Map<String, Double> defaultItemPrices() {
        Map<String, Double> m = new LinkedHashMap<>();
        // Vanilla materials
        m.put("minecraft:iron_ingot", 3.0);
        m.put("minecraft:gold_ingot", 6.0);
        m.put("minecraft:emerald", 12.0);
        m.put("minecraft:diamond", 30.0);
        m.put("minecraft:ancient_debris", 100.0);
        m.put("minecraft:netherite_scrap", 100.0);
        m.put("minecraft:netherite_ingot", 450.0);
        m.put("minecraft:iron_block", 27.0);
        m.put("minecraft:gold_block", 54.0);
        m.put("minecraft:emerald_block", 108.0);
        m.put("minecraft:diamond_block", 270.0);
        m.put("minecraft:netherite_block", 4050.0);
        m.put("minecraft:ender_pearl", 5.0);
        m.put("minecraft:blaze_rod", 6.0);
        m.put("minecraft:experience_bottle", 4.0);
        m.put("minecraft:name_tag", 40.0);
        m.put("minecraft:saddle", 30.0);
        m.put("minecraft:echo_shard", 50.0);
        m.put("minecraft:shulker_shell", 80.0);
        m.put("minecraft:golden_apple", 60.0);
        m.put("minecraft:enchanted_golden_apple", 800.0);
        m.put("minecraft:totem_of_undying", 600.0);
        m.put("minecraft:heart_of_the_sea", 400.0);
        m.put("minecraft:trident", 700.0);
        m.put("minecraft:netherite_upgrade_smithing_template", 300.0);
        m.put("minecraft:heavy_core", 900.0);
        m.put("minecraft:mace", 1500.0);
        m.put("minecraft:nether_star", 1500.0);
        m.put("minecraft:beacon", 1800.0);
        m.put("minecraft:elytra", 2500.0);
        m.put("minecraft:dragon_egg", 5000.0);
        // Cobblemon balls
        m.put("cobblemon:poke_ball", 2.0);
        m.put("cobblemon:great_ball", 6.0);
        m.put("cobblemon:ultra_ball", 15.0);
        m.put("cobblemon:quick_ball", 15.0);
        m.put("cobblemon:dusk_ball", 15.0);
        m.put("cobblemon:timer_ball", 15.0);
        m.put("cobblemon:luxury_ball", 20.0);
        m.put("cobblemon:master_ball", 3000.0);
        // Cobblemon candies and training items
        m.put("cobblemon:exp_candy_xs", 4.0);
        m.put("cobblemon:exp_candy_s", 10.0);
        m.put("cobblemon:exp_candy_m", 25.0);
        m.put("cobblemon:exp_candy_l", 60.0);
        m.put("cobblemon:exp_candy_xl", 150.0);
        m.put("cobblemon:rare_candy", 60.0);
        m.put("cobblemon:ability_capsule", 200.0);
        m.put("cobblemon:ability_patch", 600.0);
        m.put("cobblemon:bottle_cap", 150.0);
        m.put("cobblemon:gold_bottle_cap", 900.0);
        // Cobblemon evolution stones
        for (String stone : List.of("fire_stone", "water_stone", "thunder_stone", "leaf_stone", "moon_stone",
                "sun_stone", "shiny_stone", "dusk_stone", "dawn_stone", "ice_stone")) {
            m.put("cobblemon:" + stone, 25.0);
        }
        // Cobblemon held items
        m.put("cobblemon:everstone", 20.0);
        m.put("cobblemon:exp_share", 80.0);
        m.put("cobblemon:lucky_egg", 200.0);
        m.put("cobblemon:leftovers", 120.0);
        m.put("cobblemon:focus_sash", 150.0);
        m.put("cobblemon:assault_vest", 200.0);
        m.put("cobblemon:choice_band", 250.0);
        m.put("cobblemon:choice_scarf", 250.0);
        m.put("cobblemon:choice_specs", 250.0);
        m.put("cobblemon:life_orb", 250.0);
        return m;
    }
}
