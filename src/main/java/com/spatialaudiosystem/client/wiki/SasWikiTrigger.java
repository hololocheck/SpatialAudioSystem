package com.spatialaudiosystem.client.wiki;

import com.spatialaudiosystem.SpatialAudioSystem;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Runs the wiki screenshot pass shortly after joining a world, and offers
 * {@code /sas-wiki-capture} to redo it (also writing PNGs to {@code screenshots/wiki}).
 *
 * <p>The delay matters: at login the level and its resources are not settled enough to render a
 * screen faithfully, so the pass waits a couple of seconds instead of firing immediately.
 */
@EventBusSubscriber(modid = SpatialAudioSystem.MOD_ID, value = Dist.CLIENT)
public final class SasWikiTrigger {

    private static final int DELAY_TICKS = 60;   // ~3 s after join

    private static int ticksUntilCapture = -1;

    private SasWikiTrigger() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        ticksUntilCapture = DELAY_TICKS;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ticksUntilCapture = -1;
        SasWikiLiveCapture.clearCache();   // textures die with the session; retake on next join
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (ticksUntilCapture < 0) return;
        if (--ticksUntilCapture > 0) return;
        ticksUntilCapture = -1;
        // 起動前に screenshots/wiki/_request_png.txt を置くと、join 時の自動パスでも
        // PNG を保存する (runClient 全自動キャプチャ用。通常 join は従来どおり登録のみ)。
        // マーカーは「1 枚以上保存できた後」に消す — world 未準備で 0 枚のまま消費すると
        // 次回 login の retry が savePng=false で走り、PNG が永久に書かれないため。
        var req = java.nio.file.Paths.get(
                net.minecraft.client.Minecraft.getInstance().gameDirectory.getPath(),
                "screenshots", "wiki", "_request_png.txt");
        boolean savePng = false;
        try {
            savePng = java.nio.file.Files.exists(req);
        } catch (Throwable ignored) {}
        int captured = SasWikiLiveCapture.captureAll(savePng);
        if (savePng && captured > 0) {
            try {
                java.nio.file.Files.deleteIfExists(req);
            } catch (Throwable ignored) {}
        }
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("sas-wiki-capture").executes(ctx -> {
            SasWikiLiveCapture.clearCache();
            int n = SasWikiLiveCapture.captureAll(true);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[SAS Wiki] captured " + n + " screenshots (also written to screenshots/wiki)"), false);
            return n;
        }));
    }
}
