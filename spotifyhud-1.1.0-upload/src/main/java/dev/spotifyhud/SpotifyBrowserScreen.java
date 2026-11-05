package dev.spotifyhud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Queue viewer, playlists, liked songs and search results. Click a row to play it, "+Q" to add it to the queue. */
public final class SpotifyBrowserScreen extends BaseScreen {
    private static final String[] TABS = {"Queue", "Playlists", "Liked", "Search"};
    private static final int MENU_W = 300, ROW_H = 20, ROWS = 8, BTN_H = 14;

    private int tab;
    private int page = 0;
    private int menuX, menuY;

    public SpotifyBrowserScreen(int tab) {
        super(Component.literal("Spotify Queue & Playlists"));
        this.tab = tab;
    }

    private static final int HEADER = 36;

    private int menuH() {
        return HEADER + ROW_H * ROWS + 4 + BTN_H + 8;
    }

    private void place(SpotifyHudClient c) {
        int hudCx = c.config.hudX + c.panelW / 2;
        menuX = hudCx < width / 2 ? width - MENU_W - 10 : 10;
        menuY = Math.max(6, (height - menuH()) / 2);
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int tabX(int i) {
        return menuX + 6 + i * 74;
    }

    private int listY() {
        return menuY + HEADER;
    }

    private int footY() {
        return listY() + ROW_H * ROWS + 4;
    }

    private static boolean actionable(int tab) {
        return tab != 0;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (c == null) return;
        SpotifyHudClient.forceShowUntil = System.currentTimeMillis() + 300;
        place(c);
        Font font = Minecraft.getInstance().font;
        HudPanel.round(g, menuX, menuY, MENU_W, menuH(), 0xE0101010);
        g.text(font, "Queue & Playlists", menuX + 8, menuY + 5, 0xFFFFFFFF, true);
        if (c.needsScopes) {
            int bw = 90;
            int bx = menuX + MENU_W - bw - 6;
            HudPanel.button(g, font, "Re-login", bx, menuY + 3, bw, BTN_H, inRect(mouseX, mouseY, bx, menuY + 3, bw, BTN_H));
        }

        for (int i = 0; i < TABS.length; i++) {
            HudPanel.button(g, font, (i == tab ? "> " : "") + TABS[i], tabX(i), menuY + 18, 70, BTN_H,
                    i == tab || inRect(mouseX, mouseY, tabX(i), menuY + 18, 70, BTN_H));
        }

        List<SpotifyApi.Item> items = c.browserItems(tab);
        int pages = Math.max(1, (items.size() + ROWS - 1) / ROWS);
        page = Math.max(0, Math.min(page, pages - 1));

        if (items.isEmpty()) {
            String msg = c.browserLoading ? "Loading..." : emptyMessage();
            g.text(font, HudPanel.fit(font, msg, MENU_W - 16), menuX + 8, listY() + 6, 0xFFC8C8C8, false);
        }
        String note = c.browserNote;
        if (!note.isEmpty() && items.isEmpty()) {
            g.text(font, HudPanel.fit(font, note, MENU_W - 16), menuX + 8, listY() + 20, 0xFFFF7777, false);
        }

        for (int r = 0; r < ROWS; r++) {
            int idx = page * ROWS + r;
            if (idx >= items.size()) break;
            SpotifyApi.Item it = items.get(idx);
            int ry = listY() + r * ROW_H;
            boolean hover = inRect(mouseX, mouseY, menuX + 6, ry, MENU_W - 12, ROW_H - 1);
            HudPanel.round(g, menuX + 6, ry, MENU_W - 12, ROW_H - 1, hover ? 0xB0404040 : 0x80000000);
            int actionsW = actionable(tab) ? (it.track() ? 74 : 34) : 0;
            int textW = MENU_W - 24 - actionsW;
            g.text(font, HudPanel.fit(font, it.name(), textW), menuX + 10, ry + 2, 0xFFFFFFFF, false);
            g.text(font, HudPanel.fit(font, it.sub(), textW), menuX + 10, ry + 11, 0xFF9A9A9A, false);
            if (actionable(tab)) {
                int bx = playBtnX();
                HudPanel.button(g, font, "Play", bx, ry + 3, 30, 13, inRect(mouseX, mouseY, bx, ry + 3, 30, 13));
                if (it.track()) {
                    int qx = queueBtnX();
                    HudPanel.button(g, font, "+Q", qx, ry + 3, 34, 13, inRect(mouseX, mouseY, qx, ry + 3, 34, 13));
                }
            }
        }

        int fy = footY();
        HudPanel.button(g, font, "<", menuX + 6, fy, 30, BTN_H, inRect(mouseX, mouseY, menuX + 6, fy, 30, BTN_H));
        HudPanel.button(g, font, ">", menuX + 40, fy, 30, BTN_H, inRect(mouseX, mouseY, menuX + 40, fy, 30, BTN_H));
        String pg = (page + 1) + "/" + pages;
        g.text(font, pg, menuX + 76, fy + 3, 0xFFC8C8C8, false);
        HudPanel.button(g, font, "Refresh", menuX + MENU_W - 6 - 60, fy, 60, BTN_H,
                inRect(mouseX, mouseY, menuX + MENU_W - 66, fy, 60, BTN_H));
        HudPanel.button(g, font, "Back", menuX + MENU_W - 6 - 60 - 64, fy, 60, BTN_H,
                inRect(mouseX, mouseY, menuX + MENU_W - 130, fy, 60, BTN_H));
    }

    private int queueBtnX() {
        return menuX + MENU_W - 6 - 36;
    }

    private int playBtnX() {
        return queueBtnX() - 34;
    }

    private String emptyMessage() {
        return switch (tab) {
            case 0 -> "Queue is empty (or nothing is playing).";
            case 1 -> "No playlists found.";
            case 2 -> "No liked songs found.";
            default -> "Type  /spotify search <song or artist>  in chat.";
        };
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (c == null) return super.mouseClicked(event, doubleClick);
        place(c);
        double mx = event.x();
        double my = event.y();

        if (c.needsScopes && inRect(mx, my, menuX + MENU_W - 96, menuY + 3, 90, BTN_H)) {
            c.relogin();
            onClose();
            return true;
        }
        for (int i = 0; i < TABS.length; i++) {
            if (inRect(mx, my, tabX(i), menuY + 18, 70, BTN_H)) {
                tab = i;
                page = 0;
                c.loadTab(i, false);
                return true;
            }
        }
        int fy = footY();
        if (inRect(mx, my, menuX + 6, fy, 30, BTN_H)) {
            page = Math.max(0, page - 1);
            return true;
        }
        if (inRect(mx, my, menuX + 40, fy, 30, BTN_H)) {
            page++;
            return true;
        }
        if (inRect(mx, my, menuX + MENU_W - 66, fy, 60, BTN_H)) {
            c.loadTab(tab, true);
            return true;
        }
        if (inRect(mx, my, menuX + MENU_W - 130, fy, 60, BTN_H)) {
            Minecraft.getInstance().gui.setScreen(new SpotifyScreen());
            return true;
        }
        List<SpotifyApi.Item> items = c.browserItems(tab);
        for (int r = 0; r < ROWS; r++) {
            int idx = page * ROWS + r;
            if (idx >= items.size()) break;
            SpotifyApi.Item it = items.get(idx);
            int ry = listY() + r * ROW_H;
            if (!actionable(tab)) continue;
            if (inRect(mx, my, playBtnX(), ry + 3, 30, 13) || (!it.track() && inRect(mx, my, menuX + 6, ry, MENU_W - 12, ROW_H - 1))) {
                c.playItem(it);
                return true;
            }
            if (it.track() && inRect(mx, my, queueBtnX(), ry + 3, 34, 13)) {
                c.queueItem(it);
                return true;
            }
            if (it.track() && inRect(mx, my, menuX + 6, ry, MENU_W - 12, ROW_H - 1)) {
                c.playItem(it);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }
}
