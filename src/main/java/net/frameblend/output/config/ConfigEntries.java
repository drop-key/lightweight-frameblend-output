package net.frameblend.output.config;

public class ConfigEntries {
    // Current-session state is separate from the saved startup preference.
    public transient boolean recordingOverlayEnabled = false;
    public int recordingOverlayTargetFPS = 60;
    public boolean enableOnStartup = false;
}
