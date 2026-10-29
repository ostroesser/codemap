package codemap.scan.bytecode;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import org.objectweb.asm.Opcodes;

/** Conversions from JVM descriptors and generic signatures to names an LLM reads easily. */
public final class TypeNames {

    private TypeNames() {
    }

    /** {@code com/example/Foo$Bar} → {@code com.example.Foo$Bar} (binary name, the class key). */
    public static String fqn(String internalName) {
        return internalName.replace('/', '.');
    }

    /** {@code com.example.Foo$Bar} → {@code Foo.Bar}. */
    public static String simple(String fqnOrInternal) {
        String s = fqnOrInternal.replace('/', '.');
        s = s.substring(s.lastIndexOf('.') + 1);
        return s.replace('$', '.');
    }

    public static String packageOf(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? "" : fqn.substring(0, i);
    }

    /** Erased simple name of a descriptor type: {@code [Ljava/lang/String;} → {@code String[]}. */
    public static String simpleOf(Type t) {
        return switch (t.getSort()) {
            case Type.ARRAY -> simpleOf(t.getElementType()) + "[]".repeat(t.getDimensions());
            case Type.OBJECT -> simple(t.getInternalName());
            default -> t.getClassName();
        };
    }

    /** Raw fqn of a descriptor type, or null for primitives; arrays give their element type. */
    public static String rawFqn(Type t) {
        Type e = t.getSort() == Type.ARRAY ? t.getElementType() : t;
        return e.getSort() == Type.OBJECT ? fqn(e.getInternalName()) : null;
    }

    /** {@code fqn#name(ParamSimpleTypes)}: the method key. */
    public static String methodId(String ownerFqn, String name, String descriptor) {
        StringBuilder sb = new StringBuilder(ownerFqn).append('#').append(name).append('(');
        Type[] args = Type.getArgumentTypes(descriptor);
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(simpleOf(args[i]));
        }
        return sb.append(')').toString();
    }

    /** Readable type of a field or local: generic signature if present, else descriptor. */
    public static String readableType(String descriptor, String signature) {
        if (signature != null) {
            try {
                Printer p = new Printer();
                new SignatureReader(signature).acceptType(p);
                return p.toString();
            } catch (RuntimeException ignored) {
                // fall back to the erased type
            }
        }
        return simpleOf(Type.getType(descriptor));
    }

    /** Readable method parts: return type and parameter types, generic when possible. */
    public record MethodTypes(String returnType, List<String> parameterTypes) {
    }

    public static MethodTypes methodTypes(String descriptor, String signature) {
        if (signature != null) {
            try {
                MethodPrinter mp = new MethodPrinter();
                new SignatureReader(signature).accept(mp);
                int expected = Type.getArgumentTypes(descriptor).length;
                if (mp.params.size() == expected) {
                    return new MethodTypes(mp.ret.toString(), mp.params.stream().map(Printer::toString).toList());
                }
            } catch (RuntimeException ignored) {
                // fall back to the erased types
            }
        }
        List<String> params = new ArrayList<>();
        for (Type t : Type.getArgumentTypes(descriptor)) {
            params.add(simpleOf(t));
        }
        return new MethodTypes(simpleOf(Type.getReturnType(descriptor)), params);
    }

    /**
     * Raw fqns of the first-level type arguments of a field/parameter type signature:
     * {@code List<Order>} → [Order fqn]; {@code Map<String, List<X>>} → [String, List]. Empty if none.
     */
    public static List<String> typeArguments(String typeSignature) {
        if (typeSignature == null) {
            return List.of();
        }
        try {
            ArgCollector c = new ArgCollector();
            new SignatureReader(typeSignature).acceptType(c);
            return c.args;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Per method parameter: raw fqns of its first-level type arguments (empty list if none). */
    public static List<List<String>> parameterTypeArguments(String methodSignature, int parameterCount) {
        List<List<String>> result = new ArrayList<>();
        if (methodSignature != null) {
            try {
                new SignatureReader(methodSignature).accept(new SignatureVisitor(Opcodes.ASM9) {
                    @Override
                    public SignatureVisitor visitParameterType() {
                        ArgCollector c = new ArgCollector();
                        result.add(c.args);
                        return c;
                    }

                    @Override
                    public SignatureVisitor visitReturnType() {
                        return new ArgCollector();
                    }

                    @Override
                    public SignatureVisitor visitClassBound() {
                        return new ArgCollector();
                    }

                    @Override
                    public SignatureVisitor visitInterfaceBound() {
                        return new ArgCollector();
                    }

                    @Override
                    public SignatureVisitor visitExceptionType() {
                        return new ArgCollector();
                    }
                });
            } catch (RuntimeException e) {
                result.clear();
            }
        }
        if (result.size() != parameterCount) {
            result.clear();
            for (int i = 0; i < parameterCount; i++) {
                result.add(List.of());
            }
        }
        return result;
    }

    /** Collects raw names of the type arguments of the visited class type. */
    static class ArgCollector extends SignatureVisitor {
        final List<String> args = new ArrayList<>();

        ArgCollector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visitInnerClassType(String name) {
            args.clear(); // arguments of the outer type do not count
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            int index = args.size();
            args.add(null);
            return new SignatureVisitor(Opcodes.ASM9) {
                private String raw;

                @Override
                public void visitClassType(String name) {
                    raw = name;
                    args.set(index, fqn(name));
                }

                @Override
                public void visitInnerClassType(String name) {
                    raw = raw + "$" + name;
                    args.set(index, fqn(raw));
                }

                @Override
                public void visitTypeVariable(String name) {
                    args.set(index, name);
                }

                @Override
                public SignatureVisitor visitTypeArgument(char w) {
                    return new SignatureVisitor(Opcodes.ASM9) {
                    };
                }
            };
        }

        @Override
        public void visitTypeArgument() {
            args.add("?");
        }
    }

    /** Builds a readable type string from a generic signature, with simple class names. */
    static final class Printer extends SignatureVisitor {
        private final StringBuilder sb = new StringBuilder();
        private int arrayDims;
        private boolean argsOpen;

        Printer() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visitBaseType(char descriptor) {
            sb.append(Type.getType(String.valueOf(descriptor)).getClassName());
            closeArray();
        }

        @Override
        public void visitTypeVariable(String name) {
            sb.append(name);
            closeArray();
        }

        @Override
        public SignatureVisitor visitArrayType() {
            arrayDims++;
            return this;
        }

        @Override
        public void visitClassType(String name) {
            sb.append(simple(name));
        }

        @Override
        public void visitInnerClassType(String name) {
            closeArgs();
            sb.append('.').append(name);
        }

        @Override
        public void visitTypeArgument() {
            openArg();
            sb.append('?');
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            openArg();
            if (wildcard == EXTENDS) {
                sb.append("? extends ");
            } else if (wildcard == SUPER) {
                sb.append("? super ");
            }
            return new Nested(sb);
        }

        @Override
        public void visitEnd() {
            closeArgs();
            closeArray();
        }

        private void openArg() {
            sb.append(argsOpen ? ", " : "<");
            argsOpen = true;
        }

        private void closeArgs() {
            if (argsOpen) {
                sb.append('>');
                argsOpen = false;
            }
        }

        private void closeArray() {
            while (arrayDims > 0) {
                sb.append("[]");
                arrayDims--;
            }
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }

    /** A type argument printed into the parent's buffer. */
    private static final class Nested extends SignatureVisitor {
        private final StringBuilder out;
        private final Printer inner = new Printer();

        Nested(StringBuilder out) {
            super(Opcodes.ASM9);
            this.out = out;
        }

        @Override
        public void visitBaseType(char d) {
            inner.visitBaseType(d);
            flush();
        }

        @Override
        public void visitTypeVariable(String name) {
            inner.visitTypeVariable(name);
            flush();
        }

        @Override
        public SignatureVisitor visitArrayType() {
            inner.visitArrayType();
            return this;
        }

        @Override
        public void visitClassType(String name) {
            inner.visitClassType(name);
        }

        @Override
        public void visitInnerClassType(String name) {
            inner.visitInnerClassType(name);
        }

        @Override
        public void visitTypeArgument() {
            inner.visitTypeArgument();
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            return inner.visitTypeArgument(wildcard);
        }

        @Override
        public void visitEnd() {
            inner.visitEnd();
            flush();
        }

        private void flush() {
            out.append(inner);
        }
    }

    private static final class MethodPrinter extends SignatureVisitor {
        final List<Printer> params = new ArrayList<>();
        Printer ret = new Printer();

        MethodPrinter() {
            super(Opcodes.ASM9);
        }

        @Override
        public SignatureVisitor visitParameterType() {
            Printer p = new Printer();
            params.add(p);
            return p;
        }

        @Override
        public SignatureVisitor visitReturnType() {
            ret = new Printer();
            return ret;
        }

        @Override
        public SignatureVisitor visitExceptionType() {
            return new Printer();
        }

        @Override
        public SignatureVisitor visitClassBound() {
            return new Printer();
        }

        @Override
        public SignatureVisitor visitInterfaceBound() {
            return new Printer();
        }

        @Override
        public SignatureVisitor visitSuperclass() {
            return new Printer();
        }

        @Override
        public SignatureVisitor visitInterface() {
            return new Printer();
        }
    }
}
