package codemap.scan.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class TypeNamesTest {

    @Test
    void methodIdsUseErasedSimpleTypes() {
        assertEquals("com.example.A#m(long,String[],Map,Outer.Inner)", TypeNames.methodId("com.example.A", "m",
                "(J[Ljava/lang/String;Ljava/util/Map;Lcom/example/Outer$Inner;)V"));
    }

    @Test
    void genericTypesAreReadable() {
        assertEquals("Map<String, List<Integer>>",
                TypeNames.readableType("Ljava/util/Map;", "Ljava/util/Map<Ljava/lang/String;Ljava/util/List<Ljava/lang/Integer;>;>;"));
        assertEquals("List<? extends Number>[]",
                TypeNames.readableType("[Ljava/util/List;", "[Ljava/util/List<+Ljava/lang/Number;>;"));
        assertEquals("Optional<?>", TypeNames.readableType("Ljava/util/Optional;", "Ljava/util/Optional<*>;"));
        assertEquals("int[][]", TypeNames.readableType("[[I", null));
        assertEquals("Outer<String>.Inner<Long>", TypeNames.readableType("Lcom/example/Outer$Inner;",
                "Lcom/example/Outer<Ljava/lang/String;>.Inner<Ljava/lang/Long;>;"));
    }

    @Test
    void methodTypesFromSignature() {
        var types = TypeNames.methodTypes("(Ljava/util/List;I)Ljava/util/Optional;",
                "<T:Ljava/lang/Object;>(Ljava/util/List<TT;>;I)Ljava/util/Optional<TT;>;");
        assertEquals("Optional<T>", types.returnType());
        assertEquals(List.of("List<T>", "int"), types.parameterTypes());
    }
}
