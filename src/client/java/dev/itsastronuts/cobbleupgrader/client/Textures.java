package dev.itsastronuts.cobbleupgrader.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.itsastronuts.cobbleupgrader.CobbleUpgrader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Textures drawn in code: the chance ring (redrawn when the chance changes) and the hex pattern of the panels.
 * Drawn at 2x so they stay sharp at normal GUI scales.
 */
final class Textures {
    static final int RING_SIZE = 192;          // texture pixels (shown at 96x96)
    static final int PANEL_W = 180, PANEL_H = 192; // texture pixels (shown at 90x96)

    static final ResourceLocation RING = ResourceLocation.fromNamespaceAndPath(CobbleUpgrader.MOD_ID, "dynamic/ring");
    static final ResourceLocation PANEL = ResourceLocation.fromNamespaceAndPath(CobbleUpgrader.MOD_ID, "dynamic/panel");

    private static DynamicTexture ring;
    private static DynamicTexture panel;
    private static double drawnChance = -2;

    private Textures() {
    }

    static void ensure() {
        Minecraft mc = Minecraft.getInstance();
        if (ring == null) {
            ring = new DynamicTexture(new NativeImage(NativeImage.Format.RGBA, RING_SIZE, RING_SIZE, true));
            mc.getTextureManager().register(RING, ring);
            drawnChance = -2;
        }
        if (panel == null) {
            panel = new DynamicTexture(new NativeImage(NativeImage.Format.RGBA, PANEL_W, PANEL_H, true));
            drawPanel(panel.getPixels());
            panel.upload();
            mc.getTextureManager().register(PANEL, panel);
        }
    }

    static void release() {
        Minecraft mc = Minecraft.getInstance();
        if (ring != null) {
            mc.getTextureManager().release(RING);
            ring = null;
        }
        if (panel != null) {
            mc.getTextureManager().release(PANEL);
            panel = null;
        }
    }

    /** Redraws the ring for a chance between 0 and 1 (negative = nothing selected). */
    static void updateRing(double chance) {
        ensure();
        if (Math.abs(chance - drawnChance) < 1e-6) return;
        drawnChance = chance;
        NativeImage img = ring.getPixels();
        double c = RING_SIZE / 2.0;
        double inner = 62, outer = 80;          // coloured band
        double tickIn = 85, tickOut = 92;       // tick marks
        double winDeg = Math.max(0, chance) * 360.0;
        for (int y = 0; y < RING_SIZE; y++) {
            for (int x = 0; x < RING_SIZE; x++) {
                double dx = x + 0.5 - c, dy = y + 0.5 - c;
                double r = Math.sqrt(dx * dx + dy * dy);
                double deg = Math.toDegrees(Math.atan2(dx, -dy));
                if (deg < 0) deg += 360;
                int color = 0;
                // Band
                double bandAlpha = clamp(Math.min(r - inner, outer - r) + 0.5);
                if (bandAlpha > 0) {
                    boolean win = chance > 0 && deg < winDeg;
                    int rgb;
                    if (win) {
                        double t = winDeg <= 0 ? 0 : deg / winDeg;
                        rgb = lerp(0xFFD957, 0xFF7A2F, t);
                        // soft highlight on the inner edge
                        if (r - inner < 3) rgb = lerp(rgb, 0xFFF1B8, 0.35);
                    } else {
                        rgb = r - inner < 2 ? 0x3A4256 : 0x2A3042;
                    }
                    color = argb(bandAlpha, rgb);
                }
                // Inner disc (darker, behind the text)
                if (r < inner - 1) {
                    double a = clamp(inner - 1 - r + 0.5);
                    color = argb(a * 0.92, r > inner - 5 ? 0x161A24 : 0x1B2030);
                }
                // Tick marks every 6 degrees, longer every 30
                if (r >= tickIn - 4 && r <= tickOut) {
                    double nearest = Math.round(deg / 6.0) * 6.0;
                    boolean major = ((int) Math.round(nearest)) % 30 == 0;
                    double from = major ? tickIn - 4 : tickIn;
                    double arcDist = Math.abs(deg - nearest) * Math.PI / 180.0 * r;
                    double a = clamp((major ? 1.6 : 1.1) - arcDist) * clamp(Math.min(r - from, tickOut - r) + 0.5);
                    if (a > 0) {
                        boolean winTick = chance > 0 && nearest < winDeg;
                        color = argb(a, winTick ? 0xFFC861 : 0x59627A);
                    }
                }
                img.setPixelRGBA(x, y, toAbgr(color));
            }
        }
        ring.upload();
    }

    /** Dark panel with a faint honeycomb pattern. */
    private static void drawPanel(NativeImage img) {
        double w = 26;                       // hex width in texture pixels
        double h = w * Math.sqrt(3) / 2;     // row spacing
        for (int y = 0; y < PANEL_H; y++) {
            for (int x = 0; x < PANEL_W; x++) {
                double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
                int row0 = (int) Math.floor(y / h);
                for (int row = row0 - 1; row <= row0 + 2; row++) {
                    double off = (row & 1) == 0 ? 0 : w / 2;
                    int col0 = (int) Math.floor((x - off) / w);
                    for (int col = col0 - 1; col <= col0 + 2; col++) {
                        double cx = col * w + off, cy = row * h;
                        double d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
                        if (d < d1) {
                            d2 = d1;
                            d1 = d;
                        } else if (d < d2) {
                            d2 = d;
                        }
                    }
                }
                double edge = clamp(1.4 - (d2 - d1));
                // Fade the pattern towards the middle of the panel so icons stay readable
                double vx = (x - PANEL_W / 2.0) / (PANEL_W / 2.0), vy = (y - PANEL_H / 2.0) / (PANEL_H / 2.0);
                double vignette = 0.45 + 0.55 * Math.min(1, Math.sqrt(vx * vx + vy * vy));
                int rgb = lerp(0x1A1F2C, 0x2C3346, edge * vignette);
                img.setPixelRGBA(x, y, toAbgr(0xFF000000 | rgb));
            }
        }
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static int lerp(int a, int b, double t) {
        t = clamp(t);
        int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int argb(double alpha, int rgb) {
        return ((int) Math.round(clamp(alpha) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    /** NativeImage stores pixels as ABGR. */
    private static int toAbgr(int argb) {
        int a = (argb >>> 24) & 255, r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
