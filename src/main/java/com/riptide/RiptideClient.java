package com.riptide;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class RiptideClient implements ClientModInitializer {
    private static volatile boolean openGui;

    @Override
    public void onInitializeClient() {
        RiptideConfig.load();
        AutoFisher.init();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("riptide").executes(ctx -> {
                    openGui = true; // opened next tick: the chat screen closes itself right after a command runs
                    return 1;
                })));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (openGui) {
                openGui = false;
                mc.setScreenAndShow(new RiptideScreen());
            }
        });
    }
}
