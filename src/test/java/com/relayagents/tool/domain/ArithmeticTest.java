package com.relayagents.tool.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ArithmeticTest {

    @Test
    void respectsPrecedenceAndParentheses() {
        assertThat(Arithmetic.evaluate("2 + 3 * 4")).isEqualByComparingTo("14");
        assertThat(Arithmetic.evaluate("(2 + 3) * 4")).isEqualByComparingTo("20");
        assertThat(Arithmetic.evaluate("1250 * 0.2")).isEqualByComparingTo(new BigDecimal("250"));
        assertThat(Arithmetic.evaluate("17 % 5")).isEqualByComparingTo("2");
    }

    @Test
    void rejectsAnythingThatIsNotArithmetic() {
        assertThatThrownBy(() -> Arithmetic.evaluate("Runtime.exec('x')")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> Arithmetic.evaluate("1 / 0")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> Arithmetic.evaluate("(1 + 2")).isInstanceOf(RuntimeException.class);
    }
}
