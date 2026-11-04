package dev.spotifyhud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Customization menu: layout style, text, background, glow and colours. The HUD stays visible next to it. */
public final class SpotifyCustomizeScreen extends Screen {
    private static final int[] PRESETS = {-1, 0xFFFFFF, 0xFF4D4D, 0xFF9F43, 0xFFE066, 0x5EE08A, 0x4DE1E8, 0x5B8CFF, 0xA66BFF, 0xFF6BD6};
    private static final String[] PRESET_NAMES = {"Album", "White", "Red", "Orange", "Yellow", "Green", "Cyan", "Blue", "Purple", "Pink"};
    private static final String[] COLOR_NAMES = {"Text", "Bars", "Glow", "Solid bg"};
    private static final String[] ALIGN = {"Left", "Center", "Right"};
    private static final String[] BG = {"Spill", "Solid", "Off"};

    private static final int MENU_W = 300, COL_W = 140, ROW_H = 17, CTL_H = 14;

    private static final class Ctl {
        int id, x, y, w;
        String label;
        int swatch = -2;          // -2 none, -1 album, otherwise rgb
        boolean slider, plain;
        int min, max, value;
        Runnable click;
        IntConsumer set;
    }

    private int selected = 0; // which colour the RGB sliders edit
    private int dragging = -1;
    private int menuX, menuY;

    public SpotifyCustomizeScreen() {
        super(Component.literal("Customize Spotify HUD"));
    }

    // ------------------------------------------------------------ colour accessors

    private static int getColor(SpotifyConfig c, int i) {
        return switch (i) {
            case 0 -> c.textColor;
            case 1 -> c.barColor;
            case 2 -> c.glowColor;
            default -> c.bgColor;
        };
    }

    private static void setColor(SpotifyConfig c, int i, int v) {
        switch (i) {
            case 0 -> c.textColor = v;
            case 1 -> c.barColor = v;
            case 2 -> c.glowColor = v;
            default -> c.bgColor = v;
        }
    }

    private static String colorName(int v) {
        for (int i = 0; i < PRESETS.length; i++) if (PRESETS[i] == v) return PRESET_NAMES[i];
        return "Custom";
    }

    private static int nextPreset(int v) {
        for (int i = 0; i < PRESETS.length; i++) if (PRESETS[i] == v) return PRESETS[(i + 1) % PRESETS.length];
        return PRESETS[0];
    }

    // ------------------------------------------------------------ controls

    private List<Ctl> build(SpotifyHudClient c) {
        SpotifyConfig cfg = c.config;
        List<Ctl> out = new ArrayList<>();
        int lx = menuX + 6;
        int rx = menuX + 6 + COL_W + 8;
        int top = menuY + 16;
        int id = 0;

        // ---- left column
        out.add(button(id++, lx, top, "Style: " + (cfg.style == 0 ? "Block" : "CD"),
                () -> cfg.style = cfg.style == 0 ? 1 : 0));
        int alignVal = cfg.style == 1 ? cfg.alignCd : cfg.alignBlock;
        out.add(button(id++, lx, top + ROW_H, "Text align: " + ALIGN[alignVal], () -> {
            if (cfg.style == 1) cfg.alignCd = (cfg.alignCd + 1) % 3;
            else cfg.alignBlock = (cfg.alignBlock + 1) % 3;
        }));
        out.add(slider(id++, lx, top + ROW_H * 2, "Title size", "%", 100, 200, Math.round(cfg.titleScale * 100),
                v -> cfg.titleScale = v / 100f));
        out.add(button(id++, lx, top + ROW_H * 3, "Background: " + BG[cfg.bgMode], () -> cfg.bgMode = (cfg.bgMode + 1) % 3));
        out.add(slider(id++, lx, top + ROW_H * 4, "Darkness", "%", 0, 80, cfg.bgDarkness, v -> cfg.bgDarkness = v));
        out.add(button(id++, lx, top + ROW_H * 5, "Motion: " + (cfg.motion ? "On" : "Off"), () -> cfg.motion = !cfg.motion));
        out.add(button(id++, lx, top + ROW_H * 6, "Glow: " + (cfg.glow ? "On" : "Off"), () -> cfg.glow = !cfg.glow));
        out.add(slider(id++, lx, top + ROW_H * 7, "Glow strength", "%", 10, 100, cfg.glowStrength, v -> cfg.glowStrength = v));

        // ---- right column: colour rows
        for (int i = 0; i < 4; i++) {
            final int row = i;
            int col = getColor(cfg, i);
            Ctl b = button(id++, rx, top + ROW_H * i, COLOR_NAMES[i] + ": " + colorName(col), () -> {
                selected = row;
                setColor(cfg, row, nextPreset(getColor(cfg, row)));
            });
            b.swatch = col;
            out.add(b);
        }
        Ctl label = new Ctl();
        label.id = id++;
        label.x = rx;
        label.y = top + ROW_H * 4;
        label.w = COL_W;
        label.plain = true;
        label.label = "Custom colour: " + COLOR_NAMES[selected];
        out.add(label);

        int cur = getColor(cfg, selected);
        int rgb = cur < 0 ? 0xFFFFFF : cur;
        String[] chan = {"Red", "Green", "Blue"};
        for (int k = 0; k < 3; k++) {
            final int shift = 16 - 8 * k;
            out.add(slider(id++, rx, top + ROW_H * (5 + k), chan[k], "", 0, 255, (rgb >> shift) & 0xFF, v -> {
                int base = getColor(cfg, selected);
                if (base < 0) base = 0xFFFFFF;
                setColor(cfg, selected, (base & ~(0xFF << shift)) | (v << shift));
            }));
        }

        Ctl done = button(id++, menuX + 6, top + ROW_H * 8 + 2, "Done", this::onClose);
        done.w = MENU_W - 12;
        out.add(done);
        return out;
    }

    private Ctl button(int id, int x, int y, String label, Runnable click) {
        Ctl c = new Ctl();
        c.id = id;
        c.x = x;
        c.y = y;
        c.w = COL_W;
        c.label = label;
        c.click = click;
        return c;
    }

    private Ctl slider(int id, int x, int y, String name, String unit, int min, int max, int value, IntConsumer set) {
        Ctl c = new Ctl();
        c.id = id;
        c.x = x;
        c.y = y;
        c.w = COL_W;
        c.slider = true;
        c.min = min;
        c.max = max;
        c.value = Math.max(min, Math.min(max, value));
        c.label = name + ": " + c.value + unit;
        c.set = set;
        return c;
    }

    private static boolean hit(Ctl c, double mx, double my) {
        return mx >= c.x && mx < c.x + c.w && my >= c.y && my < c.y + CTL_H;
    }

    private void place(SpotifyHudClient c) {
        int menuH = 16 + ROW_H * 8 + 2 + CTL_H + 6;
        int hudCx = c.config.hudX + c.panelW / 2;
        menuX = hudCx < width / 2 ? width - MENU_W - 10 : 10;
        menuY = Math.max(6, (height - menuH) / 2);
    }

    // ------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (c == null) return;
        SpotifyHudClient.forceShowUntil = System.currentTimeMillis() + 300; // keep the HUD visible as a live preview
        place(c);
        Font font = Minecraft.getInstance().font;
        int menuH = 16 + ROW_H * 8 + 2 + CTL_H + 6;
        HudPanel.round(g, menuX, menuY, MENU_W, menuH, 0xE0101010);
        g.text(font, "Customize", menuX + 8, menuY + 5, 0xFFFFFFFF, true);

        for (Ctl ctl : build(c)) {
            if (ctl.plain) {
                g.text(font, ctl.label, ctl.x, ctl.y + 3, 0xFFC8C8C8, false);
            } else if (ctl.slider) {
                HudPanel.round(g, ctl.x, ctl.y, ctl.w, CTL_H, 0xB0000000);
                double f = (ctl.value - ctl.min) / (double) (ctl.max - ctl.min);
                int fw = (int) ((ctl.w - 2) * f);
                if (fw > 0) g.fill(ctl.x + 1, ctl.y + 1, ctl.x + 1 + fw, ctl.y + CTL_H - 1, 0x66FFFFFF);
                g.text(font, ctl.label, ctl.x + (ctl.w - font.width(ctl.label)) / 2, ctl.y + 3, 0xFFFFFFFF, false);
            } else {
                HudPanel.button(g, font, ctl.label, ctl.x, ctl.y, ctl.w, CTL_H, hit(ctl, mouseX, mouseY));
                if (ctl.swatch != -2) {
                    int rgb = ctl.swatch < 0 ? (c.accentColor() & 0xFFFFFF) : ctl.swatch;
                    g.fill(ctl.x + ctl.w - 12, ctl.y + 3, ctl.x + ctl.w - 4, ctl.y + CTL_H - 3, 0xFF000000 | rgb);
                }
            }
        }
        // highlight the colour row being edited
        int sy = menuY + 16 + ROW_H * selected;
        int sx = menuX + 6 + COL_W + 8;
        g.fill(sx - 3, sy + 2, sx - 1, sy + CTL_H - 2, 0xFFFFFFFF);
    }

    // ------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (c == null) return super.mouseClicked(event, doubleClick);
        place(c);
        double mx = event.x();
        double my = event.y();
        for (Ctl ctl : build(c)) {
            if (ctl.plain || !hit(ctl, mx, my)) continue;
            if (ctl.slider) {
                dragging = ctl.id;
                applySlider(ctl, mx);
            } else {
                ctl.click.run();
                c.saveConfig();
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void applySlider(Ctl ctl, double mx) {
        double f = Math.max(0, Math.min(1, (mx - ctl.x) / ctl.w));
        ctl.set.accept((int) Math.round(ctl.min + f * (ctl.max - ctl.min)));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (c == null || dragging < 0) return super.mouseDragged(event, dragX, dragY);
        place(c);
        for (Ctl ctl : build(c)) {
            if (ctl.id == dragging && ctl.slider) {
                applySlider(ctl, event.x());
                break;
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (dragging >= 0) {
            dragging = -1;
            if (c != null) c.saveConfig();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void onClose() {
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (c != null) c.saveConfig();
        super.onClose();
    }
}
