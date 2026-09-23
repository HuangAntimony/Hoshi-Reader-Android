package hoshi.build;

import org.objectweb.asm.*;

/** Preserve the official bindings; route only native loading to app-private files. */
public final class SherpaBindings {
    private SherpaBindings() {}
    public static byte[] redirectLoads(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(a, n, d, s, e)) {
                    @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                        if (op == Opcodes.INVOKESTATIC && owner.equals("java/lang/System") &&
                                name.equals("loadLibrary") && desc.equals("(Ljava/lang/String;)V")) {
                            owner = "moe/antimony/hoshi/features/sasayaki/transcription/SasayakiNativeLibraries";
                        }
                        super.visitMethodInsn(op, owner, name, desc, itf);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
