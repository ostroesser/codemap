package codemap.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Project-specific settings from a git-ignored {@code codemap.local.yml}. See
 * {@code codemap.example.yml} for the format.
 */
public record LocalConfig(List<RoleRule> roles, List<String> callPrefixes) {

    /** Exactly one of annotation / supertype / packageGlob is set. */
    public record RoleRule(String role, String annotation, String supertype, String packageGlob) {

        public String describe() {
            if (annotation != null) {
                return "local rule: @" + annotation.substring(annotation.lastIndexOf('.') + 1);
            }
            if (supertype != null) {
                return "local rule: extends " + supertype.substring(supertype.lastIndexOf('.') + 1);
            }
            return "local rule: package " + packageGlob;
        }

        /** {@code *} matches any run of characters, dots included. */
        public Pattern packagePattern() {
            StringBuilder regex = new StringBuilder();
            for (String part : packageGlob.split("\\*", -1)) {
                if (!regex.isEmpty()) {
                    regex.append(".*");
                }
                regex.append(Pattern.quote(part));
            }
            return Pattern.compile(regex.toString());
        }
    }

    /** Integration APIs whose calls are kept even though they are not scanned code. */
    public static final List<String> DEFAULT_CALL_PREFIXES = List.of(
            "org.springframework.kafka.core.KafkaTemplate",
            "org.springframework.kafka.core.KafkaOperations",
            "org.springframework.jms.core.JmsTemplate",
            "org.springframework.jms.core.JmsOperations",
            "org.springframework.jms.core.JmsClient",
            "org.springframework.amqp.rabbit.core.RabbitTemplate",
            "org.springframework.amqp.core.AmqpTemplate",
            "org.springframework.cloud.stream.function.StreamBridge",
            "org.springframework.web.client.RestTemplate",
            "org.springframework.web.client.RestOperations",
            "org.springframework.web.client.RestClient",
            "org.springframework.web.reactive.function.client.WebClient",
            "org.springframework.context.ApplicationEventPublisher#publishEvent",
            "org.springframework.web.service.invoker.HttpServiceProxyFactory");

    public static LocalConfig defaults() {
        return new LocalConfig(List.of(), DEFAULT_CALL_PREFIXES);
    }

    public static LocalConfig load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return defaults();
        }
        Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
        if (root == null) {
            return defaults();
        }
        List<RoleRule> roles = new ArrayList<>();
        for (Object o : list(root.get("roles"))) {
            if (!(o instanceof Map<?, ?> m) || m.get("role") == null) {
                throw new IOException(file + ": every roles entry needs a 'role'");
            }
            RoleRule rule = new RoleRule(str(m.get("role")), str(m.get("annotation")), str(m.get("extends")),
                    str(m.get("package")));
            int set = (rule.annotation() != null ? 1 : 0) + (rule.supertype() != null ? 1 : 0)
                    + (rule.packageGlob() != null ? 1 : 0);
            if (set != 1) {
                throw new IOException(file + ": roles entry for '" + rule.role()
                        + "' needs exactly one of annotation, extends, package");
            }
            roles.add(rule);
        }
        List<String> prefixes = new ArrayList<>(DEFAULT_CALL_PREFIXES);
        if (root.get("calls") instanceof Map<?, ?> calls) {
            list(calls.get("include")).forEach(p -> prefixes.add(String.valueOf(p)));
        }
        return new LocalConfig(List.copyOf(roles), List.copyOf(prefixes));
    }

    /** True if a call to this callee (fqn and method_id) should be stored although not internal. */
    public boolean keepsCall(String ownerFqn, String calleeId) {
        for (String p : callPrefixes) {
            if (p.contains("#") ? calleeId.startsWith(p) : ownerFqn.equals(p) || ownerFqn.startsWith(p + "$")
                    || ownerFqn.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
