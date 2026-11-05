package dev.itsastronuts.cobbleupgrader.net;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import dev.itsastronuts.cobbleupgrader.CobbleUpgrader;
import dev.itsastronuts.cobbleupgrader.UpgradeLogic;
import dev.itsastronuts.cobbleupgrader.UpgraderConfig;
import dev.itsastronuts.cobbleupgrader.Values;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server side of the custom screen: checks every request and runs the spin. */
public final class NetSession {
    private static final Map<UUID, NetSession> SESSIONS = new HashMap<>();

    private final ServerPlayer player;
    private boolean spinning;
    private boolean won;
    private int ticksLeft;
    private double chance;
    // What will be given if the spin wins
    private Species prizeSpecies;
    private int prizeLevel;
    private boolean prizeShiny;
    private Item prizeItem;
    private int prizeCount;

    private NetSession(ServerPlayer player) {
        this.player = player;
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(Payloads.Upgrade.TYPE, (payload, context) -> {
            NetSession session = SESSIONS.get(context.player().getUUID());
            if (session != null) session.handleUpgrade(payload);
        });
        ServerPlayNetworking.registerGlobalReceiver(Payloads.Close.TYPE, (payload, context) -> close(context.player()));
    }

    /** True if this player has the mod installed and can show the custom screen. */
    public static boolean canUse(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, Payloads.Data.TYPE);
    }

    public static void open(ServerPlayer player) {
        NetSession session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new NetSession(player));
        session.sendData(true);
    }

    public static void close(ServerPlayer player) {
        NetSession session = SESSIONS.remove(player.getUUID());
        if (session != null) session.finishNow();
    }

    public static void tickAll() {
        for (NetSession session : new ArrayList<>(SESSIONS.values())) {
            try {
                session.tick();
            } catch (Exception e) {
                CobbleUpgrader.LOGGER.error("Upgrader spin failed", e);
            }
        }
    }

    public static void finishAll() {
        for (NetSession session : new ArrayList<>(SESSIONS.values())) session.finishNow();
        SESSIONS.clear();
    }

    // ---- Requests ------------------------------------------------------------------------

    private void handleUpgrade(Payloads.Upgrade request) {
        if (spinning) return;
        UpgraderConfig c = UpgraderConfig.get();

        if (!c.allowPokemonBets) {
            error("Pokémon bets are turned off.");
            return;
        }
        long wait = CobbleUpgrader.cooldownLeftMs(player);
        if (wait > 0) {
            error("Wait " + (wait / 1000 + 1) + "s before the next upgrade.");
            return;
        }
        if (UpgradeLogic.inBattle(player)) {
            error("You can't do this during a battle.");
            return;
        }

        // The bet: a Pokémon from the party
        Pokemon pokemon = UpgradeLogic.findPokemon(player, request.bet(), false);
        if (pokemon == null) {
            error("That Pokémon is no longer in your party.");
            return;
        }
        String unusable = UpgradeLogic.pokemonUnusable(pokemon);
        if (unusable != null) {
            error(unusable);
            return;
        }

        // The prize
        double targetValue;
        if (request.targetKind() == 0) {
            if (!c.allowPokemonTargets) {
                error("Pokémon prizes are turned off.");
                return;
            }
            ResourceLocation id = ResourceLocation.tryParse(request.targetId());
            Species species = id == null ? null : PokemonSpecies.getByIdentifier(id);
            if (species == null || !Values.targetSpecies().contains(species)) {
                error("That Pokémon can't be won.");
                return;
            }
            prizeSpecies = species;
            prizeLevel = Math.max(c.minTargetLevel, Math.min(c.maxTargetLevel, request.targetAmount()));
            prizeShiny = request.shiny() && c.allowShinyTargets;
            prizeItem = null;
            targetValue = Values.targetPokemonValue(prizeSpecies, prizeLevel, prizeShiny);
        } else {
            if (!c.allowItemTargets) {
                error("Item prizes are turned off.");
                return;
            }
            ResourceLocation id = ResourceLocation.tryParse(request.targetId());
            Item item = id == null || !BuiltInRegistries.ITEM.containsKey(id) ? null : BuiltInRegistries.ITEM.get(id);
            if (item == null || !Values.targetItems().contains(item)) {
                error("That item can't be won.");
                return;
            }
            prizeItem = item;
            prizeCount = Math.max(1, Math.min(new ItemStack(item).getMaxStackSize(), request.targetAmount()));
            prizeSpecies = null;
            targetValue = Values.itemValue(item) * prizeCount;
        }

        double betValue = Values.pokemonValue(pokemon);
        if (Values.chanceTooLow(betValue, targetValue)) {
            error("That target is too valuable for this Pokémon.");
            return;
        }
        chance = Values.chance(betValue, targetValue);

        if (!UpgradeLogic.takePokemon(player, pokemon, false)) {
            error("Couldn't take that Pokémon, nothing was lost.");
            return;
        }
        CobbleUpgrader.LOGGER.info("{} bet {} (lv {}, shiny {}, value {}) at {}", player.getGameProfile().getName(),
                pokemon.getSpecies().getName(), pokemon.getLevel(), pokemon.getShiny(), betValue, Values.percent(chance));
        CobbleUpgrader.markSpin(player);

        won = player.getRandom().nextDouble() < chance;
        // The win zone is the first `chance` of the dial, starting at the top and going clockwise.
        float winDegrees = (float) (chance * 360.0);
        float margin = Math.min(4f, winDegrees * 0.2f);
        float landing = won
                ? margin + player.getRandom().nextFloat() * Math.max(0.01f, winDegrees - 2 * margin)
                : winDegrees + margin + player.getRandom().nextFloat() * Math.max(0.01f, 360f - winDegrees - 2 * margin);
        ticksLeft = c.spinTicks;
        spinning = true;
        ServerPlayNetworking.send(player, new Payloads.Spin(landing, c.spinTicks, chance));
        // The Pokémon is gone from the party now; refresh the list right away.
        sendData(false);
    }

    private void tick() {
        if (!spinning) return;
        if (!player.isAlive() || player.hasDisconnected()) {
            finishNow();
            return;
        }
        if (--ticksLeft <= 0) {
            finishNow();
        }
    }

    /** Gives the prize (if won) and tells the screen. Safe to call more than once. */
    private void finishNow() {
        if (!spinning) return;
        spinning = false;
        String message;
        if (won) {
            String prize;
            if (prizeSpecies != null) {
                UpgradeLogic.givePokemon(player, prizeSpecies, prizeLevel, prizeShiny);
                prize = (prizeShiny ? "Shiny " : "") + prizeSpecies.getTranslatedName().getString() + " (lv " + prizeLevel + ")";
            } else {
                UpgradeLogic.giveItems(player, prizeItem, prizeCount);
                prize = prizeCount + "x " + new ItemStack(prizeItem).getHoverName().getString();
            }
            message = "You won " + prize + "!";
            player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 1f, 1f);
            player.sendSystemMessage(Component.literal("Upgrade succeeded! " + message).withStyle(ChatFormatting.GREEN));
        } else {
            message = "The upgrade failed.";
            player.playNotifySound(SoundEvents.ANVIL_LAND, SoundSource.MASTER, 0.7f, 0.8f);
            player.sendSystemMessage(Component.literal("Upgrade failed. Better luck next time.").withStyle(ChatFormatting.RED));
        }
        if (!player.hasDisconnected()) {
            ServerPlayNetworking.send(player, new Payloads.Result(won, message));
            sendData(false);
        }
    }

    private void error(String message) {
        ServerPlayNetworking.send(player, new Payloads.Error(message));
    }

    // ---- Data for the screen -------------------------------------------------------------

    private void sendData(boolean open) {
        UpgraderConfig c = UpgraderConfig.get();
        Payloads.Settings settings = new Payloads.Settings(c.houseEdge, c.minChance, c.maxChance, c.minTargetLevel,
                c.maxTargetLevel, c.levelWeight, c.shinyMultiplier, c.allowItemTargets, c.allowPokemonTargets,
                c.allowShinyTargets, c.confirmPokemonBets, c.spinTicks);

        List<Payloads.PartyMon> party = new ArrayList<>();
        PlayerPartyStore store = UpgradeLogic.party(player);
        for (int slot = 0; slot < 6; slot++) {
            Pokemon pokemon = store.get(slot);
            if (pokemon == null) continue;
            String blocked = UpgradeLogic.pokemonUnusable(pokemon);
            ItemStack held = pokemon.heldItem();
            party.add(new Payloads.PartyMon(slot, pokemon.getUuid(), pokemon.getSpecies().getResourceIdentifier().toString(),
                    new ArrayList<>(pokemon.getAspects()), pokemon.getLevel(), pokemon.getShiny(), Values.ivTotal(pokemon),
                    Values.pokemonValue(pokemon), blocked == null ? "" : blocked,
                    held.isEmpty() ? "" : held.getHoverName().getString(), Values.isLegendaryLike(pokemon.getSpecies())));
        }

        Map<String, Double> items = new LinkedHashMap<>();
        if (c.allowItemTargets) {
            for (Item item : Values.targetItems()) items.put(Values.id(item), Values.itemValue(item));
        }
        List<String> species = new ArrayList<>();
        List<Double> base = new ArrayList<>();
        if (c.allowPokemonTargets) {
            for (Species s : Values.targetSpecies()) {
                species.add(s.getResourceIdentifier().toString());
                base.add(Values.speciesBaseValue(s));
            }
        }
        ServerPlayNetworking.send(player, new Payloads.Data(open, settings, party, items, species, base));
    }
}
