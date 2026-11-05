package dev.itsastronuts.cobbleupgrader.client;

import dev.itsastronuts.cobbleupgrader.net.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public class CobbleUpgraderClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Data.TYPE, (payload, context) -> {
            Minecraft mc = context.client();
            if (mc.screen instanceof UpgraderScreen screen) {
                screen.updateData(payload);
            } else if (payload.open()) {
                mc.setScreen(new UpgraderScreen(payload));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Spin.TYPE, (payload, context) -> {
            if (context.client().screen instanceof UpgraderScreen screen) screen.onSpin(payload);
        });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Result.TYPE, (payload, context) -> {
            if (context.client().screen instanceof UpgraderScreen screen) screen.onResult(payload);
        });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Error.TYPE, (payload, context) -> {
            if (context.client().screen instanceof UpgraderScreen screen) screen.onError(payload);
        });
    }
}
