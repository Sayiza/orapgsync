package me.christianrobert.orapgsync.transformation.semantic.query;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents the SELECT column list.
 *
 * <p>Grammar rule: selected_list
 * <pre>
 * selected_list:
 *     ASTERISK
 *     | select_list_elements (COMMA select_list_elements)*
 * </pre>
 *
 * <p>Current implementation status:
 * - ✅ select_list_elements (column list)
 * - ⏳ ASTERISK (SELECT * - not yet implemented)
 */
public class SelectedList implements SemanticNode {

    private final List<SelectListElement> elements;

    public SelectedList(List<SelectListElement> elements) {
        if (elements == null || elements.isEmpty()) {
            throw new IllegalArgumentException("SelectedList must have at least one element");
        }
        this.elements = new ArrayList<>(elements);
    }

    @Override
    public String toPostgres(TransformationContext context) {
        return elements.stream()
                .map(element -> element.toPostgres(context))
                .collect(Collectors.joining(", "));
    }

    public List<SelectListElement> getElements() {
        return Collections.unmodifiableList(elements);
    }

    @Override
    public String toString() {
        return "SelectedList{elements=" + elements.size() + "}";
    }
}
