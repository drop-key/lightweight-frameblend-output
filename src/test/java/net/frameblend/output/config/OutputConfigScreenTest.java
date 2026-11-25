package net.frameblend.output.config;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.Assert.*;

/** Lunar's GUI wrapper may already have scheduled the frame's only blur. */
public class OutputConfigScreenTest {
    @Test public void backgroundOverridesVanillaWithoutRequestingAnotherBlur() throws Exception {
        var screen = new ClassNode();
        try (var input = getClass().getResourceAsStream("/net/frameblend/output/config/OutputConfigScreen.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(screen, 0);
        }
        var background = screen.methods.stream().filter(m -> m.name.equals("extractBackground")).findFirst().orElseThrow();
        boolean drawsScrim = false;
        for (var instruction : background.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                assertFalse("Lunar already handles background blur", call.name.toLowerCase().contains("blur"));
                assertNotEquals("Do not delegate to Screen's blur-producing background", "net/minecraft/client/gui/screens/Screen", call.owner);
                if (call.name.equals("fill")) drawsScrim = true;
            }
        }
        assertTrue("The background must still dim the scene", drawsScrim);
    }
}
