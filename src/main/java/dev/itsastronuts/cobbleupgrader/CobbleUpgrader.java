package dev.itsastronuts.cobbleupgrader;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CobbleUpgrader implements ModInitializer {
    public static final String MOD_ID = "cobbleupgrader";
    public static final Logger LOGGER = LoggerFactory.getLogger("Cobble Upgrader");

    private static final List<UpgraderMenu> OPEN = new ArrayList<>();
    private static final Map<UUID, Long> LAST_SPIN = new HashMap<>();

    private static final SuggestionProvider<CommandSourceStack> SPECIES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(Values.targetSpecies().stream()
                    .map(s -> {
                        ResourceLocation id = s.getResourceIdentifier();
                        return id.getNamespace().equals("cobblemon") ? id.getPath() : id.toString();
                    }), builder);

    @Override
    public void onInitialize() {
        UpgraderConfig.load();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (UpgraderMenu menu : new ArrayList<>(OPEN)) {
                try {
                    menu.tick();
                } catch (Exception e) {
                    LOGGER.error("Upgrader tick failed", e);
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (UpgraderMenu menu : new ArrayList<>(OPEN)) {
                menu.finishNow();
            }
            OPEN.clear();
        });
        LOGGER.info("Cobble Upgrader ready. Open it in game with /upgrader");
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("upgrader")
                .requires(src -> src.hasPermission(UpgraderConfig.get().commandPermissionLevel))
                .executes(ctx -> open(ctx.getSource().getPlayerOrException()))
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> {
                            UpgraderConfig.load();
                            ctx.getSource().sendSuccess(() -> Component.literal("Cobble Upgrader config reloaded."), true);
                            return 1;
                        }))
                .then(Commands.literal("target")
                        .then(Commands.argument("pokemon", ResourceLocationArgument.id())
                                .suggests(SPECIES)
                                .executes(ctx -> openWithTarget(ctx, 50, false))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> openWithTarget(ctx, IntegerArgumentType.getInteger(ctx, "level"), false))
                                        .then(Commands.literal("shiny")
                                                .executes(ctx -> openWithTarget(ctx, IntegerArgumentType.getInteger(ctx, "level"), true)))))));
    }

    private static int open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, who) -> new UpgraderMenu(id, inventory, player),
                Component.literal("Cobble Upgrader")));
        return 1;
    }

    private static int openWithTarget(CommandContext<CommandSourceStack> ctx, int level, boolean shiny) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "pokemon");
        if (id.getNamespace().equals("minecraft")) {
            id = ResourceLocation.fromNamespaceAndPath("cobblemon", id.getPath());
        }
        Species species = PokemonSpecies.getByIdentifier(id);
        if (species == null || !Values.targetSpecies().contains(species)) {
            ctx.getSource().sendFailure(Component.literal("Unknown or unavailable Pokémon: " + id.getPath()).withStyle(ChatFormatting.RED));
            return 0;
        }
        open(player);
        if (player.containerMenu instanceof UpgraderMenu menu) {
            menu.setPokemonTarget(species, level, shiny);
        }
        return 1;
    }

    // ---- Shared state --------------------------------------------------------------------

    static void track(UpgraderMenu menu) {
        OPEN.add(menu);
    }

    static void untrack(UpgraderMenu menu) {
        OPEN.remove(menu);
    }

    static void markSpin(ServerPlayer player) {
        LAST_SPIN.put(player.getUUID(), System.currentTimeMillis());
    }

    static long cooldownLeftMs(ServerPlayer player) {
        Long last = LAST_SPIN.get(player.getUUID());
        if (last == null) return 0;
        return Math.max(0, last + UpgraderConfig.get().cooldownSeconds * 1000L - System.currentTimeMillis());
    }
}
