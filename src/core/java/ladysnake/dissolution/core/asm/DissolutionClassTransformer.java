package ladysnake.dissolution.core.asm;

import ladysnake.dissolution.core.SafeClassWriter;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.function.Consumer;

public class DissolutionClassTransformer implements IClassTransformer {
    private final Map<ASMUtil.MethodKey, ASMUtil.MethodInfo> livingBaseMethods = new HashMap<>();
    private final Set<String> noNullsFromDelegation = new HashSet<>();
    private final Set<ASMUtil.MethodKey> blacklist = new HashSet<>();
    private String getentityattribute;

    public DissolutionClassTransformer() {
        super();
        AddGetterClassAdapter.init(livingBaseMethods);
        String desc;
        String name;
        // getEntityAttribute
        desc = "(Lnet/minecraft/entity/ai/attributes/IAttribute;)Lnet/minecraft/entity/ai/attributes/IAttributeInstance;";
        name = FMLDeobfuscatingRemapper.INSTANCE.mapMethodName(
                "net/minecraft/entity/EntityLivingBase",
                "func_110148_a",
                desc
        );
        noNullsFromDelegation.add(name + desc);
        getentityattribute = name + desc;
        // getCapability
        desc = "(Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/util/EnumFacing;)Ljava/lang/Object;";
        name = "getCapability";
        noNullsFromDelegation.add(name + desc);
        // updateEntityActionState
        desc = "()V";
        name = FMLDeobfuscatingRemapper.INSTANCE.mapMethodName(
                "net.minecraft.entity.player.EntityPlayer",
                "func_70626_be",
                desc
        );
        blacklist.add(new ASMUtil.MethodKey(name, desc));
        // getEntityId
        desc = "()I";
        name = FMLDeobfuscatingRemapper.INSTANCE.mapMethodName(
                "net.minecraft.entity.Entity",
                "func_145782_y",
                desc
        );
        blacklist.add(new ASMUtil.MethodKey(name, desc));
        name = "getEntityId";   // workaround because the remapper does not work here for some reason
        blacklist.add(new ASMUtil.MethodKey(name, desc));
    }

    @Override
    public byte[] transform(String className, String transformedName, byte[] basicClass) {
        // replace every direct access to entity fields by getters and setters
        basicClass = kotlinify(basicClass);

        // if the target is EntityPlayer or a parent class
        String type = transformedName.replace('.', '/');
        if (AddGetterClassAdapter.AddGetterAdapter.entityClasses.contains(type)) {
            basicClass = invokeCthulhu(basicClass, classNode -> {
                // generate getters and setters for every public field
                for (FieldNode node : classNode.fields) {
                    if ((node.access & Opcodes.ACC_PUBLIC) == Opcodes.ACC_PUBLIC) {
                        AddGetterClassAdapter.generateGetterSetter(classNode, node);
                    }
                }
            });
        }

        // there may be more classes statically affected in the future
        switch (transformedName) {
            case "net.minecraft.entity.player.EntityPlayer":
                return invokeCthulhu(basicClass, classNode -> {
                    List<MethodNode> methods = classNode.methods;
                    for (MethodNode methodNode : methods) {
                        ASMUtil.MethodKey key = new ASMUtil.MethodKey(methodNode.name, methodNode.desc);
                        ASMUtil.MethodInfo info = livingBaseMethods.remove(key);
                        // if the method overrides an EntityLivingBase method, inject a hook
                        if (info != null && !blacklist.contains(key)) {
                            methodNode.instructions.insert(generateDelegationInsnList(methodNode.name, methodNode.desc));
                            if (noNullsFromDelegation.contains(methodNode.name + methodNode.desc))
                                ASMUtil.dump(methodNode.instructions);
                        }

                        // special case capability methods to avoid recursive calls
                        if (methodNode.name.equals("getCapability") || methodNode.name.equals("hasCapability")) {
                            insertCapabilityHook(methodNode);
                        }
                    }
                    // go through every remaining method and generate a delegating override
                    livingBaseMethods.forEach((methodKey, methodInfo) -> {
                        // do not override blacklisted methods
                        if (!blacklist.contains(methodKey))
                            createDelegationOverride(classNode, methodKey.name, methodKey.desc, methodInfo);
                    });
                });
        }
        return basicClass;
    }

    /**
     * Inserts a set of instructions in a (has/get)Capability method to special case the possession capability
     *
     * @param methodNode the method in which the instructions should be inserted
     */
    private void insertCapabilityHook(MethodNode methodNode) {
        InsnList preInstructions = new InsnList();
        preInstructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        preInstructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "ladysnake/dissolution/core/DissolutionHooks",
                "cap",
                "Lnet/minecraftforge/common/capabilities/Capability;"
        ));
        LabelNode lbl = new LabelNode();
        preInstructions.add(new JumpInsnNode(Opcodes.IF_ACMPNE, lbl));
        preInstructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        preInstructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        preInstructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        preInstructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/entity/EntityLivingBase",
                methodNode.name,
                methodNode.desc,
                false
        ));
        preInstructions.add(new InsnNode(Type.getReturnType(methodNode.desc).getOpcode(Opcodes.IRETURN)));
        preInstructions.add(lbl);
        methodNode.instructions.insert(preInstructions);
    }

    /**
     * Creates a method override from scratch, delegating the call if possible and calling super if not
     *
     * @param classNode  the class in which to add the method
     * @param methodName the name of the method to override
     * @param methodDesc the description of the method to override
     * @param info       the additional information defining the method
     */
    private void createDelegationOverride(ClassNode classNode, String methodName, String methodDesc, ASMUtil.MethodInfo info) {
        MethodNode methodNode = new MethodNode(info.access, methodName, methodDesc, info.signature, info.exceptions);
        if (info.abstr) throw new RuntimeException("Why do I have to generate an abstract method ?!");
        methodNode.parameters = info.params;
        // if the player is possessing something, delegate the call
        InsnList instructions = generateDelegationInsnList(methodName, methodDesc);
        // else call super
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        // hardcode a way to get the player's own attributes
        if (getentityattribute.equals(methodName + methodDesc)) {
            instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "net/minecraft/entity/EntityLivingBase",
                    "getAttributeMap",
                    "()Lnet/minecraft/entity/ai/attributes/AbstractAttributeMap;",
                    false
            ));
            instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/entity/ai/attributes/AbstractAttributeMap",
                    "getAttributeInstance",
                    "(Lnet/minecraft/entity/ai/attributes/IAttribute;)Lnet/minecraft/entity/ai/attributes/IAttributeInstance;",
                    false
            ));
            instructions.add(new InsnNode(Opcodes.ARETURN));
        } else {
            generateCall(instructions, methodName, methodDesc, Opcodes.INVOKESPECIAL);
            instructions.add(new InsnNode(Type.getType(methodDesc).getReturnType().getOpcode(Opcodes.IRETURN)));
        }
        methodNode.instructions.add(instructions);
        classNode.methods.add(methodNode);
    }

    /**
     * Generates the bytecode instructions to delegate a call to the possessed entity, if any
     *
     * @param methodName the name of the method for which to generate a delegating call
     * @param methodDesc the description of the method for which to generate a delegating call
     * @return an instruction list containing the generated instructions
     */
    private InsnList generateDelegationInsnList(String methodName, String methodDesc) {
        InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); // thisPlayer
        // get the possessed entity
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "ladysnake/dissolution/core/DissolutionHooks",
                "getPossessedEntity",
                "(Lnet/minecraft/entity/Entity;)Lnet/minecraft/entity/EntityLivingBase;",
                false
        ));
        // store the result in a local variable
        int storedRet = getFirstFreeSlot(methodDesc);
        instructions.add(new VarInsnNode(Opcodes.ASTORE, storedRet));
        // If the result is null, execute the rest of the method normally
        LabelNode lbl = new LabelNode();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, storedRet));
        instructions.add(new JumpInsnNode(Opcodes.IFNULL, lbl));
        // else, delegate the call to the possessed entity
        instructions.add(new VarInsnNode(Opcodes.ALOAD, storedRet));
        generateCall(instructions, methodName, methodDesc, Opcodes.INVOKEVIRTUAL);
        // and return immediately
        // except for some methods that need to call the player variant if the possessed returned null
        if (noNullsFromDelegation.contains(methodName + methodDesc)) {
            instructions.add(new VarInsnNode(Opcodes.ASTORE, storedRet));
            instructions.add(new VarInsnNode(Opcodes.ALOAD, storedRet));
            // if the delegation returns null, jump to player code, otherwise load the value again for return
            instructions.add(new JumpInsnNode(Opcodes.IFNULL, lbl));
            instructions.add(new VarInsnNode(Opcodes.ALOAD, storedRet));
        }
        instructions.add(new InsnNode(Type.getType(methodDesc).getReturnType().getOpcode(Opcodes.IRETURN)));
        instructions.add(lbl);
        return instructions;
    }

    /**
     * Generates the instructions to load method parameters. The callee should already be on the stack.
     *
     * @param methodName the name of the method to call
     * @param methodDesc the description of the method to call
     * @param invokeCode the {@link Opcodes} that should be used when invoking the method
     */
    private void generateCall(InsnList instructions, String methodName, String methodDesc, int invokeCode) {
        Type[] paramTypes = Type.getArgumentTypes(methodDesc);
        int paramIndex = 1;
        // add each additional parameter in order, starting from 1
        for (Type paramType : paramTypes) {
            int code = paramType.getOpcode(Opcodes.ILOAD);
            instructions.add(new VarInsnNode(code, paramIndex));
            paramIndex += paramType.getSize();
        }
        instructions.add(new MethodInsnNode(invokeCode, "net/minecraft/entity/EntityLivingBase", methodName, methodDesc, false));
    }

    /**
     * @param methodDesc the description of the method being called
     * @return the relative address of the first free variable after method args
     */
    private int getFirstFreeSlot(String methodDesc) {
        Type methodType = Type.getType(methodDesc);
        Type[] paramTypes = methodType.getArgumentTypes();
        int paramIndex = 1;
        for (Type paramType : paramTypes) {
            paramIndex += paramType.getSize();
        }
        return paramIndex;
    }

    private byte[] kotlinify(byte[] basicClass) {
        ClassReader reader = new ClassReader(basicClass);
        ClassWriter writer = new SafeClassWriter(0);
        ClassVisitor kotlinifier = new AddGetterClassAdapter(Opcodes.ASM5, writer);
        reader.accept(kotlinifier, 0);
        return writer.toByteArray();
    }

    /**
     * Transforms a class using the given transformer
     *
     * @param basicClass  a byte array representing the class being transformed
     * @param transformer a consumer taking a {@link ClassNode}
     * @return the class' bytecode after transformation
     */
    private byte[] invokeCthulhu(byte[] basicClass, Consumer<ClassNode> transformer) {
        ClassReader reader = new ClassReader(basicClass);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        transformer.accept(classNode);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        classNode.accept(writer);

        return writer.toByteArray();
    }
}
