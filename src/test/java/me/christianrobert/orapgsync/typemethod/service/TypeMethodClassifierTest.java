package me.christianrobert.orapgsync.typemethod.service;

import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dictionary value combinations from the Phase 0 verification, plus the combinations that must
 * throw. See section 2 of documentation/completed/TYPE_METHOD_IMPLEMENTATION_PLAN.md.
 */
class TypeMethodClassifierTest {

    private static final String TYPE = "DEMO_ITEM_T";

    @Nested
    @DisplayName("The six combinations observed in a real schema")
    class ObservedCombinations {

        @Test
        void selfInWithResultIsAReadOnlyMemberFunction() {
            assertEquals(TypeMethodKind.MEMBER_FUNCTION,
                    TypeMethodClassifier.classify(TYPE, "LINE_TOTAL", "PUBLIC", "IN", true));
        }

        @Test
        void selfInOutWithoutResultIsAMemberProcedure() {
            assertEquals(TypeMethodKind.MEMBER_PROCEDURE,
                    TypeMethodClassifier.classify(TYPE, "ADD_QTY", "PUBLIC", "IN OUT", false));
        }

        @Test
        void nameMatchingTheTypeIsAConstructor() {
            assertEquals(TypeMethodKind.CONSTRUCTOR,
                    TypeMethodClassifier.classify(TYPE, "DEMO_ITEM_T", "PUBLIC", "IN OUT", true));
        }

        @Test
        void noSelfRowWithResultIsAStaticFunction() {
            assertEquals(TypeMethodKind.STATIC_FUNCTION,
                    TypeMethodClassifier.classify(TYPE, "EMPTY_ITEM", "PUBLIC", null, true));
        }

        /**
         * SELF IN OUT on a method that also returns a value: a mutating member function. Classified
         * as a member function here; the rejection lives downstream, because PostgreSQL cannot
         * express INOUT parameters plus an independent RETURNS.
         */
        @Test
        void selfInOutWithResultIsAMemberFunctionAndIsFlaggedMutating() {
            TypeMethodKind kind =
                    TypeMethodClassifier.classify(TYPE, "NEXT_VALUE", "PUBLIC", "IN OUT", true);
            assertEquals(TypeMethodKind.MEMBER_FUNCTION, kind);
            assertTrue(TypeMethodClassifier.mutatesSelf("IN OUT"));
        }

        @Test
        void mapMethodIsRecognisedByMethodType() {
            assertEquals(TypeMethodKind.MAP_FUNCTION,
                    TypeMethodClassifier.classify(TYPE, "SORT_KEY", "MAP", "IN", true));
        }
    }

    @Nested
    @DisplayName("Combinations not observed, which must still be handled")
    class UnobservedButLegal {

        /**
         * The verification schema contained no ORDER methods and no static procedures. Those are
         * facts about one schema and must never become assumptions -- both are legal Oracle.
         */
        @Test
        void orderMethodIsRecognised() {
            assertEquals(TypeMethodKind.ORDER_FUNCTION,
                    TypeMethodClassifier.classify("DEMO_TAG_T", "COMPARE_TO", "ORDER", "IN", true));
        }

        @Test
        void staticProcedureIsRecognised() {
            assertEquals(TypeMethodKind.STATIC_PROCEDURE,
                    TypeMethodClassifier.classify(TYPE, "DESCRIBE_ALL", "PUBLIC", null, false));
        }
    }

    @Nested
    @DisplayName("INSTANTIABLE and METHOD_TYPE do not decide member-vs-static")
    class SupersededReadings {

        /**
         * The regression guard for the original bug. INSTANTIABLE is not a parameter of classify()
         * at all, so an abstract member method (INSTANTIABLE = NO, but with SELF) classifies as a
         * member method. Under the old reading it became static.
         */
        @Test
        void abstractMemberMethodIsStillAMemberMethod() {
            assertEquals(TypeMethodKind.MEMBER_FUNCTION,
                    TypeMethodClassifier.classify("DEMO_SHAPE_T", "AREA", "PUBLIC", "IN", true));
        }

        /** METHOD_TYPE = PUBLIC says nothing about function-vs-procedure; the result row does. */
        @Test
        void publicMethodTypeSplitsOnTheResultRowAlone() {
            assertEquals(TypeMethodKind.MEMBER_FUNCTION,
                    TypeMethodClassifier.classify(TYPE, "M", "PUBLIC", "IN", true));
            assertEquals(TypeMethodKind.MEMBER_PROCEDURE,
                    TypeMethodClassifier.classify(TYPE, "M", "PUBLIC", "IN", false));
        }
    }

    @Nested
    @DisplayName("Unexpected dictionary values throw rather than default")
    class Rejections {

        @Test
        void theSupersededMethodTypeValuesAreRejected() {
            // 'FUNCTION' is what the previous implementation expected. If it ever appears, the
            // reading has changed and must be re-verified rather than silently accepted.
            TypeMethodClassificationException e = assertThrows(
                    TypeMethodClassificationException.class,
                    () -> TypeMethodClassifier.classify(TYPE, "M", "FUNCTION", "IN", true));
            assertTrue(e.getMessage().contains("METHOD_TYPE"), e.getMessage());
        }

        @Test
        void unknownSelfModeIsRejected() {
            assertThrows(TypeMethodClassificationException.class,
                    () -> TypeMethodClassifier.classify(TYPE, "M", "PUBLIC", "OUT", true));
        }

        @Test
        void constructorWithoutSelfIsRejectedRatherThanGuessed() {
            TypeMethodClassificationException e = assertThrows(
                    TypeMethodClassificationException.class,
                    () -> TypeMethodClassifier.classify(TYPE, TYPE, "PUBLIC", null, true));
            assertTrue(e.getMessage().contains("named after its type"), e.getMessage());
        }

        @Test
        void constructorWithoutResultRowIsRejected() {
            assertThrows(TypeMethodClassificationException.class,
                    () -> TypeMethodClassifier.classify(TYPE, TYPE, "PUBLIC", "IN OUT", false));
        }

        @Test
        void mapMethodWithoutSelfIsRejected() {
            assertThrows(TypeMethodClassificationException.class,
                    () -> TypeMethodClassifier.classify(TYPE, "SORT_KEY", "MAP", null, true));
        }

        @Test
        void nullMethodTypeIsRejected() {
            assertThrows(TypeMethodClassificationException.class,
                    () -> TypeMethodClassifier.classify(TYPE, "M", null, "IN", true));
        }
    }

    @Nested
    @DisplayName("Dictionary value normalisation")
    class Normalisation {

        @Test
        void selfModeIsCaseAndWhitespaceInsensitive() {
            assertEquals(TypeMethodKind.MEMBER_PROCEDURE,
                    TypeMethodClassifier.classify(TYPE, "M", "PUBLIC", "in  out", false));
            assertTrue(TypeMethodClassifier.mutatesSelf("in out"));
        }

        /** An empty PARAM_MODE must read as "no SELF row", not as an unknown mode. */
        @Test
        void blankSelfModeMeansStatic() {
            assertEquals(TypeMethodKind.STATIC_FUNCTION,
                    TypeMethodClassifier.classify(TYPE, "M", "PUBLIC", "   ", true));
        }

        @Test
        void constructorNameMatchIsCaseInsensitive() {
            assertEquals(TypeMethodKind.CONSTRUCTOR,
                    TypeMethodClassifier.classify("demo_item_t", "DEMO_ITEM_T", "PUBLIC", "IN OUT", true));
        }

        @Test
        void mutatesSelfIsFalseForReadOnlyAndStatic() {
            assertFalse(TypeMethodClassifier.mutatesSelf("IN"));
            assertFalse(TypeMethodClassifier.mutatesSelf(null));
        }
    }
}
