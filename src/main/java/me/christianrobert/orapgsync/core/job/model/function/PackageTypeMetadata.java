package me.christianrobert.orapgsync.core.job.model.function;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The types one Oracle package declares in its <b>specification</b>.
 * This is a pure data model without dependencies on other services.
 *
 * <p>Extracted once, during function extraction (step 22), and read by every job that has to place
 * a {@code pkg.type} reference. Before this existed the two jobs that need it had neither the same
 * data nor the same answer: the function stub job had no package context at all, so it could not
 * place a package type in any signature, and the implementation job built a context only for the
 * package it happened to be transforming — so whether {@code type.string} resolved to {@code text}
 * or fell back to {@code jsonb} depended on which packages had been processed first.
 *
 * <p><b>Only what resolution needs.</b> A structured type is carried as a name, not as a field
 * list, because every one of them becomes {@code jsonb} and a signature needs nothing further. The
 * consequence is deliberate and worth knowing: a variable declared as <i>another</i> package's
 * RECORD still gets {@code jsonb} without an initializer, exactly as before. Only the enclosing
 * package's own {@code PackageContext} carries field lists, and only a declaration site needs them.
 *
 * <p>Subtypes are separate because a subtype is an alias, not a structure — {@code SUBTYPE string
 * IS VARCHAR2(4000)} resolves to {@code text}, and answering {@code jsonb} for it was the largest
 * single package error on the 2026-08-30 run.
 */
public class PackageTypeMetadata {

    private String schema;
    private String packageName;

    /** Subtype name (lower-case) → the Oracle type it aliases, as written. */
    private Map<String, String> subtypes;

    /** Names (lower-case) of RECORD / TABLE OF / VARRAY / INDEX BY declarations. */
    private Set<String> structuredTypes;

    public PackageTypeMetadata() {
        this.subtypes = new HashMap<>();
        this.structuredTypes = new HashSet<>();
    }

    public PackageTypeMetadata(String schema, String packageName) {
        this();
        this.schema = schema;
        this.packageName = packageName;
    }

    /** Key used by every lookup: {@code "schema.package"}, lower-case. */
    public String getQualifiedName() {
        return (schema + "." + packageName).toLowerCase();
    }

    public void addSubtype(String subtypeName, String baseOracleType) {
        this.subtypes.put(subtypeName.toLowerCase(), baseOracleType);
    }

    public void addStructuredType(String typeName) {
        this.structuredTypes.add(typeName.toLowerCase());
    }

    public boolean isEmpty() {
        return subtypes.isEmpty() && structuredTypes.isEmpty();
    }

    // Getters and setters

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public Map<String, String> getSubtypes() {
        return subtypes;
    }

    public void setSubtypes(Map<String, String> subtypes) {
        this.subtypes = subtypes;
    }

    public Set<String> getStructuredTypes() {
        return structuredTypes;
    }

    public void setStructuredTypes(Set<String> structuredTypes) {
        this.structuredTypes = structuredTypes;
    }

    @Override
    public String toString() {
        return "PackageTypeMetadata{" + getQualifiedName()
                + ", subtypes=" + subtypes.size()
                + ", structuredTypes=" + structuredTypes.size() + "}";
    }
}
