package net.frameblend.output.recording;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.Arrays;
import static org.lwjgl.opengl.GL33.*;

/** Isolated GPU timing; this does not measure Minecraft or Spout performance. */
public class RecordingShaderBenchmark {
    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(1, 1, "FBO shader benchmark", 0L, 0L);
        if (window == 0L) throw new IllegalStateException("Hidden GL context failed");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("GPU: " + glGetString(GL_RENDERER));
            Method compile = RecordingShaderTest.class.getDeclaredMethod("program", String.class);
            compile.setAccessible(true);
            int[] programs = {
                    (int)compile.invoke(null, Files.readString(Path.of(args[0]))),
                    (int)compile.invoke(null, Files.readString(Path.of(args[1])))
            };
            int w = 1920, h = 1080;
            glBindVertexArray(glGenVertexArrays());
            glViewport(0, 0, w, h);
            glDisable(GL_FRAMEBUFFER_SRGB);
            int fbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            int output = texture(w, h, null);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, output, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("FBO incomplete");
            ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
            int random = 4070;
            for (int i = 0; i < w * h; i++) {
                random ^= random << 13; random ^= random >>> 17; random ^= random << 5;
                pixels.putInt(random | 0xff000000);
            }
            pixels.flip();
            int[] sources = new int[24];
            for (int i = 0; i < sources.length; i++) sources[i] = texture(w, h, pixels);
            int rollingFbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, rollingFbo);
            int sumOutput = texture(w, h, null, GL_RGBA32F);
            int copiedSource = texture(w, h, null);
            int previous = texture(w, h, pixels, GL_RGBA32F);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, sumOutput, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, output, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, copiedSource, 0);
            glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2});
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("MRT FBO incomplete");
            int rollingUbo = glGenBuffers();
            glBindBuffer(GL_UNIFORM_BUFFER, rollingUbo);
            ByteBuffer delta = BufferUtils.createByteBuffer(32);
            delta.putFloat(0.04f).putFloat(0.04f).putFloat(0).putFloat(0).putFloat(0.08f).putFloat(0).putFloat(0).putFloat(0).flip();
            glBufferData(GL_UNIFORM_BUFFER, delta, GL_DYNAMIC_DRAW);
            int ubo = glGenBuffers();
            glBindBuffer(GL_UNIFORM_BUFFER, ubo);
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, ubo);
            for (int program : programs) {
                glUseProgram(program);
                glUniformBlockBinding(program, glGetUniformBlockIndex(program, program == programs[0] ? "FrameBlendParamsUniforms" : "RollingParams"), 0);
                for (int i = 0; i < 24; i++) {
                    glActiveTexture(GL_TEXTURE0 + i);
                    glBindTexture(GL_TEXTURE_2D, sources[i]);
                    glUniform1i(glGetUniformLocation(program, "Sample" + i + "Sampler"), i);
                }
            }
            glUseProgram(programs[1]);
            glActiveTexture(GL_TEXTURE0 + 24);
            glBindTexture(GL_TEXTURE_2D, previous);
            glUniform1i(glGetUniformLocation(programs[1], "PreviousSampler"), 24);
            glUniform1i(glGetUniformLocation(programs[1], "CurrentSampler"), 0);
            for (int i = 0; i < 4; i++) glUniform1i(glGetUniformLocation(programs[1], "Removed" + i + "Sampler"), i + 1);
            for (int count : new int[]{8, 16, 24}) {
                ByteBuffer params = BufferUtils.createByteBuffer(112);
                params.putFloat(1.0f).putInt(count);
                for (int i = 0; i < 24; i++) params.putFloat(i < count ? 1.0f / count : 0.0f);
                params.putInt(0).putFloat(0.0f).flip();
                glBufferData(GL_UNIFORM_BUFFER, params, GL_DYNAMIC_DRAW);
                for (int index = 0; index < 2; index++) {
                    glUseProgram(programs[index]);
                    glBindFramebuffer(GL_FRAMEBUFFER, index == 0 ? fbo : rollingFbo);
                    glBindBufferBase(GL_UNIFORM_BUFFER, 0, index == 0 ? ubo : rollingUbo);
                    for (int i = 0; i < 32; i++) glDrawArrays(GL_TRIANGLES, 0, 3);
                }
                glFinish();
                double[][] times = new double[2][10];
                for (int run = 0; run < 10; run++) {
                    for (int p = 0; p < 2; p++) {
                        int index = (p + run) % 2;
                        glUseProgram(programs[index]);
                        glBindFramebuffer(GL_FRAMEBUFFER, index == 0 ? fbo : rollingFbo);
                        glBindBufferBase(GL_UNIFORM_BUFFER, 0, index == 0 ? ubo : rollingUbo);
                        int query = glGenQueries();
                        glBeginQuery(GL_TIME_ELAPSED, query);
                        for (int i = 0; i < 32; i++) glDrawArrays(GL_TRIANGLES, 0, 3);
                        glEndQuery(GL_TIME_ELAPSED);
                        times[index][run] = glGetQueryObjecti64(query, GL_QUERY_RESULT) / 32.0 / 1000.0;
                        glDeleteQueries(query);
                    }
                }
                Arrays.sort(times[0]); Arrays.sort(times[1]);
                System.out.printf("1080p, %d samples: full blend %.2f us; rolling + output + history %.2f us; change %.1f%%%n",
                        count, times[0][5], times[1][5], 100.0 * (times[1][5] / times[0][5] - 1.0));
            }
            if (glGetError() != GL_NO_ERROR) throw new AssertionError("GL error");
        } finally {
            GLFW.glfwMakeContextCurrent(0L);
            GL.setCapabilities(null);
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static int texture(int w, int h, ByteBuffer data) {
        return texture(w, h, data, GL_RGBA8);
    }

    private static int texture(int w, int h, ByteBuffer data, int format) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, format, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
        return texture;
    }
}
