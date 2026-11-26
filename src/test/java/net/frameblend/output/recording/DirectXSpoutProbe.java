package net.frameblend.output.recording;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryUtil;
import java.nio.file.Path;
import java.util.Locale;

final class DirectXSpoutProbe {
    static void run(GpuDevice device, VulkanDevice backend, RenderTarget current, RenderTarget previous,
                    RenderTarget next, RenderTarget output, RenderTarget source, RollingExposure<RenderTarget> delta) throws Exception {
        System.load(Path.of("build/generated-resources/natives/win64/fbo_spout_dx.dll").toAbsolutePath().toString());
        var loaded = DxSpoutBridge.class.getDeclaredField("loaded"); loaded.setAccessible(true); loaded.set(null, true);
        if (!Boolean.getBoolean("fbo.directXOnly")) verifyReceiver(device, backend, output, "FBO Vulkan Probe");
        System.setProperty("frameblendoutput.spoutSenderName", "FBO DirectX Probe");
        try {
            for (int repeat = 0; repeat < (Boolean.getBoolean("fbo.directXOnly") ? 2 : 4); repeat++) {
                if (repeat % 2 == 1 && !Boolean.getBoolean("fbo.directXOnly")) benchmarkLegacy(device, current, previous, next, output, source, delta);
                int count = 2000;
                int published = 0;
                VulkanDxSpoutSender.resume();
                long start = System.nanoTime();
                for (int i = 0; i < count; i++) {
                    try (var direct = VulkanDxSpoutSender.begin(backend, output.width, output.height)) {
                        if (!RollingExposureRenderer.render(current, previous, next, direct == null ? output : direct.target(), source, delta, 110))
                            throw new AssertionError("DirectX rolling render failed");
                        if (direct != null) { direct.finish(true); published++; }
                        else if (!VulkanDxSpoutSender.active()) throw new AssertionError("DirectX transport failed");
                    }
                    device.createCommandEncoder().submit();
                    VulkanDxSpoutSender.onFrameSubmitted();
                    long work = System.nanoTime();
                    while (System.nanoTime() - work < 500_000L) Thread.onSpinWait();
                }
                System.out.printf(Locale.ROOT, "Rolling + 0.5ms CPU work + DirectX Spout: %.0f fps (%d/%d published)%n", count * 1e9 / (System.nanoTime() - start), published, count);
                VulkanDxSpoutSender.pause();
                verifyReceiver(device, backend, output, "FBO DirectX Probe");
            }
            VulkanDxSpoutSender.reportTimings();
            verifyLifecycle(device, backend);
            if (!Boolean.getBoolean("fbo.directXOnly")) verifyLegacyRouting(device, backend);
        } finally {
            VulkanDxSpoutSender.destroy();
            System.setProperty("frameblendoutput.spoutSenderName", "FBO Vulkan Probe");
        }
    }

    private static void verifyLegacyRouting(GpuDevice device, VulkanDevice backend) {
        int width = 640, height = 360;
        var reference = new com.mojang.blaze3d.pipeline.TextureTarget("Legacy routing", width, height, false, com.mojang.blaze3d.GpuFormat.RGBA8_UNORM);
        System.setProperty("frameblendoutput.glSpout", "true");
        try {
            SpoutFrameSender.endResourceReload();
            var color = new org.joml.Vector4f(0.2f, 0.5f, 0.8f, 1);
            device.createCommandEncoder().clearColorTexture(reference.getColorTexture(), color);
            try (var frame = SpoutFrameSender.beginDirectFrame(width, height)) {
                if (!(frame instanceof VulkanGlSharedSpoutSender.DirectFrame)) throw new AssertionError("Legacy transport routing failed");
                device.createCommandEncoder().clearColorTexture(frame.target().getColorTexture(), color);
                frame.finish(true);
            }
            device.createCommandEncoder().submit(); SpoutFrameSender.onFrameSubmitted();
            SpoutFrameSender.beginResourceReload();
            verifyReceiver(device, backend, reference, "FBO Vulkan Probe");
            System.out.println("Production sender routing to the legacy OpenGL transport passed.");
        } finally {
            System.clearProperty("frameblendoutput.glSpout");
            reference.destroyBuffers();
            SpoutFrameSender.endResourceReload();
        }
    }

    private static void verifyReceiver(GpuDevice device, VulkanDevice backend, RenderTarget reference, String sender) {
        int bytes = reference.width * reference.height * 4;
        var buffer = device.createBuffer(() -> "Spout receiver reference", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, bytes);
        var pixels = MemoryUtil.memAlloc(bytes);
        var encoder = device.createCommandEncoder();
        encoder.copyTextureToBuffer(reference.getColorTexture(), buffer, 0, () -> {}, 0);
        encoder.submit(); backend.graphicsQueue().waitIdle();
        try (var mapped = buffer.map(true, false)) {
            if (!DxSpoutBridge.nReadSender(sender, pixels, reference.width, reference.height)) throw new AssertionError("No independent receiver pixels");
            int normalMismatches = 0, flippedMismatches = 0;
            for (int i = 0; i < bytes; i++) {
                int a = Byte.toUnsignedInt(pixels.get(i)), b = Byte.toUnsignedInt(mapped.data().get(i));
                if (Math.abs(a - b) > 1) normalMismatches++;
                int rowBytes = reference.width * 4;
                int flippedIndex = (reference.height - 1 - i / rowBytes) * rowBytes + i % rowBytes;
                if (Math.abs(a - Byte.toUnsignedInt(mapped.data().get(flippedIndex))) > 1) flippedMismatches++;
            }
            if (flippedMismatches != 0) throw new AssertionError("Independent receiver image differs from the previous Spout output");
            System.out.println(sender + ": independent receiver matched every RGBA pixel and the previous output orientation.");
        } finally { MemoryUtil.memFree(pixels); buffer.close(); }
    }

    private static void verifyLifecycle(GpuDevice device, VulkanDevice backend) throws Exception {
        for (int width : new int[]{640, 1920, 640, 1920}) {
            int height = width * 9 / 16;
            var image = new com.mojang.blaze3d.pipeline.TextureTarget("DirectX resize pattern", width, height, false, com.mojang.blaze3d.GpuFormat.RGBA8_UNORM);
            var pixels = MemoryUtil.memAlloc(width * height * 4);
            try {
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) pixels.put((byte)x).put((byte)y).put((byte)(x ^ y)).put((byte)255);
                pixels.flip();
                device.createCommandEncoder().writeToTexture(image.getColorTexture(), pixels, 0, 0, 0, 0, width, height);
                SpoutFrameSender.endResourceReload();
                SpoutFrameSender.send(image, width, height);
                device.createCommandEncoder().submit(); SpoutFrameSender.onFrameSubmitted();
                SpoutFrameSender.beginResourceReload();
                verifyReceiver(device, backend, image, "FBO DirectX Probe");
                // Uncommitted writes must be drained, without replacing the last published image.
                SpoutFrameSender.endResourceReload();
                try (var cancelled = SpoutFrameSender.beginDirectFrame(width, height)) {
                    if (!(cancelled instanceof VulkanDxSpoutSender.Frame)) throw new AssertionError("DirectX reservation did not resume");
                    device.createCommandEncoder().clearColorTexture(cancelled.target().getColorTexture(), new org.joml.Vector4f(0, 0, 0, 1));
                }
                // Pause must submit the cancelled work itself before joining its publisher.
                SpoutFrameSender.beginResourceReload();
                verifyReceiver(device, backend, image, "FBO DirectX Probe");
            } finally { image.destroyBuffers(); MemoryUtil.memFree(pixels); }
        }
        verifyStreaming(device);
        SpoutFrameSender.beginResourceReload();
        VulkanDxSpoutSender.destroy();
        VulkanDxSpoutSender.destroy();
        SpoutFrameSender.endResourceReload();
        System.out.println("DirectX output passed production copy/direct routing, repeated resize, pause/resume, cancellation, concurrent receiver, and repeated shutdown.");
    }

    private static void verifyStreaming(GpuDevice device) throws Exception {
        int width = 640, height = 360;
        SpoutFrameSender.endResourceReload();
        try (var frame = SpoutFrameSender.beginDirectFrame(width, height)) {
            if (frame == null) throw new AssertionError("No streaming output image");
            device.createCommandEncoder().clearColorTexture(frame.target().getColorTexture(), new org.joml.Vector4f(0, 0, 0, 1));
            frame.finish(true);
        }
        device.createCommandEncoder().submit(); SpoutFrameSender.onFrameSubmitted();
        VulkanDxSpoutSender.pause(); VulkanDxSpoutSender.resume();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread receiver = new Thread(() -> {
            try {
                if (DxSpoutBridge.nVerifyStreaming("FBO DirectX Probe", width, height, 100) < 100) throw new AssertionError("Too few concurrent receiver frames");
            } catch (Throwable error) { failure.set(error); }
        }, "fbo-independent-spout-receiver");
        receiver.start();
        for (int i = 0; i < 2000; i++) {
            try (var frame = SpoutFrameSender.beginDirectFrame(width, height)) {
                if (frame != null) {
                    device.createCommandEncoder().clearColorTexture(frame.target().getColorTexture(), new org.joml.Vector4f((i & 1), ((i >> 1) & 1), ((i >> 2) & 1), 1));
                    frame.finish(true);
                }
            }
            device.createCommandEncoder().submit(); SpoutFrameSender.onFrameSubmitted();
            long work = System.nanoTime(); while (System.nanoTime() - work < 500_000L) Thread.onSpinWait();
        }
        receiver.join(5000);
        if (receiver.isAlive()) throw new AssertionError("Independent receiver hung");
        if (failure.get() != null) throw new AssertionError("Concurrent receiver failed", failure.get());
    }

    private static void benchmarkLegacy(GpuDevice device, RenderTarget current, RenderTarget previous, RenderTarget next,
                                        RenderTarget output, RenderTarget source, RollingExposure<RenderTarget> delta) {
        VulkanGlSharedSpoutSender.endResourceReload();
        int count = 2000;
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            try (var frame = VulkanGlSharedSpoutSender.beginDirectFrame(output.width, output.height)) {
                if (!RollingExposureRenderer.render(current, previous, next, frame == null ? output : frame.target(), source, delta, 110))
                    throw new AssertionError("Legacy rolling render failed");
                if (frame != null) frame.finish(true);
            }
            device.createCommandEncoder().submit();
            VulkanGlSharedSpoutSender.onFrameSubmitted();
            long work = System.nanoTime();
            while (System.nanoTime() - work < 500_000L) Thread.onSpinWait();
        }
        System.out.printf(Locale.ROOT, "Repeated rolling + CPU + legacy GL Spout: %.0f fps%n", count * 1e9 / (System.nanoTime() - start));
        VulkanGlSharedSpoutSender.beginResourceReload();
    }
}
