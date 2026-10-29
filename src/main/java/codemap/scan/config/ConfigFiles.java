package codemap.scan.config;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * Finds Spring Boot config files of a module and flattens them to (profile, key, value).
 * Profiles come from the file name ({@code application-dev.yml}) and from
 * {@code spring.config.activate.on-profile} (or legacy {@code spring.profiles}) in multi-document
 * files. The activation key itself is kept as a regular property.
 */
public final class ConfigFiles {

    public static final String DEFAULT_PROFILE = "(default)";

    private static final Pattern FILE_NAME =
            Pattern.compile("(application|bootstrap)(?:-([^.]+))?\\.(yml|yaml|properties)");

    public record Entry(String file, String profile, String key, String value) {
    }

    public record Result(List<Entry> entries, List<String> problems) {
    }

    private ConfigFiles() {
    }

    public static Result scanModule(Path moduleDir) {
        Path resources = moduleDir.resolve("src/main/resources");
        List<Entry> entries = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (!Files.isDirectory(resources)) {
            return new Result(entries, problems);
        }
        List<Path> files;
        try (Stream<Path> s = Files.walk(resources, 2)) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> FILE_NAME.matcher(p.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            problems.add("cannot list " + resources + ": " + e.getMessage());
            return new Result(entries, problems);
        }
        for (Path file : files) {
            String name = resources.relativize(file).toString().replace('\\', '/');
            Matcher m = FILE_NAME.matcher(file.getFileName().toString());
            m.matches();
            String fileProfile = m.group(2) != null ? m.group(2) : DEFAULT_PROFILE;
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                List<Map<String, String>> docs = m.group(3).equals("properties") ? parseProperties(text) : parseYaml(text);
                for (Map<String, String> doc : docs) {
                    String profile = fileProfile;
                    String activation = doc.getOrDefault("spring.config.activate.on-profile", doc.get("spring.profiles"));
                    if (activation != null && fileProfile.equals(DEFAULT_PROFILE)) {
                        profile = activation;
                    }
                    for (var e : doc.entrySet()) {
                        entries.add(new Entry(name, profile, e.getKey(), e.getValue()));
                    }
                }
            } catch (Exception e) {
                problems.add("cannot parse " + name + ": " + firstLine(e.getMessage()));
            }
        }
        return new Result(entries, problems);
    }

    static List<Map<String, String>> parseYaml(String text) {
        // No implicit typing: every scalar stays the exact text written in the file.
        Resolver stringsOnly = new Resolver() {
            @Override
            protected void addImplicitResolvers() {
            }
        };
        LoaderOptions options = new LoaderOptions();
        DumperOptions dumper = new DumperOptions();
        Yaml yaml = new Yaml(new SafeConstructor(options), new Representer(dumper), dumper, options, stringsOnly);
        List<Map<String, String>> docs = new ArrayList<>();
        for (Object doc : yaml.loadAll(text)) {
            Map<String, String> flat = new LinkedHashMap<>();
            flatten("", doc, flat);
            if (!flat.isEmpty()) {
                docs.add(flat);
            }
        }
        return docs;
    }

    static List<Map<String, String>> parseProperties(String text) throws IOException {
        List<Map<String, String>> docs = new ArrayList<>();
        for (String part : text.split("(?m)^[#!]---\\s*$")) {
            Properties p = new Properties();
            p.load(new StringReader(part));
            Map<String, String> flat = new LinkedHashMap<>();
            p.stringPropertyNames().stream().sorted().forEach(k -> flat.put(k, p.getProperty(k)));
            if (!flat.isEmpty()) {
                docs.add(flat);
            }
        }
        return docs;
    }

    private static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            if (map.isEmpty() && !prefix.isEmpty()) {
                out.put(prefix, "");
            }
            for (var e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                flatten(prefix.isEmpty() ? key : prefix + "." + key, e.getValue(), out);
            }
        } else if (node instanceof Collection<?> list) {
            int i = 0;
            for (Object item : list) {
                flatten(prefix + "[" + i++ + "]", item, out);
            }
            if (i == 0) {
                out.put(prefix, "");
            }
        } else if (!prefix.isEmpty()) {
            out.put(prefix, node == null ? null : String.valueOf(node));
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
