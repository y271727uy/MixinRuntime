package cn.ksmcbrigade.mr.transformers.compat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public final class FastEventListenerFactoryTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS =
            "zank/mods/fast_event/EventListenerFactory$Constants";
    private static final String TARGET_OWNER = TARGET_CLASS;
    private static final String EVENT_LISTENER =
            "net/minecraftforge/eventbus/api/IEventListener";
    private static final String EVENT = "net/minecraftforge/eventbus/api/Event";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className) || classfileBuffer == null) {
            return null;
        }

        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(
                    reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            boolean[] replacedInitializer = {false};

            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if ("<clinit>".equals(name) && "()V".equals(descriptor)) {
                        replacedInitializer[0] = true;
                        return null;
                    }
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }

                @Override
                public void visitEnd() {
                    if (replacedInitializer[0]) {
                        writeInitializer(cv);
                    }
                    super.visitEnd();
                }
            }, 0);

            if (!replacedInitializer[0]) {
                throw new IllegalStateException("FastEvent constants initializer was not found");
            }

            System.out.println("[MixinRuntime] Fixed FastEvent listener method lookup");
            return writer.toByteArray();
        } catch (Throwable e) {
            System.err.println("[MixinRuntime] Failed to fix FastEvent listener method lookup");
            e.printStackTrace(System.err);
            return null;
        }
    }

    private static void writeInitializer(ClassVisitor visitor) {
        MethodVisitor mv = visitor.visitMethod(
                Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();

        mv.visitLdcInsn(Type.getObjectType(EVENT_LISTENER));
        mv.visitFieldInsn(Opcodes.PUTSTATIC, TARGET_OWNER, "CLAZZ", "Ljava/lang/Class;");

        mv.visitFieldInsn(Opcodes.GETSTATIC, TARGET_OWNER, "CLAZZ", "Ljava/lang/Class;");
        mv.visitLdcInsn("invoke");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLdcInsn(Type.getObjectType(EVENT));
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                false);
        mv.visitFieldInsn(
                Opcodes.PUTSTATIC,
                TARGET_OWNER,
                "METHOD",
                "Ljava/lang/reflect/Method;");

        mv.visitFieldInsn(
                Opcodes.GETSTATIC,
                TARGET_OWNER,
                "METHOD",
                "Ljava/lang/reflect/Method;");
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "getName",
                "()Ljava/lang/String;",
                false);
        mv.visitFieldInsn(
                Opcodes.PUTSTATIC,
                TARGET_OWNER,
                "METHOD_NAME",
                "Ljava/lang/String;");

        mv.visitFieldInsn(
                Opcodes.GETSTATIC,
                TARGET_OWNER,
                "METHOD",
                "Ljava/lang/reflect/Method;");
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "getReturnType",
                "()Ljava/lang/Class;",
                false);
        mv.visitFieldInsn(
                Opcodes.GETSTATIC,
                TARGET_OWNER,
                "METHOD",
                "Ljava/lang/reflect/Method;");
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "getParameterTypes",
                "()[Ljava/lang/Class;",
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/invoke/MethodType",
                "methodType",
                "(Ljava/lang/Class;[Ljava/lang/Class;)Ljava/lang/invoke/MethodType;",
                false);
        mv.visitFieldInsn(
                Opcodes.PUTSTATIC,
                TARGET_OWNER,
                "METHOD_TYPE",
                "Ljava/lang/invoke/MethodType;");

        mv.visitFieldInsn(Opcodes.GETSTATIC, TARGET_OWNER, "CLAZZ", "Ljava/lang/Class;");
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/invoke/MethodType",
                "methodType",
                "(Ljava/lang/Class;)Ljava/lang/invoke/MethodType;",
                false);
        mv.visitFieldInsn(
                Opcodes.PUTSTATIC,
                TARGET_OWNER,
                "RETURNS_IT",
                "Ljava/lang/invoke/MethodType;");

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
