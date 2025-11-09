package me.christianrobert.orapgsync.transformer.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Data model representing function/procedure boundaries within an Oracle package.
 *
 * Used by FunctionBoundaryScanner to store identified function segments after scanning
 * a package body or spec.
 *
 * Each FunctionSegment contains character positions marking the start and end of a function,
 * enabling extraction without full ANTLR parsing.
 */
public class PackageSegments {

    private final List<FunctionSegment> functions = new ArrayList<>();

    /**
     * Adds a function segment to the collection.
     *
     * @param segment The function segment to add
     */
    public void addFunction(FunctionSegment segment) {
        functions.add(segment);
    }

    /**
     * Returns all identified function segments.
     *
     * @return Immutable list of function segments
     */
    public List<FunctionSegment> getFunctions() {
        return new ArrayList<>(functions);
    }

    /**
     * Returns the number of functions found.
     *
     * @return Function count
     */
    public int getFunctionCount() {
        return functions.size();
    }

    /**
     * Represents a single function or procedure within a package.
     *
     * Stores character positions and metadata needed for extraction and stub generation.
     */
    public static class FunctionSegment {

        private final String name;
        private final int startPos;      // Start of FUNCTION/PROCEDURE keyword
        private final int endPos;        // After final ';'
        private final int bodyStartPos;  // After IS/AS keyword
        private final int bodyEndPos;    // Before final END keyword
        private final boolean isFunction; // true = FUNCTION, false = PROCEDURE

        /**
         * Constructs a function segment.
         *
         * @param name Function/procedure name
         * @param startPos Character position where FUNCTION/PROCEDURE keyword starts
         * @param endPos Character position after the final ';'
         * @param bodyStartPos Character position after IS/AS keyword
         * @param bodyEndPos Character position before the final END keyword
         * @param isFunction true for FUNCTION, false for PROCEDURE
         */
        public FunctionSegment(String name, int startPos, int endPos,
                               int bodyStartPos, int bodyEndPos, boolean isFunction) {
            this.name = name;
            this.startPos = startPos;
            this.endPos = endPos;
            this.bodyStartPos = bodyStartPos;
            this.bodyEndPos = bodyEndPos;
            this.isFunction = isFunction;
        }

        public String getName() {
            return name;
        }

        public int getStartPos() {
            return startPos;
        }

        public int getEndPos() {
            return endPos;
        }

        public int getBodyStartPos() {
            return bodyStartPos;
        }

        public int getBodyEndPos() {
            return bodyEndPos;
        }

        public boolean isFunction() {
            return isFunction;
        }

        public boolean isProcedure() {
            return !isFunction;
        }

        /**
         * Returns the length of the entire function (including signature and body).
         *
         * @return Length in characters
         */
        public int getLength() {
            return endPos - startPos;
        }

        /**
         * Returns the length of just the body (between IS/AS and END).
         *
         * @return Body length in characters
         */
        public int getBodyLength() {
            return bodyEndPos - bodyStartPos;
        }

        @Override
        public String toString() {
            return String.format("%s %s [%d-%d, body: %d-%d]",
                isFunction ? "FUNCTION" : "PROCEDURE",
                name,
                startPos, endPos,
                bodyStartPos, bodyEndPos);
        }
    }
}
