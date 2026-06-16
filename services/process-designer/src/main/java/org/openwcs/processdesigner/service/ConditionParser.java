package org.openwcs.processdesigner.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates the restricted, safe condition grammar used by step {@code transitions[].when} and the
 * Phase 2 step {@code skipWhen} (spec §6). This is a <em>parser/validator only</em> — the client
 * evaluates conditions at runtime; the server parses them at publish time so a malformed condition
 * fails publish (422) rather than blowing up on a handheld.
 *
 * <p>Grammar (no scripting, no host access, no {@code eval}):
 * <pre>
 *   expr    := or
 *   or      := and ( "or" and )*
 *   and     := not ( "and" not )*
 *   not     := "not" not | comparison | "(" expr ")"
 *   comparison := operand ( ("==" | "!=" | "&lt;" | "&lt;=" | "&gt;" | "&gt;=") operand )?
 *   operand := identifier | number | string | boolean
 * </pre>
 * An identifier (data-object variable, dotted access allowed) standing alone is a truthy test.
 * Throws {@link IllegalArgumentException} on any syntax error.
 */
final class ConditionParser {

    private final List<String> tokens;
    private int pos;

    private ConditionParser(List<String> tokens) {
        this.tokens = tokens;
    }

    /** Parse a condition string; throws {@link IllegalArgumentException} if malformed. */
    static void validate(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("Condition is empty.");
        }
        ConditionParser p = new ConditionParser(tokenize(expr));
        p.parseOr();
        if (p.pos != p.tokens.size()) {
            throw new IllegalArgumentException("Unexpected token '" + p.peek() + "' in condition.");
        }
    }

    private void parseOr() {
        parseAnd();
        while ("or".equals(peek())) {
            next();
            parseAnd();
        }
    }

    private void parseAnd() {
        parseNot();
        while ("and".equals(peek())) {
            next();
            parseNot();
        }
    }

    private void parseNot() {
        if ("not".equals(peek())) {
            next();
            parseNot();
            return;
        }
        parseComparison();
    }

    private void parseComparison() {
        if ("(".equals(peek())) {
            next();
            parseOr();
            expect(")");
            return;
        }
        parseOperand();
        String op = peek();
        if (isComparator(op)) {
            next();
            parseOperand();
        }
    }

    private void parseOperand() {
        String t = peek();
        if (t == null) {
            throw new IllegalArgumentException("Expected an operand but reached end of condition.");
        }
        if ("(".equals(t) || ")".equals(t) || isKeyword(t) || isComparator(t)) {
            throw new IllegalArgumentException("Expected an operand but found '" + t + "'.");
        }
        next();
    }

    private void expect(String token) {
        if (!token.equals(peek())) {
            throw new IllegalArgumentException("Expected '" + token + "' in condition.");
        }
        next();
    }

    private String peek() {
        return pos < tokens.size() ? tokens.get(pos) : null;
    }

    private void next() {
        pos++;
    }

    private static boolean isComparator(String t) {
        return "==".equals(t) || "!=".equals(t) || "<".equals(t) || "<=".equals(t)
                || ">".equals(t) || ">=".equals(t);
    }

    private static boolean isKeyword(String t) {
        return "and".equals(t) || "or".equals(t) || "not".equals(t);
    }

    /** Split into tokens: operators, parens, quoted strings, and bare words. */
    private static List<String> tokenize(String expr) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = expr.length();
        while (i < n) {
            char c = expr.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '(' || c == ')') {
                out.add(String.valueOf(c));
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < n && expr.charAt(j) != c) {
                    j++;
                }
                if (j >= n) {
                    throw new IllegalArgumentException("Unterminated string literal in condition.");
                }
                out.add(expr.substring(i, j + 1));
                i = j + 1;
                continue;
            }
            if (c == '=' || c == '!' || c == '<' || c == '>') {
                if (i + 1 < n && expr.charAt(i + 1) == '=') {
                    out.add(expr.substring(i, i + 2));
                    i += 2;
                } else if (c == '<' || c == '>') {
                    out.add(String.valueOf(c));
                    i++;
                } else {
                    // a lone '=' or '!' is not a valid operator
                    throw new IllegalArgumentException("Invalid operator '" + c + "' in condition.");
                }
                continue;
            }
            // bare word: identifier (with dots), number, or boolean literal
            int j = i;
            while (j < n) {
                char d = expr.charAt(j);
                if (Character.isWhitespace(d) || d == '(' || d == ')' || d == '=' || d == '!'
                        || d == '<' || d == '>') {
                    break;
                }
                j++;
            }
            out.add(expr.substring(i, j));
            i = j;
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Condition is empty.");
        }
        return out;
    }
}
