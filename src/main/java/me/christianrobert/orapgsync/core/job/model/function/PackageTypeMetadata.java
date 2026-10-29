package me.christianrobert.orapgsync.core.job.model.function;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The types one Oracle package declares, in its <b>specification</b> and in its <b>body</b>.
 * This is a pure data model without dependencies on other services.
 *
 * <p>Extracted once, during function extraction (step 22), and read by every job that has to place
 * a {@code pkg.type} reference. Before this existed the two jobs that need it had neither the same
 * data nor the same answer: the function stub job had no package context at all, so it could not
 * place a package type in any signature, and the implementation job built a context only for the
 * package it happened to be transforming — so whether {@code kindlib.label} resolved to {@code text}
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
 *
 * <p><b>Body declarations are kept apart</b> because they are private: Oracle resolves them only for
 * a bare name inside the package itself (and for the package's own name as qualifier), never for
 * {@code pkg.type} written in another unit. They are what a package-private member's signature
 * names most often, and the stub job has no other way to see them (ROADMAP P1.5).
 */
public class PackageTypeMetadata {

    private String schema;
    private String packageName;

    /** Subtype name (lower-case) → the Oracle type it aliases, as written. */
    private Map<String, String> subtypes;

    /** Names (lower-case) of RECORD / TABLE OF / VARRAY / INDEX BY declarations. */
    private Set<String> structuredTypes;

    /** As {@link #subtypes}, declared in the package body. */
    private Map<String, String> bodySubtypes;

    /** As {@link #structuredTypes}, declared in the package body. */
    private Set<String> bodyStructuredTypes;

    public PackageTypeMetadata() {
        this.subtypes = new HashMap<>();
        this.structuredTypes = new HashSet<>();
        this.bodySubtypes = new HashMap<>();
        this.bodyStructuredTypes = new HashSet<>();
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

    public void addBodySubtype(String subtypeName, String baseOracleType) {
        this.bodySubtypes.put(subtypeName.toLowerCase(), baseOracleType);
    }

    public void addBodyStructuredType(String typeName) {
        this.bodyStructuredTypes.add(typeName.toLowerCase());
    }

    /**
     * The Oracle base type of a subtype, or null if not declared.
     *
     * @param includeBody whether body declarations are visible — true only for a lookup made from
     *        inside this package
     */
    public String findSubtype(String subtypeName, boolean includeBody) {
        String key = subtypeName.toLowerCase();
        String base = subtypes.get(key);
        return base != null || !includeBody ? base : bodySubtypes.get(key);
    }

    /** As {@link #findSubtype}, for RECORD / TABLE OF / VARRAY / INDEX BY declarations. */
    public boolean declaresStructuredType(String typeName, boolean includeBody) {
        String key = typeName.toLowerCase();
        return structuredTypes.contains(key) || (includeBody && bodyStructuredTypes.contains(key));
    }

    public boolean isEmpty() {
        return subtypes.isEmpty() && structuredTypes.isEmpty()
                && bodySubtypes.isEmpty() && bodyStructuredTypes.isEmpty();
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

    public Map<String, String> getBodySubtypes() {
        return bodySubtypes;
    }

    public void setBodySubtypes(Map<String, String> bodySubtypes) {
        this.bodySubtypes = bodySubtypes;
    }

    public Set<String> getBodyStructuredTypes() {
        return bodyStructuredTypes;
    }

    public void setBodyStructuredTypes(Set<String> bodyStructuredTypes) {
        this.bodyStructuredTypes = bodyStructuredTypes;
    }

    @Override
    public String toString() {
        return "PackageTypeMetadata{" + getQualifiedName()
                + ", subtypes=" + subtypes.size()
                + ", structuredTypes=" + structuredTypes.size()
                + ", bodySubtypes=" + bodySubtypes.size()
                + ", bodyStructuredTypes=" + bodyStructuredTypes.size() + "}";
    }
}
