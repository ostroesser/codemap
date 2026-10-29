package codemap.run;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class SourceViewerTest {

    private static final List<String> FILE = List.of(
            "class A {",                                    // 1
            "    /** doc */",                               // 2
            "    @Override",                                // 3
            "    @Transactional(readOnly = true)",          // 4
            "    public String run(",                       // 5
            "            String x) {",                      // 6
            "        if (x.equals(\"}\")) {",               // 7
            "            return \"{\";",                    // 8
            "        }",                                    // 9
            "        return x; // }",                       // 10
            "    }",                                        // 11
            "",                                             // 12
            "    abstract void later(int a);",              // 13
            "    A() {",                                    // 14
            "    }",                                        // 15
            "}");                                           // 16

    @Test
    void widensBytecodeLinesToDeclarationAndClosingBrace() {
        assertArrayEquals(new int[]{3, 11}, SourceViewer.range(FILE, new String[]{"A#run(String)", "run", "7", "10"}, "A"));
    }

    @Test
    void abstractMethodsAreFoundByName() {
        assertArrayEquals(new int[]{13, 13}, SourceViewer.range(FILE, new String[]{"A#later(int)", "later", null, null}, "A"));
    }

    @Test
    void constructorsUseTheClassName() {
        assertArrayEquals(new int[]{14, 15}, SourceViewer.range(FILE, new String[]{"A#<init>()", "<init>", "14", "15"}, "A"));
    }
}
