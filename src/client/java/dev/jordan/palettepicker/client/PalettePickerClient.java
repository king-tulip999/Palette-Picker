package dev.jordan.palettepicker.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.jordan.palettepicker.core.ShareCode;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Entry point: registers the open-picker key (default B; vanilla uses P for Social Interactions). */
public class PalettePickerClient implements ClientModInitializer {
    public static final String MOD_ID = "palettepicker";

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.palettepicker.open", InputConstants.Type.KEYBOARD, InputConstants.KEY_B, CATEGORY));
        // Remember palette codes others post, since chat text can't be copied in game. Plugin-relayed chat arrives as game messages.
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, type, time) -> rememberCode(message.getString()));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) rememberCode(message.getString()); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (LayoutAudit.ENABLED) LayoutAudit.tick(client);
            if (Showcase.ENABLED) Showcase.tick(client);
            while (openKey.consumeClick()) {
                if (client.gui.screen() == null && client.player != null) client.setScreenAndShow(new SetupScreen(PickerState.get()));
            }
        });
    }

    private static final Pattern CODE = Pattern.compile(Pattern.quote(ShareCode.PREFIX) + "[A-Za-z0-9_-]+");

    private static void rememberCode(String text) {
        Matcher m = CODE.matcher(text);
        if (m.find()) PickerState.lastChatCode = m.group();
    }
}
