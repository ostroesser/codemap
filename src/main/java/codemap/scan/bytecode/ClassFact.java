package codemap.scan.bytecode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything read from one class file. Mutable while the reader fills it. */
public final class ClassFact {

    public String fqn;
    public String kind;
    public String visibility;
    public boolean isAbstract;
    public String superclass;
    public List<String> interfaces = new ArrayList<>();
    /** Generic supertypes, readable (e.g. {@code JpaRepository<Product, Long>}). */
    public List<String> genericSupertypes = new ArrayList<>();
    /** Raw supertype fqn → fqns of its type arguments, e.g. JpaRepository → [Product, Long]. */
    public Map<String, List<String>> supertypeArguments = new LinkedHashMap<>();
    public String sourceFileName;
    public List<AnnotationFact> annotations = new ArrayList<>();
    public List<FieldFact> fields = new ArrayList<>();
    public List<MethodFact> methods = new ArrayList<>();
    /** Internal (slash) names of referenced types with reference counts, for class_dependencies. */
    public Map<String, Integer> referencedTypes = new LinkedHashMap<>();

    void ref(String internalName) {
        referencedTypes.merge(internalName, 1, Integer::sum);
    }

    public record AnnotationFact(String fqn, Map<String, Object> attributes) {
    }

    public static final class FieldFact {
        public String name;
        public String type;
        public String typeFqn;
        public boolean isStatic;
        public boolean isFinal;
        public Object constantValue;
        /** Fqns of the type arguments, e.g. List&lt;OrderLine&gt; → [OrderLine fqn]. */
        public List<String> typeArguments = List.of();
        public List<AnnotationFact> annotations = new ArrayList<>();
    }

    public static final class MethodFact {
        public String id;
        public String name;
        public String descriptor;
        public String signature;
        public String returnType;
        public List<String> parameterTypes = new ArrayList<>();
        public List<String> parameterNames = new ArrayList<>();
        public String visibility;
        public boolean isStatic;
        public boolean isAbstract;
        public boolean isSynthetic;
        public int firstLine;
        public int lastLine;
        public List<AnnotationFact> annotations = new ArrayList<>();
        public List<List<AnnotationFact>> parameterAnnotations = new ArrayList<>();
        public List<CallFact> calls = new ArrayList<>();
        /** Per parameter: fqns of its type arguments. */
        public List<List<String>> parameterTypeArguments = new ArrayList<>();
        /** Constructors: field name → index of the parameter stored into it. */
        public Map<String, Integer> fieldsFromParameters = new LinkedHashMap<>();

        /** {@code List<Order> findAll(Long customerId)}. */
        public String readableSignature() {
            StringBuilder sb = new StringBuilder();
            if (!name.equals("<init>")) {
                sb.append(returnType).append(' ');
            }
            sb.append(name).append('(');
            for (int i = 0; i < parameterTypes.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(parameterTypes.get(i));
                if (i < parameterNames.size() && parameterNames.get(i) != null) {
                    sb.append(' ').append(parameterNames.get(i));
                }
            }
            return sb.append(')').toString();
        }
    }

    /**
     * A call site. {@code hints} are the most recent constant-ish operands pushed before the call
     * (string literals, static/instance field reads), used later to guess topics and URLs.
     */
    public record CallFact(String ownerFqn, String name, String descriptor, String calleeId, int line,
                           String invokeKind, List<String> hints) {
    }
}
