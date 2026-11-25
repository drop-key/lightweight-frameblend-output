package net.frameblend.output.config;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

public class CommandManager {
    public static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("frameblend")
                    .executes(context -> { ConfigManager.openConfigScreen(); return 1; }));
            dispatcher.register(ClientCommands.literal("fbo")
                    .executes(context -> dispatcher.execute("frameblend", context.getSource())));
        });
    }
}
