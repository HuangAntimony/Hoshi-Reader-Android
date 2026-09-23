package hoshi.build;

import org.junit.Test;
import org.objectweb.asm.*;
import static org.junit.Assert.*;

public class SherpaBindingsTest {
    @Test public void redirectsNativeLoadWithoutChangingOtherSystemCalls() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Fixture", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        method.visitLdcInsn("sherpa-onnx-jni");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "loadLibrary", "(Ljava/lang/String;)V", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "gc", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0); method.visitEnd(); writer.visitEnd();
        byte[] patched = SherpaBindings.redirectLoads(writer.toByteArray());
        var calls = new java.util.ArrayList<String>();
        new ClassReader(patched).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                        calls.add(owner + "." + name + desc);
                    }
                };
            }
        }, 0);
        assertEquals(java.util.List.of(
            "moe/antimony/hoshi/features/sasayaki/transcription/SasayakiNativeLibraries.loadLibrary(Ljava/lang/String;)V",
            "java/lang/System.gc()V"), calls);
    }
}
