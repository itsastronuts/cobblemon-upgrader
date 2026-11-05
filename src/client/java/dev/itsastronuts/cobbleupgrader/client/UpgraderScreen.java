package dev.itsastronuts.cobbleupgrader.client;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.Species;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import dev.itsastronuts.cobbleupgrader.Values;
import dev.itsastronuts.cobbleupgrader.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The upgrader screen: your Pokémon on the left, the chance dial in the middle, the prize on the right. */
public class UpgraderScreen extends Screen {
    // Window
    private static final int W = 300, H = 232;
    // Colours
    private static final int FRAME = 0xF0121620, FRAME_EDGE = 0xFF3A4357, FRAME_HI = 0xFF262D3D;
    private static final int PANEL_EDGE = 0xFF353D52, SLOT = 0xFF1A1F2B, SLOT_EDGE = 0xFF2E3547;
    private static final int GOLD = 0xFFF5B83D, GOLD_DARK = 0xFFC9851E, GOLD_TEXT = 0xFFFFD36B;
    private static final int TEXT = 0xFFE8EAF0, MUTED = 0xFF8A93A8, GREEN = 0xFF6BE08A, RED = 0xFFFF6B6B;
    private static final int SPIN_LAPS = 5;

    private Payloads.Data data;
    private final Map<String, Species> speciesById = new HashMap<>();
    private final Map<String, ItemStack> iconCache = new HashMap<>();

    // Selection
    private UUID bet;
    private int targetKind = -1; // -1 none, 0 Pokémon, 1 item
    private String targetId;
    private int targetAmount = 1;
    private boolean targetShiny;
    private boolean confirming;

    // Spin animation
    private boolean spinning;
    private long spinStart;
    private long spinMillis;
    private float spinTotal;
    private float lastTickAngle;
    private double spinChance;
    private Boolean resultWon;
    private long resultAt;
    private UUID spinBet;
    private ItemStack spinBetIcon = ItemStack.EMPTY;

    // Messages
    private String message;
    private int messageColor;
    private long messageAt;

    // Catalog overlay
    private boolean catalogOpen;
    private int catalogTab; // 0 Pokémon, 1 items
    private int catalogScroll;
    private EditBox search;
    private String searchText = "";
    private List<String> filtered = List.of();

    private boolean draggingSlider;
    private int left, top;

    public UpgraderScreen(Payloads.Data data) {
        super(Component.literal("Pokémon Upgrader"));
        this.data = data;
    }

    // ---- Updates from the server ---------------------------------------------------------

    public void updateData(Payloads.Data newData) {
        this.data = newData;
        if (bet != null && findMon(bet) == null) bet = null;
        refilter();
    }

    public void onSpin(Payloads.Spin spin) {
        spinning = true;
        spinStart = System.currentTimeMillis();
        spinMillis = spin.ticks() * 50L;
        spinTotal = 360f * SPIN_LAPS + spin.landingDegrees();
        lastTickAngle = 0;
        spinChance = spin.chance();
        resultWon = null;
        spinBet = bet;
        Payloads.PartyMon mon = bet == null ? null : findMon(bet);
        spinBetIcon = mon == null ? ItemStack.EMPTY : monIcon(mon);
        bet = null;
        confirming = false;
        catalogOpen = false;
    }

    public void onResult(Payloads.Result result) {
        spinning = false;
        resultWon = result.won();
        resultAt = System.currentTimeMillis();
        spinBet = null;
        showMessage(result.message(), result.won() ? GREEN : RED);
    }

    public void onError(Payloads.Error error) {
        confirming = false;
        showMessage(error.message(), RED);
    }

    private void showMessage(String text, int color) {
        message = text;
        messageColor = color;
        messageAt = System.currentTimeMillis();
    }

    // ---- Screen lifecycle ----------------------------------------------------------------

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        search = new EditBox(font, left + 150, top + 26, 118, 14, Component.literal("Search"));
        search.setHint(Component.literal("Search...").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(searchText);
        search.setResponder(text -> {
            searchText = text;
            catalogScroll = 0;
            refilter();
        });
        search.setVisible(catalogOpen);
        addWidget(search);
        Textures.ensure();
        refilter();
    }

    @Override
    public void removed() {
        ClientPlayNetworking.send(Payloads.Close.INSTANCE);
        Textures.release();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- Values (same formulas as the server) --------------------------------------------

    private double betValue() {
        Payloads.PartyMon mon = bet == null ? null : findMon(bet);
        return mon == null ? 0 : mon.value();
    }

    private double targetValue() {
        if (targetKind == 0) {
            int index = data.species().indexOf(targetId);
            if (index < 0) return 0;
            double value = data.speciesBase().get(index) * (1.0 + Math.max(1, targetAmount) * data.settings().levelWeight());
            if (targetShiny) value *= data.settings().shinyMultiplier();
            return Values.round(value);
        }
        if (targetKind == 1) {
            Double price = data.items().get(targetId);
            return price == null ? 0 : price * targetAmount;
        }
        return 0;
    }

    private double rawChance() {
        double b = betValue(), t = targetValue();
        if (b <= 0 || t <= 0) return 0;
        return data.settings().houseEdge() * b / t;
    }

    private double chance() {
        return Math.min(data.settings().maxChance(), rawChance());
    }

    private boolean tooLow() {
        return rawChance() < data.settings().minChance();
    }

    private int minAmount() {
        return targetKind == 0 ? data.settings().minLevel() : 1;
    }

    private int maxAmount() {
        if (targetKind == 0) return data.settings().maxLevel();
        Item item = item(targetId);
        return item == null ? 64 : new ItemStack(item).getMaxStackSize();
    }

    private void setAmount(int amount) {
        targetAmount = Math.max(minAmount(), Math.min(maxAmount(), amount));
        confirming = false;
    }

    /** Sets the level / amount so the target is worth `desired`. */
    private void aimFor(double desired) {
        if (targetKind < 0) {
            showMessage("Pick a target first.", GOLD_TEXT);
            return;
        }
        if (bet == null) {
            showMessage("Pick a Pokémon to bet first.", GOLD_TEXT);
            return;
        }
        if (targetKind == 0) {
            int index = data.species().indexOf(targetId);
            double base = data.speciesBase().get(index) * (targetShiny ? data.settings().shinyMultiplier() : 1);
            double lw = data.settings().levelWeight();
            if (lw <= 0 || base <= 0) return;
            setAmount((int) Math.round((desired / base - 1) / lw));
        } else {
            Double price = data.items().get(targetId);
            if (price == null || price <= 0) return;
            setAmount((int) Math.round(desired / price));
        }
        double c = chance();
        if (tooLow()) showMessage("Even the lowest setting is out of reach for this bet.", RED);
        else showMessage("Chance is now " + Values.percent(c), MUTED);
    }

    // ---- Rendering -----------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        List<Component> tooltip = null;
        ItemStack tooltipStack = ItemStack.EMPTY;

        // Frame
        g.fill(left, top, left + W, top + H, FRAME);
        g.renderOutline(left, top, W, H, FRAME_EDGE);
        g.renderOutline(left + 1, top + 1, W - 2, H - 2, FRAME_HI);
        drawTitle(g);

        // While the prize picker is open, draw only the picker: the main screen's Pokémon models and text
        // would otherwise show through it.
        if (catalogOpen) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            Object hovered = drawCatalog(g, mouseX, mouseY);
            search.render(g, mouseX, mouseY, delta);
            g.pose().popPose();
            if (hovered instanceof ItemStack stack) g.renderTooltip(font, stack, mouseX, mouseY);
            else if (hovered instanceof List<?> list) {
                @SuppressWarnings("unchecked") List<Component> lines = (List<Component>) list;
                g.renderComponentTooltip(font, lines, mouseX, mouseY);
            }
            return;
        }
        int mx = mouseX, my = mouseY;

        // ---- Bet panel (left)
        int bx = left + 10, by = top + 24;
        drawPanel(g, bx, by);
        Payloads.PartyMon betMon = bet == null ? null : findMon(bet);
        if (spinning && !spinBetIcon.isEmpty()) {
            drawClipped(g, spinBetIcon, bx + 45 - 24, by + 14, 3f, bx + 1, by + 1, bx + 89, by + 66);
            g.drawCenteredString(font, "In the wheel...", bx + 45, by + 70, MUTED);
        } else if (betMon != null) {
            drawClipped(g, monIcon(betMon), bx + 45 - 24, by + 8, 3f, bx + 1, by + 1, bx + 89, by + 58);
            String name = speciesName(betMon.species());
            g.drawCenteredString(font, fit(name, 84), bx + 45, by + 60, betMon.shiny() ? GOLD_TEXT : TEXT);
            g.drawCenteredString(font, "Lv " + betMon.level(), bx + 45, by + 71, MUTED);
            g.drawCenteredString(font, "Value: " + Values.format(betMon.value()), bx + 45, by + 82, GOLD_TEXT);
            if (inside(mx, my, bx, by, 90, 96)) tooltip = monTooltip(betMon, "Click to take it back out.");
        } else {
            g.drawCenteredString(font, "Your bet", bx + 45, by + 34, TEXT);
            g.drawCenteredString(font, "Pick a Pokémon", bx + 45, by + 50, MUTED);
            g.drawCenteredString(font, "from your party", bx + 45, by + 60, MUTED);
        }

        // ---- Chance dial (middle)
        int rx = left + 102, ry = top + 24;
        double shownChance = spinning ? spinChance : (bet != null && targetKind >= 0 ? chance() : -1);
        if (resultWon != null && !spinning && System.currentTimeMillis() - resultAt < 2500) shownChance = spinChance;
        Textures.updateRing(shownChance);
        RenderSystem.enableBlend();
        g.blit(Textures.RING, rx, ry, 96, 96, 0f, 0f, Textures.RING_SIZE, Textures.RING_SIZE, Textures.RING_SIZE, Textures.RING_SIZE);
        RenderSystem.disableBlend();
        drawNeedle(g, rx + 48, ry + 48, shownChance);
        drawDialText(g, rx + 48, ry + 48);

        // ---- Target panel (right)
        int tx = left + 200, ty = top + 24;
        drawPanel(g, tx, ty);
        if (targetKind >= 0) {
            ItemStack icon = targetIcon();
            drawClipped(g, icon, tx + 45 - 16, ty + 6, 2f, tx + 1, ty + 1, tx + 75, ty + 40);
            g.drawCenteredString(font, fit(targetName(), 84), tx + 45, ty + 41, targetShiny ? GOLD_TEXT : TEXT);
            g.drawCenteredString(font, "Value: " + Values.format(targetValue()), tx + 45, ty + 51, GOLD_TEXT);
            g.drawCenteredString(font, targetKind == 0 ? "Level" : "Amount", tx + 45, ty + 62, MUTED);
            // - value +
            drawSmallButton(g, tx + 8, ty + 71, 12, 11, "-", mx, my, true);
            drawSmallButton(g, tx + 70, ty + 71, 12, 11, "+", mx, my, true);
            g.drawCenteredString(font, String.valueOf(targetAmount), tx + 45, ty + 73, GOLD_TEXT);
            // slider
            drawSlider(g, tx + 8, ty + 86, 74);
            // shiny toggle
            if (targetKind == 0 && data.settings().allowShiny()) {
                boolean hover = inside(mx, my, tx + 76, ty + 3, 11, 11);
                g.fill(tx + 76, ty + 3, tx + 87, ty + 14, targetShiny ? 0xFF5A4410 : hover ? 0xFF2E3547 : 0xFF222838);
                g.renderOutline(tx + 76, ty + 3, 11, 11, targetShiny ? GOLD : SLOT_EDGE);
                g.drawCenteredString(font, "★", tx + 82, ty + 4, targetShiny ? GOLD_TEXT : MUTED);
                if (hover) tooltip = List.of(Component.literal(targetShiny ? "Shiny: ON" : "Shiny: OFF"),
                        Component.literal("Shiny is worth x" + Values.format(data.settings().shinyMultiplier())).withStyle(ChatFormatting.GRAY));
            }
            if (tooltip == null && inside(mx, my, tx + 20, ty + 4, 50, 46)) {
                if (targetKind == 1) tooltipStack = icon;
                else tooltip = List.of(Component.literal(targetName()), Component.literal("Click to choose another").withStyle(ChatFormatting.GRAY));
            }
        } else {
            g.drawCenteredString(font, "Prize", tx + 45, ty + 34, TEXT);
            g.drawCenteredString(font, "Click to choose", tx + 45, ty + 50, MUTED);
            g.drawCenteredString(font, "what to win", tx + 45, ty + 60, MUTED);
        }

        // ---- Upgrade button and presets
        drawUpgradeButton(g, left + 10, top + 128, 136, 20, mx, my);
        String[] labels = {"x2", "x4", "x8", "30%", "50%", "70%"};
        int[] colors = {TEXT, TEXT, TEXT, 0xFFFF7A7A, 0xFFFFD36B, 0xFF7CE38F};
        for (int i = 0; i < 6; i++) {
            int x = left + 151 + i * 23;
            boolean hover = inside(mx, my, x, top + 128, 21, 20);
            g.fill(x, top + 128, x + 21, top + 148, hover ? 0xFF2C3446 : 0xFF202636);
            g.renderOutline(x, top + 128, 21, 20, hover ? 0xFF4A556E : SLOT_EDGE);
            g.drawCenteredString(font, labels[i], x + 11, top + 134, colors[i]);
            if (hover) tooltip = List.of(Component.literal(i < 3
                    ? "Set the prize to " + labels[i] + " the value of your bet"
                    : "Set the prize so your chance is about " + labels[i]));
        }

        // ---- Party
        g.drawString(font, "Your party", left + 10, top + 156, MUTED, false);
        for (int slot = 0; slot < 6; slot++) {
            int sx = left + 9 + slot * 46, sy = top + 167;
            Payloads.PartyMon mon = monInSlot(slot);
            boolean selected = mon != null && mon.uuid().equals(bet);
            boolean hover = inside(mx, my, sx, sy, 42, 42);
            boolean blocked = mon != null && !mon.blocked().isEmpty();
            g.fill(sx, sy, sx + 42, sy + 42, selected ? 0xFF3A2E12 : hover && mon != null ? 0xFF242B3B : SLOT);
            g.renderOutline(sx, sy, 42, 42, selected ? GOLD : blocked ? 0xFF5A2A30 : SLOT_EDGE);
            if (mon != null) {
                drawClipped(g, monIcon(mon), sx + 5, sy + 3, 2f, sx + 1, sy + 1, sx + 41, sy + 41);
                g.drawString(font, "Lv" + mon.level(), sx + 3, sy + 32, blocked ? 0xFF9A6A6A : TEXT, true);
                if (mon.shiny()) g.drawString(font, "★", sx + 34, sy + 3, GOLD_TEXT, true);
                if (blocked) g.fill(sx + 1, sy + 1, sx + 41, sy + 41, 0x70100808);
                if (hover) tooltip = monTooltip(mon, blocked ? null : selected ? "Selected" : "Click to bet this Pokémon");
            }
        }

        // ---- Message line
        if (message != null && System.currentTimeMillis() - messageAt < 4000) {
            g.drawCenteredString(font, fit(message, W - 20), left + W / 2, top + H - 12, messageColor);
        }

        if (!tooltipStack.isEmpty()) g.renderTooltip(font, tooltipStack, mouseX, mouseY);
        else if (tooltip != null) g.renderComponentTooltip(font, tooltip, mouseX, mouseY);

        tickSpinSounds();
    }

    private void drawTitle(GuiGraphics g) {
        String title = "POKÉMON UPGRADER";
        g.pose().pushPose();
        g.pose().translate(left + W / 2f, top + 7, 0);
        g.pose().scale(1.25f, 1.25f, 1f);
        g.drawCenteredString(font, title, 0, 0, TEXT);
        g.pose().popPose();
    }

    private void drawPanel(GuiGraphics g, int x, int y) {
        g.blit(Textures.PANEL, x, y, 90, 96, 0f, 0f, Textures.PANEL_W, Textures.PANEL_H, Textures.PANEL_W, Textures.PANEL_H);
        g.renderOutline(x, y, 90, 96, PANEL_EDGE);
    }

    private float needleAngle(double chance) {
        if (spinning) {
            double t = Math.min(1, (System.currentTimeMillis() - spinStart) / (double) Math.max(1, spinMillis));
            double eased = 1 - Math.pow(1 - t, 4);
            return (float) (spinTotal * eased);
        }
        if (resultWon != null && System.currentTimeMillis() - resultAt < 2500) return spinTotal;
        return chance > 0 ? (float) (chance * 360.0) : 0f;
    }

    private void drawNeedle(GuiGraphics g, int cx, int cy, double chance) {
        if (chance < 0 && !spinning) return;
        float angle = needleAngle(chance);
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(angle));
        // needle from just outside the inner disc to past the ring
        g.fill(-1, -46, 1, -30, 0xFFFFFFFF);
        g.fill(-2, -48, 2, -45, GOLD);
        g.fill(-1, -49, 1, -48, GOLD_TEXT);
        g.pose().popPose();
    }

    private void drawDialText(GuiGraphics g, int cx, int cy) {
        long sinceResult = System.currentTimeMillis() - resultAt;
        if (spinning) {
            drawBig(g, Values.percent(spinChance), cx, cy - 9, GOLD_TEXT);
            g.drawCenteredString(font, "spinning...", cx, cy + 6, MUTED);
        } else if (resultWon != null && sinceResult < 2500) {
            drawBig(g, resultWon ? "SUCCESS!" : "FAILED", cx, cy - 6, resultWon ? GREEN : RED);
        } else if (bet != null && targetKind >= 0) {
            if (tooLow()) {
                drawBig(g, "< " + Values.percent(data.settings().minChance()), cx, cy - 9, RED);
                g.drawCenteredString(font, "too low", cx, cy + 6, MUTED);
            } else {
                double c = chance();
                int color = c >= 0.5 ? GREEN : c >= 0.2 ? GOLD_TEXT : c >= 0.05 ? 0xFFFF9A4A : RED;
                drawBig(g, Values.percent(c), cx, cy - 9, color);
                g.drawCenteredString(font, "chance", cx, cy + 6, MUTED);
            }
        } else {
            drawBig(g, "--%", cx, cy - 9, MUTED);
            g.drawCenteredString(font, "chance", cx, cy + 6, MUTED);
        }
    }

    private void drawBig(GuiGraphics g, String text, int cx, int y, int color) {
        float scale = font.width(text) * 1.5f > 58 ? 58f / font.width(text) : 1.5f;
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawCenteredString(font, text, 0, 0, color);
        g.pose().popPose();
    }

    private void drawSlider(GuiGraphics g, int x, int y, int w) {
        int min = minAmount(), max = maxAmount();
        double t = max <= min ? 1 : (targetAmount - min) / (double) (max - min);
        int fillTo = x + (int) Math.round(w * t);
        g.fill(x, y, x + w, y + 4, 0xFF2A3042);
        g.fillGradient(x, y, fillTo, y + 4, GOLD_TEXT, GOLD_DARK);
        g.fill(fillTo - 2, y - 2, fillTo + 2, y + 6, 0xFFFFFFFF);
        g.renderOutline(fillTo - 2, y - 2, 4, 8, 0xFF8A6A2A);
    }

    private void drawUpgradeButton(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        boolean hover = inside(mx, my, x, y, w, h);
        boolean ready = !spinning && bet != null && targetKind >= 0 && !tooLow();
        int cTop, cBottom, edge, text;
        String label;
        if (confirming) {
            cTop = 0xFFE0524F; cBottom = 0xFFA8302E; edge = 0xFFFF8A87; text = 0xFFFFFFFF;
            label = "CONFIRM? (it could be lost)";
        } else if (ready) {
            cTop = hover ? 0xFFFFD36B : GOLD; cBottom = hover ? 0xFFE09A2A : GOLD_DARK; edge = 0xFFFFE6A3; text = 0xFF2A1A05;
            label = "⇧ UPGRADE";
        } else {
            cTop = 0xFF3A3F4C; cBottom = 0xFF2C303B; edge = 0xFF4A5060; text = 0xFF8A8F9C;
            label = spinning ? "SPINNING..." : "⇧ UPGRADE";
        }
        g.fillGradient(x, y, x + w, y + h, cTop, cBottom);
        g.renderOutline(x, y, w, h, edge);
        // Dark text on the gold button reads better without the drop shadow
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, text, !ready || confirming);
    }

    private void drawSmallButton(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, boolean enabled) {
        boolean hover = enabled && inside(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, hover ? 0xFF3A4357 : 0xFF262D3D);
        g.renderOutline(x, y, w, h, hover ? 0xFF5A6580 : SLOT_EDGE);
        g.drawCenteredString(font, label, x + w / 2 + 1, y + 2, enabled ? TEXT : MUTED);
    }

    /** Draws the prize picker. Returns the hovered entry's tooltip (an ItemStack or a list of lines). */
    private Object drawCatalog(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + 6, y = top + 20, w = W - 12, h = H - 26;
        g.fill(x, y, x + w, y + h, 0xF6141822);
        g.renderOutline(x, y, w, h, FRAME_EDGE);
        // tabs
        boolean pokemonAllowed = data.settings().allowPokemonTargets(), itemsAllowed = data.settings().allowItemTargets();
        drawTab(g, x + 6, y + 6, 60, "Pokémon", catalogTab == 0, pokemonAllowed, mouseX, mouseY);
        drawTab(g, x + 70, y + 6, 50, "Items", catalogTab == 1, itemsAllowed, mouseX, mouseY);
        drawSmallButton(g, x + w - 18, y + 6, 12, 12, "x", mouseX, mouseY, true);
        // grid
        int cols = 14, rows = 8, cell = 20;
        int gx = x + (w - cols * cell) / 2, gy = y + 26;
        Object hovered = null;
        int start = catalogScroll * cols;
        for (int i = 0; i < cols * rows; i++) {
            int index = start + i;
            if (index >= filtered.size()) break;
            String id = filtered.get(index);
            int cx = gx + (i % cols) * cell, cy = gy + (i / cols) * cell;
            boolean hover = inside(mouseX, mouseY, cx, cy, cell - 2, cell - 2);
            boolean chosen = id.equals(targetId) && targetKind == catalogTab;
            g.fill(cx, cy, cx + cell - 2, cy + cell - 2, chosen ? 0xFF3A2E12 : hover ? 0xFF2C3446 : SLOT);
            if (hover || chosen) g.renderOutline(cx, cy, cell - 2, cell - 2, chosen ? GOLD : 0xFF4A556E);
            ItemStack icon = catalogTab == 0 ? speciesIcon(id, false) : new ItemStack(item(id));
            g.enableScissor(cx, cy, cx + cell - 2, cy + cell - 2);
            g.renderItem(icon, cx + 1, cy + 1);
            g.disableScissor();
            if (hover) {
                if (catalogTab == 0) {
                    int index2 = data.species().indexOf(id);
                    double lv = Math.max(minAmountFor(0), Math.min(data.settings().maxLevel(), targetKind == 0 ? targetAmount : 50));
                    double value = data.speciesBase().get(index2) * (1 + lv * data.settings().levelWeight());
                    List<Component> lines = new ArrayList<>();
                    lines.add(Component.literal(speciesName(id)));
                    lines.add(Component.literal("Value at lv " + (int) lv + ": " + Values.format(Values.round(value))).withStyle(ChatFormatting.YELLOW));
                    if (data.settings().allowShiny()) lines.add(Component.literal("Shift-click for a shiny").withStyle(ChatFormatting.GRAY));
                    hovered = lines;
                } else {
                    hovered = List.of(icon.getHoverName(),
                            Component.literal("Value: " + Values.format(data.items().getOrDefault(id, 0.0))).withStyle(ChatFormatting.YELLOW));
                }
            }
        }
        int total = (filtered.size() + cols - 1) / cols;
        if (total > rows) {
            int barX = gx + cols * cell + 1, barH = rows * cell - 2;
            g.fill(barX, gy, barX + 3, gy + barH, 0xFF222838);
            int thumbH = Math.max(10, barH * rows / total);
            int thumbY = gy + (barH - thumbH) * catalogScroll / Math.max(1, total - rows);
            g.fill(barX, thumbY, barX + 3, thumbY + thumbH, GOLD_DARK);
        }
        if (filtered.isEmpty()) g.drawCenteredString(font, "Nothing found", x + w / 2, gy + 40, MUTED);
        g.drawString(font, filtered.size() + " to choose from · scroll for more", gx, gy + rows * cell + 4, MUTED, false);
        return hovered;
    }

    private int minAmountFor(int kind) {
        return kind == 0 ? data.settings().minLevel() : 1;
    }

    private void drawTab(GuiGraphics g, int x, int y, int w, String label, boolean active, boolean enabled, int mx, int my) {
        boolean hover = enabled && inside(mx, my, x, y, w, 14);
        g.fill(x, y, x + w, y + 14, active ? 0xFF3A2E12 : hover ? 0xFF2C3446 : 0xFF202636);
        g.renderOutline(x, y, w, 14, active ? GOLD : SLOT_EDGE);
        g.drawCenteredString(font, label, x + w / 2, y + 3, !enabled ? 0xFF555B6A : active ? GOLD_TEXT : TEXT);
    }

    /** Draws a scaled item, cut off at the given box so big 3D models can't spill over other parts. */
    private static void drawClipped(GuiGraphics g, ItemStack stack, int x, int y, float scale, int x1, int y1, int x2, int y2) {
        g.enableScissor(x1, y1, x2, y2);
        drawScaledItem(g, stack, x, y, scale);
        g.disableScissor();
    }

    private static void drawScaledItem(GuiGraphics g, ItemStack stack, int x, int y, float scale) {
        if (stack.isEmpty()) return;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.renderItem(stack, 0, 0);
        g.pose().popPose();
    }

    private void tickSpinSounds() {
        if (!spinning) return;
        float angle = needleAngle(spinChance);
        if (angle - lastTickAngle >= 30f) {
            lastTickAngle = angle - (angle % 30f);
            float pitch = 0.9f + 0.6f * Math.min(1f, angle / Math.max(1f, spinTotal));
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), pitch, 0.5f));
        }
    }

    // ---- Input ---------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX, my = (int) mouseY;
        if (catalogOpen) {
            return clickCatalog(mx, my, button) || super.mouseClicked(mouseX, mouseY, button);
        }
        if (spinning) return true;
        boolean wasConfirming = confirming;
        confirming = false;

        // Bet panel: take the Pokémon back out
        if (inside(mx, my, left + 10, top + 24, 90, 96) && bet != null) {
            bet = null;
            click();
            return true;
        }
        // Target panel
        int tx = left + 200, ty = top + 24;
        if (targetKind >= 0) {
            int step = hasShiftDown() ? 10 : 1;
            if (inside(mx, my, tx + 8, ty + 71, 12, 11)) {
                setAmount(targetAmount - step);
                click();
                return true;
            }
            if (inside(mx, my, tx + 70, ty + 71, 12, 11)) {
                setAmount(targetAmount + step);
                click();
                return true;
            }
            if (inside(mx, my, tx + 6, ty + 82, 78, 12)) {
                draggingSlider = true;
                dragSlider(mx);
                return true;
            }
            if (targetKind == 0 && data.settings().allowShiny() && inside(mx, my, tx + 76, ty + 3, 11, 11)) {
                targetShiny = !targetShiny;
                click();
                return true;
            }
        }
        if (inside(mx, my, tx, ty, 90, 96)) {
            openCatalog();
            click();
            return true;
        }
        // Upgrade
        if (inside(mx, my, left + 10, top + 128, 136, 20)) {
            pressUpgrade(wasConfirming);
            return true;
        }
        // Presets
        for (int i = 0; i < 6; i++) {
            if (inside(mx, my, left + 151 + i * 23, top + 128, 21, 20)) {
                double b = betValue();
                double desired = switch (i) {
                    case 0 -> b * 2;
                    case 1 -> b * 4;
                    case 2 -> b * 8;
                    case 3 -> data.settings().houseEdge() * b / 0.30;
                    case 4 -> data.settings().houseEdge() * b / 0.50;
                    default -> data.settings().houseEdge() * b / 0.70;
                };
                aimFor(desired);
                click();
                return true;
            }
        }
        // Party
        for (int slot = 0; slot < 6; slot++) {
            if (inside(mx, my, left + 9 + slot * 46, top + 167, 42, 42)) {
                Payloads.PartyMon mon = monInSlot(slot);
                if (mon == null) return true;
                if (!mon.blocked().isEmpty()) {
                    showMessage(mon.blocked(), RED);
                } else {
                    bet = mon.uuid().equals(bet) ? null : mon.uuid();
                    click();
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void pressUpgrade(boolean wasConfirming) {
        if (spinning) return;
        if (bet == null) {
            showMessage("Pick a Pokémon from your party to bet.", GOLD_TEXT);
            return;
        }
        if (targetKind < 0) {
            showMessage("Click the prize panel to choose what to win.", GOLD_TEXT);
            return;
        }
        if (tooLow()) {
            showMessage("That prize is too valuable for this Pokémon. Lower it or bet something stronger.", RED);
            return;
        }
        if (data.settings().confirmBets() && !wasConfirming) {
            confirming = true;
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8f));
            return;
        }
        ClientPlayNetworking.send(new Payloads.Upgrade(bet, targetKind, targetId, targetAmount, targetShiny));
    }

    private boolean clickCatalog(int mx, int my, int button) {
        int x = left + 6, y = top + 20, w = W - 12;
        if (inside(mx, my, x + w - 18, y + 6, 12, 12)) {
            closeCatalog();
            return true;
        }
        if (inside(mx, my, x + 6, y + 6, 60, 14) && data.settings().allowPokemonTargets()) {
            switchTab(0);
            return true;
        }
        if (inside(mx, my, x + 70, y + 6, 50, 14) && data.settings().allowItemTargets()) {
            switchTab(1);
            return true;
        }
        int cols = 14, rows = 8, cell = 20;
        int gx = x + (w - cols * cell) / 2, gy = y + 26;
        if (inside(mx, my, gx, gy, cols * cell, rows * cell)) {
            int col = (mx - gx) / cell, row = (my - gy) / cell;
            int index = (catalogScroll + row) * cols + col;
            if (index < filtered.size()) {
                String id = filtered.get(index);
                if (catalogTab == 0) {
                    int level = targetKind == 0 ? targetAmount : Math.max(data.settings().minLevel(), Math.min(data.settings().maxLevel(), 50));
                    targetKind = 0;
                    targetId = id;
                    targetShiny = hasShiftDown() && data.settings().allowShiny();
                    targetAmount = level;
                } else {
                    targetKind = 1;
                    targetId = id;
                    targetShiny = false;
                    targetAmount = 1;
                }
                click();
                closeCatalog();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingSlider) {
            dragSlider((int) mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingSlider = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void dragSlider(int mx) {
        int x = left + 208, w = 74;
        double t = Math.max(0, Math.min(1, (mx - x) / (double) w));
        setAmount(minAmount() + (int) Math.round(t * (maxAmount() - minAmount())));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (catalogOpen) {
            int total = (filtered.size() + 13) / 14;
            catalogScroll = Math.max(0, Math.min(Math.max(0, total - 8), catalogScroll - (int) Math.signum(scrollY)));
            return true;
        }
        if (targetKind >= 0 && inside((int) mouseX, (int) mouseY, left + 200, top + 24, 90, 96)) {
            setAmount(targetAmount + (int) Math.signum(scrollY) * (hasShiftDown() ? 10 : 1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (catalogOpen && keyCode == 256) { // Escape closes the picker first
            closeCatalog();
            return true;
        }
        if (catalogOpen && search.isFocused()) {
            return search.keyPressed(keyCode, scanCode, modifiers) || true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void openCatalog() {
        catalogOpen = true;
        if (!data.settings().allowPokemonTargets()) catalogTab = 1;
        else if (!data.settings().allowItemTargets()) catalogTab = 0;
        else if (targetKind >= 0) catalogTab = targetKind;
        catalogScroll = 0;
        search.setVisible(true);
        setFocused(search);
        search.setFocused(true);
        refilter();
        scrollToTarget();
    }

    private void closeCatalog() {
        catalogOpen = false;
        search.setFocused(false);
        search.setVisible(false);
        setFocused(null);
    }

    private void switchTab(int tab) {
        catalogTab = tab;
        catalogScroll = 0;
        refilter();
        click();
    }

    private void scrollToTarget() {
        if (targetId == null || targetKind != catalogTab) return;
        int index = filtered.indexOf(targetId);
        if (index >= 0) catalogScroll = Math.max(0, index / 14 - 3);
    }

    private void refilter() {
        String q = searchText == null ? "" : searchText.toLowerCase(Locale.ROOT).trim();
        List<String> source = catalogTab == 0 ? data.species() : new ArrayList<>(data.items().keySet());
        List<String> out = new ArrayList<>();
        for (String id : source) {
            if (q.isEmpty()) {
                out.add(id);
                continue;
            }
            String name = catalogTab == 0 ? speciesName(id) : new ItemStack(item(id)).getHoverName().getString();
            if (name.toLowerCase(Locale.ROOT).contains(q) || id.toLowerCase(Locale.ROOT).contains(q)) out.add(id);
        }
        filtered = out;
    }

    private void click() {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    // ---- Lookups -------------------------------------------------------------------------

    private Payloads.PartyMon findMon(UUID uuid) {
        for (Payloads.PartyMon mon : data.party()) {
            if (mon.uuid().equals(uuid)) return mon;
        }
        return null;
    }

    private Payloads.PartyMon monInSlot(int slot) {
        for (Payloads.PartyMon mon : data.party()) {
            if (mon.slot() == slot) return mon;
        }
        return null;
    }

    private Species species(String id) {
        return speciesById.computeIfAbsent(id, key -> {
            ResourceLocation rl = ResourceLocation.tryParse(key);
            return rl == null ? null : PokemonSpecies.getByIdentifier(rl);
        });
    }

    private String speciesName(String id) {
        Species s = species(id);
        return s == null ? id : s.getTranslatedName().getString();
    }

    private ItemStack speciesIcon(String id, boolean shiny) {
        String key = id + (shiny ? "#shiny" : "");
        return iconCache.computeIfAbsent(key, k -> {
            Species s = species(id);
            if (s == null) return new ItemStack(Items.BARRIER);
            Set<String> aspects = shiny ? Set.of("shiny") : Collections.emptySet();
            return PokemonItem.from(s, aspects, 1, null);
        });
    }

    private ItemStack monIcon(Payloads.PartyMon mon) {
        String key = "mon#" + mon.uuid() + "#" + mon.species() + "#" + String.join(",", mon.aspects());
        return iconCache.computeIfAbsent(key, k -> {
            Species s = species(mon.species());
            if (s == null) return new ItemStack(Items.BARRIER);
            return PokemonItem.from(s, new HashSet<>(mon.aspects()), 1, null);
        });
    }

    private static Item item(String id) {
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
        return rl == null ? Items.BARRIER : BuiltInRegistries.ITEM.get(rl);
    }

    private ItemStack targetIcon() {
        if (targetKind == 0) return speciesIcon(targetId, targetShiny);
        ItemStack stack = new ItemStack(item(targetId));
        stack.setCount(Math.max(1, Math.min(stack.getMaxStackSize(), targetAmount)));
        return stack;
    }

    private String targetName() {
        if (targetKind == 0) return (targetShiny ? "Shiny " : "") + speciesName(targetId);
        return targetAmount + "x " + new ItemStack(item(targetId)).getHoverName().getString();
    }

    private List<Component> monTooltip(Payloads.PartyMon mon, String hint) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal((mon.shiny() ? "★ " : "") + speciesName(mon.species()))
                .withStyle(mon.shiny() ? ChatFormatting.GOLD : ChatFormatting.WHITE));
        lines.add(Component.literal("Level " + mon.level()).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("IVs " + mon.ivTotal() + "/186" + (mon.ivTotal() >= 186 ? " (perfect!)" : "")).withStyle(ChatFormatting.GRAY));
        if (mon.legendary()) lines.add(Component.literal("Legendary").withStyle(ChatFormatting.LIGHT_PURPLE));
        lines.add(Component.literal("Value: " + Values.format(mon.value())).withStyle(ChatFormatting.YELLOW));
        if (!mon.held().isEmpty()) lines.add(Component.literal("Holding " + mon.held() + " (goes with it)").withStyle(ChatFormatting.RED));
        if (!mon.blocked().isEmpty()) lines.add(Component.literal(mon.blocked()).withStyle(ChatFormatting.RED));
        else if (hint != null) lines.add(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private String fit(String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, width - font.width("...")) + "...";
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }
}
