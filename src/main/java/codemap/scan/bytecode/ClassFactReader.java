package codemap.scan.bytecode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import codemap.scan.bytecode.ClassFact.AnnotationFact;
import codemap.scan.bytecode.ClassFact.CallFact;
import codemap.scan.bytecode.ClassFact.FieldFact;
import codemap.scan.bytecode.ClassFact.MethodFact;

/**
 * Reads one class file into a {@link ClassFact} in a single ASM pass. Synthetic lambda bodies
 * are folded into the method that declares the lambda, so call edges stay where a reader of
 * the source expects them. Bridge and other synthetic methods are dropped.
 */
public final class ClassFactReader {

    private static final int HINT_WINDOW = 6;

    private ClassFactReader() {
    }

    public static ClassFact read(byte[] bytes) {
        ClassFact fact = new ClassFact();
        new ClassReader(bytes).accept(new Visitor(fact), ClassReader.SKIP_FRAMES);
        foldLambdas(fact);
        return fact;
    }

    /** Light read for external classes: supertypes and class-level annotations only. */
    public static ClassFact readHeader(byte[] bytes) {
        ClassFact fact = new ClassFact();
        new ClassReader(bytes).accept(new Visitor(fact, true),
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return fact;
    }

    private static void foldLambdas(ClassFact fact) {
        Map<String, MethodFact> byName = new HashMap<>();
        for (MethodFact m : fact.methods) {
            if (!m.isSynthetic) {
                byName.putIfAbsent(m.name, m);
            }
        }
        for (MethodFact m : fact.methods) {
            if (m.isSynthetic && m.name.startsWith("lambda$")) {
                String[] parts = m.name.split("\\$");
                MethodFact enclosing = parts.length >= 2 ? byName.get(parts[1].equals("new") ? "<init>" : parts[1]) : null;
                if (enclosing != null) {
                    enclosing.calls.addAll(m.calls);
                }
            }
        }
        fact.methods.removeIf(m -> m.isSynthetic);
    }

    static String visibility(int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) {
            return "public";
        }
        if ((access & Opcodes.ACC_PROTECTED) != 0) {
            return "protected";
        }
        if ((access & Opcodes.ACC_PRIVATE) != 0) {
            return "private";
        }
        return "package";
    }

    // ── class ──────────────────────────────────────────────────────────

    private static final class Visitor extends ClassVisitor {
        private final ClassFact fact;
        private final boolean headerOnly;
        private String owner;

        Visitor(ClassFact fact) {
            this(fact, false);
        }

        Visitor(ClassFact fact, boolean headerOnly) {
            super(Opcodes.ASM9);
            this.fact = fact;
            this.headerOnly = headerOnly;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                          String[] interfaces) {
            owner = name;
            fact.fqn = TypeNames.fqn(name);
            fact.visibility = visibility(access);
            fact.isAbstract = (access & Opcodes.ACC_ABSTRACT) != 0 && (access & Opcodes.ACC_INTERFACE) == 0;
            if ((access & Opcodes.ACC_ANNOTATION) != 0) {
                fact.kind = "annotation";
            } else if ((access & Opcodes.ACC_INTERFACE) != 0) {
                fact.kind = "interface";
            } else if ((access & Opcodes.ACC_ENUM) != 0) {
                fact.kind = "enum";
            } else if ("java/lang/Record".equals(superName)) {
                fact.kind = "record";
            } else {
                fact.kind = "class";
            }
            if (superName != null && !superName.equals("java/lang/Object")) {
                fact.superclass = TypeNames.fqn(superName);
                fact.ref(superName);
            }
            if (interfaces != null) {
                for (String i : interfaces) {
                    fact.interfaces.add(TypeNames.fqn(i));
                    fact.ref(i);
                }
            }
            if (signature != null) {
                readClassSignature(signature);
            }
        }

        private void readClassSignature(String signature) {
            try {
                List<TypeNames.Printer> printers = new ArrayList<>();
                new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
                    @Override
                    public SignatureVisitor visitSuperclass() {
                        return add();
                    }

                    @Override
                    public SignatureVisitor visitInterface() {
                        return add();
                    }

                    @Override
                    public SignatureVisitor visitClassBound() {
                        return new TypeNames.Printer();
                    }

                    @Override
                    public SignatureVisitor visitInterfaceBound() {
                        return new TypeNames.Printer();
                    }

                    private SignatureVisitor add() {
                        TypeNames.Printer p = new TypeNames.Printer();
                        printers.add(p);
                        return p;
                    }
                });
                printers.forEach(p -> fact.genericSupertypes.add(p.toString()));
                collectSignatureTypes(signature);
                new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
                    @Override
                    public SignatureVisitor visitSuperclass() {
                        return supertype();
                    }

                    @Override
                    public SignatureVisitor visitInterface() {
                        return supertype();
                    }

                    @Override
                    public SignatureVisitor visitClassBound() {
                        return new TypeNames.ArgCollector();
                    }

                    @Override
                    public SignatureVisitor visitInterfaceBound() {
                        return new TypeNames.ArgCollector();
                    }

                    private SignatureVisitor supertype() {
                        return new TypeNames.ArgCollector() {
                            @Override
                            public void visitClassType(String name) {
                                fact.supertypeArguments.put(TypeNames.fqn(name), args);
                            }
                        };
                    }
                });
            } catch (RuntimeException ignored) {
                // generic info is optional
            }
        }

        private void collectSignatureTypes(String signature) {
            new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
                @Override
                public void visitClassType(String name) {
                    fact.ref(name);
                }
            });
        }

        @Override
        public void visitSource(String source, String debug) {
            fact.sourceFileName = source;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return annotation(descriptor, fact.annotations::add, fact);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if (headerOnly || (access & Opcodes.ACC_SYNTHETIC) != 0) {
                return null;
            }
            FieldFact f = new FieldFact();
            f.name = name;
            f.type = TypeNames.readableType(descriptor, signature);
            f.typeFqn = TypeNames.rawFqn(Type.getType(descriptor));
            f.isStatic = (access & Opcodes.ACC_STATIC) != 0;
            f.isFinal = (access & Opcodes.ACC_FINAL) != 0;
            f.constantValue = value;
            f.typeArguments = TypeNames.typeArguments(signature);
            fact.fields.add(f);
            addType(Type.getType(descriptor));
            if (signature != null) {
                collectSignatureTypes(signature);
            }
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    return annotation(desc, f.annotations::add, fact);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                         String[] exceptions) {
            if (headerOnly || name.equals("<clinit>") || (access & Opcodes.ACC_BRIDGE) != 0) {
                return null;
            }
            MethodFact m = new MethodFact();
            m.name = name;
            m.descriptor = descriptor;
            m.signature = signature;
            m.id = TypeNames.methodId(fact.fqn, name, descriptor);
            m.visibility = visibility(access);
            m.isStatic = (access & Opcodes.ACC_STATIC) != 0;
            m.isAbstract = (access & Opcodes.ACC_ABSTRACT) != 0;
            m.isSynthetic = (access & Opcodes.ACC_SYNTHETIC) != 0;
            TypeNames.MethodTypes types = TypeNames.methodTypes(descriptor, signature);
            m.returnType = types.returnType();
            m.parameterTypes.addAll(types.parameterTypes());
            Type[] args = Type.getArgumentTypes(descriptor);
            m.parameterTypeArguments.addAll(TypeNames.parameterTypeArguments(signature, args.length));
            for (Type t : args) {
                addType(t);
                m.parameterAnnotations.add(new ArrayList<>());
            }
            addType(Type.getReturnType(descriptor));
            if (signature != null) {
                collectSignatureTypes(signature);
            }
            fact.methods.add(m);
            return new MethodReader(m, args);
        }

        private void addType(Type t) {
            String raw = TypeNames.rawFqn(t);
            if (raw != null) {
                fact.ref(raw.replace('.', '/'));
            }
        }

        // ── method bodies ──────────────────────────────────────────────

        private final class MethodReader extends MethodVisitor {
            private final MethodFact m;
            private final Type[] args;
            private final String[] mpNames;
            private final Map<Integer, String> lvtNames = new HashMap<>();
            private final Deque<String> hints = new ArrayDeque<>();
            private int mpIndex;
            private int line;
            private int lastLoadedSlot = -1;

            MethodReader(MethodFact m, Type[] args) {
                super(Opcodes.ASM9);
                this.m = m;
                this.args = args;
                this.mpNames = new String[args.length];
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return annotation(descriptor, m.annotations::add, fact);
            }

            @Override
            public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                if (parameter < 0 || parameter >= m.parameterAnnotations.size()) {
                    return null;
                }
                return annotation(descriptor, m.parameterAnnotations.get(parameter)::add, fact);
            }

            @Override
            public void visitParameter(String name, int access) {
                if (mpIndex < mpNames.length) {
                    mpNames[mpIndex++] = name;
                }
            }

            @Override
            public void visitLineNumber(int lineNumber, Label start) {
                line = lineNumber;
                if (m.firstLine == 0 || lineNumber < m.firstLine) {
                    m.firstLine = lineNumber;
                }
                m.lastLine = Math.max(m.lastLine, lineNumber);
            }

            @Override
            public void visitLocalVariable(String name, String descriptor, String signature, Label start,
                                           Label end, int index) {
                lvtNames.putIfAbsent(index, name);
            }

            @Override
            public void visitVarInsn(int opcode, int varIndex) {
                lastLoadedSlot = opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD ? varIndex : -1;
            }

            @Override
            public void visitInsn(int opcode) {
                lastLoadedSlot = -1;
            }

            @Override
            public void visitLdcInsn(Object value) {
                lastLoadedSlot = -1;
                if (value instanceof String s) {
                    hint("str:" + s);
                } else if (value instanceof Type t && t.getSort() == Type.OBJECT) {
                    fact.ref(t.getInternalName());
                }
            }

            @Override
            public void visitFieldInsn(int opcode, String fieldOwner, String name, String descriptor) {
                if (opcode == Opcodes.PUTFIELD && m.name.equals("<init>") && fieldOwner.equals(owner)
                        && lastLoadedSlot > 0) {
                    int slot = 1;
                    for (int i = 0; i < args.length; i++) {
                        if (slot == lastLoadedSlot) {
                            m.fieldsFromParameters.putIfAbsent(name, i);
                        }
                        slot += args[i].getSize();
                    }
                }
                lastLoadedSlot = -1;
                if (opcode == Opcodes.GETSTATIC) {
                    hint("static:" + TypeNames.fqn(fieldOwner) + "." + name);
                } else if (opcode == Opcodes.GETFIELD) {
                    hint("field:" + TypeNames.fqn(fieldOwner) + "." + name);
                }
                if (!fieldOwner.equals(owner)) {
                    fact.ref(fieldOwner);
                }
            }

            @Override
            public void visitTypeInsn(int opcode, String type) {
                lastLoadedSlot = -1;
                if (!type.startsWith("[")) {
                    fact.ref(type);
                    if (opcode == Opcodes.NEW) {
                        hint("new:" + TypeNames.fqn(type));
                    }
                }
            }

            @Override
            public void visitMethodInsn(int opcode, String calleeOwner, String name, String descriptor,
                                        boolean isInterface) {
                if (calleeOwner.startsWith("[")) {
                    return;
                }
                String kind = switch (opcode) {
                    case Opcodes.INVOKESTATIC -> "static";
                    case Opcodes.INVOKEINTERFACE -> "interface";
                    case Opcodes.INVOKESPECIAL -> "special";
                    default -> "virtual";
                };
                addCall(calleeOwner, name, descriptor, kind);
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... bsmArgs) {
                for (Object arg : bsmArgs) {
                    // Method references; skips lambda bodies (folded separately) and field handles.
                    if (arg instanceof Handle h && h.getTag() >= Opcodes.H_INVOKEVIRTUAL
                            && !h.getName().startsWith("lambda$")) {
                        addCall(h.getOwner(), h.getName(), h.getDesc(), "dynamic");
                    }
                }
            }

            private void addCall(String calleeOwner, String name, String descriptor, String kind) {
                lastLoadedSlot = -1;
                String ownerFqn = TypeNames.fqn(calleeOwner);
                m.calls.add(new CallFact(ownerFqn, name, descriptor,
                        TypeNames.methodId(ownerFqn, name, descriptor), line, kind, List.copyOf(hints)));
                fact.ref(calleeOwner);
            }

            private void hint(String h) {
                hints.addLast(h);
                if (hints.size() > HINT_WINDOW) {
                    hints.removeFirst();
                }
            }

            @Override
            public void visitEnd() {
                int slot = m.isStatic ? 0 : 1;
                for (int i = 0; i < args.length; i++) {
                    String name = mpNames[i] != null ? mpNames[i] : lvtNames.get(slot);
                    m.parameterNames.add(name);
                    slot += args[i].getSize();
                }
            }
        }
    }

    // ── annotations ───────────────────────────────────────────────────

    interface Sink {
        void add(AnnotationFact a);
    }

    private static AnnotationVisitor annotation(String descriptor, Sink sink, ClassFact fact) {
        String internal = Type.getType(descriptor).getInternalName();
        fact.ref(internal);
        Map<String, Object> attrs = new LinkedHashMap<>();
        return new MapVisitor(attrs) {
            @Override
            public void visitEnd() {
                sink.add(new AnnotationFact(TypeNames.fqn(internal), attrs));
            }
        };
    }

    private static class MapVisitor extends AnnotationVisitor {
        private final Map<String, Object> target;

        MapVisitor(Map<String, Object> target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public void visit(String name, Object value) {
            target.put(name, convert(value));
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            target.put(name, value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("@type", TypeNames.simple(Type.getType(descriptor).getInternalName()));
            target.put(name, nested);
            return new MapVisitor(nested);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            List<Object> list = new ArrayList<>();
            target.put(name, list);
            return new ListVisitor(list);
        }
    }

    private static final class ListVisitor extends AnnotationVisitor {
        private final List<Object> list;

        ListVisitor(List<Object> list) {
            super(Opcodes.ASM9);
            this.list = list;
        }

        @Override
        public void visit(String name, Object value) {
            list.add(convert(value));
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            list.add(value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("@type", TypeNames.simple(Type.getType(descriptor).getInternalName()));
            list.add(nested);
            return new MapVisitor(nested);
        }
    }

    private static Object convert(Object value) {
        if (value instanceof Type t) {
            return TypeNames.fqn(t.getSort() == Type.ARRAY ? t.getElementType().getInternalName() : t.getInternalName());
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> list = new ArrayList<>();
            int n = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < n; i++) {
                list.add(java.lang.reflect.Array.get(value, i));
            }
            return list;
        }
        return value;
    }
}
