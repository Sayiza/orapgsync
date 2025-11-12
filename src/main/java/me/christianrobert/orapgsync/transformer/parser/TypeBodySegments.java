package me.christianrobert.orapgsync.transformer.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of scanning a type body for method boundaries.
 *
 * Contains a list of identified type methods with their positions in the source code.
 */
public class TypeBodySegments {

    private final List<TypeMethodSegment> methods;

    public TypeBodySegments() {
        this.methods = new ArrayList<>();
    }

    public void addMethod(TypeMethodSegment method) {
        this.methods.add(method);
    }

    public List<TypeMethodSegment> getMethods() {
        return methods;
    }

    /**
     * Represents a single type method (MEMBER/STATIC function or procedure) within a type body.
     */
    public static class TypeMethodSegment {
        private final String name;
        private final MethodType methodType;
        private final int startPos;
        private final int endPos;
        private final int bodyStartPos;
        private final int bodyEndPos;

        public TypeMethodSegment(String name, MethodType methodType, int startPos, int endPos,
                                  int bodyStartPos, int bodyEndPos) {
            this.name = name;
            this.methodType = methodType;
            this.startPos = startPos;
            this.endPos = endPos;
            this.bodyStartPos = bodyStartPos;
            this.bodyEndPos = bodyEndPos;
        }

        public String getName() {
            return name;
        }

        public MethodType getMethodType() {
            return methodType;
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
            return methodType.isFunction();
        }

        public boolean isProcedure() {
            return methodType.isProcedure();
        }

        public boolean isMemberMethod() {
            return methodType == MethodType.MEMBER_FUNCTION || methodType == MethodType.MEMBER_PROCEDURE;
        }

        public boolean isStaticMethod() {
            return methodType == MethodType.STATIC_FUNCTION || methodType == MethodType.STATIC_PROCEDURE;
        }

        public boolean isMapMethod() {
            return methodType == MethodType.MAP_FUNCTION;
        }

        public boolean isOrderMethod() {
            return methodType == MethodType.ORDER_FUNCTION;
        }

        public boolean isConstructor() {
            return methodType == MethodType.CONSTRUCTOR;
        }

        @Override
        public String toString() {
            return String.format("TypeMethodSegment{name='%s', type=%s, startPos=%d, endPos=%d}",
                    name, methodType, startPos, endPos);
        }
    }

    /**
     * Type of method found in a type body.
     */
    public enum MethodType {
        MEMBER_FUNCTION(true, false),
        MEMBER_PROCEDURE(false, false),
        STATIC_FUNCTION(true, false),
        STATIC_PROCEDURE(false, false),
        MAP_FUNCTION(true, false),
        ORDER_FUNCTION(true, false),
        CONSTRUCTOR(true, true);  // Constructors are functions that return SELF

        private final boolean isFunction;
        private final boolean isConstructor;

        MethodType(boolean isFunction, boolean isConstructor) {
            this.isFunction = isFunction;
            this.isConstructor = isConstructor;
        }

        public boolean isFunction() {
            return isFunction;
        }

        public boolean isProcedure() {
            return !isFunction;
        }

        public boolean isConstructor() {
            return isConstructor;
        }
    }
}
