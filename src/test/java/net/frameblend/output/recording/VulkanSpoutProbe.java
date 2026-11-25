package net.frameblend.output.recording;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.*;
import com.mojang.blaze3d.vulkan.checkpoints.NoopCheckpointExtension;
import net.frameblend.output.util.RingBufferCleanup;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.vulkan.VkDevice;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;

/** Opt-in native transport probe; creates its own sender, separate from OBS's source. */
public final class VulkanSpoutProbe {
    public static void main(String[] args) throws Exception {
        System.setProperty("frameblendoutput.spoutSenderName", "FBO Vulkan Probe");
        net.minecraft.SharedConstants.tryDetectVersion();
        var bootstrap = com.mojang.blaze3d.platform.NativeLibrariesBootstrap.class;
        var load = bootstrap.getDeclaredMethod("tryLoadingVulkan");
        load.setAccessible(true);
        if (!Boolean.TRUE.equals(load.invoke(null))) throw new AssertionError("Vulkan loader missing");
        set(bootstrap, "vulkanLoaderAvailable", true);
        if (!GLFW.glfwInit()) throw new AssertionError("GLFW initialization failed");
        GLFW.glfwDefaultWindowHints();
        new VulkanBackend().setWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long window = GLFW.glfwCreateWindow(16, 16, "FBO Vulkan transport verification", 0, 0);
        if (window == 0) throw new AssertionError("Hidden window failed");
        var constructor = VulkanInstance.class.getDeclaredConstructor(int.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        var instance = constructor.newInstance(0, false, false);
        var find = VulkanBackend.class.getDeclaredMethod("findPhysicalDevice", VulkanInstance.class);
        find.setAccessible(true);
        var physical = (VulkanPhysicalDevice)find.invoke(null, instance);
        Set<String> extensions = new HashSet<>(VulkanBackend.REQUIRED_DEVICE_EXTENSIONS);
        extensions.addAll(List.of("VK_KHR_external_memory", "VK_KHR_external_memory_win32",
                "VK_KHR_external_semaphore", "VK_KHR_external_semaphore_win32"));
        var create = VulkanBackend.class.getDeclaredMethod("createDevice", Collection.class, VulkanPhysicalDevice.class, Set.class);
        create.setAccessible(true);
        var vk = (VkDevice)create.invoke(null, extensions, physical, VulkanBackend.REQUIRED_DEVICE_FEATURES);
        var allocator = VulkanBackend.class.getDeclaredMethod("createVma", VkDevice.class);
        allocator.setAccessible(true);
        String fragment = Files.readString(Path.of("src/main/resources/assets/frameblendoutput/shaders/post/rolling_exposure.fsh"));
        String splitFragment = Files.readString(Path.of("src/main/resources/assets/frameblendoutput/shaders/post/rolling_exposure_split.fsh"));
        String vertex = """
                #version 450
                layout(location = 0) out vec2 texCoord;
                void main() {
                    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                    texCoord = p;
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                }
                """;
        var backend = new VulkanDevice((id, type) -> type == ShaderType.VERTEX ? vertex
                : id.getPath().endsWith("rolling_exposure_split") ? splitFragment : fragment, instance, physical, extensions, vk,
                (long)allocator.invoke(null, vk), NoopCheckpointExtension.INSTANCE);
        physical.close();
        var device = new GpuDevice(backend, () -> {});
        RenderSystem.initRenderThread();
        RenderSystem.initRenderer(device);
        boolean directXOnly = Boolean.getBoolean("fbo.directXOnly");
        if (!directXOnly) {
        System.load(Path.of("src/main/resources/natives/win64/SpoutLibrary.dll").toAbsolutePath().toString());
        System.load(Path.of("src/main/resources/natives/win64/fbo_spout_bridge.dll").toAbsolutePath().toString());
        var version = SpoutBridge.class.getDeclaredMethod("nBridgeVersion");
        version.setAccessible(true);
        if ((int)version.invoke(null) < 37) throw new AssertionError("Old bridge");
        set(SpoutBridge.class, "loadAttempted", true);
        set(SpoutBridge.class, "loaded", true);
        set(SpoutBridge.class, "modernBridge", true);
        }
        try {
            verifyRingCleanup(device);
            if (Boolean.getBoolean("fbo.benchmarkVulkanRecording")) benchmarkRolling(device, backend);
            if (directXOnly) return;
            run(device, 1920, 1080, 4000, true);
            // Pause/resume without reallocating the shared images.
            run(device, 1920, 1080, 40, false);
            if (!Boolean.getBoolean("frameblendoutput.copySpout") && !Boolean.getBoolean("frameblendoutput.synchronousSpout")) {
                runDirect(device, 1920, 1080, 2000);
                runDirect(device, 640, 360, 80);
                runDirect(device, 1920, 1080, 80);
            }
            // Resize forces a worker stop, context transfer, and native sender resize.
            run(device, 640, 360, 40, false);
            run(device, 1920, 1080, 40, false);
            System.out.println("Vulkan/GL/Spout pixel, pause/resume, resize and shutdown verification passed.");
        } catch (Throwable failure) {
            failure.printStackTrace();
            throw failure;
        } finally {
            if (directXOnly) SpoutFrameSender.shutdown();
            else {
            VulkanGlSharedSpoutSender.beginResourceReload();
            bindSharedContext();
            var release = SpoutBridge.class.getDeclaredMethod("nReleaseSender");
            release.setAccessible(true);
            release.invoke(null);
            VulkanGlSharedSpoutSender.destroy();
            }
            RollingExposureRenderer.invalidate();
            RenderSystem.shutdownRenderer();
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void runDirect(GpuDevice device, int width, int height, int frames) throws Exception {
        VulkanGlSharedSpoutSender.endResourceReload();
        int rendered = 0;
        long[] costs = new long[frames];
        for (int frame = 0; frame < frames; frame++) {
            long began = System.nanoTime();
            try (var direct = VulkanGlSharedSpoutSender.beginDirectFrame(width, height)) {
                if (direct != null) {
                    device.createCommandEncoder().clearColorTexture(direct.target().getColorTexture(), new Vector4f(1, 0, 0, 1));
                    direct.finish(true);
                    rendered++;
                }
            }
            device.createCommandEncoder().submit();
            VulkanGlSharedSpoutSender.onFrameSubmitted();
            long ended = System.nanoTime();
            costs[frame] = ended - began;
            while (System.nanoTime() - ended < 500_000L) Thread.onSpinWait();
        }
        VulkanGlSharedSpoutSender.beginResourceReload();
        if (rendered < frames / 2) throw new AssertionError("Too few direct frames: " + rendered);
        bindSharedContext();
        Object[] slots = (Object[])get(VulkanGlSharedSpoutSender.class, "slots");
        ByteBuffer pixels = org.lwjgl.system.MemoryUtil.memAlloc(width * height * 4);
        try {
            for (Object slot : slots) {
                if (get(slot, "renderTarget") == null) continue;
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, (int)get(slot, "glTexture"));
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                for (int pixel = 0; pixel < width * height; pixel++) {
                    int offset = pixel * 4;
                    if (Byte.toUnsignedInt(pixels.get(offset)) != 255 || pixels.get(offset + 1) != 0
                            || pixels.get(offset + 2) != 0 || Byte.toUnsignedInt(pixels.get(offset + 3)) != 255)
                        throw new AssertionError("Direct shared pixel corrupted at " + pixel);
                }
            }
        } finally { org.lwjgl.system.MemoryUtil.memFree(pixels); }
        Arrays.sort(costs);
        System.out.printf(Locale.ROOT, "Direct clear+signal+submit CPU: median %.1fus (%d/%d frames, %dx%d)%n", costs[frames / 2] / 1000.0, rendered, frames, width, height);
        // Closing an uncommitted reservation must drain its GPU ownership
        // without publishing the incomplete frame to the OBS sender.
        long sentBeforeCancel = (long)get(VulkanGlSharedSpoutSender.class, "sentFrames");
        VulkanGlSharedSpoutSender.endResourceReload();
        try (var cancelled = VulkanGlSharedSpoutSender.beginDirectFrame(width, height)) {
            if (cancelled == null) throw new AssertionError("No free direct slot after draining");
            device.createCommandEncoder().clearColorTexture(cancelled.target().getColorTexture(), new Vector4f(0, 0, 1, 1));
        }
        device.createCommandEncoder().submit();
        VulkanGlSharedSpoutSender.beginResourceReload();
        if ((long)get(VulkanGlSharedSpoutSender.class, "sentFrames") != sentBeforeCancel)
            throw new AssertionError("A cancelled frame was published");
    }

    private static void verifyRingCleanup(GpuDevice device) {
        GpuBuffer[] buffers = new GpuBuffer[9];
        GpuFence[] fences = new GpuFence[4];
        for (int i = 0; i < buffers.length; i++) buffers[i] = device.createBuffer(() -> "Ring cleanup probe",
                GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM, 32L);
        for (int i = 0; i < fences.length; i++) fences[i] = device.createCommandEncoder().createFence();
        device.createCommandEncoder().submit();
        GpuBuffer[] originals = buffers.clone();
        RingBufferCleanup.close(buffers, fences);
        RingBufferCleanup.close(buffers, fences);
        for (GpuBuffer buffer : originals) if (!buffer.isClosed()) throw new AssertionError("Ring buffer leaked");
        System.out.println("Vulkan ring cleanup passed with nine buffers, four fences, and repeated close.");
    }

    private static void benchmarkRolling(GpuDevice device, VulkanDevice backend) throws Exception {
        int width = 1920, height = 1080, frames = 240;
        var history = new ExposureFrameHistory<RenderTarget>();
        var old = history.capture(100, 100, () -> new TextureTarget("Old", width, height, false, GpuFormat.RGBA8_UNORM), true).target;
        var source = history.capture(110, 100, () -> new TextureTarget("Source", width, height, false, GpuFormat.RGBA8_UNORM), true).target;
        var current = new TextureTarget("Current", width, height, false, GpuFormat.RGBA8_UNORM);
        var previous = new TextureTarget("Previous", width, height, false, RecordingPipelineFormats.BLEND_FORMAT);
        var next = new TextureTarget("Next", width, height, false, RecordingPipelineFormats.BLEND_FORMAT);
        var output = new TextureTarget("Output", width, height, false, GpuFormat.RGBA8_UNORM);
        // Noise prevents a constant-color texture from hiding real memory costs.
        var pixels = org.lwjgl.system.MemoryUtil.memAlloc(width * height * 16);
        try {
            Random random = new Random(54);
            for (int i = 0; i < width * height; i++) {
                for (int c = 0; c < 3; c++) pixels.putFloat(random.nextFloat());
                pixels.putFloat(1.0f);
            }
            pixels.flip();
            var encoder = device.createCommandEncoder();
            encoder.writeToTexture(previous.getColorTexture(), pixels, 0, 0, 0, 0, width, height);
            pixels.clear().limit(width * height * 4);
            while (pixels.hasRemaining()) pixels.putInt(random.nextInt());
            pixels.flip();
            encoder.writeToTexture(current.getColorTexture(), pixels, 0, 0, 0, 0, width, height);
            encoder.writeToTexture(old.getColorTexture(), pixels, 0, 0, 0, 0, width, height);
            var delta = new RollingExposure<RenderTarget>();
            delta.rebased(100, 100);
            if (!delta.prepare(history, 110, 100)) throw new AssertionError("Rolling setup failed");
            var query = device.createTimestampQueryPool(frames * 2);
            for (int frame = 0; frame < frames; frame++) {
                CaptureGpuTimings.begin();
                encoder.writeTimestamp(query, frame * 2);
                if (!RollingExposureRenderer.render(current, previous, next, output, source, delta, 110))
                    throw new AssertionError("Production rolling render failed");
                encoder.writeTimestamp(query, frame * 2 + 1);
                CaptureGpuTimings.beforeSend();
                CaptureGpuTimings.end();
                encoder.submit();
            }
            backend.graphicsQueue().waitIdle();
            CaptureGpuTimings.report();
            var values = query.getValues(0, frames * 2);
            double[] gpuTimes = new double[frames - 40];
            for (int frame = 40; frame < frames; frame++) {
                gpuTimes[frame - 40] = (values[frame * 2 + 1].orElseThrow() - values[frame * 2].orElseThrow())
                        * (double)device.getDeviceInfo().timestampPeriod() / 1000.0;
            }
            Arrays.sort(gpuTimes);
            System.out.printf(Locale.ROOT, "Minecraft Vulkan rolling pass GPU: median %.1fus, p95 %.1fus%n",
                    gpuTimes[gpuTimes.length / 2], gpuTimes[gpuTimes.length * 95 / 100]);
            query.close();
            for (int mode = 0; mode < (Boolean.getBoolean("fbo.directXOnly") ? 1 : 3); mode++) {
                if (mode == 2 && (Boolean.getBoolean("frameblendoutput.copySpout") || Boolean.getBoolean("frameblendoutput.synchronousSpout"))) continue;
                VulkanGlSharedSpoutSender.endResourceReload();
                int combinedFrames = 2000;
                long began = System.nanoTime();
                for (int frame = 0; frame < combinedFrames; frame++) {
                    try (var direct = mode == 2 ? VulkanGlSharedSpoutSender.beginDirectFrame(width, height) : null) {
                        if (!RollingExposureRenderer.render(current, previous, next, direct != null ? direct.target() : output, source, delta, 110))
                            throw new AssertionError("Combined rolling render failed");
                        if (direct != null) direct.finish(true);
                        else if (mode != 0 && !VulkanGlSharedSpoutSender.send(output.getColorTexture(), width, height))
                            throw new AssertionError("Combined shared transport failed");
                    }
                    encoder.submit();
                    if (mode == 2) VulkanGlSharedSpoutSender.onFrameSubmitted();
                    long workStart = System.nanoTime();
                    while (System.nanoTime() - workStart < 500_000L) Thread.onSpinWait();
                }
                System.out.printf(Locale.ROOT, "Rolling + 0.5ms CPU work%s: %.0f fps%n",
                        mode == 0 ? "" : mode == 1 ? " + shared-copy Spout" : " + direct Spout", combinedFrames * 1e9 / (System.nanoTime() - began));
                VulkanGlSharedSpoutSender.beginResourceReload();
            }
            if (!Boolean.getBoolean("fbo.directXOnly") && !Boolean.getBoolean("frameblendoutput.copySpout") && !Boolean.getBoolean("frameblendoutput.synchronousSpout"))
                verifyDirectPixels(device, backend, output);
            if (Boolean.getBoolean("fbo.verifyDirectX"))
                DirectXSpoutProbe.run(device, backend, current, previous, next, output, source, delta);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(pixels);
            history.clear(RenderTarget::destroyBuffers);
            current.destroyBuffers(); previous.destroyBuffers(); next.destroyBuffers(); output.destroyBuffers();
            RollingExposureRenderer.invalidate();
        }
    }

    private static void verifyDirectPixels(GpuDevice device, VulkanDevice backend, RenderTarget reference) throws Exception {
        int bytes = reference.width * reference.height * 4;
        var readback = device.createBuffer(() -> "Direct output reference", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, bytes);
        var encoder = device.createCommandEncoder();
        encoder.copyTextureToBuffer(reference.getColorTexture(), readback, 0L, () -> {}, 0);
        encoder.submit();
        backend.graphicsQueue().waitIdle();
        bindSharedContext();
        var pixels = org.lwjgl.system.MemoryUtil.memAlloc(bytes);
        try (var view = readback.map(true, false)) {
            int compared = 0;
            for (Object slot : (Object[])get(VulkanGlSharedSpoutSender.class, "slots")) {
                if (get(slot, "renderTarget") == null) continue;
                compared++;
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, (int)get(slot, "glTexture"));
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                for (int i = 0; i < bytes; i++) {
                    if (Math.abs(Byte.toUnsignedInt(pixels.get(i)) - Byte.toUnsignedInt(view.data().get(i))) > 1)
                        throw new AssertionError("Direct blurred output differs at byte " + i);
                }
            }
            if (compared == 0) throw new AssertionError("No directly rendered images were compared");
            System.out.println("Direct blurred output matched the copied reference across every 1080p RGBA pixel in " + compared + " shared images.");
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(pixels);
            readback.close();
        }
    }

    private static void run(GpuDevice device, int width, int height, int count, boolean measure) throws Exception {
        VulkanGlSharedSpoutSender.endResourceReload();
        var source = new TextureTarget("FBO transport probe", width, height, false, GpuFormat.RGBA8_UNORM);
        device.createCommandEncoder().clearColorTexture(source.getColorTexture(), new Vector4f(1, 0, 0, 1));
        long[] times = new long[count];
        int submitted = 0;
        Object previousSlots = get(VulkanGlSharedSpoutSender.class, "slots");
        for (int frame = 0; frame < count + 100; frame++) {
            long start = System.nanoTime();
            if (!VulkanGlSharedSpoutSender.send(source.getColorTexture(), width, height))
                throw new AssertionError("Shared transport failed");
            device.createCommandEncoder().submit();
            long end = System.nanoTime();
            if (frame >= 100) times[frame - 100] = end - start;
            // Model the remaining ~0.5ms of a 1900fps game frame.
            while (System.nanoTime() - end < 500_000L) Thread.onSpinWait();
        }
        VulkanGlSharedSpoutSender.beginResourceReload();
        if (measure) VulkanGlSharedSpoutSender.reportTimings();
        if (Boolean.TRUE.equals(get(VulkanGlSharedSpoutSender.class, "disabled"))) throw new AssertionError("Transport disabled");
        bindSharedContext();
        Object[] slots = (Object[])previousSlots;
        for (Object slot : slots) {
            if (!Boolean.TRUE.equals(get(slot, "imageInitialized"))) continue;
            submitted++;
            int texture = (int)get(slot, "glTexture");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            ByteBuffer pixels = org.lwjgl.system.MemoryUtil.memAlloc(width * height * 4);
            try {
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                for (int pixel = 0; pixel < width * height; pixel++) {
                    int offset = pixel * 4;
                    if (Byte.toUnsignedInt(pixels.get(offset)) != 255 || pixels.get(offset + 1) != 0
                            || pixels.get(offset + 2) != 0 || Byte.toUnsignedInt(pixels.get(offset + 3)) != 255)
                        throw new AssertionError("Shared pixel corrupted at " + pixel);
                }
            } finally { org.lwjgl.system.MemoryUtil.memFree(pixels); }
        }
        if (submitted == 0) throw new AssertionError("No shared image was written");
        if (measure) {
            Arrays.sort(times);
            System.out.printf(Locale.ROOT, "Shared send+submit CPU: median %.1fus, p95 %.1fus (%s)%n",
                    times[count / 2] / 1000.0, times[(count * 95) / 100] / 1000.0,
                    Boolean.getBoolean("frameblendoutput.synchronousSpout") ? "synchronous" : "asynchronous");
        }
        source.destroyBuffers();
    }

    private static void bindSharedContext() throws Exception {
        GLFW.glfwMakeContextCurrent((long)get(VulkanGlSharedSpoutSender.class, "glWindow"));
        GL.setCapabilities((GLCapabilities)get(VulkanGlSharedSpoutSender.class, "glCapabilities"));
    }

    private static Object get(Object owner, String name) throws Exception {
        Class<?> type = owner instanceof Class<?> c ? c : owner.getClass();
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner instanceof Class<?> ? null : owner);
    }

    private static void set(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
