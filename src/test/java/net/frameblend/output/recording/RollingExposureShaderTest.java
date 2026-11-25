package net.frameblend.output.recording;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import static org.junit.Assert.*;
import static org.lwjgl.opengl.GL33.*;

public class RollingExposureShaderTest {
    private static final int W = 8, H = 8;
    private static final class Source {
        final int texture = texture(GL_RGBA8);
        final byte[] pixels = new byte[W * H * 4];
    }

    @Test
    public void rollingPixelsAndHistoryMatchAFullWindowReference() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("fbo.verifyGpu"));
        assertTrue(GLFW.glfwInit());
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(1, 1, "Rolling exposure verification", 0L, 0L);
        assertTrue(window != 0L);
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            for (boolean split : new boolean[]{false, true}) {
            Method compile = RecordingShaderTest.class.getDeclaredMethod("program", String.class);
            compile.setAccessible(true);
            String shader = Files.readString(Path.of("src/main/resources/assets/frameblendoutput/shaders/post/" + (split ? "rolling_exposure_split.fsh" : "rolling_exposure.fsh")));
            int regularProgram = (int)compile.invoke(null, shader);
            int seedProgram = split ? (int)compile.invoke(null, shader.replace("#version 330", "#version 330\n#define SEED_FROM_RGBA")) : regularProgram;
            for (int program : new int[]{regularProgram, seedProgram}) {
                int block = glGetUniformBlockIndex(program, "RollingParams");
                assertEquals(32, glGetActiveUniformBlocki(program, block, GL_UNIFORM_BLOCK_DATA_SIZE));
                glUniformBlockBinding(program, block, 0);
            }
            glBindVertexArray(glGenVertexArrays());
            glViewport(0, 0, W, H);
            glDisable(GL_FRAMEBUFFER_SRGB);
            int fbo = glGenFramebuffers(), ubo = glGenBuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glBindBuffer(GL_UNIFORM_BUFFER, ubo);
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, ubo);
            int read = texture(GL_RGBA32F), write = texture(GL_RGBA32F), output = texture(GL_RGBA8), currentTexture = texture(GL_RGBA8);
            int rgRead = split ? texture(GL_RG32F) : 0, rgWrite = split ? texture(GL_RG32F) : 0;
            int bRead = split ? texture(GL_R32F) : 0, bWrite = split ? texture(GL_R32F) : 0;
            boolean reseed = true;
            ExposureFrameHistory<Source> history = new ExposureFrameHistory<>();
            RollingExposure<Source> delta = new RollingExposure<>();
            Random random = new Random(4070);
            long now = 1_000_000_000L, exposure = 1_000_000_000L / 60;
            int updates = 0;
            for (int frameIndex = 0; frameIndex < 600; frameIndex++) {
                now += 250_000L + random.nextInt(1_000_000);
                Source current = history.capture(now, exposure, Source::new, true).target;
                for (int i = 0; i < W * H; i++) {
                    for (int c = 0; c < 3; c++) current.pixels[i * 4 + c] = (byte)(frameIndex % 200 < 60 ? 0 : frameIndex % 200 < 120 ? 255 : random.nextInt(256));
                    current.pixels[i * 4 + 3] = (byte)255;
                }
                double[] reference = new double[W * H * 4];
                for (ExposureFrameHistory.Frame<Source> frame : history.frames()) {
                    double weight = (double)frame.overlap(now - exposure, now) / exposure;
                    for (int i = 0; i < reference.length; i++) {
                        double c = Byte.toUnsignedInt(frame.target.pixels[i]) / 255.0;
                        reference[i] += c * c * weight;
                    }
                }
                upload(currentTexture, current.pixels);
                if (!delta.prepare(history, now, exposure)) {
                    upload(current.texture, current.pixels);
                    FloatBuffer pixels = BufferUtils.createFloatBuffer(reference.length);
                    for (double value : reference) pixels.put((float)value);
                    pixels.flip();
                    glBindTexture(GL_TEXTURE_2D, read);
                    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, H, GL_RGBA, GL_FLOAT, pixels);
                    delta.rebased(now, exposure);
                    reseed = true;
                } else {
                    updates++;
                    ByteBuffer params = BufferUtils.createByteBuffer(32);
                    for (int i = 0; i < 4; i++) params.putFloat(i < delta.removalCount() ? delta.removedWeight(i) : 0.0f);
                    params.putFloat(delta.addedWeight(now)).putFloat(0).putFloat(0).putFloat(0).flip();
                    glBufferData(GL_UNIFORM_BUFFER, params, GL_DYNAMIC_DRAW);
                    int program = reseed ? seedProgram : regularProgram;
                    glUseProgram(program);
                    if (split && !reseed) {
                        bind(program, "PreviousRGSampler", 0, rgRead);
                        bind(program, "PreviousBSampler", 1, bRead);
                    } else bind(program, "PreviousSampler", 0, read);
                    bind(program, "CurrentSampler", 2, currentTexture);
                    for (int i = 0; i < 4; i++) bind(program, "Removed" + i + "Sampler", i + 3,
                            i < delta.removalCount() ? delta.removedTarget(i).texture : currentTexture);
                    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, split ? rgWrite : write, 0);
                    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, split ? bWrite : output, 0);
                    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, split ? output : current.texture, 0);
                    if (split) glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT3, GL_TEXTURE_2D, current.texture, 0);
                    glDrawBuffers(split ? new int[]{GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2, GL_COLOR_ATTACHMENT3}
                            : new int[]{GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2});
                    assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
                    glDrawArrays(GL_TRIANGLES, 0, 3);
                    ByteBuffer pixels = BufferUtils.createByteBuffer(reference.length);
                    glReadBuffer(split ? GL_COLOR_ATTACHMENT2 : GL_COLOR_ATTACHMENT1);
                    glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                    for (int i = 0; i < reference.length; i++) {
                        int expected = (int)Math.round(Math.sqrt(Math.max(reference[i], 0.0)) * 255.0);
                        int actual = Byte.toUnsignedInt(pixels.get(i));
                        assertTrue("Rolling GPU pixel differs from full exposure", Math.abs(expected - actual) <= 1);
                    }
                    glReadBuffer(split ? GL_COLOR_ATTACHMENT3 : GL_COLOR_ATTACHMENT2);
                    glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                    for (int i = 0; i < reference.length; i++) assertEquals("Source history changed", current.pixels[i], pixels.get(i));
                    if (split) {
                        int swap = rgRead; rgRead = rgWrite; rgWrite = swap;
                        swap = bRead; bRead = bWrite; bWrite = swap;
                    } else { int swap = read; read = write; write = swap; }
                    reseed = false;
                    delta.updated(now);
                }
                history.releaseRetired();
            }
            assertTrue(updates > 550);
            assertEquals(GL_NO_ERROR, glGetError());
            history.clear(frame -> glDeleteTextures(frame.texture));
            glDeleteProgram(regularProgram);
            if (seedProgram != regularProgram) glDeleteProgram(seedProgram);
            }
        } finally {
            GLFW.glfwMakeContextCurrent(0L);
            GL.setCapabilities(null);
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static int texture(int format) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, format, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer)null);
        return id;
    }

    private static void upload(int texture, byte[] data) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(data.length).put(data).flip();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    }

    private static void bind(int program, String name, int unit, int texture) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform1i(glGetUniformLocation(program, name), unit);
    }
}
