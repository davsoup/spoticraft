package dev.spotifyhud;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Base for the mod's screens. Overrides the vanilla menu background hooks so the world and the HUD stay sharp
 * behind the menus. These intentionally have no @Override: if a name doesn't exist in this Minecraft version the
 * method is simply unused.
 */
abstract class BaseScreen extends Screen {
    protected BaseScreen(Component title) {
        super(title);
    }

    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
    }

    public void extractTransparentBackground(GuiGraphicsExtractor g) {
    }

    public void extractBlurredBackground(GuiGraphicsExtractor g) {
    }

    public void extractMenuBackground(GuiGraphicsExtractor g) {
    }
}
