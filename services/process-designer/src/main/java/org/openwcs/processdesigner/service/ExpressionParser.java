package org.openwcs.processdesigner.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates the value-returning expression grammar used by a {@code compute} step's
 * {@code set[].expr} (no-code variable assignment). This is a <em>parser/validator only</em> — the
 * client (handheld) evaluates each expression at runtime against the data object; the server parses
 * it at publish time so a malformed expression fails publish (422) rather than blowing up on a
 * device. There is no {@code eval}, no function calls, no member access, no host access.
 *
 * <p>It is a superset of {@link ConditionParser}: every boolean condition is also a valid
 * expression, plus arithmetic. An expression yields a value (number / string / boolean). A bare
 * identifier or literal (e.g. {@code qty}) is a valid expression.
 *
 * <p>Grammar (precedence low to high; {@code and}/{@code or} are lowest, then comparison, then
 * additive, then multiplicative, then unary):
 * <pre>
 *   expr     := or
 *   or       := and ( "or" and )*
 *   and      := not ( "and" not )*
 *   not      := "not" not | comparison
 *   comparison := additive ( ("==" | "!=" | "&lt;&gt;" | "&lt;" | "&lt;=" | "&gt;" | "&gt;=") additive )?
 *   additive := multiplicative ( ("+" | "-") multiplicative )*
 *   multiplicative := unary ( ("*" | "/") unary )*
 *   unary    := "-" unary | primary
 *   primary  := "(" expr ")" | identifier | number | string | boolean
 * </pre>
 * Primaries: an identifier is a data-object variable; number literals (e.g. {@code 12}, {@code 3.5});
 * string literals ({@code '...'} or {@code "..."}); boolean literals {@code true} / {@code false}.
 * Throws {@link IllegalArgumentException} on any syntax error.
 */
final class ExpressionParser {

    private final List<String> tokens;
    private int pos;

    private ExpressionParser(List<String> tokens) {
        this.tokens = tokens;
    }

    /** Parse an expression string; throws {@link IllegalArgumentException} if malformed. */
    static void validate(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("Expression is empty.");
        }
        ExpressionParser p = new ExpressionParser(tokenize(expr));
        p.parseOr();
        if (p.pos != p.tokens.size()) {
            throw new IllegalArgumentException("Unexpected token '" + p.peek() + "' in expression.");
        }
    }

    /** The data-object variables an expression references (identifiers that are not keywords or
     *  literals). Used to check every referenced variable is actually declared. */
    static java.util.Set<String> identifiers(String expr) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (expr == null || expr.isBlank()) {
            return out;
        }
        for (String t : tokenize(expr)) {
            // null is a literal in the condition grammar (e.g. prevCount != null), not a variable.
            if (isIdentifier(t) && !isKeyword(t) && !isLiteral(t) && !"null".equals(t)) {
                out.add(t);
            }
        }
        return out;
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
        parseAdditive();
        if (isComparator(peek())) {
            next();
            parseAdditive();
        }
    }

    private void parseAdditive() {
        parseMultiplicative();
        while ("+".equals(peek()) || "-".equals(peek())) {
            next();
            parseMultiplicative();
        }
    }

    private void parseMultiplicative() {
        parseUnary();
        while ("*".equals(peek()) || "/".equals(peek())) {
            next();
            parseUnary();
        }
    }

    private void parseUnary() {
        if ("-".equals(peek())) {
            next();
            parseUnary();
            return;
        }
        parsePrimary();
    }

    private void parsePrimary() {
        String t = peek();
        if (t == null) {
            throw new IllegalArgumentException("Expected a value but reached end of expression.");
        }
        if ("(".equals(t)) {
            next();
            parseOr();
            expect(")");
            return;
        }
        if (")".equals(t) || isKeyword(t) || isComparator(t) || isArithmetic(t)) {
            throw new IllegalArgumentException("Expected a value but found '" + t + "'.");
        }
        // A bare word must be a string literal, a number, a boolean, or a plain identifier.
        // Member access (a.b) and function calls are not supported.
        if (!isLiteral(t) && !isIdentifier(t)) {
            throw new IllegalArgumentException("Invalid value '" + t + "' in expression "
                    + "(no member access, function calls, or stray characters).");
        }
        next();
    }

    private static boolean isLiteral(String t) {
        if (t.length() >= 2
                && ((t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\'')
                    || (t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"'))) {
            return true; // string literal
        }
        if ("true".equals(t) || "false".equals(t)) {
            return true; // boolean literal
        }
        return isNumber(t); // number literal
    }

    private static boolean isNumber(String t) {
        boolean dot = false;
        boolean digit = false;
        for (int k = 0; k < t.length(); k++) {
            char ch = t.charAt(k);
            if (ch == '.') {
                if (dot) {
                    return false;
                }
                dot = true;
            } else if (Character.isDigit(ch)) {
                digit = true;
            } else {
                return false;
            }
        }
        return digit;
    }

    private static boolean isIdentifier(String t) {
        if (t.isEmpty()) {
            return false;
        }
        char first = t.charAt(0);
        if (first != '_' && !Character.isLetter(first)) {
            return false;
        }
        for (int k = 1; k < t.length(); k++) {
            char ch = t.charAt(k);
            if (ch != '_' && !Character.isLetterOrDigit(ch)) {
                return false; // rejects dots (member access) and other punctuation
            }
        }
        return true;
    }

    private void expect(String token) {
        if (!token.equals(peek())) {
            throw new IllegalArgumentException("Expected '" + token + "' in expression.");
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
        return "==".equals(t) || "!=".equals(t) || "<>".equals(t) || "<".equals(t) || "<=".equals(t)
                || ">".equals(t) || ">=".equals(t);
    }

    private static boolean isArithmetic(String t) {
        return "+".equals(t) || "-".equals(t) || "*".equals(t) || "/".equals(t);
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
            if (c == '(' || c == ')' || c == '+' || c == '*' || c == '/') {
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
                    throw new IllegalArgumentException("Unterminated string literal in expression.");
                }
                out.add(expr.substring(i, j + 1));
                i = j + 1;
                continue;
            }
            if (c == '=' || c == '!') {
                if (i + 1 < n && expr.charAt(i + 1) == '=') {
                    out.add(expr.substring(i, i + 2));
                    i += 2;
                    continue;
                }
                throw new IllegalArgumentException("Invalid operator '" + c + "' in expression.");
            }
            if (c == '<' || c == '>') {
                if (i + 1 < n && expr.charAt(i + 1) == '=') {
                    out.add(expr.substring(i, i + 2));
                    i += 2;
                } else if (c == '<' && i + 1 < n && expr.charAt(i + 1) == '>') {
                    out.add("<>");
                    i += 2;
                } else {
                    out.add(String.valueOf(c));
                    i++;
                }
                continue;
            }
            if (c == '-') {
                // Standalone '-' operator (unary or binary); the parser decides which.
                out.add("-");
                i++;
                continue;
            }
            // bare word: identifier, number, or boolean literal.
            int j = i;
            while (j < n) {
                char d = expr.charAt(j);
                if (Character.isWhitespace(d) || d == '(' || d == ')' || d == '=' || d == '!'
                        || d == '<' || d == '>' || d == '+' || d == '-' || d == '*' || d == '/'
                        || d == '\'' || d == '"') {
                    break;
                }
                j++;
            }
            out.add(expr.substring(i, j));
            i = j;
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Expression is empty.");
        }
        return out;
    }
}
