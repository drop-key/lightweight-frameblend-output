package net.frameblend.output.recording;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.joml.Vector4f;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.glfw.GLFW;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

/** Runs the production MRT pass through Minecraft's actual Vulkan encoder. */
public class RollingVulkanIntegrationTest {
    @Test
    public void minecraftVulkanPassProducesExpectedPixels() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("fbo.verifyGpu"));
        net.minecraft.SharedConstants.tryDetectVersion();
        // The standalone test skips Minecraft.main's native-library bootstrap.
        Class<?> bootstrap = com.mojang.blaze3d.platform.NativeLibrariesBootstrap.class;
        var probe = bootstrap.getDeclaredMethod("tryLoadingVulkan");
        probe.setAccessible(true);
        assertEquals(Boolean.TRUE, probe.invoke(null));
        var available = bootstrap.getDeclaredField("vulkanLoaderAvailable");
        available.setAccessible(true);
        available.setBoolean(null, true);
        assertTrue(GLFW.glfwInit());
        GLFW.glfwDefaultWindowHints();
        VulkanBackend backend = new VulkanBackend();
        backend.setWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long window = GLFW.glfwCreateWindow(16, 16, "FBO Vulkan verification", 0L, 0L);
        assertTrue(window != 0L);
        GpuDevice device = null;
        try {
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
            device = backend.createDevice(window, (id, type) -> type == ShaderType.VERTEX ? vertex
                            : id.getPath().endsWith("rolling_exposure_split") ? splitFragment : fragment,
                    new GpuDebugOptions(0, false, false, false), () -> {});
            RenderSystem.initRenderThread();
            RenderSystem.initRenderer(device);
            RollingExposureRenderer.invalidate();
            ExposureFrameHistory<RenderTarget> history = new ExposureFrameHistory<>();
            RenderTarget old = history.capture(100L, 100L, () -> target("Old", GpuFormat.RGBA8_UNORM), true).target;
            RenderTarget sourceHistory = history.capture(110L, 100L, () -> target("History", GpuFormat.RGBA8_UNORM), true).target;
            RenderTarget current = target("Current", GpuFormat.RGBA8_UNORM);
            RenderTarget previous = target("Previous", RecordingPipelineFormats.BLEND_FORMAT);
            RenderTarget next = target("Next", RecordingPipelineFormats.BLEND_FORMAT);
            RenderTarget output = target("Output", GpuFormat.RGBA8_UNORM);
            var encoder = device.createCommandEncoder();
            encoder.clearColorTexture(old.getColorTexture(), new Vector4f(0, 1, 1, 1));
            encoder.clearColorTexture(current.getColorTexture(), new Vector4f(1, 0, 0.6f, 1));
            encoder.clearColorTexture(previous.getColorTexture(), new Vector4f(0, 1, 1, 1));
            RollingExposure<RenderTarget> delta = new RollingExposure<>();
            delta.rebased(100L, 100L);
            assertTrue(delta.prepare(history, 110L, 100L));
            assertTrue("Production Vulkan render failed", RollingExposureRenderer.render(current, previous, next, output, sourceHistory, delta, 110L));
            delta.updated(110L);
            RenderTarget secondHistory = history.capture(120L, 100L, () -> target("History 2", GpuFormat.RGBA8_UNORM), true).target;
            assertTrue(delta.prepare(history, 120L, 100L));
            assertTrue(RollingExposureRenderer.render(current, previous, next, output, secondHistory, delta, 120L));
            boolean split = RollingExposureRenderer.usesSplitState();
            float blue = 153.0f / 255.0f;
            float expectedBlue = split ? 0.8f + 0.2f * blue * blue : 0.9f + 0.1f * blue * blue;
            assertEquals(!Boolean.getBoolean("frameblendoutput.rgbaRolling"), split);
            // The regular split pass must use its own updated mean instead of
            // reseeding from the still-green full-rebuild texture each frame.
            RenderTarget state = next;
            RenderTarget blueState = null;
            if (split) {
                var rg = RollingExposureRenderer.class.getDeclaredField("splitRGRead"); rg.setAccessible(true);
                var b = RollingExposureRenderer.class.getDeclaredField("splitBRead"); b.setAccessible(true);
                state = (RenderTarget)rg.get(null);
                blueState = (RenderTarget)b.get(null);
            }
            GpuBuffer readback = device.createBuffer(() -> "FBO Vulkan verification readback",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 16L * 16 * 4);
            GpuBuffer historyReadback = device.createBuffer(() -> "FBO Vulkan history readback",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 16L * 16 * 4);
            GpuBuffer linearReadback = device.createBuffer(() -> "FBO Vulkan linear readback",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 16L * 16 * (split ? 8 : 16));
            GpuBuffer blueReadback = split ? device.createBuffer(() -> "FBO Vulkan B readback",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 16L * 16 * 4) : null;
            encoder.copyTextureToBuffer(output.getColorTexture(), readback, 0L, () -> {}, 0);
            encoder.copyTextureToBuffer(secondHistory.getColorTexture(), historyReadback, 0L, () -> {}, 0);
            encoder.copyTextureToBuffer(state.getColorTexture(), linearReadback, 0L, () -> {}, 0);
            if (split) encoder.copyTextureToBuffer(blueState.getColorTexture(), blueReadback, 0L, () -> {}, 0);
            encoder.submit();
            Field field = GpuDevice.class.getDeclaredField("backend");
            field.setAccessible(true);
            ((VulkanDevice)field.get(device)).graphicsQueue().waitIdle();
            try (var view = readback.map(0L, 16L * 16 * 4, true, false)) {
                var pixels = view.data();
                for (int pixel = 0; pixel < 16 * 16; pixel++) {
                    int expectedRed = split ? 114 : 81, expectedGreen = split ? 228 : 242;
                    assertTrue(Math.abs(Byte.toUnsignedInt(pixels.get(pixel * 4)) - expectedRed) <= 1);
                    assertTrue(Math.abs(Byte.toUnsignedInt(pixels.get(pixel * 4 + 1)) - expectedGreen) <= 1);
                    assertTrue(Math.abs(Byte.toUnsignedInt(pixels.get(pixel * 4 + 2)) - Math.round(Math.sqrt(expectedBlue) * 255)) <= 1);
                    assertEquals(255, Byte.toUnsignedInt(pixels.get(pixel * 4 + 3)));
                }
            }
            try (var view = historyReadback.map(0L, 16L * 16 * 4, true, false)) {
                var pixels = view.data();
                for (int pixel = 0; pixel < 16 * 16; pixel++) {
                    assertEquals(255, Byte.toUnsignedInt(pixels.get(pixel * 4)));
                    assertEquals(0, Byte.toUnsignedInt(pixels.get(pixel * 4 + 1)));
                    assertEquals(153, Byte.toUnsignedInt(pixels.get(pixel * 4 + 2)));
                    assertEquals(255, Byte.toUnsignedInt(pixels.get(pixel * 4 + 3)));
                }
            }
            try (var view = linearReadback.map(true, false)) {
                var pixels = view.data().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
                for (int pixel = 0; pixel < 16 * 16; pixel++) {
                    assertEquals(split ? 0.2f : 0.1f, pixels.get(pixel * (split ? 2 : 4)), 0.000001f);
                    assertEquals(split ? 0.8f : 0.9f, pixels.get(pixel * (split ? 2 : 4) + 1), 0.000001f);
                    if (!split) assertEquals(expectedBlue, pixels.get(pixel * 4 + 2), 0.000001f);
                }
            }
            if (split) {
                try (var view = blueReadback.map(true, false)) {
                    var pixels = view.data().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
                    for (int pixel = 0; pixel < 16 * 16; pixel++) assertEquals(expectedBlue, pixels.get(pixel), 0.000001f);
                }
                blueReadback.close();
            }
            // Force a new full-history result; the next update must reseed the
            // split state instead of continuing its previous incremental mean.
            history.reset();
            RenderTarget freshOld = history.capture(200L, 100L, () -> target("Rebase old", GpuFormat.RGBA8_UNORM), true).target;
            RenderTarget freshHistory = history.capture(210L, 100L, () -> target("Rebase history", GpuFormat.RGBA8_UNORM), true).target;
            encoder.clearColorTexture(freshOld.getColorTexture(), new Vector4f(0, 1, 1, 1));
            delta.rebased(200L, 100L);
            RollingExposureRenderer.rebased();
            assertTrue(delta.prepare(history, 210L, 100L));
            assertTrue(RollingExposureRenderer.render(current, previous, next, output, freshHistory, delta, 210L));
            encoder.copyTextureToBuffer(output.getColorTexture(), readback, 0L, () -> {}, 0);
            encoder.submit();
            ((VulkanDevice)field.get(device)).graphicsQueue().waitIdle();
            try (var view = readback.map(true, false)) {
                for (int pixel = 0; pixel < 16 * 16; pixel++) {
                    assertTrue(Math.abs(Byte.toUnsignedInt(view.data().get(pixel * 4)) - 81) <= 1);
                    assertTrue(Math.abs(Byte.toUnsignedInt(view.data().get(pixel * 4 + 1)) - 242) <= 1);
                    assertTrue(Math.abs(Byte.toUnsignedInt(view.data().get(pixel * 4 + 2)) - Math.round(Math.sqrt(0.9f + 0.1f * blue * blue) * 255)) <= 1);
                }
            }
            readback.close();
            historyReadback.close();
            linearReadback.close();
            history.clear(RenderTarget::destroyBuffers);
            for (RenderTarget target : new RenderTarget[]{current, previous, next, output}) target.destroyBuffers();
        } finally {
            if (device != null) {
                RollingExposureRenderer.invalidate();
                RenderSystem.shutdownRenderer();
            }
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static RenderTarget target(String name, GpuFormat format) {
        return new TextureTarget("FBO Vulkan test / " + name, 16, 16, false, format);
    }
}
