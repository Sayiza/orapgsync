#!/usr/bin/env python3
"""
Code generator for semantic syntax tree from ANTLR grammar.

Usage: python3 codegen/generate.py

This script:
1. Parses PlSqlParser.g4 to extract grammar rules
2. Generates semantic node classes for each rule
3. Generates SemanticTreeBuilder.java with all visit methods
4. SKIPS existing files (delete manually to regenerate)
"""

import re
import os
from pathlib import Path
from typing import List, Dict, Set, Optional, Tuple
from dataclasses import dataclass, field


# ============================================================================
# Configuration - hardcoded paths
# ============================================================================

PROJECT_ROOT = Path(__file__).parent.parent
GRAMMAR_FILE = PROJECT_ROOT / "src/main/antlr4/me/christianrobert/orapgsync/antlr/PlSqlParser.g4"
SEMANTIC_PACKAGE_DIR = PROJECT_ROOT / "src/main/java/me/christianrobert/orapgsync/transformation/semantic"
BUILDER_PACKAGE_DIR = PROJECT_ROOT / "src/main/java/me/christianrobert/orapgsync/transformation/builder"

BASE_PACKAGE = "me.christianrobert.orapgsync.transformation"
SEMANTIC_PACKAGE = f"{BASE_PACKAGE}.semantic"


# ============================================================================
# Data Models
# ============================================================================

@dataclass
class RuleElement:
    """Represents an element within a grammar rule (child rule or token)."""
    name: str           # Element name (e.g., "select_list", "SELECT")
    is_token: bool      # True if uppercase token, False if rule reference
    cardinality: str    # "", "?", "*", "+"
    is_rule_ref: bool   # True if this is a child rule reference

    def is_optional(self) -> bool:
        return self.cardinality in ("?", "*")

    def is_list(self) -> bool:
        return self.cardinality in ("*", "+")


@dataclass
class Alternative:
    """Represents one alternative in a grammar rule."""
    label: Optional[str]  # Label from #label syntax, or None
    elements: List[RuleElement]


@dataclass
class GrammarRule:
    """Represents a complete grammar rule."""
    name: str                    # Rule name (e.g., "select_statement")
    alternatives: List[Alternative]

    def java_class_name(self) -> str:
        """Convert snake_case to PascalCase."""
        return ''.join(word.capitalize() for word in self.name.split('_'))

    def has_multiple_alternatives(self) -> bool:
        return len(self.alternatives) > 1


# ============================================================================
# Grammar Parser
# ============================================================================

class GrammarParser:
    """Parses ANTLR grammar file to extract rules."""

    def __init__(self, grammar_file: Path):
        self.grammar_file = grammar_file
        self.rules: Dict[str, GrammarRule] = {}

    def parse(self) -> Dict[str, GrammarRule]:
        """Parse grammar file and return dict of rules."""
        content = self.grammar_file.read_text()

        # Extract all rules using regex
        # Pattern: rule_name : alternatives ;
        rule_pattern = re.compile(
            r'^([a-z_][a-z0-9_]*)\s*:\s*(.+?)\s*;',
            re.MULTILINE | re.DOTALL
        )

        for match in rule_pattern.finditer(content):
            rule_name = match.group(1)
            rule_body = match.group(2)

            # Skip utility rules we don't want to generate
            if self._should_skip_rule(rule_name):
                continue

            rule = self._parse_rule(rule_name, rule_body)
            self.rules[rule_name] = rule

        return self.rules

    def _should_skip_rule(self, rule_name: str) -> bool:
        """Skip rules that shouldn't become semantic nodes."""
        # Skip top-level entry rules
        skip_rules = {
            'sql_script',
            'unit_statement',
            'sql_plus_command',
            'sql_plus_command_no_semicolon'
        }
        return rule_name in skip_rules

    def _parse_rule(self, rule_name: str, rule_body: str) -> GrammarRule:
        """Parse a single rule body into alternatives."""
        # Split by | to get alternatives (basic approach, may need refinement)
        # This is simplified and may not handle nested | correctly
        alternatives = []

        # Handle labeled alternatives (#label)
        alt_parts = self._split_alternatives(rule_body)

        for alt_text in alt_parts:
            label = None

            # Check for label (#label syntax)
            label_match = re.search(r'#\s*([a-z_][a-z0-9_]*)', alt_text)
            if label_match:
                label = label_match.group(1)
                # Remove label from text
                alt_text = re.sub(r'#\s*[a-z_][a-z0-9_]*', '', alt_text)

            elements = self._parse_alternative(alt_text)
            alternatives.append(Alternative(label=label, elements=elements))

        return GrammarRule(name=rule_name, alternatives=alternatives)

    def _split_alternatives(self, rule_body: str) -> List[str]:
        """Split rule body by | respecting parentheses."""
        # Simple implementation: split by | at depth 0
        parts = []
        current = []
        depth = 0

        for char in rule_body:
            if char == '(' or char == '[':
                depth += 1
                current.append(char)
            elif char == ')' or char == ']':
                depth -= 1
                current.append(char)
            elif char == '|' and depth == 0:
                parts.append(''.join(current).strip())
                current = []
            else:
                current.append(char)

        if current:
            parts.append(''.join(current).strip())

        return parts if parts else [rule_body]

    def _parse_alternative(self, alt_text: str) -> List[RuleElement]:
        """Parse elements within an alternative."""
        elements = []

        # Pattern to match rule elements with optional cardinality
        # Matches: rule_name, RULE_NAME, 'keyword', followed by ?, *, or +
        element_pattern = re.compile(
            r"([a-z_][a-z0-9_]*|[A-Z_][A-Z0-9_]*|'[^']+'|\"[^\"]+\")"
            r"([?*+])?"
        )

        for match in element_pattern.finditer(alt_text):
            element_name = match.group(1)
            cardinality = match.group(2) or ""

            # Skip string literals and keywords
            if element_name.startswith("'") or element_name.startswith('"'):
                continue

            # Determine if token (uppercase) or rule (lowercase)
            is_token = element_name[0].isupper()
            is_rule_ref = not is_token

            elements.append(RuleElement(
                name=element_name,
                is_token=is_token,
                cardinality=cardinality,
                is_rule_ref=is_rule_ref
            ))

        return elements


# ============================================================================
# Code Generators
# ============================================================================

class SemanticNodeGenerator:
    """Generates semantic node Java classes."""

    def __init__(self, output_dir: Path, base_package: str):
        self.output_dir = output_dir
        self.base_package = base_package

    def generate(self, rule: GrammarRule, skip_existing: bool = True):
        """Generate a semantic node class for a grammar rule."""
        class_name = rule.java_class_name()

        # Determine subdirectory based on rule type
        subdir = self._determine_subdir(rule.name)
        output_path = self.output_dir / subdir / f"{class_name}.java"

        # Skip if file exists
        if skip_existing and output_path.exists():
            print(f"  SKIP (exists): {output_path.relative_to(PROJECT_ROOT)}")
            return

        # Generate class content
        content = self._generate_class(rule, subdir)

        # Create directory and write file
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(content)
        print(f"  GENERATED: {output_path.relative_to(PROJECT_ROOT)}")

    def _determine_subdir(self, rule_name: str) -> str:
        """Determine subdirectory for semantic node based on rule name."""
        # Categorize rules into subdirectories
        if 'statement' in rule_name:
            return 'statement'
        elif 'expression' in rule_name or rule_name.endswith('_expression'):
            return 'expression'
        elif 'clause' in rule_name:
            return 'clause'
        elif any(keyword in rule_name for keyword in ['query', 'subquery', 'select', 'selected']):
            return 'query'
        elif any(keyword in rule_name for keyword in ['table', 'column', 'index']):
            return 'element'
        else:
            return 'misc'

    def _generate_class(self, rule: GrammarRule, subdir: str) -> str:
        """Generate complete Java class content."""
        class_name = rule.java_class_name()
        package_name = f"{self.base_package}.{subdir}"

        # If rule has labeled alternatives, it's more complex
        if any(alt.label for alt in rule.alternatives):
            return self._generate_interface_with_implementations(rule, package_name)

        # Single alternative or unlabeled alternatives
        alt = rule.alternatives[0] if rule.alternatives else Alternative(None, [])

        return f"""package {package_name};

import {self.base_package}.SemanticNode;
import {self.base_package.rsplit('.', 1)[0]}.context.TransformationContext;
import {self.base_package.rsplit('.', 1)[0]}.context.TransformationException;

import java.util.List;

/**
 * Semantic node for grammar rule: {rule.name}
 *
 * Generated by codegen/generate.py - DO NOT EDIT MANUALLY
 * Delete this file and regenerate if grammar changes.
 */
public class {class_name} implements SemanticNode {{

{self._generate_fields(alt)}

{self._generate_constructor(class_name, alt)}

{self._generate_getters(alt)}

    @Override
    public String toPostgres(TransformationContext ctx) {{
        // TODO: Implement transformation logic for {rule.name}
        throw new TransformationException("{rule.name} transformation not yet implemented");
    }}
}}
"""

    def _generate_interface_with_implementations(self, rule: GrammarRule, package_name: str) -> str:
        """Generate interface for rules with labeled alternatives (future enhancement)."""
        # For now, generate a simple class for the first alternative
        # TODO: Implement proper interface generation for labeled alternatives
        return self._generate_class(rule, package_name.split('.')[-1])

    def _generate_fields(self, alt: Alternative) -> str:
        """Generate field declarations."""
        fields = []
        seen_names = set()

        for elem in alt.elements:
            if not elem.is_rule_ref:
                continue

            field_name = self._to_camel_case(elem.name)

            # Avoid duplicate field names
            if field_name in seen_names:
                continue
            seen_names.add(field_name)

            field_type = self._get_field_type(elem)
            fields.append(f"    private final {field_type} {field_name};")

        return '\n'.join(fields) if fields else "    // No child nodes"

    def _generate_constructor(self, class_name: str, alt: Alternative) -> str:
        """Generate constructor."""
        params = []
        assignments = []
        seen_names = set()

        for elem in alt.elements:
            if not elem.is_rule_ref:
                continue

            field_name = self._to_camel_case(elem.name)

            # Avoid duplicate parameters
            if field_name in seen_names:
                continue
            seen_names.add(field_name)

            field_type = self._get_field_type(elem)
            params.append(f"{field_type} {field_name}")
            assignments.append(f"        this.{field_name} = {field_name};")

        if not params:
            return f"""    public {class_name}() {{
        // No child nodes
    }}"""

        return f"""    public {class_name}({', '.join(params)}) {{
{chr(10).join(assignments)}
    }}"""

    def _generate_getters(self, alt: Alternative) -> str:
        """Generate getter methods."""
        getters = []
        seen_names = set()

        for elem in alt.elements:
            if not elem.is_rule_ref:
                continue

            field_name = self._to_camel_case(elem.name)

            # Avoid duplicate getters
            if field_name in seen_names:
                continue
            seen_names.add(field_name)

            field_type = self._get_field_type(elem)
            getter_name = f"get{field_name[0].upper()}{field_name[1:]}"

            getters.append(f"""    public {field_type} {getter_name}() {{
        return {field_name};
    }}""")

        return '\n\n'.join(getters) if getters else ""

    def _get_field_type(self, elem: RuleElement) -> str:
        """Determine Java type for a field."""
        base_type = self._rule_to_class_name(elem.name)

        if elem.is_list():
            return f"List<{base_type}>"
        elif elem.is_optional():
            return base_type  # Will be null if not present
        else:
            return base_type

    def _rule_to_class_name(self, rule_name: str) -> str:
        """Convert rule name to Java class name."""
        return ''.join(word.capitalize() for word in rule_name.split('_'))

    def _to_camel_case(self, snake_str: str) -> str:
        """Convert snake_case to camelCase."""
        components = snake_str.split('_')
        return components[0] + ''.join(x.capitalize() for x in components[1:])


class TreeBuilderGenerator:
    """Generates SemanticTreeBuilder.java with all visit methods."""

    def __init__(self, output_dir: Path, base_package: str):
        self.output_dir = output_dir
        self.base_package = base_package

    def generate(self, rules: Dict[str, GrammarRule], skip_existing: bool = True):
        """Generate complete SemanticTreeBuilder.java."""
        output_path = self.output_dir / "SemanticTreeBuilder.java"

        # Skip if file exists
        if skip_existing and output_path.exists():
            print(f"  SKIP (exists): {output_path.relative_to(PROJECT_ROOT)}")
            return

        content = self._generate_builder_class(rules)

        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(content)
        print(f"  GENERATED: {output_path.relative_to(PROJECT_ROOT)}")

    def _generate_builder_class(self, rules: Dict[str, GrammarRule]) -> str:
        """Generate complete tree builder class."""

        # Generate all visit methods
        visit_methods = []
        for rule_name, rule in sorted(rules.items()):
            method = self._generate_visit_method(rule)
            visit_methods.append(method)

        return f"""package {self.base_package}.builder;

import {self.base_package.rsplit('.', 1)[0]}.antlr.PlSqlParser;
import {self.base_package.rsplit('.', 1)[0]}.antlr.PlSqlParserBaseVisitor;
import {self.base_package}.context.TransformationException;
import {self.base_package}.semantic.SemanticNode;
import {self.base_package}.semantic.statement.*;
import {self.base_package}.semantic.expression.*;
import {self.base_package}.semantic.clause.*;
import {self.base_package}.semantic.query.*;
import {self.base_package}.semantic.element.*;
import {self.base_package}.semantic.misc.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Visitor that converts ANTLR parse tree to semantic syntax tree.
 *
 * Generated by codegen/generate.py - DO NOT EDIT MANUALLY
 * Delete this file and regenerate if grammar changes.
 *
 * Architecture: Uses visitor pattern to delegate to child nodes.
 * Each visit method creates a semantic node and calls visit() on children.
 */
public class SemanticTreeBuilder extends PlSqlParserBaseVisitor<SemanticNode> {{

{chr(10).join(visit_methods)}

}}
"""

    def _generate_visit_method(self, rule: GrammarRule) -> str:
        """Generate a single visit method for a rule."""
        method_name = f"visit{rule.java_class_name()}"
        ctx_type = f"PlSqlParser.{rule.java_class_name()}Context"
        class_name = rule.java_class_name()

        # Get first alternative (simplified approach)
        alt = rule.alternatives[0] if rule.alternatives else Alternative(None, [])

        # Generate visitor body
        body = self._generate_visit_body(rule, alt)

        return f"""    @Override
    public SemanticNode {method_name}({ctx_type} ctx) {{
{body}
    }}
"""

    def _generate_visit_body(self, rule: GrammarRule, alt: Alternative) -> str:
        """Generate the body of a visit method."""
        lines = []
        constructor_args = []
        seen_names = set()

        for elem in alt.elements:
            if not elem.is_rule_ref:
                continue

            field_name = self._to_camel_case(elem.name)

            # Avoid duplicates
            if field_name in seen_names:
                continue
            seen_names.add(field_name)

            class_name = self._rule_to_class_name(elem.name)
            ctx_method = f"ctx.{elem.name}()"

            if elem.is_list():
                # Handle list: visit each element
                lines.append(f"        List<{class_name}> {field_name}List = new ArrayList<>();")
                lines.append(f"        if ({ctx_method} != null) {{")
                lines.append(f"            for (PlSqlParser.{class_name}Context item : {ctx_method}) {{")
                lines.append(f"                {field_name}List.add(({class_name}) visit(item));")
                lines.append(f"            }}")
                lines.append(f"        }}")
                constructor_args.append(f"{field_name}List")
            elif elem.is_optional():
                # Handle optional: may be null
                lines.append(f"        {class_name} {field_name} = null;")
                lines.append(f"        if ({ctx_method} != null) {{")
                lines.append(f"            {field_name} = ({class_name}) visit({ctx_method});")
                lines.append(f"        }}")
                constructor_args.append(field_name)
            else:
                # Handle required: throw if missing
                lines.append(f"        {class_name} {field_name};")
                lines.append(f"        if ({ctx_method} == null) {{")
                lines.append(f"            throw new TransformationException(\"{rule.name} missing {elem.name}\");")
                lines.append(f"        }}")
                lines.append(f"        {field_name} = ({class_name}) visit({ctx_method});")
                constructor_args.append(field_name)

        # Generate return statement
        if constructor_args:
            lines.append(f"        return new {rule.java_class_name()}({', '.join(constructor_args)});")
        else:
            lines.append(f"        return new {rule.java_class_name()}();")

        return '\n'.join(lines)

    def _rule_to_class_name(self, rule_name: str) -> str:
        """Convert rule name to Java class name."""
        return ''.join(word.capitalize() for word in rule_name.split('_'))

    def _to_camel_case(self, snake_str: str) -> str:
        """Convert snake_case to camelCase."""
        components = snake_str.split('_')
        return components[0] + ''.join(x.capitalize() for x in components[1:])


# ============================================================================
# Main
# ============================================================================

def main():
    print("=" * 80)
    print("Code Generator for Semantic Syntax Tree")
    print("=" * 80)
    print()

    # Parse grammar
    print(f"Parsing grammar: {GRAMMAR_FILE.relative_to(PROJECT_ROOT)}")
    parser = GrammarParser(GRAMMAR_FILE)
    rules = parser.parse()
    print(f"  Found {len(rules)} rules")
    print()

    # Generate semantic nodes
    print("Generating semantic node classes...")
    node_gen = SemanticNodeGenerator(SEMANTIC_PACKAGE_DIR, SEMANTIC_PACKAGE)
    for rule_name, rule in sorted(rules.items()):
        node_gen.generate(rule, skip_existing=True)
    print()

    # Generate tree builder
    print("Generating SemanticTreeBuilder...")
    builder_gen = TreeBuilderGenerator(BUILDER_PACKAGE_DIR, BASE_PACKAGE)
    builder_gen.generate(rules, skip_existing=True)
    print()

    print("=" * 80)
    print("Generation complete!")
    print("=" * 80)
    print()
    print("Next steps:")
    print("1. Review generated files")
    print("2. Delete files you want to regenerate")
    print("3. Run 'mvn clean compile' to check for errors")
    print("4. Implement toPostgres() methods as needed")


if __name__ == "__main__":
    main()
