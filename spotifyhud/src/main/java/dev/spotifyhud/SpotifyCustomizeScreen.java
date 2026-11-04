package dev.spotifyhud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Customization menu with four pages: Card, Lyrics, Glow, Colors. The HUD stays visible as a live preview. */
public final class SpotifyCustomizeScreen extends BaseScreen {
    private static final int[] PRESETS = {-1, 0xFFFFFF, 0xFF4D4D, 0xFF9F43, 0xFFE066, 0x5EE08A, 0x4DE1E8, 0x5B8CFF, 0xA66BFF, 0xFF6BD6, 0x000000};
    private static final String[] PRESET_NAMES = {"Album", "White", "Red", "Orange", "Yellow", "Green", "Cyan", "Blue", "Purple", "Pink", "Black"};
    private static final String[] COLOR_NAMES = {"Text", "Bars", "Glow", "Solid bg", "Lyrics bg"};
    private static final String[] PAGES = {"Card", "Lyrics", "Glow", "Colors"};
    private static final String[] ALIGN = {"Left", "Center", "Right"};
    private static final String[] BOX = {"Mograph", "Solid", "Off"};
    private static final String[] LBG = {"Dark", "Color", "Mograph", "Off"};

    private static final int MENU_W = 300, COL_W = 140, ROW_H = 17, CTL_H = 14, TOP = 36, ROWS = 8;

    private static final class Ctl {
        int id, x, y, w;
        String label;
        int swatch = -2;          // -2 none, -1 album, otherwise rgb
        int colorIdx = -1;
        boolean slider, plain;
        int min, max, value;
        Runnable click;
        IntConsumer set;
    }

    private int page = 0;
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
            case 3 -> c.bgColor;
            default -> c.lyricsBgColor;
        };
    }

    private static void setColor(SpotifyConfig c, int i, int v) {
        switch (i) {
            case 0 -> c.textColor = v;
            case 1 -> c.barColor = v;
            case 2 -> c.glowColor = v;
            case 3 -> c.bgColor = v;
            default -> c.lyricsBgColor = v;
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
        int top = menuY + TOP;
        int id = 0;
        boolean cd = cfg.style == 1;

        switch (page) {
            case 0 -> {
                out.add(button(id++, lx, row(top, 0), "Style: " + (cd ? "CD" : "Block"), () -> cfg.style = cd ? 0 : 1));
                int alignVal = cd ? cfg.alignCd : cfg.alignBlock;
                out.add(button(id++, lx, row(top, 1), "Text align: " + ALIGN[alignVal], () -> {
                    if (cd) cfg.alignCd = (cfg.alignCd + 1) % 3;
                    else cfg.alignBlock = (cfg.alignBlock + 1) % 3;
                }));
                out.add(slider(id++, lx, row(top, 2), "Title size", "%", 100, 200, Math.round(cfg.titleScale * 100),
                        v -> cfg.titleScale = v / 100f));
                out.add(button(id++, lx, row(top, 3), "Corners: " + (cfg.rounded ? "Round" : "Square"), () -> cfg.rounded = !cfg.rounded));
                out.add(button(id++, lx, row(top, 4), "Block bg: " + BOX[cfg.bgMode], () -> cfg.bgMode = (cfg.bgMode + 1) % 3));
                out.add(slider(id++, lx, row(top, 5), "Darkness", "%", 0, 80, cfg.bgDarkness, v -> cfg.bgDarkness = v));
                out.add(button(id++, lx, row(top, 6), "Motion: " + (cfg.motion ? "On" : "Off"), () -> cfg.motion = !cfg.motion));
                out.add(button(id++, lx, row(top, 7), "Shuffle/repeat badge: " + (cfg.modeIndicators ? "On" : "Off"),
                        () -> cfg.modeIndicators = !cfg.modeIndicators));

                out.add(button(id++, rx, row(top, 0), "CD text: " + (cfg.cdText ? "On" : "Off"), () -> cfg.cdText = !cfg.cdText));
                out.add(button(id++, rx, row(top, 1), "CD text box: " + BOX[cfg.cdBgMode], () -> cfg.cdBgMode = (cfg.cdBgMode + 1) % 3));
                out.add(slider(id++, rx, row(top, 2), "Cover X", "", -20, 20, cfg.artOffX, v -> cfg.artOffX = v));
                out.add(slider(id++, rx, row(top, 3), "Cover Y", "", -20, 20, cfg.artOffY, v -> cfg.artOffY = v));
                out.add(button(id++, rx, row(top, 4), "Auto-fit text: " + (cfg.autoFit ? "On" : "Off"), () -> cfg.autoFit = !cfg.autoFit));
                out.add(button(id++, rx, row(top, 5), "Change menu key...", () -> c.openKeybindScreen(this)));
            }
            case 1 -> {
                out.add(button(id++, lx, row(top, 0), "Lyrics align: " + ALIGN[cfg.alignLyrics], () -> cfg.alignLyrics = (cfg.alignLyrics + 1) % 3));
                out.add(slider(id++, lx, row(top, 1), "Lyrics size", "%", 70, 200, cfg.lyricsScale, v -> cfg.lyricsScale = v));
                out.add(slider(id++, lx, row(top, 2), "Lyrics distance", "", -30, 120, cfg.lyricsOffset, v -> cfg.lyricsOffset = v));
                out.add(button(id++, lx, row(top, 3), "Lyrics bg: " + LBG[cfg.lyricsBgMode], () -> cfg.lyricsBgMode = (cfg.lyricsBgMode + 1) % 4));
                out.add(colorButton(id++, lx, row(top, 4), cfg, 4));
            }
            case 2 -> {
                out.add(button(id++, lx, row(top, 0), "Glow: " + (cfg.glow ? "On" : "Off"), () -> cfg.glow = !cfg.glow));
                out.add(colorButton(id++, lx, row(top, 1), cfg, 2));
                out.add(slider(id++, lx, row(top, 2), "Intensity", "%", 0, 100, cfg.glowStrength, v -> cfg.glowStrength = v));
                out.add(slider(id++, lx, row(top, 3), "Spread", "", 4, 40, cfg.glowSpread, v -> cfg.glowSpread = v));
                out.add(slider(id++, lx, row(top, 4), "Softness", "%", 0, 100, cfg.glowSoftness, v -> cfg.glowSoftness = v));
            }
            default -> {
                for (int i = 0; i < 5; i++) out.add(colorButton(id++, lx, row(top, i), cfg, i));
                Ctl label = new Ctl();
                label.id = id++;
                label.x = rx;
                label.y = row(top, 0) + 3;
                label.w = COL_W;
                label.plain = true;
                label.label = "Mix: " + COLOR_NAMES[selected];
                out.add(label);
                int cur = getColor(cfg, selected);
                int rgb = cur < 0 ? 0xFFFFFF : cur;
                String[] chan = {"Red", "Green", "Blue"};
                for (int k = 0; k < 3; k++) {
                    final int shift = 16 - 8 * k;
                    out.add(slider(id++, rx, row(top, 1 + k), chan[k], "", 0, 255, (rgb >> shift) & 0xFF, v -> {
                        int base = getColor(cfg, selected);
                        if (base < 0) base = 0xFFFFFF;
                        setColor(cfg, selected, (base & ~(0xFF << shift)) | (v << shift));
                    }));
                }
            }
        }

        Ctl done = button(id++, menuX + 6, top + ROW_H * ROWS + 2, "Done", this::onClose);
        done.w = MENU_W - 12;
        out.add(done);
        return out;
    }

    private static int row(int top, int i) {
        return top + ROW_H * i;
    }

    private Ctl colorButton(int id, int x, int y, SpotifyConfig cfg, int which) {
        int col = getColor(cfg, which);
        Ctl b = button(id, x, y, COLOR_NAMES[which] + ": " + colorName(col), () -> {
            selected = which;
            setColor(cfg, which, nextPreset(getColor(cfg, which)));
        });
        b.swatch = col;
        b.colorIdx = which;
        return b;
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

    private int menuH() {
        return TOP + ROW_H * ROWS + 2 + CTL_H + 6;
    }

    private void place(SpotifyHudClient c) {
        int hudCx = c.config.hudX + c.panelW / 2;
        menuX = hudCx < width / 2 ? width - MENU_W - 10 : 10;
        menuY = Math.max(6, (height - menuH()) / 2);
    }

    private int tabX(int i) {
        return menuX + 6 + i * 74;
    }

    // ------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (c == null) return;
        SpotifyHudClient.forceShowUntil = System.currentTimeMillis() + 300; // keep the HUD visible as a live preview
        place(c);
        Font font = Minecraft.getInstance().font;
        HudPanel.round(g, menuX, menuY, MENU_W, menuH(), 0xE0101010);
        g.text(font, "Customize", menuX + 8, menuY + 5, 0xFFFFFFFF, true);
        for (int i = 0; i < PAGES.length; i++) {
            HudPanel.toggleButton(g, font, PAGES[i], tabX(i), menuY + 18, 70, CTL_H,
                    mouseX >= tabX(i) && mouseX < tabX(i) + 70 && mouseY >= menuY + 18 && mouseY < menuY + 18 + CTL_H, i == page);
        }

        for (Ctl ctl : build(c)) {
            if (ctl.plain) {
                g.text(font, ctl.label, ctl.x, ctl.y, 0xFFC8C8C8, false);
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
                if (page == 3 && ctl.colorIdx == selected) {
                    g.fill(ctl.x - 3, ctl.y + 2, ctl.x - 1, ctl.y + CTL_H - 2, 0xFFFFFFFF);
                }
            }
        }
    }

    // ------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (c == null) return super.mouseClicked(event, doubleClick);
        place(c);
        double mx = event.x();
        double my = event.y();
        for (int i = 0; i < PAGES.length; i++) {
            if (mx >= tabX(i) && mx < tabX(i) + 70 && my >= menuY + 18 && my < menuY + 18 + CTL_H) {
                page = i;
                return true;
            }
        }
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
