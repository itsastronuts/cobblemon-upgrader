package dev.itsastronuts.cobbleupgrader;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Server-side rules shared by the chest screen and the custom screen. */
public final class UpgradeLogic {
    private UpgradeLogic() {
    }

    public static PlayerPartyStore party(ServerPlayer player) {
        return Cobblemon.INSTANCE.getStorage().getParty(player);
    }

    public static PCStore pc(ServerPlayer player) {
        return Cobblemon.INSTANCE.getStorage().getPC(player);
    }

    public static Pokemon findPokemon(ServerPlayer player, UUID uuid, boolean fromPc) {
        Iterable<Pokemon> store = fromPc ? pc(player) : party(player);
        for (Pokemon pokemon : store) {
            if (pokemon != null && pokemon.getUuid().equals(uuid)) return pokemon;
        }
        return null;
    }

    public static boolean inBattle(ServerPlayer player) {
        return BattleRegistry.getBattleByParticipatingPlayer(player) != null;
    }

    /** Why this Pokémon can't be bet, or null if it can. */
    public static String pokemonUnusable(Pokemon pokemon) {
        if (!pokemon.getTradeable()) return "That Pokémon is marked as untradeable.";
        if (pokemon.getTetheringId() != null) return "Take that Pokémon out of its pasture first.";
        if (Values.isSpeciesBlacklisted(pokemon.getSpecies())) return "That Pokémon can't be used in the upgrader.";
        return null;
    }

    /** Removes a Pokémon from the player's party or PC. Returns false if it couldn't be taken. */
    public static boolean takePokemon(ServerPlayer player, Pokemon pokemon, boolean fromPc) {
        if (pokemon.getEntity() != null) {
            pokemon.recall();
        }
        return fromPc ? pc(player).remove(pokemon) : party(player).remove(pokemon);
    }

    /** Takes items out of an inventory slot. Returns false if the slot no longer holds them. */
    public static boolean takeItems(ServerPlayer player, int slot, Item item, int amount) {
        if (slot < 0 || slot >= 36 || amount <= 0) return false;
        ItemStack stack = player.getInventory().getItem(slot);
        if (!stack.is(item) || stack.getCount() < amount) return false;
        stack.shrink(amount);
        player.getInventory().setChanged();
        return true;
    }

    /** Gives a freshly generated Pokémon: to the party, or the PC when the party is full. */
    public static void givePokemon(ServerPlayer player, Species species, int level, boolean shiny) {
        PokemonProperties properties = new PokemonProperties();
        properties.setSpecies(species.getResourceIdentifier().toString());
        properties.setLevel(level);
        properties.setShiny(shiny);
        Pokemon pokemon = properties.create();
        party(player).add(pokemon);
        CobbleUpgrader.LOGGER.info("{} won {}{} (lv {})", player.getGameProfile().getName(),
                shiny ? "shiny " : "", species.getName(), level);
    }

    public static void giveItems(ServerPlayer player, Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        if (!player.getInventory().add(stack) || !stack.isEmpty()) {
            player.drop(stack, false);
        }
    }
}
