package me.christianrobert.orapgsync.typemethod.service;

import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.transformer.parser.TypeBodySegments;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TypeMethodSourcePairingTest {

    private static final String SCHEMA = "hr";
    private static final String TYPE = "demo_item_t";

    private static TypeMethodMetadata dictionaryRow(String methodName, String methodNo) {
        TypeMethodMetadata m = new TypeMethodMetadata(SCHEMA, TYPE, methodName, "PUBLIC");
        m.setMethodNo(methodNo);
        m.setInstantiable("YES");
        return m;
    }

    private static TypeMethodMetadata abstractRow(String methodName, String methodNo) {
        TypeMethodMetadata m = dictionaryRow(methodName, methodNo);
        m.setInstantiable("NO");
        return m;
    }

    private static TypeBodySegments.TypeMethodSegment segment(String name) {
        return new TypeBodySegments.TypeMethodSegment(
                name, TypeBodySegments.MethodType.MEMBER_FUNCTION, 0, 10, 2, 8);
    }

    private static List<TypeBodySegments.TypeMethodSegment> segments(String... names) {
        List<TypeBodySegments.TypeMethodSegment> list = new ArrayList<>();
        for (String name : names) {
            list.add(segment(name));
        }
        return list;
    }

    @Nested
    @DisplayName("Overload ordinals follow method_no within a name")
    class Ordinals {

        @Test
        void overloadsOfOneNameAreRankedByMethodNo() {
            List<TypeMethodMetadata> methods = List.of(
                    dictionaryRow("add_item", "7"),
                    dictionaryRow("add_item", "3"),
                    dictionaryRow("add_item", "5"));

            List<TypeMethodMetadata> mutable = new ArrayList<>(methods);
            TypeMethodSourcePairing.assignOverloadOrdinals(mutable);

            assertEquals(0, findByMethodNo(mutable, "3").getOverloadOrdinal());
            assertEquals(1, findByMethodNo(mutable, "5").getOverloadOrdinal());
            assertEquals(2, findByMethodNo(mutable, "7").getOverloadOrdinal());
        }

        /** method_no arrives as text; a lexicographic sort would put 10 before 2. */
        @Test
        void methodNoSortsNumericallyNotLexicographically() {
            List<TypeMethodMetadata> methods = new ArrayList<>(List.of(
                    dictionaryRow("f", "10"),
                    dictionaryRow("f", "2")));

            TypeMethodSourcePairing.assignOverloadOrdinals(methods);

            assertEquals(0, findByMethodNo(methods, "2").getOverloadOrdinal());
            assertEquals(1, findByMethodNo(methods, "10").getOverloadOrdinal());
        }

        @Test
        void distinctNamesEachStartAtZero() {
            List<TypeMethodMetadata> methods = new ArrayList<>(List.of(
                    dictionaryRow("alpha", "1"),
                    dictionaryRow("beta", "2")));

            TypeMethodSourcePairing.assignOverloadOrdinals(methods);

            assertEquals(0, methods.get(0).getOverloadOrdinal());
            assertEquals(0, methods.get(1).getOverloadOrdinal());
        }

        private TypeMethodMetadata findByMethodNo(List<TypeMethodMetadata> methods, String methodNo) {
            return methods.stream()
                    .filter(m -> methodNo.equals(m.getMethodNo()))
                    .findFirst()
                    .orElseThrow();
        }
    }

    @Nested
    @DisplayName("Pairing when the two sides agree")
    class Consistent {

        @Test
        void singleMethodPairsAtOrdinalZero() {
            var result = TypeMethodSourcePairing.pair(SCHEMA, TYPE,
                    List.of(dictionaryRow("line_total", "1")),
                    segments("line_total"));

            assertTrue(result.isConsistent());
            assertEquals(1, result.segmentsByKey().size());
            assertTrue(result.segmentsByKey().containsKey("line_total#0"));
        }

        @Test
        void overloadsPairInSourceOrder() {
            var result = TypeMethodSourcePairing.pair(SCHEMA, TYPE,
                    List.of(dictionaryRow("add_item", "1"),
                            dictionaryRow("add_item", "2"),
                            dictionaryRow("add_item", "3")),
                    segments("add_item", "add_item", "add_item"));

            assertTrue(result.isConsistent());
            assertEquals(3, result.segmentsByKey().size());
            assertTrue(result.segmentsByKey().containsKey("add_item#0"));
            assertTrue(result.segmentsByKey().containsKey("add_item#1"));
            assertTrue(result.segmentsByKey().containsKey("add_item#2"));
        }

        @Test
        void nameMatchingIsCaseInsensitive() {
            var result = TypeMethodSourcePairing.pair(SCHEMA, TYPE,
                    List.of(dictionaryRow("LINE_TOTAL", "1")),
                    segments("line_total"));

            assertTrue(result.isConsistent(), () -> result.mismatches().toString());
        }

        /**
         * An abstract method is declared in the type spec and has no body. Counting it as expected
         * would make every abstract-bearing type look inconsistent.
         */
        @Test
        void abstractMethodsAreNotExpectedToHaveBodies() {
            var result = TypeMethodSourcePairing.pair(SCHEMA, TYPE,
                    List.of(abstractRow("area", "1"), dictionaryRow("describe", "2")),
                    segments("describe"));

            assertTrue(result.isConsistent(), () -> result.mismatches().toString());
            assertEquals(1, result.segmentsByKey().size());
            assertTrue(result.segmentsByKey().containsKey("describe#0"));
        }
    }

    @Nested
    @DisplayName("Pairing refuses when the two sides disagree")
    class Inconsistent {

        /**
         * The case the whole design exists for. Pairing two segments against three dictionary rows
         * would attach a body to the wrong signature -- both individually plausible, so nothing
         * downstream would notice.
         */
        @Test
        void fewerSegmentsThanDictionaryRowsIsRefused() {
            var result = TypeMethodSourcePairing.pair(SCHEMA, TYPE,
                    List.of(dictionaryRow("add_item", "1"),
                            dictionaryRow("add_item", "2"),
                            dictionaryRow("add_item", "3")),
                    segments("add_item", "add_item"));

            assertFalse(result.isConsistent());
            assertTrue(result.segmentsByKey().isEmpty(), "nothing may be paired on a mismatch");
            assertEquals(1, result.mismatches().size());
            assertTrue(result.mismatches().get(0).contains("3"), result.mismatches().get(0));
            assertTrue(result.mismatches().get(0).contains("2"), result.mismatches().get(0));
        }

        @Test
        void moreSegmentsThanDictionaryRowsIsRefused() {
            var result = TypeMethodSourcePairing.pair(SCHEMA, TYPE,
                    List.of(dictionaryRow("f", "1")),
                    segments("f", "f"));

            assertFalse(result.isConsistent());
            assertTrue(result.segmentsByKey().isEmpty());
        }

        /** A body method absent from the dictionary is a disagreement, not something to store. */
        @Test
        void segmentWithNoDictionaryRowIsRefused() {
            var result = TypeMethodSourcePairing.pair(SCHEMA, TYPE,
                    List.of(dictionaryRow("known", "1")),
                    segments("known", "unknown"));

            assertFalse(result.isConsistent());
            assertTrue(result.mismatches().get(0).contains("unknown"), result.mismatches().toString());
        }

        /**
         * One bad name rejects the whole type: a scanner that mis-detected one boundary may have
         * shifted its neighbours, so the names that happen to tally are not trustworthy either.
         */
        @Test
        void oneBadNameRejectsTheEntireType() {
            var result = TypeMethodSourcePairing.pair(SCHEMA, TYPE,
                    List.of(dictionaryRow("good", "1"),
                            dictionaryRow("bad", "2"),
                            dictionaryRow("bad", "3")),
                    segments("good", "bad"));

            assertFalse(result.isConsistent());
            assertTrue(result.segmentsByKey().isEmpty(),
                    "the consistent name must not be stored either");
        }

        @Test
        void concreteMethodWithNoBodyIsRefused() {
            var result = TypeMethodSourcePairing.pair(SCHEMA, TYPE,
                    List.of(dictionaryRow("implemented", "1")),
                    segments());

            assertFalse(result.isConsistent());
        }
    }
}
