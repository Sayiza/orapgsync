package me.christianrobert.orapgsync.typemethod.service;

import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodParameter;
import me.christianrobert.orapgsync.core.tools.OracleTypeClassifier;
import me.christianrobert.orapgsync.core.tools.PostgresIdentifierNormalizer;
import me.christianrobert.orapgsync.core.tools.TypeConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the PostgreSQL stub DDL for one Oracle type method — plan Phase 5.
 *
 * <p><b>The signature this produces must be byte-for-byte compatible with the one
 * {@code TypeMethodEmitter} produces at implementation time</b>, because step 29 replaces the stub
 * with {@code CREATE OR REPLACE FUNCTION}. PostgreSQL identifies a function by
 * {@code (schema, name, IN/INOUT argument types)} and refuses to change anything else about it, so
 * any disagreement is not a cosmetic difference — it either creates a second overload or fails
 * outright. The two are built from different inputs (data dictionary here, parse tree there) and so
 * cannot share code; {@code TypeMethodStubSignatureAgreementTest} compares their output instead.
 *
 * <p>The first full run measured what disagreement costs: <b>449 of 1081 failures were
 * {@code cannot change routine kind}</b> — this job emitted {@code CREATE OR REPLACE PROCEDURE} for
 * every method classified as a procedure while the emitter always emits a FUNCTION. Three rules
 * fell out of aligning them, and each is load-bearing:
 *
 * <ul>
 *   <li><b>Always a FUNCTION, never a PROCEDURE.</b> The CLAUDE.md invariant, and the only shape
 *       {@code CREATE OR REPLACE} can later replace.</li>
 *   <li><b>A member procedure takes {@code INOUT self}</b>, a member function takes {@code self}.
 *       Oracle grants a member procedure {@code SELF IN OUT} implicitly — that is what lets
 *       {@code obj.set_x(1)} modify {@code obj}. Emitting it as plain {@code self} changes the
 *       function's result type, and {@code CREATE OR REPLACE} then fails on the return type.</li>
 *   <li><b>A procedure's RETURNS is dictated by its OUT parameters</b>, with {@code INOUT self}
 *       counting as one: none → {@code void}, one → that type, several → {@code RECORD}.</li>
 * </ul>
 *
 * <p><b>Constructors are named after their type</b> ({@code hr.demo_item_t}, not
 * {@code hr.demo_item_t__demo_item_t}) and return it — plan section 5, relying on PostgreSQL's
 * native overloading. {@code getPostgresName()} is deliberately not used for them.
 */
public final class TypeMethodStubSqlBuilder {

    private static final Logger log = LoggerFactory.getLogger(TypeMethodStubSqlBuilder.class);

    private TypeMethodStubSqlBuilder() {
    }

    /**
     * Two Oracle methods whose PostgreSQL signatures are indistinguishable.
     *
     * @param signature the shared PostgreSQL identity signature
     * @param methods   the Oracle methods that collapse onto it, in extraction order
     */
    public record SignatureCollision(String signature, List<TypeMethodMetadata> methods) {

        /** Human-readable description naming every Oracle method involved. */
        public String describe() {
            List<String> names = new ArrayList<>();
            for (TypeMethodMetadata method : methods) {
                names.add(method.getDisplayName() + " (method_no " + method.getMethodNo() + ", "
                        + method.getMemberTypeDescription() + ")");
            }
            return signature + " ← " + String.join(", ", names);
        }
    }

    /**
     * Finds overload sets that collapse to one PostgreSQL signature.
     *
     * <p>Oracle distinguishes {@code VARCHAR2} from {@code CHAR} and {@code NUMBER(10)} from
     * {@code NUMBER}; the type mapping collapses both pairs to {@code text} and {@code numeric}. Two
     * Oracle overloads then produce one PostgreSQL function and the second
     * {@code CREATE OR REPLACE} <b>silently overwrites the first</b> — no error, one method's body
     * simply becomes the other's. Refusing the whole colliding set is the same call
     * {@code PostgresIdentifierNormalizer.findCollisions()} makes for column names.
     *
     * @param methods all methods being created, any schema
     * @return one entry per colliding signature; empty when every signature is unique
     */
    public static List<SignatureCollision> findSignatureCollisions(List<TypeMethodMetadata> methods) {
        Map<String, List<TypeMethodMetadata>> bySignature = new LinkedHashMap<>();
        for (TypeMethodMetadata method : methods) {
            bySignature.computeIfAbsent(identitySignature(method), k -> new ArrayList<>()).add(method);
        }

        List<SignatureCollision> collisions = new ArrayList<>();
        bySignature.forEach((signature, group) -> {
            if (group.size() > 1) {
                collisions.add(new SignatureCollision(signature, group));
            }
        });
        return collisions;
    }

    /**
     * The signature PostgreSQL uses to tell one overload from another: qualified name plus the
     * <b>input</b> argument types. Pure OUT parameters are excluded, exactly as PostgreSQL excludes
     * them from {@code proargtypes}, so two methods differing only in an OUT parameter collide.
     */
    public static String identitySignature(TypeMethodMetadata method) {
        return qualifiedFunctionName(method) + "(" + String.join(",", inputTypes(method)) + ")";
    }

    /**
     * The full {@code CREATE OR REPLACE FUNCTION} for one method's stub.
     */
    public static String createStubSql(TypeMethodMetadata method) {
        StringBuilder sql = new StringBuilder();

        sql.append("CREATE OR REPLACE FUNCTION ").append(qualifiedFunctionName(method));
        sql.append("(").append(String.join(", ", parameterDefinitions(method))).append(")\n");
        sql.append(returnsClause(method)).append("\n");
        sql.append("LANGUAGE plpgsql\n");
        sql.append("AS $$\n");
        sql.append("BEGIN\n");
        sql.append("    ").append(stubReturnStatement(method)).append("  -- Stub: Oracle ")
                .append(method.getSchema().toUpperCase()).append(".")
                .append(method.getTypeName().toUpperCase()).append(".")
                .append(method.getMethodName().toUpperCase())
                .append(" (").append(method.getMemberTypeDescription()).append(")\n");
        sql.append("END;\n");
        sql.append("$$;");

        return sql.toString();
    }

    /**
     * {@code schema.typename__methodname}, or {@code schema.typename} for a constructor.
     */
    static String qualifiedFunctionName(TypeMethodMetadata method) {
        String schema = PostgresIdentifierNormalizer.normalizeIdentifier(method.getSchema());
        if (method.isConstructor()) {
            return schema + "." + PostgresIdentifierNormalizer.normalizeIdentifier(method.getTypeName());
        }
        return schema + "." + PostgresIdentifierNormalizer.normalizeIdentifier(method.getPostgresName());
    }

    /** The migrated composite type of the owning object type. */
    static String selfType(TypeMethodMetadata method) {
        return PostgresIdentifierNormalizer.normalizeIdentifier(method.getSchema()) + "."
                + PostgresIdentifierNormalizer.normalizeIdentifier(method.getTypeName());
    }

    /**
     * Rendered parameters, SELF first for member methods.
     *
     * <p>A member <b>procedure</b> gets {@code INOUT self}: Oracle gives it {@code SELF IN OUT}
     * implicitly, and the mode is part of the signature the implementation will replace.
     */
    static List<String> parameterDefinitions(TypeMethodMetadata method) {
        List<String> definitions = new ArrayList<>();

        if (method.isMemberMethod()) {
            definitions.add(method.isProcedure()
                    ? "INOUT self " + selfType(method)
                    : "self " + selfType(method));
        }

        for (TypeMethodParameter param : method.getParameters()) {
            String mode = parameterMode(param);
            String name = PostgresIdentifierNormalizer.normalizeIdentifier(param.getParameterName());
            String prefix = "IN".equals(mode) ? "" : mode + " ";
            definitions.add(prefix + name + " " + parameterType(param, method));
        }

        return definitions;
    }

    /**
     * Types of the parameters PostgreSQL uses for overload resolution: SELF (member methods) plus
     * every IN and INOUT parameter, in declaration order.
     */
    static List<String> inputTypes(TypeMethodMetadata method) {
        List<String> types = new ArrayList<>();

        if (method.isMemberMethod()) {
            types.add(selfType(method));
        }
        for (TypeMethodParameter param : method.getParameters()) {
            if (!"OUT".equals(parameterMode(param))) {
                types.add(parameterType(param, method));
            }
        }
        return types;
    }

    /**
     * The RETURNS clause, derived exactly as {@code TypeMethodEmitter.returnsClause} derives it.
     */
    static String returnsClause(TypeMethodMetadata method) {
        if (method.isConstructor()) {
            return "RETURNS " + selfType(method);
        }
        if (method.isFunction()) {
            return "RETURNS " + returnType(method);
        }

        // Procedure: PostgreSQL has no independent return, so the OUT/INOUT parameters dictate it.
        int outCount = 0;
        String singleOutType = null;

        if (method.isMemberMethod()) {
            outCount++;
            singleOutType = selfType(method);
        }
        for (TypeMethodParameter param : method.getParameters()) {
            if (!"IN".equals(parameterMode(param))) {
                outCount++;
                if (outCount == 1) {
                    singleOutType = parameterType(param, method);
                }
            }
        }

        if (outCount == 0) {
            return "RETURNS void";
        }
        if (outCount == 1) {
            return "RETURNS " + singleOutType;
        }
        return "RETURNS RECORD";
    }

    /**
     * PL/pgSQL forbids {@code RETURN <expr>} in a function that has OUT parameters, and requires a
     * bare {@code RETURN} for {@code RETURNS void}. A stub that ignores this does not compile.
     */
    static String stubReturnStatement(TypeMethodMetadata method) {
        if (method.isConstructor()) {
            return "RETURN NULL;";
        }
        if (hasOutputParameters(method)) {
            return "RETURN;";
        }
        return method.isFunction() ? "RETURN NULL;" : "RETURN;";
    }

    private static boolean hasOutputParameters(TypeMethodMetadata method) {
        if (method.isMemberMethod() && method.isProcedure()) {
            return true;  // INOUT self
        }
        for (TypeMethodParameter param : method.getParameters()) {
            if (!"IN".equals(parameterMode(param))) {
                return true;
            }
        }
        return false;
    }

    /** Oracle's {@code IN} / {@code OUT} / {@code IN/OUT} as a PostgreSQL mode keyword. */
    private static String parameterMode(TypeMethodParameter param) {
        String inOut = param.getInOut();
        if (inOut == null) {
            return "IN";
        }
        String upper = inOut.toUpperCase();
        if (upper.contains("IN") && upper.contains("OUT")) {
            return "INOUT";
        }
        if (upper.contains("OUT")) {
            return "OUT";
        }
        return "IN";
    }

    private static String parameterType(TypeMethodParameter param, TypeMethodMetadata method) {
        if (param.isCustomDataType()) {
            String owner = param.getDataTypeOwner().toLowerCase();
            String typeName = param.getDataTypeName().toLowerCase();

            if (OracleTypeClassifier.isComplexOracleSystemType(owner, typeName)) {
                return "jsonb";
            }
            return owner + "." + typeName;
        }

        String postgresType = TypeConverter.toPostgre(param.getDataType());
        if (postgresType == null) {
            log.warn("Unknown data type '{}' for parameter '{}' in type method '{}', using text",
                    param.getDataType(), param.getParameterName(), method.getDisplayName());
            return "text";
        }
        return postgresType;
    }

    private static String returnType(TypeMethodMetadata method) {
        if (method.isCustomReturnType()) {
            String owner = method.getReturnTypeOwner().toLowerCase();
            String typeName = method.getReturnTypeName().toLowerCase();

            if (OracleTypeClassifier.isXmlType(owner, typeName)) {
                return "xml";
            }
            if (OracleTypeClassifier.isComplexOracleSystemType(owner, typeName)) {
                return "jsonb";
            }
            return owner + "." + typeName;
        }

        String postgresType = TypeConverter.toPostgre(method.getReturnDataType());
        if (postgresType == null) {
            log.warn("Unknown return data type '{}' for type method '{}', using text",
                    method.getReturnDataType(), method.getDisplayName());
            return "text";
        }
        return postgresType;
    }
}
