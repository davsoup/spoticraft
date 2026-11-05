package dev.spotifyhud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Customization menu: a sidebar with one page per feature (General, Block, CD, Vinyl, Lyrics, Queue, Ambient,
 * Glow, Colors). The HUD stays visible as a live preview.
 */
public final class SpotifyCustomizeScreen extends BaseScreen {
    private static final int[] PRESETS = {-1, 0xFFFFFF, 0xFF4D4D, 0xFF9F43, 0xFFE066, 0x5EE08A, 0x4DE1E8, 0x5B8CFF, 0xA66BFF, 0xFF6BD6, 0x000000};
    private static final String[] PRESET_NAMES = {"Album", "White", "Red", "Orange", "Yellow", "Green", "Cyan", "Blue", "Purple", "Pink", "Black"};
    private static final String[] COLOR_NAMES = {"Text", "Bars", "Glow", "Solid bg", "Lyrics bg", "Lyric fill", "Ambient", "Queue text", "Tonearm"};
    private static final String[] PAGES = {"General", "Block", "CD", "Vinyl", "Lyrics", "Queue", "Ambient", "Glow", "Colors", "Beat", "Links"};
    private static final String[] COLOR_FIELDS = {"textColor", "barColor", "glowColor", "bgColor", "lyricsBgColor", "lyricsFillColor", "ambientColor", "queuePeekColor", "vinylArmColor"};
    private static final String[] SRC_NAMES = Mod.SOURCES;
    private static final String[] STYLES = {"Block", "CD", "Vinyl"};
    private static final String[] ALIGN = {"Left", "Center", "Right"};
    private static final String[] BOX = {"Mograph", "Solid", "Off"};
    private static final String[] LBG = {"Dark", "Color", "Mograph", "Off"};
    private static final String[] SIDE = {"Right", "Left", "Below"};
    private static final String[] EDGES = {"All", "Sides", "Top+Bottom"};
    private static final int P_GENERAL = 0, P_BLOCK = 1, P_CD = 2, P_VINYL = 3, P_LYRICS = 4, P_QUEUE = 5,
            P_AMBIENT = 6, P_GLOW = 7, P_COLORS = 8, P_BEAT = 9, P_LINKS = 10;

    private static final int SIDE_W = 70, COL_W = 128, ROW_H = 17, CTL_H = 14, TOP = 22, ROWS = 11;
    private static final int MENU_W = 8 + SIDE_W + 6 + COL_W + 6 + COL_W + 8;

    private static final class Ctl {
        int id, x, y, w;
        String label;
        int swatch = -2;          // -2 none, -1 album, otherwise rgb
        int colorIdx = -1;
        boolean slider, plain;
        int min, max, value;
        Runnable click;
        IntConsumer set;
        String param;             // config field this control edits (a beat link can drive it)
        boolean meter;            // read-only level bar
        int fullW;                // >0: wider than a column

        Ctl p(String field) {
            param = field;
            return this;
        }
    }

    private int page = 0;
    private int selected = 0; // which colour the RGB sliders edit
    private int dragging = -1;
    private boolean linkMode = false;   // clicking a control picks it for linking instead of changing it
    private String linkSel = null;      // setting being linked (shows the link editor)
    private int linksPage = 0;
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
            case 4 -> c.lyricsBgColor;
            case 5 -> c.lyricsFillColor;
            case 6 -> c.ambientColor;
            case 7 -> c.queuePeekColor;
            default -> c.vinylArmColor;
        };
    }

    private static void setColor(SpotifyConfig c, int i, int v) {
        switch (i) {
            case 0 -> c.textColor = v;
            case 1 -> c.barColor = v;
            case 2 -> c.glowColor = v;
            case 3 -> c.bgColor = v;
            case 4 -> c.lyricsBgColor = v;
            case 5 -> c.lyricsFillColor = v;
            case 6 -> c.ambientColor = v;
            case 7 -> c.queuePeekColor = v;
            default -> c.vinylArmColor = v;
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
        int lx = menuX + 8 + SIDE_W + 6;
        int rx = lx + COL_W + 6;
        int top = menuY + TOP;
        int id = 0;
        if (linkSel != null) return buildLinkEditor(c, lx, rx, top);

        switch (page) {
            case P_GENERAL -> {
                out.add(button(id++, lx, row(top, 0), "HUD: " + onOff(cfg.showHud), c::toggleHud).p("showHud"));
                out.add(button(id++, lx, row(top, 1), "Style: " + STYLES[cfg.style], () -> cfg.style = (cfg.style + 1) % 3).p("style"));
                out.add(button(id++, lx, row(top, 2), "Motion: " + onOff(cfg.motion), () -> cfg.motion = !cfg.motion).p("motion"));
                out.add(button(id++, lx, row(top, 3), "Corners: " + (cfg.rounded ? "Round" : "Square"), () -> cfg.rounded = !cfg.rounded).p("rounded"));
                out.add(slider(id++, lx, row(top, 4), "Title size", "%", 100, 200, Math.round(cfg.titleScale * 100),
                        v -> cfg.titleScale = v / 100f).p("titleScale"));
                out.add(button(id++, lx, row(top, 5), "Auto-fit text: " + onOff(cfg.autoFit), () -> cfg.autoFit = !cfg.autoFit).p("autoFit"));
                out.add(button(id++, rx, row(top, 0), "Lyrics: " + onOff(cfg.showLyrics), c::toggleLyrics).p("showLyrics"));
                out.add(button(id++, rx, row(top, 1), "Shuffle/repeat badge: " + onOff(cfg.modeIndicators),
                        () -> cfg.modeIndicators = !cfg.modeIndicators).p("modeIndicators"));
                out.add(button(id++, rx, row(top, 2), "Change menu key...", () -> c.openKeybindScreen(this)));
                out.add(note(id++, rx, row(top, 4), "Drag the card or a corner"));
                out.add(note(id++, rx, row(top, 5), "on the control screen"));
                out.add(note(id++, rx, row(top, 6), "to move or resize it."));
            }
            case P_BLOCK -> {
                out.add(button(id++, lx, row(top, 0), "Text align: " + ALIGN[cfg.alignBlock], () -> cfg.alignBlock = (cfg.alignBlock + 1) % 3).p("alignBlock"));
                out.add(button(id++, lx, row(top, 1), "Background: " + BOX[cfg.bgMode], () -> cfg.bgMode = (cfg.bgMode + 1) % 3).p("bgMode"));
                out.add(slider(id++, lx, row(top, 2), "Darkness", "%", 0, 80, cfg.bgDarkness, v -> cfg.bgDarkness = v).p("bgDarkness"));
                out.add(slider(id++, lx, row(top, 3), "Cover X", "", -20, 20, cfg.artOffX, v -> cfg.artOffX = v).p("artOffX"));
                out.add(slider(id++, lx, row(top, 4), "Cover Y", "", -20, 20, cfg.artOffY, v -> cfg.artOffY = v).p("artOffY"));
                out.add(colorButton(id++, rx, row(top, 0), cfg, 0));
                out.add(colorButton(id++, rx, row(top, 1), cfg, 1));
                out.add(colorButton(id++, rx, row(top, 2), cfg, 3));
            }
            case P_CD -> {
                out.add(button(id++, lx, row(top, 0), "Text: " + onOff(cfg.cdText), () -> cfg.cdText = !cfg.cdText).p("cdText"));
                out.add(button(id++, lx, row(top, 1), "Text align: " + ALIGN[cfg.alignCd], () -> cfg.alignCd = (cfg.alignCd + 1) % 3).p("alignCd"));
                out.add(button(id++, lx, row(top, 2), "Text box: " + BOX[cfg.cdBgMode], () -> cfg.cdBgMode = (cfg.cdBgMode + 1) % 3).p("cdBgMode"));
                out.add(slider(id++, lx, row(top, 3), "Darkness", "%", 0, 80, cfg.cdDarkness, v -> cfg.cdDarkness = v).p("cdDarkness"));
                out.add(slider(id++, lx, row(top, 4), "Disc size", "", 40, 220, cfg.cdSize, v -> cfg.cdSize = v).p("cdSize"));
                out.add(colorButton(id++, rx, row(top, 0), cfg, 0));
                out.add(colorButton(id++, rx, row(top, 1), cfg, 1));
                out.add(colorButton(id++, rx, row(top, 2), cfg, 3));
            }
            case P_VINYL -> {
                out.add(button(id++, lx, row(top, 0), "Text: " + onOff(cfg.vinylText), () -> cfg.vinylText = !cfg.vinylText).p("vinylText"));
                out.add(button(id++, lx, row(top, 1), "Text align: " + ALIGN[cfg.alignVinyl], () -> cfg.alignVinyl = (cfg.alignVinyl + 1) % 3).p("alignVinyl"));
                out.add(button(id++, lx, row(top, 2), "Text box: " + BOX[cfg.vinylBgMode], () -> cfg.vinylBgMode = (cfg.vinylBgMode + 1) % 3).p("vinylBgMode"));
                out.add(slider(id++, lx, row(top, 3), "Darkness", "%", 0, 80, cfg.vinylDarkness, v -> cfg.vinylDarkness = v).p("vinylDarkness"));
                out.add(slider(id++, lx, row(top, 4), "Record size", "", 40, 220, cfg.vinylSize, v -> cfg.vinylSize = v).p("vinylSize"));
                out.add(button(id++, rx, row(top, 0), "Record: " + (cfg.vinylTint ? "Album tint" : "Black"), () -> cfg.vinylTint = !cfg.vinylTint).p("vinylTint"));
                out.add(button(id++, rx, row(top, 1), "Tonearm: " + onOff(cfg.vinylArm), () -> cfg.vinylArm = !cfg.vinylArm).p("vinylArm"));
                out.add(colorButton(id++, rx, row(top, 2), cfg, 8));
                out.add(colorButton(id++, rx, row(top, 3), cfg, 0));
                out.add(colorButton(id++, rx, row(top, 4), cfg, 1));
                out.add(colorButton(id++, rx, row(top, 5), cfg, 3));
            }
            case P_LYRICS -> {
                out.add(button(id++, lx, row(top, 0), "Lyrics: " + onOff(cfg.showLyrics), c::toggleLyrics).p("showLyrics"));
                out.add(button(id++, lx, row(top, 1), "Align: " + ALIGN[cfg.alignLyrics], () -> cfg.alignLyrics = (cfg.alignLyrics + 1) % 3).p("alignLyrics"));
                out.add(slider(id++, lx, row(top, 2), "Size", "%", 70, 200, cfg.lyricsScale, v -> cfg.lyricsScale = v).p("lyricsScale"));
                out.add(slider(id++, lx, row(top, 3), "Distance", "", -30, 120, cfg.lyricsOffset, v -> cfg.lyricsOffset = v).p("lyricsOffset"));
                out.add(button(id++, lx, row(top, 4), "Background: " + LBG[cfg.lyricsBgMode], () -> cfg.lyricsBgMode = (cfg.lyricsBgMode + 1) % 4).p("lyricsBgMode"));
                out.add(colorButton(id++, rx, row(top, 0), cfg, 4));
                out.add(button(id++, rx, row(top, 1), "Line flash: " + onOff(cfg.lyricsFlash), () -> cfg.lyricsFlash = !cfg.lyricsFlash).p("lyricsFlash"));
                out.add(button(id++, rx, row(top, 2), "Karaoke fill: " + onOff(cfg.lyricsKaraoke), () -> cfg.lyricsKaraoke = !cfg.lyricsKaraoke).p("lyricsKaraoke"));
                out.add(colorButton(id++, rx, row(top, 3), cfg, 5));
            }
            case P_QUEUE -> {
                out.add(button(id++, lx, row(top, 0), "Queue peek: " + onOff(cfg.queuePeek), () -> cfg.queuePeek = !cfg.queuePeek).p("queuePeek"));
                out.add(button(id++, lx, row(top, 1), "In game: " + onOff(cfg.queuePeekHud), () -> cfg.queuePeekHud = !cfg.queuePeekHud).p("queuePeekHud"));
                out.add(slider(id++, lx, row(top, 2), "Songs", "", 1, 5, cfg.queuePeekCount, v -> cfg.queuePeekCount = v).p("queuePeekCount"));
                out.add(slider(id++, lx, row(top, 3), "Box size", "%", 50, 300, Math.round(cfg.queuePeekScale * 100),
                        v -> cfg.queuePeekScale = v / 100f).p("queuePeekScale"));
                out.add(slider(id++, lx, row(top, 4), "Text size", "%", 70, 200, cfg.queuePeekText, v -> cfg.queuePeekText = v).p("queuePeekText"));
                out.add(button(id++, lx, row(top, 5), "Text align: " + ALIGN[cfg.queuePeekAlign], () -> cfg.queuePeekAlign = (cfg.queuePeekAlign + 1) % 3).p("queuePeekAlign"));
                out.add(button(id++, lx, row(top, 6), "Covers: " + onOff(cfg.queuePeekCovers), () -> cfg.queuePeekCovers = !cfg.queuePeekCovers).p("queuePeekCovers"));
                out.add(button(id++, rx, row(top, 0), "Background: " + BOX[cfg.queuePeekBg], () -> cfg.queuePeekBg = (cfg.queuePeekBg + 1) % 3).p("queuePeekBg"));
                out.add(slider(id++, rx, row(top, 1), "Darkness", "%", 0, 80, cfg.queuePeekDarkness, v -> cfg.queuePeekDarkness = v).p("queuePeekDarkness"));
                out.add(colorButton(id++, rx, row(top, 2), cfg, 7));
                out.add(button(id++, rx, row(top, 3), cfg.queuePeekCustom ? "Position: Custom (reset)" : "Side: " + SIDE[cfg.queuePeekSide],
                        () -> {
                            if (cfg.queuePeekCustom) cfg.queuePeekCustom = false;
                            else cfg.queuePeekSide = (cfg.queuePeekSide + 1) % 3;
                        }).p("queuePeekSide"));
                out.add(note(id++, rx, row(top, 5), "Control screen (P): drag the"));
                out.add(note(id++, rx, row(top, 6), "box to move it, drag a corner"));
                out.add(note(id++, rx, row(top, 7), "to resize, click a song to skip."));
            }
            case P_AMBIENT -> {
                out.add(button(id++, lx, row(top, 0), "Ambient glow: " + onOff(cfg.ambient), () -> cfg.ambient = !cfg.ambient).p("ambient"));
                out.add(button(id++, lx, row(top, 1), "Mograph: " + onOff(cfg.ambientMograph), () -> cfg.ambientMograph = !cfg.ambientMograph).p("ambientMograph"));
                out.add(button(id++, lx, row(top, 2), "Edges: " + EDGES[cfg.ambientEdges], () -> cfg.ambientEdges = (cfg.ambientEdges + 1) % 3).p("ambientEdges"));
                out.add(slider(id++, lx, row(top, 3), "Size", "", 20, 200, cfg.ambientSize, v -> cfg.ambientSize = v).p("ambientSize"));
                out.add(slider(id++, lx, row(top, 4), "Intensity", "%", 0, 100, cfg.ambientIntensity, v -> cfg.ambientIntensity = v).p("ambientIntensity"));
                out.add(colorButton(id++, rx, row(top, 0), cfg, 6));
                out.add(slider(id++, rx, row(top, 1), "Mograph speed", "", 1, 10, cfg.ambientSpeed, v -> cfg.ambientSpeed = v).p("ambientSpeed"));
            }
            case P_GLOW -> {
                out.add(button(id++, lx, row(top, 0), "Glow: " + onOff(cfg.glow), () -> cfg.glow = !cfg.glow).p("glow"));
                out.add(colorButton(id++, lx, row(top, 1), cfg, 2));
                out.add(slider(id++, lx, row(top, 2), "Intensity", "%", 0, 100, cfg.glowStrength, v -> cfg.glowStrength = v).p("glowStrength"));
                out.add(slider(id++, lx, row(top, 3), "Spread", "", 4, 40, cfg.glowSpread, v -> cfg.glowSpread = v).p("glowSpread"));
                out.add(slider(id++, lx, row(top, 4), "Softness", "%", 0, 100, cfg.glowSoftness, v -> cfg.glowSoftness = v).p("glowSoftness"));
                out.add(note(id++, rx, row(top, 0), "Glow around the card,"));
                out.add(note(id++, rx, row(top, 1), "the CD and the record."));
            }
            case P_BEAT -> {
                out.add(button(id++, lx, row(top, 0), "Beat link: " + onOff(cfg.beatEnabled), () -> cfg.beatEnabled = !cfg.beatEnabled));
                out.add(button(id++, lx, row(top, 1), "Listen to: " + (cfg.beatSource == 0 ? "Spotify only" : "All PC audio"),
                        () -> cfg.beatSource = (cfg.beatSource + 1) % 2));
                out.add(slider(id++, lx, row(top, 2), "Sensitivity", "%", 0, 100, cfg.beatSensitivity, v -> cfg.beatSensitivity = v));
                out.add(button(id++, lx, row(top, 3), "Link mode: " + onOff(linkMode), () -> linkMode = !linkMode));
                out.add(button(id++, lx, row(top, 4), "Clear all links (" + cfg.links.size() + ")", () -> cfg.links.clear()));
                out.add(note(id++, lx, row(top, 6), "Turn Link mode on, then click"));
                out.add(note(id++, lx, row(top, 7), "ANY setting in any page to wire"));
                out.add(note(id++, lx, row(top, 8), "a music signal to it."));
                String[] names = {"Bass", "Mid", "Treble", "Level", "Beat"};
                int[] srcs = {1, 2, 3, 4, 5};
                for (int i = 0; i < 5; i++) {
                    Ctl m = new Ctl();
                    m.id = id++;
                    m.x = rx;
                    m.y = row(top, i);
                    m.w = COL_W;
                    m.slider = true;
                    m.meter = true;
                    m.min = 0;
                    m.max = 100;
                    m.value = (int) Math.round(BeatAudio.signal(srcs[i]) * 100);
                    m.label = names[i];
                    out.add(m);
                }
                out.add(note(id++, rx, row(top, 5), BeatAudio.status));
                out.add(note(id++, rx, row(top, 6), cfg.beatEnabled ? "" : "Beat link is off."));
                out.add(note(id++, rx, row(top, 8), "Windows 10/11 only."));
            }
            case P_LINKS -> {
                List<Link> all = new ArrayList<>();
                for (Link l : cfg.links) if (l.src > 0 && Mod.def(l.param) != null) all.add(l);
                int per = 9;
                int pages = Math.max(1, (all.size() + per - 1) / per);
                linksPage = Math.max(0, Math.min(linksPage, pages - 1));
                for (int i = 0; i < per; i++) {
                    int idx = linksPage * per + i;
                    if (idx >= all.size()) break;
                    Link l = all.get(idx);
                    Ctl b = button(id++, lx, row(top, i), Mod.def(l.param).label() + "  <  " + SRC_NAMES[l.src] + " " + l.amount + "%",
                            () -> linkSel = l.param);
                    b.w = COL_W * 2 + 6;
                    out.add(b);
                }
                if (all.isEmpty()) {
                    out.add(note(id++, lx, row(top, 0), "No links yet. Turn on Link mode (Beat page"));
                    out.add(note(id++, lx, row(top, 1), "or the Link button), then click any setting."));
                }
                out.add(button(id++, lx, row(top, 9), "< Prev", () -> linksPage--));
                out.add(button(id++, rx, row(top, 9), "Next >", () -> linksPage++));
                out.add(note(id++, lx, row(top, 10), "Page " + (linksPage + 1) + " / " + pages + "   (" + all.size() + " links)"));
            }
            default -> {
                for (int i = 0; i < COLOR_NAMES.length; i++) out.add(colorButton(id++, lx, row(top, i), cfg, i));
                out.add(note(id++, rx, row(top, 0), "Mix: " + COLOR_NAMES[selected]));
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
                out.add(note(id++, rx, row(top, 5), "Click a colour to cycle"));
                out.add(note(id++, rx, row(top, 6), "presets, or mix your own."));
            }
        }

        Ctl done = button(id++, menuX + 8, top + ROW_H * ROWS + 2, "Done", this::onClose);
        done.w = MENU_W - 16;
        out.add(done);
        return out;
    }

    /** The link editor: pick a signal for one setting and shape how it drives it. */
    private List<Ctl> buildLinkEditor(SpotifyHudClient c, int lx, int rx, int top) {
        SpotifyConfig cfg = c.config;
        List<Ctl> out = new ArrayList<>();
        int id = 0;
        String sel = linkSel;
        Mod.Def d = Mod.def(sel);
        Link l = Mod.find(cfg, sel);
        out.add(note(id++, lx, row(top, 0), "Linking: " + (d != null ? d.label() : sel)));
        out.add(button(id++, lx, row(top, 1), "Signal: " + SRC_NAMES[l == null ? 0 : l.src], () -> {
            Link cur = Mod.find(cfg, sel);
            if (cur == null) {
                Link nl = new Link();
                nl.param = sel;
                nl.src = 1;
                cfg.links.add(nl);
                cfg.beatEnabled = true;
            } else if (cur.src >= SRC_NAMES.length - 1) {
                cfg.links.remove(cur);
            } else {
                cur.src++;
            }
        }));
        if (l != null) {
            out.add(slider(id++, lx, row(top, 2), "Amount", "%", -100, 100, l.amount, v -> l.amount = v));
            out.add(slider(id++, lx, row(top, 3), "Smooth", "%", 0, 100, l.smooth, v -> l.smooth = v));
            out.add(slider(id++, lx, row(top, 4), "Gate", "%", 0, 90, l.gate, v -> l.gate = v));
            out.add(button(id++, lx, row(top, 5), "Curve: " + Mod.CURVES[l.curve], () -> l.curve = (l.curve + 1) % Mod.CURVES.length));
            if (d != null && d.kind() == Mod.K.COLOR) {
                out.add(button(id++, lx, row(top, 6), "Colour: " + Mod.COLOR_MODES[l.mode], () -> l.mode = (l.mode + 1) % Mod.COLOR_MODES.length));
            }
            Ctl m = new Ctl();
            m.id = id++;
            m.x = rx;
            m.y = row(top, 1);
            m.w = COL_W;
            m.slider = true;
            m.meter = true;
            m.min = 0;
            m.max = 100;
            m.value = (int) Math.round(Math.max(0, Math.min(1, l.val)) * 100);
            m.label = "Live";
            out.add(m);
            for (String line : explain(d, l)) out.add(note(id++, rx, row(top, 2 + (out.size() % 1)), line));
            int ny = 2;
            for (Ctl n : out) if (n.plain && n.x == rx) n.y = row(top, ny++) + 3;
            out.add(button(id++, lx, row(top, 8), "Remove link", () -> {
                cfg.links.remove(l);
                linkSel = null;
            }));
        } else {
            out.add(note(id++, lx, row(top, 3), "Pick a signal to wire this"));
            out.add(note(id++, lx, row(top, 4), "setting to the music."));
        }
        Ctl back = button(id++, lx, row(top, 9), "Back", () -> linkSel = null);
        back.w = COL_W * 2 + 6;
        out.add(back);
        return out;
    }

    private static List<String> explain(Mod.Def d, Link l) {
        List<String> t = new ArrayList<>();
        if (d == null) return t;
        switch (d.kind()) {
            case INT, FLOAT -> {
                t.add("Value moves with the");
                t.add("signal by Amount % of");
                t.add("its range.");
            }
            case BOOL -> {
                t.add("Flips On/Off while the");
                t.add("signal is above 50%.");
                t.add("Negative = inverted.");
            }
            case ENUM -> {
                t.add("Steps to the next");
                t.add("option on every hit.");
                t.add("Negative = backwards.");
            }
            case COLOR -> {
                t.add("Hue shift/Brightness/");
                t.add("Flash follow the level.");
                t.add("Cycle = new colour per hit.");
            }
        }
        return t;
    }

    private boolean isLinked(SpotifyConfig cfg, String param) {
        return param != null && Mod.find(cfg, param) != null;
    }

    private static String onOff(boolean b) {
        return b ? "On" : "Off";
    }

    private Ctl note(int id, int x, int y, String text) {
        Ctl n = new Ctl();
        n.id = id;
        n.x = x;
        n.y = y + 3;
        n.w = COL_W;
        n.plain = true;
        n.label = text;
        return n;
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
        b.param = COLOR_FIELDS[which];
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

    private int linkBtnX() {
        return menuX + MENU_W - 48;
    }

    private boolean inLinkBtn(double mx, double my) {
        return mx >= linkBtnX() && mx < linkBtnX() + 40 && my >= menuY + 4 && my < menuY + 4 + CTL_H - 2;
    }

    private int menuH() {
        return TOP + ROW_H * ROWS + 2 + CTL_H + 8;
    }

    private void place(SpotifyHudClient c) {
        int hudCx = c.config.hudX + c.panelW / 2;
        menuX = hudCx < width / 2 ? width - MENU_W - 10 : 10;
        menuY = Math.max(6, (height - menuH()) / 2);
    }

    private int tabY(int i) {
        return menuY + TOP + i * ROW_H;
    }

    private boolean inTab(int i, double mx, double my) {
        return mx >= menuX + 8 && mx < menuX + 8 + SIDE_W && my >= tabY(i) && my < tabY(i) + CTL_H;
    }

    /** Opening a style's page also switches to that style so you can see what you're editing. */
    private void openPage(SpotifyHudClient c, int i) {
        page = i;
        if (i == P_BLOCK) c.config.style = 0;
        else if (i == P_CD) c.config.style = 1;
        else if (i == P_VINYL) c.config.style = 2;
        c.saveConfig();
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
        g.text(font, "Customize", menuX + 8, menuY + 6, 0xFFFFFFFF, true);
        for (int i = 0; i < PAGES.length; i++) {
            HudPanel.toggleButton(g, font, PAGES[i], menuX + 8, tabY(i), SIDE_W, CTL_H,
                    inTab(i, mouseX, mouseY), i == page);
        }

        HudPanel.toggleButton(g, font, "Link", linkBtnX(), menuY + 4, 40, CTL_H - 2,
                inLinkBtn(mouseX, mouseY), linkMode);
        for (Ctl ctl : build(c)) {
            if (ctl.param != null && isLinked(c.config, ctl.param)) {
                g.fill(ctl.x - 3, ctl.y + 2, ctl.x - 1, ctl.y + CTL_H - 2, 0xFF3CDC6E);
            }
            if (linkMode && linkSel == null && ctl.param != null && hit(ctl, mouseX, mouseY)) {
                g.fill(ctl.x, ctl.y, ctl.x + ctl.w, ctl.y + 1, 0xFF3CDC6E);
                g.fill(ctl.x, ctl.y + CTL_H - 1, ctl.x + ctl.w, ctl.y + CTL_H, 0xFF3CDC6E);
            }
            if (ctl.plain) {
                g.text(font, ctl.label, ctl.x, ctl.y, 0xFFC8C8C8, false);
            } else if (ctl.slider) {
                HudPanel.round(g, ctl.x, ctl.y, ctl.w, CTL_H, 0xB0000000);
                double f = (ctl.value - ctl.min) / (double) (ctl.max - ctl.min);
                int fw = (int) ((ctl.w - 2) * f);
                if (fw > 0) g.fill(ctl.x + 1, ctl.y + 1, ctl.x + 1 + fw, ctl.y + CTL_H - 1, 0x66FFFFFF);
                if (ctl.meter) g.fill(ctl.x + 1, ctl.y + 1, ctl.x + 1 + fw, ctl.y + CTL_H - 1, 0xAA3CDC6E);
                g.text(font, ctl.label, ctl.x + (ctl.w - font.width(ctl.label)) / 2, ctl.y + 3, 0xFFFFFFFF, false);
            } else {
                HudPanel.button(g, font, ctl.label, ctl.x, ctl.y, ctl.w, CTL_H, hit(ctl, mouseX, mouseY));
                if (ctl.swatch != -2) {
                    int rgb = ctl.swatch < 0 ? (c.accentColor() & 0xFFFFFF) : ctl.swatch;
                    g.fill(ctl.x + ctl.w - 12, ctl.y + 3, ctl.x + ctl.w - 4, ctl.y + CTL_H - 3, 0xFF000000 | rgb);
                }
                if (page == P_COLORS && ctl.colorIdx == selected) {
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
        if (inLinkBtn(mx, my)) {
            linkMode = !linkMode;
            if (!linkMode) linkSel = null;
            return true;
        }
        for (int i = 0; i < PAGES.length; i++) {
            if (inTab(i, mx, my)) {
                linkSel = null;
                openPage(c, i);
                return true;
            }
        }
        for (Ctl ctl : build(c)) {
            if (ctl.plain || !hit(ctl, mx, my)) continue;
            if (ctl.meter) return true;
            if (linkMode && linkSel == null && ctl.param != null) {
                linkSel = ctl.param;
                return true;
            }
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
