package com.asbestosstar.nativeaccelerator.mixinconfig;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adds a real METAL enum constant to Minecraft 26.3's PreferredGraphicsApi before the class is
 * defined. This is intentionally done at the ClassNode level: the graphics option is populated
 * from PreferredGraphicsApi.values(), so merely changing getBackendsToTry() cannot add a GUI entry.
 *
 * <p>The hook is deliberately Minecraft-linkage-free. It works only with internal JVM names and
 * bytecode descriptors so it remains safe inside the early Mixin config-plugin phase.</p>
 */
public final class PreferredGraphicsApiEnumHook implements ClassNodeHook {
    private static final String TARGET = "net.minecraft.client.PreferredGraphicsApi";
    private static final String OWNER = "net/minecraft/client/PreferredGraphicsApi";
    private static final String ENUM_DESC = "L" + OWNER + ";";
    private static final String ARRAY_DESC = "[L" + OWNER + ";";
    private static final String VALUES_FACTORY_DESC = "()" + ARRAY_DESC;
    private static final String ENUM_CTOR_DESC =
            "(Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)V";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private PreferredGraphicsApiEnumHook() {
    }

    public static void install() {
        if (INSTALLED.compareAndSet(false, true)) {
            NativeAcceleratorMixinConfigPlugin.registerClassNodeHook(new PreferredGraphicsApiEnumHook());
        }
    }

    @Override
    public void preApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo) {
        if (!TARGET.equals(targetClassName) && !OWNER.equals(targetClass.name)) {
            return;
        }
        extendEnum(targetClass);
    }

    private static void extendEnum(ClassNode targetClass) {
        if (hasField(targetClass, "METAL", ENUM_DESC)) {
            return;
        }

        MethodNode valuesFactory = findValuesFactory(targetClass);
        MethodNode clinit = findMethod(targetClass, "<clinit>", "()V");
        if (valuesFactory == null || clinit == null) {
            System.err.println("[Native Accelerator] Could not extend PreferredGraphicsApi: "
                    + "unexpected enum bytecode shape");
            return;
        }

        addMetalField(targetClass);
        rewriteValuesFactory(valuesFactory);

        MethodInsnNode valuesFactoryCall = null;
        for (var insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && OWNER.equals(call.owner)
                    && valuesFactory.name.equals(call.name)
                    && VALUES_FACTORY_DESC.equals(call.desc)) {
                valuesFactoryCall = call;
                break;
            }
        }

        if (valuesFactoryCall == null) {
            System.err.println("[Native Accelerator] Could not extend PreferredGraphicsApi: "
                    + "enum values factory call not found");
            removeField(targetClass, "METAL", ENUM_DESC);
            return;
        }

        InsnList initMetal = new InsnList();
        initMetal.add(new TypeInsnNode(Opcodes.NEW, OWNER));
        initMetal.add(new InsnNode(Opcodes.DUP));
        initMetal.add(new LdcInsnNode("METAL"));
        initMetal.add(pushInt(3));
        initMetal.add(new LdcInsnNode("metal"));
        initMetal.add(new LdcInsnNode("options.graphicsApi.metal"));
        initMetal.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                OWNER,
                "<init>",
                ENUM_CTOR_DESC,
                false));
        initMetal.add(new FieldInsnNode(Opcodes.PUTSTATIC, OWNER, "METAL", ENUM_DESC));
        clinit.instructions.insertBefore(valuesFactoryCall, initMetal);

        // Mixin's class writer normally recomputes frames/maxs, but keeping sane maxima also makes
        // the transformed node valid for tooling that inspects it before final emission.
        clinit.maxStack = Math.max(clinit.maxStack, 6);
    }

    private static void addMetalField(ClassNode targetClass) {
        FieldNode metal = new FieldNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                "METAL",
                ENUM_DESC,
                null,
                null);

        int insertionPoint = targetClass.fields.size();
        for (int i = 0; i < targetClass.fields.size(); i++) {
            FieldNode field = targetClass.fields.get(i);
            if (ARRAY_DESC.equals(field.desc) && (field.access & Opcodes.ACC_SYNTHETIC) != 0) {
                insertionPoint = i;
                break;
            }
        }
        targetClass.fields.add(insertionPoint, metal);
    }

    private static void rewriteValuesFactory(MethodNode method) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }

        InsnList code = method.instructions;
        code.add(pushInt(4));
        code.add(new TypeInsnNode(Opcodes.ANEWARRAY, OWNER));
        addEnumArrayEntry(code, 0, "DEFAULT");
        addEnumArrayEntry(code, 1, "OPENGL");
        addEnumArrayEntry(code, 2, "VULKAN");
        addEnumArrayEntry(code, 3, "METAL");
        code.add(new InsnNode(Opcodes.ARETURN));

        method.maxStack = 4;
        method.maxLocals = 0;
    }

    private static void addEnumArrayEntry(InsnList code, int index, String fieldName) {
        code.add(new InsnNode(Opcodes.DUP));
        code.add(pushInt(index));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, OWNER, fieldName, ENUM_DESC));
        code.add(new InsnNode(Opcodes.AASTORE));
    }

    private static InsnNode pushSmallInt(int value) {
        return new InsnNode(Opcodes.ICONST_0 + value);
    }

    private static org.objectweb.asm.tree.AbstractInsnNode pushInt(int value) {
        if (value >= 0 && value <= 5) {
            return pushSmallInt(value);
        }
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(Opcodes.BIPUSH, value);
        }
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new IntInsnNode(Opcodes.SIPUSH, value);
        }
        return new LdcInsnNode(value);
    }

    private static MethodNode findValuesFactory(ClassNode targetClass) {
        for (MethodNode method : targetClass.methods) {
            if (VALUES_FACTORY_DESC.equals(method.desc)
                    && (method.access & Opcodes.ACC_STATIC) != 0
                    && (method.access & Opcodes.ACC_PRIVATE) != 0
                    && (method.access & Opcodes.ACC_SYNTHETIC) != 0) {
                return method;
            }
        }
        return null;
    }

    private static MethodNode findMethod(ClassNode targetClass, String name, String desc) {
        for (MethodNode method : targetClass.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    private static boolean hasField(ClassNode targetClass, String name, String desc) {
        for (FieldNode field : targetClass.fields) {
            if (name.equals(field.name) && desc.equals(field.desc)) {
                return true;
            }
        }
        return false;
    }

    private static void removeField(ClassNode targetClass, String name, String desc) {
        targetClass.fields.removeIf(field -> name.equals(field.name) && desc.equals(field.desc));
    }
}
