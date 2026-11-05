package dev.itsastronuts.cobbleupgrader;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The whole upgrader lives in one server-side chest screen, so players don't need the mod installed.
 * Nothing in this screen can be picked up: every click is a button.
 */
public class UpgraderMenu extends ChestMenu {
    private static final int SIZE = 54;
    private static final int PAGE = 45;
    private static final int TRACK_START = 9;
    private static final int TRACK = 27;
    private static final int LAPS = 2;

    // Main screen slots
    private static final int S_INFO = 4;
    private static final int S_FROM_PARTY = 10, S_FROM_PC = 11, S_FROM_INV = 12;
    private static final int S_PICK_POKEMON = 14, S_PICK_ITEM = 15, S_CLEAR = 16;
    private static final int S_BET = 20, S_CHANCE = 22, S_TARGET = 24;
    private static final int S_BET_M10 = 27, S_BET_M1 = 28, S_BET_P1 = 29, S_BET_P10 = 30;
    private static final int S_TGT_M10 = 32, S_TGT_M1 = 33, S_TGT_P1 = 34, S_TGT_P10 = 35;
    private static final int S_SHINY = 42;
    private static final int S_UPGRADE = 49, S_CLOSE = 53;
    // Picker navigation slots
    private static final int S_BACK = 45, S_PREV10 = 46, S_PREV = 48, S_PAGE = 49, S_NEXT = 50, S_NEXT10 = 52;

    private enum View { MAIN, PARTY, PC, INVENTORY, SPECIES, ITEMS, SPIN }

    private sealed interface Bet permits PokemonBet, ItemBet {}
    private record PokemonBet(UUID uuid, boolean fromPc) implements Bet {}
    private record ItemBet(int slot, Item item, int amount) implements Bet {}

    private sealed interface Target permits PokemonTarget, ItemTarget {}
    private record PokemonTarget(Species species, int level, boolean shiny) implements Target {}
    private record ItemTarget(Item item, int count) implements Target {}

    private final ServerPlayer player;
    private final Container gui;
    private View view = View.MAIN;
    private int page = 0;
    private Bet bet;
    private Target target;
    private boolean confirming;
    private String notice;

    // Spin state
    private boolean spinning;
    private boolean won;
    private int winCells;
    private int step;
    private int totalSteps;
    private int stepTimer;
    private int resultTicks;
    private boolean rewardGiven = true;
    private Target spinTarget;
    private ItemStack spinBetIcon = ItemStack.EMPTY;
    private double spinChance;

    private List<Species> speciesCache;
    private List<Item> itemCache;
    private List<Pokemon> pcCache;

    public UpgraderMenu(int id, Inventory inventory, ServerPlayer player) {
        this(id, inventory, player, new SimpleContainer(SIZE));
    }

    private UpgraderMenu(int id, Inventory inventory, ServerPlayer player, Container gui) {
        super(MenuType.GENERIC_9x6, id, inventory, gui, 6);
        this.player = player;
        this.gui = gui;
        CobbleUpgrader.track(this);
        render();
    }

    // ---- Minecraft hooks -----------------------------------------------------------------

    @Override
    public void clicked(int slot, int button, ClickType type, Player who) {
        // Never call super: nothing can be moved in or out of this screen.
        if (slot >= 0 && slot < SIZE && (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE)) {
            try {
                handleClick(slot, button == 1, type == ClickType.QUICK_MOVE);
            } catch (Exception e) {
                CobbleUpgrader.LOGGER.error("Upgrader click failed", e);
                notice = "Something went wrong, see the server log.";
                view = spinning ? View.SPIN : View.MAIN;
                render();
            }
        }
        sendAllDataToRemote();
    }

    @Override
    public ItemStack quickMoveStack(Player who, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player who) {
        return true;
    }

    @Override
    public void removed(Player who) {
        super.removed(who);
        finishNow();
        CobbleUpgrader.untrack(this);
    }

    /** Called every server tick while the screen is open. */
    public void tick() {
        if (!spinning) return;
        if (step < totalSteps) {
            if (--stepTimer > 0) return;
            step++;
            double progress = step / (double) totalSteps;
            stepTimer = 1 + (int) (5 * Math.pow(progress, 4));
            renderSpin();
            playSound(SoundEvents.NOTE_BLOCK_HAT.value(), 0.6f, 1.0f + (float) progress * 0.8f);
            if (step >= totalSteps) {
                land();
            }
            broadcastChanges();
        } else if (--resultTicks <= 0) {
            spinning = false;
            view = View.MAIN;
            render();
            broadcastChanges();
        }
    }

    /** Hand out the result right away (screen closed or server stopping mid-spin). */
    public void finishNow() {
        if (spinning && !rewardGiven) {
            step = totalSteps;
            land();
        }
        spinning = false;
    }

    // ---- Click handling ------------------------------------------------------------------

    private void handleClick(int slot, boolean right, boolean shift) {
        if (spinning) return;
        boolean wasConfirming = confirming;
        confirming = false;
        notice = null;
        switch (view) {
            case MAIN -> clickMain(slot, right, shift, wasConfirming);
            case PARTY -> clickParty(slot);
            case PC -> clickPc(slot);
            case INVENTORY -> clickInventory(slot, right);
            case SPECIES -> clickSpecies(slot, shift);
            case ITEMS -> clickItems(slot);
            default -> {
            }
        }
        render();
        if (view != View.SPIN) playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.2f);
    }

    private void clickMain(int slot, boolean right, boolean shift, boolean wasConfirming) {
        UpgraderConfig c = UpgraderConfig.get();
        int mult = shift ? 5 : 1;
        switch (slot) {
            case S_FROM_PARTY -> {
                if (c.allowPokemonBets) open(View.PARTY);
            }
            case S_FROM_PC -> {
                if (c.allowPokemonBets) open(View.PC);
            }
            case S_FROM_INV -> {
                if (c.allowItemBets) open(View.INVENTORY);
            }
            case S_PICK_POKEMON -> {
                if (c.allowPokemonTargets) open(View.SPECIES);
            }
            case S_PICK_ITEM -> {
                if (c.allowItemTargets) open(View.ITEMS);
            }
            case S_CLEAR -> {
                bet = null;
                target = null;
            }
            case S_BET -> bet = null;
            case S_TARGET -> target = null;
            case S_BET_M10 -> changeBetAmount(-10 * mult);
            case S_BET_M1 -> changeBetAmount(-mult);
            case S_BET_P1 -> changeBetAmount(mult);
            case S_BET_P10 -> changeBetAmount(10 * mult);
            case S_TGT_M10 -> changeTarget(-10 * mult);
            case S_TGT_M1 -> changeTarget(-mult);
            case S_TGT_P1 -> changeTarget(mult);
            case S_TGT_P10 -> changeTarget(10 * mult);
            case S_SHINY -> {
                if (target instanceof PokemonTarget t && c.allowShinyTargets) {
                    target = new PokemonTarget(t.species(), t.level(), !t.shiny());
                }
            }
            case S_UPGRADE -> pressUpgrade(wasConfirming);
            case S_CLOSE -> player.closeContainer();
            default -> {
            }
        }
    }

    private void open(View next) {
        view = next;
        page = 0;
        speciesCache = null;
        itemCache = null;
        pcCache = null;
    }

    private void changeBetAmount(int delta) {
        if (!(bet instanceof ItemBet b)) return;
        ItemStack stack = player.getInventory().getItem(b.slot());
        int max = stack.is(b.item()) ? stack.getCount() : b.amount();
        int amount = Math.max(1, Math.min(max, b.amount() + delta));
        bet = new ItemBet(b.slot(), b.item(), amount);
    }

    private void changeTarget(int delta) {
        UpgraderConfig c = UpgraderConfig.get();
        if (target instanceof PokemonTarget t) {
            int level = Math.max(c.minTargetLevel, Math.min(c.maxTargetLevel, t.level() + delta));
            target = new PokemonTarget(t.species(), level, t.shiny());
        } else if (target instanceof ItemTarget t) {
            int max = new ItemStack(t.item()).getMaxStackSize();
            target = new ItemTarget(t.item(), Math.max(1, Math.min(max, t.count() + delta)));
        }
    }

    private void clickParty(int slot) {
        if (slot == S_BACK) {
            view = View.MAIN;
            return;
        }
        if (slot >= 0 && slot < 6) {
            Pokemon pokemon = party().get(slot);
            if (pokemon != null && checkPokemonUsable(pokemon)) {
                bet = new PokemonBet(pokemon.getUuid(), false);
                view = View.MAIN;
            }
        }
    }

    private void clickPc(int slot) {
        List<Pokemon> list = pcList();
        if (navigate(slot, list.size())) return;
        int index = page * PAGE + slot;
        if (slot < PAGE && index < list.size()) {
            Pokemon pokemon = list.get(index);
            if (checkPokemonUsable(pokemon)) {
                bet = new PokemonBet(pokemon.getUuid(), true);
                view = View.MAIN;
            }
        }
    }

    private void clickInventory(int slot, boolean right) {
        if (slot == S_BACK) {
            view = View.MAIN;
            return;
        }
        if (slot >= 0 && slot < 36) {
            int invSlot = inventoryIndex(slot);
            ItemStack stack = player.getInventory().getItem(invSlot);
            if (stack.isEmpty()) return;
            if (Values.isItemBlacklisted(stack.getItem())) {
                notice = "That item can't be used in the upgrader.";
                return;
            }
            int amount = right ? 1 : stack.getCount();
            bet = new ItemBet(invSlot, stack.getItem(), amount);
            view = View.MAIN;
        }
    }

    private void clickSpecies(int slot, boolean shift) {
        List<Species> list = species();
        if (navigate(slot, list.size())) return;
        int index = page * PAGE + slot;
        if (slot < PAGE && index < list.size()) {
            UpgraderConfig c = UpgraderConfig.get();
            int level = target instanceof PokemonTarget t ? t.level() : Math.max(c.minTargetLevel, Math.min(c.maxTargetLevel, 50));
            boolean shiny = shift && c.allowShinyTargets;
            target = new PokemonTarget(list.get(index), level, shiny);
            view = View.MAIN;
        }
    }

    private void clickItems(int slot) {
        List<Item> list = items();
        if (navigate(slot, list.size())) return;
        int index = page * PAGE + slot;
        if (slot < PAGE && index < list.size()) {
            target = new ItemTarget(list.get(index), 1);
            view = View.MAIN;
        }
    }

    /** Handles the bottom navigation row of picker screens. Returns true if the click was a navigation button. */
    private boolean navigate(int slot, int count) {
        int pages = Math.max(1, (count + PAGE - 1) / PAGE);
        switch (slot) {
            case S_BACK -> view = View.MAIN;
            case S_PREV -> page = Math.max(0, page - 1);
            case S_NEXT -> page = Math.min(pages - 1, page + 1);
            case S_PREV10 -> page = Math.max(0, page - 10);
            case S_NEXT10 -> page = Math.min(pages - 1, page + 10);
            default -> {
                return slot >= PAGE;
            }
        }
        return true;
    }

    // ---- Upgrade -------------------------------------------------------------------------

    private void pressUpgrade(boolean wasConfirming) {
        UpgraderConfig c = UpgraderConfig.get();
        String problem = problem();
        if (problem != null) {
            notice = problem;
            playSound(SoundEvents.VILLAGER_NO, 0.6f, 1.0f);
            return;
        }
        long wait = CobbleUpgrader.cooldownLeftMs(player);
        if (wait > 0) {
            notice = "Wait " + (wait / 1000 + 1) + "s before the next upgrade.";
            return;
        }
        if (bet instanceof PokemonBet && c.confirmPokemonBets && !wasConfirming) {
            confirming = true;
            return;
        }
        startSpin();
    }

    /** Why the current upgrade can't run, or null if it can. */
    private String problem() {
        if (bet == null) return "Choose what to bet first.";
        if (target == null) return "Choose what you want to win.";
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) return "You can't do this during a battle.";
        if (bet instanceof PokemonBet b) {
            Pokemon pokemon = findPokemon(b);
            if (pokemon == null) return "That Pokémon is no longer in your " + (b.fromPc() ? "PC." : "party.");
            String reason = pokemonUnusable(pokemon);
            if (reason != null) return reason;
        } else if (bet instanceof ItemBet b) {
            ItemStack stack = player.getInventory().getItem(b.slot());
            if (!stack.is(b.item()) || stack.getCount() < b.amount()) return "Those items are no longer in your inventory.";
        }
        if (target instanceof PokemonTarget t && Values.isSpeciesBlacklisted(t.species())) return "That Pokémon can't be won.";
        if (target instanceof ItemTarget t && Values.isItemBlacklisted(t.item())) return "That item can't be won.";
        double betValue = betValue();
        double targetValue = targetValue();
        if (Values.chanceTooLow(betValue, targetValue)) {
            return "Target is too valuable for this bet (below " + Values.percent(UpgraderConfig.get().minChance) + ").";
        }
        return null;
    }

    private void startSpin() {
        double betValue = betValue();
        double targetValue = targetValue();
        double chance = Values.chance(betValue, targetValue);
        ItemStack betIcon = betIcon();

        // Take the bet. From here on the outcome is fixed: closing the screen just skips the animation.
        if (bet instanceof PokemonBet b) {
            Pokemon pokemon = findPokemon(b);
            if (pokemon == null) return;
            if (pokemon.getEntity() != null) {
                pokemon.recall();
            }
            boolean removed = b.fromPc() ? pc().remove(pokemon) : party().remove(pokemon);
            if (!removed) {
                notice = "Couldn't take that Pokémon, nothing was lost.";
                return;
            }
            CobbleUpgrader.LOGGER.info("{} bet {} (lv {}, shiny {}, value {}) for {} at {}",
                    player.getGameProfile().getName(), pokemon.getSpecies().getName(), pokemon.getLevel(),
                    pokemon.getShiny(), betValue, describe(target), Values.percent(chance));
        } else if (bet instanceof ItemBet b) {
            player.getInventory().getItem(b.slot()).shrink(b.amount());
            player.getInventory().setChanged();
        }

        won = player.getRandom().nextDouble() < chance;
        spinTarget = target;
        spinBetIcon = betIcon;
        spinChance = chance;
        rewardGiven = false;
        bet = null;
        CobbleUpgrader.markSpin(player);

        winCells = (int) Math.round(chance * TRACK);
        winCells = Math.max(1, Math.min(TRACK - 1, winCells));
        int landing = won
                ? player.getRandom().nextInt(winCells)
                : winCells + player.getRandom().nextInt(TRACK - winCells);
        step = 0;
        totalSteps = LAPS * TRACK + landing;
        stepTimer = 1;
        resultTicks = 50;
        spinning = true;
        view = View.SPIN;
    }

    private void land() {
        giveReward();
        if (won) {
            playSound(SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f);
            player.sendSystemMessage(Component.literal("Upgrade succeeded! You won ").withStyle(ChatFormatting.GREEN)
                    .append(targetName(spinTarget).copy().withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(" (" + Values.percent(spinChance) + ")").withStyle(ChatFormatting.GRAY)));
        } else {
            playSound(SoundEvents.ANVIL_LAND, 0.7f, 0.8f);
            player.sendSystemMessage(Component.literal("Upgrade failed. Better luck next time.").withStyle(ChatFormatting.RED));
        }
        renderSpin();
    }

    private void giveReward() {
        if (rewardGiven) return;
        rewardGiven = true;
        if (!won || spinTarget == null) return;
        if (spinTarget instanceof PokemonTarget t) {
            PokemonProperties properties = new PokemonProperties();
            properties.setSpecies(t.species().getResourceIdentifier().toString());
            properties.setLevel(t.level());
            properties.setShiny(t.shiny());
            Pokemon pokemon = properties.create();
            // Adds to the party, or to the PC when the party is full.
            party().add(pokemon);
            CobbleUpgrader.LOGGER.info("{} won {}", player.getGameProfile().getName(), describe(t));
        } else if (spinTarget instanceof ItemTarget t) {
            ItemStack stack = new ItemStack(t.item(), t.count());
            if (!player.getInventory().add(stack) || !stack.isEmpty()) {
                player.drop(stack, false);
            }
        }
    }

    // ---- Lookups -------------------------------------------------------------------------

    private PlayerPartyStore party() {
        return Cobblemon.INSTANCE.getStorage().getParty(player);
    }

    private PCStore pc() {
        return Cobblemon.INSTANCE.getStorage().getPC(player);
    }

    private Pokemon findPokemon(PokemonBet b) {
        Iterable<Pokemon> store = b.fromPc() ? pc() : party();
        for (Pokemon pokemon : store) {
            if (pokemon != null && pokemon.getUuid().equals(b.uuid())) return pokemon;
        }
        return null;
    }

    private boolean checkPokemonUsable(Pokemon pokemon) {
        String reason = pokemonUnusable(pokemon);
        if (reason != null) {
            notice = reason;
            return false;
        }
        return true;
    }

    private static String pokemonUnusable(Pokemon pokemon) {
        if (!pokemon.getTradeable()) return "That Pokémon is marked as untradeable.";
        if (pokemon.getTetheringId() != null) return "Take that Pokémon out of its pasture first.";
        if (Values.isSpeciesBlacklisted(pokemon.getSpecies())) return "That Pokémon can't be used in the upgrader.";
        return null;
    }

    private List<Pokemon> pcList() {
        if (pcCache == null) {
            pcCache = new ArrayList<>();
            for (Pokemon pokemon : pc()) {
                if (pokemon != null) pcCache.add(pokemon);
            }
        }
        return pcCache;
    }

    private List<Species> species() {
        if (speciesCache == null) speciesCache = Values.targetSpecies();
        return speciesCache;
    }

    private List<Item> items() {
        if (itemCache == null) itemCache = Values.targetItems();
        return itemCache;
    }

    private double betValue() {
        if (bet instanceof PokemonBet b) {
            Pokemon pokemon = findPokemon(b);
            return pokemon == null ? 0 : Values.pokemonValue(pokemon);
        }
        if (bet instanceof ItemBet b) {
            return Values.itemValue(b.item()) * b.amount();
        }
        return 0;
    }

    private double targetValue() {
        return targetValue(target);
    }

    private static double targetValue(Target target) {
        if (target instanceof PokemonTarget t) return Values.targetPokemonValue(t.species(), t.level(), t.shiny());
        if (target instanceof ItemTarget t) return Values.itemValue(t.item()) * t.count();
        return 0;
    }

    /** Menu slots 0-26 show the main inventory, 27-35 the hotbar (like the real inventory screen). */
    private static int inventoryIndex(int menuSlot) {
        return menuSlot < 27 ? menuSlot + 9 : menuSlot - 27;
    }

    // ---- Rendering -----------------------------------------------------------------------

    private void render() {
        for (int i = 0; i < SIZE; i++) gui.setItem(i, ItemStack.EMPTY);
        switch (view) {
            case MAIN -> renderMain();
            case PARTY -> renderParty();
            case PC -> renderPc();
            case INVENTORY -> renderInventory();
            case SPECIES -> renderSpecies();
            case ITEMS -> renderItems();
            case SPIN -> renderSpin();
        }
    }

    private void renderMain() {
        UpgraderConfig c = UpgraderConfig.get();
        fill(Items.BLACK_STAINED_GLASS_PANE);
        gui.setItem(S_INFO, button(Items.BOOK, gold("How it works"),
                gray("1. Pick something to bet: a Pokémon or items."),
                gray("2. Pick what you want to win."),
                gray("3. Press UPGRADE and watch the wheel."),
                gray(""),
                gray("Your odds = value of your bet / value of the target"),
                gray("x " + Values.format(c.houseEdge) + " house edge, capped at " + Values.percent(c.maxChance) + "."),
                red("Win or lose, your bet is used up.")));

        gui.setItem(S_FROM_PARTY, c.allowPokemonBets
                ? button(cobblemonItem("poke_ball", Items.ENDER_PEARL), aqua("Bet a Pokémon from your party"))
                : disabled("Pokémon bets are turned off"));
        gui.setItem(S_FROM_PC, c.allowPokemonBets
                ? button(cobblemonItem("pc", Items.ENDER_CHEST), aqua("Bet a Pokémon from your PC"))
                : disabled("Pokémon bets are turned off"));
        gui.setItem(S_FROM_INV, c.allowItemBets
                ? button(Items.CHEST, aqua("Bet items from your inventory"))
                : disabled("Item bets are turned off"));
        gui.setItem(S_PICK_POKEMON, c.allowPokemonTargets
                ? button(cobblemonItem("great_ball", Items.EGG), green("Win a Pokémon"), gray("Shift-click a species for a shiny."))
                : disabled("Pokémon prizes are turned off"));
        gui.setItem(S_PICK_ITEM, c.allowItemTargets
                ? button(Items.DIAMOND, green("Win an item"))
                : disabled("Item prizes are turned off"));
        gui.setItem(S_CLEAR, button(Items.BARRIER, red("Clear bet and target")));

        // Bet
        double betValue = betValue();
        if (bet == null) {
            gui.setItem(S_BET, button(Items.LIGHT_GRAY_STAINED_GLASS_PANE, white("Your bet: nothing yet"),
                    gray("Use the buttons above.")));
        } else {
            ItemStack icon = betIcon();
            List<Component> lore = new ArrayList<>(lore(icon));
            lore.add(yellow("Value: " + Values.format(betValue)));
            lore.add(gray("Click to remove."));
            setLore(icon, lore);
            gui.setItem(S_BET, icon);
        }
        if (bet instanceof ItemBet b) {
            gui.setItem(S_BET_M10, button(Items.RED_STAINED_GLASS_PANE, white("-10 items"), gray("Shift: -50")));
            gui.setItem(S_BET_M1, button(Items.PINK_STAINED_GLASS_PANE, white("-1 item"), gray("Shift: -5")));
            gui.setItem(S_BET_P1, button(Items.LIME_STAINED_GLASS_PANE, white("+1 item"), gray("Shift: +5")));
            gui.setItem(S_BET_P10, button(Items.GREEN_STAINED_GLASS_PANE, white("+10 items"), gray("Shift: +50")));
            gui.getItem(S_BET).setCount(Math.max(1, Math.min(99, b.amount())));
        }

        // Target
        double targetValue = targetValue();
        if (target == null) {
            gui.setItem(S_TARGET, button(Items.LIGHT_GRAY_STAINED_GLASS_PANE, white("Target: nothing yet"),
                    gray("Use the buttons above.")));
        } else {
            ItemStack icon = targetIcon(target);
            List<Component> lore = new ArrayList<>(lore(icon));
            lore.add(yellow("Value: " + Values.format(targetValue)));
            lore.add(gray("Click to remove."));
            setLore(icon, lore);
            gui.setItem(S_TARGET, icon);
            String unit = target instanceof PokemonTarget ? "level" : "item";
            String units = target instanceof PokemonTarget ? "levels" : "items";
            gui.setItem(S_TGT_M10, button(Items.RED_STAINED_GLASS_PANE, white("-10 " + units), gray("Shift: -50")));
            gui.setItem(S_TGT_M1, button(Items.PINK_STAINED_GLASS_PANE, white("-1 " + unit), gray("Shift: -5")));
            gui.setItem(S_TGT_P1, button(Items.LIME_STAINED_GLASS_PANE, white("+1 " + unit), gray("Shift: +5")));
            gui.setItem(S_TGT_P10, button(Items.GREEN_STAINED_GLASS_PANE, white("+10 " + units), gray("Shift: +50")));
            if (target instanceof PokemonTarget t && c.allowShinyTargets) {
                ItemStack shiny = button(Items.NETHER_STAR, t.shiny() ? gold("Shiny: ON") : white("Shiny: OFF"),
                        gray("Shiny Pokémon are worth x" + Values.format(c.shinyMultiplier) + "."));
                if (t.shiny()) shiny.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                gui.setItem(S_SHINY, shiny);
            }
            if (target instanceof ItemTarget t) {
                gui.getItem(S_TARGET).setCount(Math.max(1, Math.min(99, t.count())));
            }
        }

        // Chance
        if (bet != null && target != null) {
            double chance = Values.chance(betValue, targetValue);
            boolean tooLow = Values.chanceTooLow(betValue, targetValue);
            gui.setItem(S_CHANCE, button(tooLow ? Items.RED_DYE : chance >= 0.5 ? Items.LIME_DYE : chance >= 0.1 ? Items.YELLOW_DYE : Items.ORANGE_DYE,
                    tooLow ? red("Chance: too low") : gold("Chance: " + Values.percent(chance)),
                    gray(Values.format(betValue) + " vs " + Values.format(targetValue))));
        } else {
            gui.setItem(S_CHANCE, button(Items.SPECTRAL_ARROW, white("→"), gray("Pick a bet and a target to see your chance.")));
        }

        // Upgrade button
        if (confirming) {
            ItemStack confirm = button(Items.TNT, red("Click again to confirm"),
                    yellow("If you lose, this Pokémon is gone for good."),
                    gray("Click anything else to cancel."));
            gui.setItem(S_UPGRADE, confirm);
        } else {
            String problem = problem();
            long wait = CobbleUpgrader.cooldownLeftMs(player);
            ItemStack up = problem == null && wait <= 0
                    ? button(Items.EMERALD_BLOCK, green("UPGRADE"), gray("Click to spin the wheel."))
                    : button(Items.REDSTONE_BLOCK, red("UPGRADE"), gray(problem != null ? problem : "Cooling down..."));
            gui.setItem(S_UPGRADE, up);
        }
        if (notice != null) {
            gui.setItem(S_UPGRADE - 9, button(Items.PAPER, yellow(notice)));
        }
        gui.setItem(S_CLOSE, button(Items.OAK_DOOR, white("Close")));
    }

    private void renderParty() {
        fill(Items.GRAY_STAINED_GLASS_PANE);
        PlayerPartyStore party = party();
        for (int i = 0; i < 6; i++) {
            Pokemon pokemon = party.get(i);
            gui.setItem(i, pokemon == null
                    ? button(Items.LIGHT_GRAY_STAINED_GLASS_PANE, gray("Empty slot"))
                    : pokemonIcon(pokemon, true));
        }
        gui.setItem(S_BACK, button(Items.ARROW, white("Back")));
        noticeRow();
    }

    private void renderPc() {
        List<Pokemon> list = pcList();
        for (int i = 0; i < PAGE; i++) {
            int index = page * PAGE + i;
            if (index < list.size()) gui.setItem(i, pokemonIcon(list.get(index), true));
        }
        navRow(list.size(), list.isEmpty() ? "Your PC is empty." : null);
    }

    private void renderInventory() {
        fill(Items.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(inventoryIndex(i)).copy();
            if (stack.isEmpty()) {
                gui.setItem(i, ItemStack.EMPTY);
                continue;
            }
            List<Component> lore = new ArrayList<>(lore(stack));
            if (Values.isItemBlacklisted(stack.getItem())) {
                lore.add(red("Can't be used in the upgrader."));
            } else {
                lore.add(yellow("Value: " + Values.format(Values.itemValue(stack.getItem())) + " each"));
                lore.add(gray("Left-click: bet the whole stack"));
                lore.add(gray("Right-click: bet one"));
            }
            setLore(stack, lore);
            gui.setItem(i, stack);
        }
        gui.setItem(S_BACK, button(Items.ARROW, white("Back")));
        noticeRow();
    }

    private void renderSpecies() {
        List<Species> list = species();
        UpgraderConfig c = UpgraderConfig.get();
        int level = target instanceof PokemonTarget t ? t.level() : Math.max(c.minTargetLevel, Math.min(c.maxTargetLevel, 50));
        for (int i = 0; i < PAGE; i++) {
            int index = page * PAGE + i;
            if (index >= list.size()) break;
            Species species = list.get(index);
            ItemStack icon = PokemonItem.from(species, Collections.emptySet(), 1, null);
            setName(icon, species.getTranslatedName().copy().withStyle(plain(ChatFormatting.WHITE)));
            List<Component> lore = new ArrayList<>();
            lore.add(gray("#" + species.getNationalPokedexNumber()));
            lore.add(yellow("Value at lv " + level + ": " + Values.format(Values.targetPokemonValue(species, level, false))));
            if (c.allowShinyTargets) lore.add(gray("Shift-click for a shiny."));
            setLore(icon, lore);
            gui.setItem(i, icon);
        }
        navRow(list.size(), null);
    }

    private void renderItems() {
        List<Item> list = items();
        for (int i = 0; i < PAGE; i++) {
            int index = page * PAGE + i;
            if (index >= list.size()) break;
            Item item = list.get(index);
            ItemStack icon = new ItemStack(item);
            setLore(icon, List.of(yellow("Value: " + Values.format(Values.itemValue(item))), gray("Click to pick.")));
            gui.setItem(i, icon);
        }
        navRow(list.size(), list.isEmpty() ? "No prizes are set up in the config." : null);
    }

    private void renderSpin() {
        for (int i = 0; i < SIZE; i++) gui.setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < 9; i++) gui.setItem(i, filler(Items.BLACK_STAINED_GLASS_PANE));
        for (int i = 36; i < SIZE; i++) gui.setItem(i, filler(Items.BLACK_STAINED_GLASS_PANE));
        gui.setItem(2, spinBetIcon.copy());
        gui.setItem(4, button(Items.CLOCK, gold("Chance: " + Values.percent(spinChance))));
        if (spinTarget != null) gui.setItem(6, targetIcon(spinTarget));

        int pointer = step % TRACK;
        boolean landed = step >= totalSteps;
        for (int i = 0; i < TRACK; i++) {
            boolean winCell = i < winCells;
            ItemStack cell;
            if (i == pointer) {
                cell = landed
                        ? button(winCell ? Items.EMERALD_BLOCK : Items.REDSTONE_BLOCK, winCell ? green("WIN!") : red("LOSE"))
                        : button(winCell ? Items.LIME_CONCRETE : Items.RED_CONCRETE, white("▼"));
                cell.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            } else {
                cell = filler(winCell ? Items.LIME_STAINED_GLASS_PANE : Items.RED_STAINED_GLASS_PANE);
            }
            gui.setItem(TRACK_START + i, cell);
        }
        gui.setItem(49, landed
                ? button(won ? Items.EMERALD : Items.BARRIER, won ? green("You won!") : red("You lost"))
                : button(Items.HOPPER, yellow("Spinning...")));
    }

    private void navRow(int count, String empty) {
        int pages = Math.max(1, (count + PAGE - 1) / PAGE);
        for (int i = PAGE; i < SIZE; i++) gui.setItem(i, filler(Items.BLACK_STAINED_GLASS_PANE));
        gui.setItem(S_BACK, button(Items.ARROW, white("Back")));
        if (page > 0) gui.setItem(S_PREV, button(Items.SPECTRAL_ARROW, white("Previous page")));
        if (page >= 10) gui.setItem(S_PREV10, button(Items.SPECTRAL_ARROW, white("Back 10 pages")));
        if (page < pages - 1) gui.setItem(S_NEXT, button(Items.SPECTRAL_ARROW, white("Next page")));
        if (page < pages - 10) gui.setItem(S_NEXT10, button(Items.SPECTRAL_ARROW, white("Forward 10 pages")));
        gui.setItem(S_PAGE, button(Items.PAPER, white("Page " + (page + 1) + " of " + pages),
                gray(empty != null ? empty : count + " total")));
        if (notice != null) gui.setItem(S_PAGE + 2, button(Items.PAPER, yellow(notice)));
    }

    private void noticeRow() {
        if (notice != null) gui.setItem(S_PAGE, button(Items.PAPER, yellow(notice)));
    }

    // ---- Icons ---------------------------------------------------------------------------

    private ItemStack betIcon() {
        if (bet instanceof PokemonBet b) {
            Pokemon pokemon = findPokemon(b);
            return pokemon == null ? button(Items.BARRIER, red("Missing Pokémon")) : pokemonIcon(pokemon, false);
        }
        if (bet instanceof ItemBet b) {
            ItemStack stack = player.getInventory().getItem(b.slot()).copy();
            if (!stack.is(b.item())) stack = new ItemStack(b.item());
            stack.setCount(Math.max(1, Math.min(99, b.amount())));
            setLore(stack, List.of(white("x" + b.amount())));
            return stack;
        }
        return ItemStack.EMPTY;
    }

    private ItemStack pokemonIcon(Pokemon pokemon, boolean pickable) {
        ItemStack icon = PokemonItem.from(pokemon);
        MutableComponent name = pokemon.getSpecies().getTranslatedName().copy()
                .withStyle(plain(pokemon.getShiny() ? ChatFormatting.GOLD : ChatFormatting.WHITE));
        if (pokemon.getShiny()) name.append(Component.literal(" ★").withStyle(plain(ChatFormatting.GOLD)));
        setName(icon, name);
        List<Component> lore = new ArrayList<>();
        lore.add(gray("Level " + pokemon.getLevel()));
        lore.add(gray("IVs: " + Values.ivTotal(pokemon) + "/186" + (Values.isPerfectIv(pokemon) ? " (perfect!)" : "")));
        if (Values.isLegendaryLike(pokemon.getSpecies())) lore.add(gold("Legendary"));
        if (!pokemon.heldItem().isEmpty()) {
            lore.add(red("Holding " + pokemon.heldItem().getHoverName().getString() + " (it goes with it)"));
        }
        if (pickable) {
            String reason = pokemonUnusable(pokemon);
            lore.add(yellow("Value: " + Values.format(Values.pokemonValue(pokemon))));
            lore.add(reason != null ? red(reason) : gray("Click to bet this Pokémon."));
        }
        setLore(icon, lore);
        return icon;
    }

    private static ItemStack targetIcon(Target target) {
        if (target instanceof PokemonTarget t) {
            Set<String> aspects = t.shiny() ? Set.of("shiny") : Collections.emptySet();
            ItemStack icon = PokemonItem.from(t.species(), aspects, 1, null);
            setName(icon, targetName(t).copy().withStyle(plain(t.shiny() ? ChatFormatting.GOLD : ChatFormatting.WHITE)));
            setLore(icon, List.of(gray("Level " + t.level()), gray("IVs are rolled randomly if you win.")));
            if (t.shiny()) icon.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            return icon;
        }
        if (target instanceof ItemTarget t) {
            ItemStack icon = new ItemStack(t.item());
            icon.setCount(Math.max(1, Math.min(99, t.count())));
            setLore(icon, List.of(white("x" + t.count())));
            return icon;
        }
        return ItemStack.EMPTY;
    }

    private static Component targetName(Target target) {
        if (target instanceof PokemonTarget t) {
            MutableComponent name = Component.empty();
            if (t.shiny()) name.append("Shiny ");
            return name.append(t.species().getTranslatedName()).append(" (lv " + t.level() + ")");
        }
        if (target instanceof ItemTarget t) {
            return Component.literal(t.count() + "x ").append(new ItemStack(t.item()).getHoverName());
        }
        return Component.literal("nothing");
    }

    private static String describe(Target target) {
        return targetName(target).getString();
    }

    private static Item cobblemonItem(String path, Item fallback) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cobblemon", path);
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)
                ? net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id)
                : fallback;
    }

    private void fill(Item pane) {
        for (int i = 0; i < SIZE; i++) {
            if (gui.getItem(i).isEmpty()) gui.setItem(i, filler(pane));
        }
    }

    private static ItemStack filler(Item pane) {
        ItemStack stack = new ItemStack(pane);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        stack.set(DataComponents.HIDE_TOOLTIP, net.minecraft.util.Unit.INSTANCE);
        return stack;
    }

    private static ItemStack disabled(String text) {
        return button(Items.GRAY_DYE, gray(text));
    }

    private static ItemStack button(Item item, Component name, Component... lore) {
        ItemStack stack = new ItemStack(item);
        setName(stack, name);
        if (lore.length > 0) setLore(stack, List.of(lore));
        return stack;
    }

    private static void setName(ItemStack stack, Component name) {
        stack.set(DataComponents.CUSTOM_NAME, name);
    }

    private static void setLore(ItemStack stack, List<Component> lines) {
        stack.set(DataComponents.LORE, new ItemLore(List.copyOf(lines)));
    }

    private static List<Component> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        return lore == null ? List.of() : lore.lines();
    }

    private void playSound(SoundEvent sound, float volume, float pitch) {
        player.playNotifySound(sound, SoundSource.MASTER, volume, pitch);
    }

    private static Style plain(ChatFormatting color) {
        return Style.EMPTY.withItalic(false).withColor(color);
    }

    private static Component text(String s, ChatFormatting color) {
        return Component.literal(s).withStyle(plain(color));
    }

    private static Component gray(String s) { return text(s, ChatFormatting.GRAY); }
    private static Component white(String s) { return text(s, ChatFormatting.WHITE); }
    private static Component gold(String s) { return text(s, ChatFormatting.GOLD); }
    private static Component green(String s) { return text(s, ChatFormatting.GREEN); }
    private static Component red(String s) { return text(s, ChatFormatting.RED); }
    private static Component aqua(String s) { return text(s, ChatFormatting.AQUA); }
    private static Component yellow(String s) { return text(s, ChatFormatting.YELLOW); }

    // ---- Command support -----------------------------------------------------------------

    /** Used by /upgrader target, to pick a Pokémon by name instead of scrolling. */
    public void setPokemonTarget(Species species, int level, boolean shiny) {
        if (spinning) return;
        UpgraderConfig c = UpgraderConfig.get();
        level = Math.max(c.minTargetLevel, Math.min(c.maxTargetLevel, level));
        target = new PokemonTarget(species, level, shiny && c.allowShinyTargets);
        view = View.MAIN;
        render();
        broadcastChanges();
    }
}
