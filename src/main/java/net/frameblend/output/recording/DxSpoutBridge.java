package net.frameblend.output.recording;

import net.fabricmc.loader.api.FabricLoader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;

/** DirectX-only Spout publisher; no OpenGL context or CPU pixel transfer. */
final class DxSpoutBridge {
    private static boolean loaded;

    static void load() throws Exception {
        if (loaded) return;
        var directory = FabricLoader.getInstance().getConfigDir().resolve("frameblendoutput/natives");
        Files.createDirectories(directory);
        byte[] bytes;
        try (var input = DxSpoutBridge.class.getResourceAsStream("/natives/win64/fbo_spout_dx.dll")) {
            if (input == null) throw new IllegalStateException("DirectX Spout library missing");
            bytes = input.readAllBytes();
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var library = directory.resolve("fbo_spout_dx-" + hash.substring(0, 16) + ".dll");
        if (!Files.exists(library)) Files.write(library, bytes);
        System.load(library.toAbsolutePath().toString());
        loaded = true;
    }

    static native long nCreate(ByteBuffer adapterLuid, String sender, int width, int height, int slots);
    static native long nTextureHandle(long context, int slot);
    static native long nReadyHandle(long context);
    static native void nPublish(long context, int slot, long value, boolean publish);
    static native long nCompleted(long context);
    static native void nDrain(long context);
    static native void nDestroy(long context);
    static native boolean nReadSender(String sender, ByteBuffer pixels, int width, int height);
    static native int nVerifyStreaming(String sender, int width, int height, int frames);
}
