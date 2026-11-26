package net.frameblend.output.recording;

import org.junit.Test;
import org.junit.Assume;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33;
import static org.lwjgl.util.shaderc.Shaderc.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;
import static org.lwjgl.opengl.GL33.*;

/** Optional, invisible GPU test: ./gradlew test -PverifyRecordingGpu --rerun-tasks */
public class RecordingShaderTest {
    private static final int W = 8, H = 8;
    private static final Path SHADERS = Path.of("src/main/resources/assets/frameblendoutput/shaders/post");

    @Test
    public void shadersCompileForVulkan() throws Exception {
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_auto_bind_uniforms(options, true);
            shaderc_compile_options_set_auto_map_locations(options, true);
            for (String name : new String[]{"frame_blending.fsh", "frame_blending_gl.fsh", "frame_resolve.fsh", "frame_blending_direct.fsh", "frame_blending_direct_gl.fsh", "rolling_exposure.fsh", "rolling_exposure_split.fsh"}) {
                String source = Files.readString(SHADERS.resolve(name)).replace("#version 330", "#version 450");
                long result = shaderc_compile_into_spv(compiler, source, shaderc_fragment_shader, name, "main", options);
                try {
                    assertEquals(shaderc_result_get_error_message(result), shaderc_compilation_status_success,
                            shaderc_result_get_compilation_status(result));
                } finally {
                    shaderc_result_release(result);
                }
            }
        } finally {
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    @Test
    public void batchedShadersMatchTheFullPrecisionWeightedImage() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("fbo.verifyGpu"));
        assertTrue("GLFW initialization failed", GLFW.glfwInit());
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(1, 1, "OBS shader verification", 0L, 0L);
        assertTrue("Could not create hidden GL context", window != 0L);
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("Shader validation GPU: " + glGetString(GL_RENDERER));
            glBindVertexArray(glGenVertexArrays());
            glViewport(0, 0, W, H);
            glDisable(GL_FRAMEBUFFER_SRGB);
            int resolve = program(Files.readString(SHADERS.resolve("frame_resolve.fsh")));
            for (int batchSize : new int[]{12, 24}) {
                String shader = batchSize == 12 ? "frame_blending_gl.fsh" : "frame_blending.fsh";
                int blend = program(Files.readString(SHADERS.resolve(shader)));
                int block = glGetUniformBlockIndex(blend, "FrameBlendParamsUniforms");
                assertTrue(block != GL_INVALID_INDEX);
                assertEquals(112, glGetActiveUniformBlocki(blend, block, GL_UNIFORM_BLOCK_DATA_SIZE));
                glUniformBlockBinding(blend, block, 0);
                for (int samples : new int[]{1, 12, 24, 25, 34, 73}) {
                    verifyPixels(blend, resolve, batchSize, samples, false);
                }
                glDeleteProgram(blend);
                int direct = program(Files.readString(SHADERS.resolve(batchSize == 12
                        ? "frame_blending_direct_gl.fsh" : "frame_blending_direct.fsh")));
                glUniformBlockBinding(direct, glGetUniformBlockIndex(direct, "FrameBlendParamsUniforms"), 0);
                for (int samples : new int[]{1, batchSize - 1, batchSize}) {
                    verifyPixels(direct, resolve, batchSize, samples, true);
                }
                glDeleteProgram(direct);
            }
            glDeleteProgram(resolve);
        } finally {
            GLFW.glfwMakeContextCurrent(0L);
            GL.setCapabilities(null);
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void verifyPixels(int blend, int resolve, int batchSize, int samples, boolean direct) {
        int[] sources = new int[samples];
        double[] weights = new double[samples];
        double totalWeight = 0.0;
        for (int i = 0; i < samples; i++) { weights[i] = 1.0 + (i * 7 % 13); totalWeight += weights[i]; }
        for (int i = 0; i < samples; i++) {
            ByteBuffer image = BufferUtils.createByteBuffer(W * H * 4);
            for (int pixel = 0; pixel < W * H; pixel++) {
                for (int c = 0; c < 3; c++) image.put((byte)value(i, pixel, c));
                image.put((byte)255);
            }
            sources[i] = texture(GL_RGBA8, image.flip());
        }
        int read = texture(GL_RGBA32F, null), write = texture(GL_RGBA32F, null);
        int output = texture(GL_RGBA8, null);
        int fbo = glGenFramebuffers(), ubo = glGenBuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glBindBuffer(GL_UNIFORM_BUFFER, ubo);
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, ubo);
        for (int offset = 0; offset < samples; offset += batchSize) {
            int count = Math.min(batchSize, samples - offset);
            ByteBuffer params = BufferUtils.createByteBuffer(112);
            params.putFloat(1.0f).putInt(count);
            for (int i = 0; i < 24; i++) params.putFloat(i < count ? (float)(weights[offset + i] / totalWeight) : 0.0f);
            params.putInt(offset > 0 ? 1 : 0).putFloat(0.0f).flip();
            glBufferData(GL_UNIFORM_BUFFER, params, GL_DYNAMIC_DRAW);
            glUseProgram(blend);
            bind(blend, "PreviousSumSampler", 0, read);
            for (int i = 0; i < batchSize; i++) bind(blend, "Sample" + i + "Sampler", i + 1, sources[offset + Math.min(i, count - 1)]);
            drawTo(direct ? output : write);
            int swap = read; read = write; write = swap;
        }
        if (!direct) {
            glUseProgram(resolve);
            bind(resolve, "InSampler", 0, read);
            drawTo(output);
        }
        ByteBuffer result = BufferUtils.createByteBuffer(W * H * 4);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, result);
        for (int pixel = 0; pixel < W * H; pixel++) {
            for (int c = 0; c < 3; c++) {
                double linear = 0.0;
                for (int i = 0; i < samples; i++) {
                    double channel = value(i, pixel, c) / 255.0;
                    linear += channel * channel * weights[i] / totalWeight;
                }
                int expected = (int)Math.round(Math.sqrt(linear) * 255.0);
                int actual = Byte.toUnsignedInt(result.get(pixel * 4 + c));
                assertTrue("Pixel mismatch: samples=" + samples + ", batch=" + batchSize + ", expected=" + expected + ", actual=" + actual,
                        Math.abs(actual - expected) <= 1);
            }
        }
        assertEquals(GL_NO_ERROR, glGetError());
        for (int source : sources) glDeleteTextures(source);
        glDeleteTextures(read); glDeleteTextures(write); glDeleteTextures(output);
        glDeleteFramebuffers(fbo); glDeleteBuffers(ubo);
    }

    private static int value(int sample, int pixel, int channel) { return (sample * 37 + pixel * 19 + channel * 71) & 255; }

    private static void bind(int program, String name, int unit, int texture) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform1i(glGetUniformLocation(program, name), unit);
    }

    private static void drawTo(int target) {
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static int texture(int format, ByteBuffer bytes) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, format, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
        return texture;
    }

    private static int program(String fragment) {
        int vertex = compile(GL_VERTEX_SHADER, """
                #version 330
                out vec2 texCoord;
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    texCoord = p;
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                }
                """);
        int frag = compile(GL_FRAGMENT_SHADER, fragment);
        int program = glCreateProgram();
        glAttachShader(program, vertex); glAttachShader(program, frag);
        glLinkProgram(program);
        assertEquals(glGetProgramInfoLog(program), GL_TRUE, glGetProgrami(program, GL_LINK_STATUS));
        glDeleteShader(vertex); glDeleteShader(frag);
        return program;
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source); glCompileShader(shader);
        assertEquals(glGetShaderInfoLog(shader), GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS));
        return shader;
    }
}
