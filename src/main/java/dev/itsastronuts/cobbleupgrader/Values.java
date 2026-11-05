package dev.itsastronuts.cobbleupgrader;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Prices for Pokémon and items, and the odds of an upgrade. */
public final class Values {
    private static final Stat[] STATS = {Stats.HP, Stats.ATTACK, Stats.DEFENCE,
            Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED};
    private static final int MAX_IV_TOTAL = 31 * 6;
    /** IV total assumed for a Pokémon you could win (its real IVs are rolled when it is created). */
    private static final int AVERAGE_IV_TOTAL = MAX_IV_TOTAL / 2;

    private Values() {
    }

    // ---- Pokémon --------------------------------------------------------------------------

    /** Base value of a species: stronger (base stat total) and harder to catch (catch rate) = worth more. */
    public static double speciesBaseValue(Species species) {
        UpgraderConfig c = UpgraderConfig.get();
        Double override = c.speciesValueOverrides.get(species.getResourceIdentifier().toString());
        if (override != null) {
            return override;
        }
        int bst = 0;
        for (Integer v : species.getBaseStats().values()) {
            if (v != null) bst += v;
        }
        bst = Math.max(bst, 100);
        int catchRate = Math.max(1, Math.min(255, species.getCatchRate()));
        double value = c.pokemonValueScale * Math.pow(bst / 300.0, 2) * Math.sqrt(255.0 / catchRate);

        Set<String> labels = species.getLabels();
        if (labels.contains(CobblemonPokemonLabels.MYTHICAL)) {
            value *= c.mythicalMultiplier;
        } else if (labels.contains(CobblemonPokemonLabels.LEGENDARY)) {
            value *= c.legendaryMultiplier;
        } else if (labels.contains(CobblemonPokemonLabels.ULTRA_BEAST)) {
            value *= c.ultraBeastMultiplier;
        } else if (labels.contains(CobblemonPokemonLabels.PARADOX)) {
            value *= c.paradoxMultiplier;
        }
        return value;
    }

    public static double pokemonValue(Species species, int level, boolean shiny, int ivTotal) {
        UpgraderConfig c = UpgraderConfig.get();
        double value = speciesBaseValue(species);
        value *= 1.0 + Math.max(1, level) * c.levelWeight;
        if (shiny) {
            value *= c.shinyMultiplier;
        }
        double ivFraction = Math.max(0, Math.min(1, ivTotal / (double) MAX_IV_TOTAL));
        value *= 1.0 - c.ivSpread / 2 + c.ivSpread * ivFraction;
        if (ivTotal >= MAX_IV_TOTAL) {
            value *= c.perfectIvBonus;
        }
        return round(value);
    }

    /** Value of a Pokémon the player owns. */
    public static double pokemonValue(Pokemon pokemon) {
        return pokemonValue(pokemon.getSpecies(), pokemon.getLevel(), pokemon.getShiny(), ivTotal(pokemon));
    }

    /** Value of a Pokémon the player could win (IVs not rolled yet, so average IVs are assumed). */
    public static double targetPokemonValue(Species species, int level, boolean shiny) {
        return pokemonValue(species, level, shiny, AVERAGE_IV_TOTAL);
    }

    public static int ivTotal(Pokemon pokemon) {
        int total = 0;
        for (Stat stat : STATS) {
            total += pokemon.getIvs().getOrDefault(stat);
        }
        return total;
    }

    public static boolean isPerfectIv(Pokemon pokemon) {
        return ivTotal(pokemon) >= MAX_IV_TOTAL;
    }

    public static boolean isSpeciesBlacklisted(Species species) {
        return UpgraderConfig.get().blacklistedSpecies.contains(species.getResourceIdentifier().toString());
    }

    public static boolean isLegendaryLike(Species species) {
        Set<String> labels = species.getLabels();
        return labels.contains(CobblemonPokemonLabels.LEGENDARY) || labels.contains(CobblemonPokemonLabels.MYTHICAL)
                || labels.contains(CobblemonPokemonLabels.ULTRA_BEAST);
    }

    /** Every species that can be chosen as an upgrade target, in Pokédex order. */
    public static List<Species> targetSpecies() {
        UpgraderConfig c = UpgraderConfig.get();
        List<Species> list = new ArrayList<>();
        for (Species species : PokemonSpecies.getSpecies()) {
            if (!species.getImplemented() || isSpeciesBlacklisted(species)) continue;
            if (!c.allowLegendaryTargets && isLegendaryLike(species)) continue;
            list.add(species);
        }
        list.sort(Comparator.comparingInt(Species::getNationalPokedexNumber)
                .thenComparing(s -> s.getResourceIdentifier().toString()));
        return list;
    }

    // ---- Items ---------------------------------------------------------------------------

    public static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static boolean isItemBlacklisted(Item item) {
        return item == Items.AIR || item instanceof SpawnEggItem
                || UpgraderConfig.get().blacklistedItems.contains(id(item));
    }

    public static double itemValue(Item item) {
        UpgraderConfig c = UpgraderConfig.get();
        Double price = c.itemPrices.get(id(item));
        if (price != null) {
            return price;
        }
        double multiplier = switch (new ItemStack(item).getRarity()) {
            case UNCOMMON -> c.uncommonItemMultiplier;
            case RARE -> c.rareItemMultiplier;
            case EPIC -> c.epicItemMultiplier;
            default -> 1.0;
        };
        return c.defaultItemValue * multiplier;
    }

    /** Items that can be won: those with a fixed price in the config, cheapest first. */
    public static List<Item> targetItems() {
        List<Item> list = new ArrayList<>();
        for (Map.Entry<String, Double> e : UpgraderConfig.get().itemPrices.entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(e.getKey());
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) continue;
            Item item = BuiltInRegistries.ITEM.get(id);
            if (!isItemBlacklisted(item)) list.add(item);
        }
        list.sort(Comparator.comparingDouble(Values::itemValue).thenComparing(Values::id));
        return list;
    }

    // ---- Odds ----------------------------------------------------------------------------

    /** Chance before the max clamp; can be below minChance (then the upgrade is refused). */
    public static double rawChance(double betValue, double targetValue) {
        if (betValue <= 0 || targetValue <= 0) return 0;
        return UpgraderConfig.get().houseEdge * betValue / targetValue;
    }

    public static double chance(double betValue, double targetValue) {
        return Math.min(UpgraderConfig.get().maxChance, rawChance(betValue, targetValue));
    }

    public static boolean chanceTooLow(double betValue, double targetValue) {
        return rawChance(betValue, targetValue) < UpgraderConfig.get().minChance;
    }

    public static double round(double value) {
        if (value >= 100) return Math.round(value);
        return Math.round(value * 100.0) / 100.0;
    }

    public static String format(double value) {
        if (value >= 1_000_000) return String.format("%.2fM", value / 1_000_000);
        if (value >= 10_000) return String.format("%.1fk", value / 1_000);
        if (value == Math.rint(value)) return String.format("%,d", (long) value);
        return String.format("%.2f", value);
    }

    public static String percent(double chance) {
        double p = chance * 100;
        if (p >= 10) return String.format("%.1f%%", p);
        if (p >= 1) return String.format("%.2f%%", p);
        return String.format("%.3f%%", p);
    }
}
