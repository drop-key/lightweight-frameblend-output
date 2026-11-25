package net.frameblend.output.recording;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import static org.lwjgl.opengl.GL33.*;

/** Measures the bundled native sender in its own process, with a separate sender name. */
public class SpoutTransportBenchmark {
    public static void main(String[] args) throws Exception {
        Path natives = Path.of("src/main/resources/natives/win64").toAbsolutePath();
        System.load(natives.resolve("SpoutLibrary.dll").toString());
        System.load(natives.resolve("fbo_spout_bridge.dll").toString());
        Method create = nativeMethod("nCreateSender", String.class, int.class, int.class);
        Method send = nativeMethod("nSendTexture", int.class, int.class, int.class, int.class, boolean.class);
        Method release = nativeMethod("nReleaseSender");
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        long window = GLFW.glfwCreateWindow(1, 1, "FBO Spout transport probe", 0L, 0L);
        if (window == 0L) throw new IllegalStateException("Hidden context failed");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("GPU: " + glGetString(GL_RENDERER));
            System.out.println("Bridge version: " + nativeMethod("nBridgeVersion").invoke(null));
            int w = 1920, h = 1080;
            int texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
            int random = 4070;
            for (int i = 0; i < w * h; i++) {
                random ^= random << 13; random ^= random >>> 17; random ^= random << 5;
                pixels.putInt(random | 0xff000000);
            }
            pixels.flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            glFinish();
            if (!Boolean.TRUE.equals(create.invoke(null, "FBO Performance Probe", w, h))) throw new AssertionError("Sender creation failed");
            for (int i = 0; i < 128; i++) {
                if (!Boolean.TRUE.equals(send.invoke(null, texture, GL_TEXTURE_2D, w, h, true))) throw new AssertionError("SendTexture failed");
            }
            double[] timings = new double[10];
            for (int run = 0; run < timings.length; run++) {
                long started = System.nanoTime();
                for (int i = 0; i < 128; i++) send.invoke(null, texture, GL_TEXTURE_2D, w, h, true);
                timings[run] = (System.nanoTime() - started) / (128.0 * 1000.0);
            }
            Arrays.sort(timings);
            System.out.printf("Bundled Spout SendTexture at 1080p: median %.1f us/call; min %.1f; max %.1f%n",
                    timings[5], timings[0], timings[timings.length - 1]);
            release.invoke(null);
            glDeleteTextures(texture);
        } finally {
            GLFW.glfwMakeContextCurrent(0L);
            GL.setCapabilities(null);
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static Method nativeMethod(String name, Class<?>... args) throws Exception {
        Method method = SpoutBridge.class.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }
}
