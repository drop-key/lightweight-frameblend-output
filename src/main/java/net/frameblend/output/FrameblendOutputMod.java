package net.frameblend.output;

import net.fabricmc.api.ClientModInitializer;
import net.frameblend.output.config.CommandManager;
import net.frameblend.output.config.ConfigManager;
import net.frameblend.output.config.EventManager;
import net.frameblend.output.recording.RecordingShaderManager;

public class FrameblendOutputMod implements ClientModInitializer {
    public static final String ID = "frameblendoutput";

    @Override
    public void onInitializeClient() {
        ConfigManager.loadConfig();
        CommandManager.registerCommands();
        EventManager.registerEvents();

        Runtime.getRuntime().addShutdownHook(new Thread(RecordingShaderManager::destroyFromAnyThread,
                "fbo-recording-output-cleanup"));
    }
}