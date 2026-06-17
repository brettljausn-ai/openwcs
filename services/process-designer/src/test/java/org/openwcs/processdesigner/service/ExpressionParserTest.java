package org.openwcs.processdesigner.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the value-returning {@link ExpressionParser} (the grammar the compute step's
 * set[].expr uses, mirrored client-side). It accepts boolean conditions, arithmetic, comparisons,
 * and bare identifiers/literals; it rejects malformed input, function calls, and member access.
 */
class ExpressionParserTest {

    @Test
    void acceptsBooleanAndArithmeticAndBareExpressions() {
        assertThatCode(() -> ExpressionParser.validate("qty == expected or qty == prevCount"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ExpressionParser.validate("qty")).doesNotThrowAnyException();
        assertThatCode(() -> ExpressionParser.validate("expected - qty")).doesNotThrowAnyException();
        assertThatCode(() -> ExpressionParser.validate("(a + b) * 2 >= c")).doesNotThrowAnyException();
        assertThatCode(() -> ExpressionParser.validate("not done")).doesNotThrowAnyException();
        // A few more shapes the frontend must also accept.
        assertThatCode(() -> ExpressionParser.validate("-qty")).doesNotThrowAnyException();
        assertThatCode(() -> ExpressionParser.validate("'ok'")).doesNotThrowAnyException();
        assertThatCode(() -> ExpressionParser.validate("3.5")).doesNotThrowAnyException();
        assertThatCode(() -> ExpressionParser.validate("true")).doesNotThrowAnyException();
        assertThatCode(() -> ExpressionParser.validate("a <> b")).doesNotThrowAnyException();
    }

    @Test
    void rejectsMalformedExpressions() {
        assertThatThrownBy(() -> ExpressionParser.validate("qty =="))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpressionParser.validate("foo("))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpressionParser.validate("a.b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpressionParser.validate(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpressionParser.validate("(a + b"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
