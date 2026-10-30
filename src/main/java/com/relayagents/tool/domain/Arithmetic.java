package com.relayagents.tool.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Evaluates + - * / % and parentheses with Dijkstra's shunting-yard algorithm. Models are bad at
 * arithmetic, and a calculator tool is the classic fix; this one cannot run anything but arithmetic.
 */
public final class Arithmetic {

    private static final int MAX_LENGTH = 200;
    private static final MathContext PRECISION = MathContext.DECIMAL64;

    private Arithmetic() {
    }

    public static BigDecimal evaluate(String expression) {
        if (expression == null || expression.isBlank() || expression.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Expression must be 1-" + MAX_LENGTH + " characters");
        }
        Deque<BigDecimal> values = new ArrayDeque<>();
        Deque<Character> operators = new ArrayDeque<>();
        List<Object> tokens = tokenize(expression);
        for (Object token : tokens) {
            if (token instanceof BigDecimal number) {
                values.push(number);
            } else {
                char op = (Character) token;
                switch (op) {
                    case '(' -> operators.push(op);
                    case ')' -> {
                        while (!operators.isEmpty() && operators.peek() != '(') {
                            apply(values, operators.pop());
                        }
                        if (operators.isEmpty()) {
                            throw new IllegalArgumentException("Unbalanced ')'");
                        }
                        operators.pop();
                    }
                    default -> {
                        while (!operators.isEmpty() && operators.peek() != '('
                            && precedence(operators.peek()) >= precedence(op)) {
                            apply(values, operators.pop());
                        }
                        operators.push(op);
                    }
                }
            }
        }
        while (!operators.isEmpty()) {
            char op = operators.pop();
            if (op == '(') {
                throw new IllegalArgumentException("Unbalanced '('");
            }
            apply(values, op);
        }
        if (values.size() != 1) {
            throw new IllegalArgumentException("Malformed expression");
        }
        return values.pop().stripTrailingZeros();
    }

    private static List<Object> tokenize(String expression) {
        List<Object> tokens = new ArrayList<>();
        int i = 0;
        boolean expectOperand = true;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || c == '.' || (c == '-' && expectOperand)) {
                int start = i;
                i++;
                while (i < expression.length() && (Character.isDigit(expression.charAt(i)) || expression.charAt(i) == '.')) {
                    i++;
                }
                String literal = expression.substring(start, i);
                if (literal.equals("-")) {
                    // Unary minus before a parenthesis: "-(x)" becomes "-1 * (x)", which keeps precedence right.
                    tokens.add(BigDecimal.ONE.negate());
                    tokens.add('*');
                    continue;
                }
                try {
                    tokens.add(new BigDecimal(literal));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Bad number '" + literal + "'");
                }
                expectOperand = false;
            } else if ("+-*/%()".indexOf(c) >= 0) {
                tokens.add(c);
                expectOperand = c != ')';
                i++;
            } else {
                throw new IllegalArgumentException("Unexpected character '" + c + "'");
            }
        }
        return tokens;
    }

    private static int precedence(char op) {
        return op == '+' || op == '-' ? 1 : 2;
    }

    private static void apply(Deque<BigDecimal> values, char op) {
        if (values.size() < 2) {
            throw new IllegalArgumentException("Missing operand for '" + op + "'");
        }
        BigDecimal right = values.pop();
        BigDecimal left = values.pop();
        values.push(switch (op) {
            case '+' -> left.add(right, PRECISION);
            case '-' -> left.subtract(right, PRECISION);
            case '*' -> left.multiply(right, PRECISION);
            case '/' -> {
                if (right.signum() == 0) {
                    throw new IllegalArgumentException("Division by zero");
                }
                yield left.divide(right, PRECISION);
            }
            case '%' -> {
                if (right.signum() == 0) {
                    throw new IllegalArgumentException("Division by zero");
                }
                yield left.remainder(right, PRECISION);
            }
            default -> throw new IllegalArgumentException("Unknown operator '" + op + "'");
        });
    }
}
