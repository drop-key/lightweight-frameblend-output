package net.frameblend.output.recording;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.Assert.*;

/** Verify the actual Minecraft hook is after final rendering and before reset. */
public class CaptureSubmissionOrderTest {
    @Test
    public void frameSubmissionFollowsFinalRenderAndPrecedesResourceReset() throws Exception {
        ClassNode minecraft = new ClassNode();
        try (var stream = getClass().getResourceAsStream("/net/minecraft/client/Minecraft.class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(minecraft, 0);
        }
        var render = minecraft.methods.stream().filter(m -> m.name.equals("renderFrame") && m.desc.equals("(Z)V")).findFirst().orElseThrow();
        int finalRender = -1, submit = -1, reset = -1, endFrame = -1, index = 0, submits = 0;
        for (var instruction : render.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("net/minecraft/client/renderer/GameRenderer") && call.name.equals("render")) finalRender = index;
                if (call.owner.equals("com/mojang/blaze3d/systems/CommandEncoder") && call.name.equals("submit")) { submit = index; submits++; }
                if (call.owner.equals("net/minecraft/client/renderer/DynamicUniforms") && call.name.equals("reset")) reset = index;
                if (call.owner.equals("net/minecraft/client/renderer/LevelRenderer") && call.name.equals("endFrame")) endFrame = index;
            }
            index++;
        }
        assertEquals("The capture hook needs one submission point per rendered frame", 1, submits);
        assertTrue(finalRender >= 0 && finalRender < submit);
        assertTrue(submit < reset && submit < endFrame);
    }
}
