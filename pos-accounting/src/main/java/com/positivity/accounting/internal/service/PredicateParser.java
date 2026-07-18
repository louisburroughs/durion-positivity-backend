package com.positivity.accounting.internal.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Hand-written tokenizer + parser for the posting-rule condition predicate
 * whitelist grammar (story E2, issue #946).
 *
 * <p>Grammar (EBNF, authoritative copy in the accounting domain
 * {@code POSTING_RULES_SCHEMA.md} §2.1):
 *
 * <pre>
 * predicate := clause ( '&amp;&amp;' clause )*
 * clause    := lhs op literal
 * lhs       := 'eventType' | 'payload' '.' path
 * path      := identifier ( '.' identifier )*
 * identifier:= [A-Za-z_][A-Za-z0-9_]*
 * op        := '==' | '!=' | '&gt;' | '&gt;=' | '&lt;' | '&lt;='
 * literal   := '&lt;single-quoted string&gt;' | number
 * number    := ['+'|'-'] digits ['.' digits]
 * </pre>
 *
 * This is deliberately <em>not</em> a general expression engine: no
 * scripting, no reflection, no method calls, no parentheses, no OR, no
 * arithmetic. Ordering operators ({@code > >= < <=}) require a numeric
 * literal — rejected at parse time so the publish gate catches it.
 * Parsing is strict: trailing garbage, unbalanced quotes, unknown
 * identifiers, malformed numbers, and empty clauses all throw
 * {@link ParseException} with position information. The implementation is
 * a single linear scan plus an iterative (non-recursive) clause loop, so
 * it can neither hang nor overflow the stack on adversarial input.
 *
 * <p>Evaluation semantics ({@link Predicate#matches}): clauses are
 * AND-combined with short-circuit; a clause whose left-hand side cannot be
 * resolved to a comparable scalar is simply {@code false} — never an
 * error (safe default, matching the evaluator's long-standing
 * unrecognized-condition behavior).
 */
public final class PredicateParser {

    private PredicateParser() {}

    /**
     * Parses a condition predicate into its AST.
     *
     * <p>Callers are expected to handle the catch-all forms
     * ({@code null}/blank/{@code "*"}) before calling — this method parses
     * the strict grammar only.
     *
     * @param input the raw predicate string
     * @return the parsed, immutable predicate AST
     * @throws ParseException if the input does not conform to the grammar
     */
    @NonNull
    public static Predicate parse(@NonNull String input) {
        Cursor cursor = new Cursor(tokenize(input));

        List<Clause> clauses = new ArrayList<>();
        clauses.add(parseClause(cursor));
        while (cursor.peek().type() == TokenType.AND) {
            cursor.next();
            clauses.add(parseClause(cursor));
        }

        Token trailing = cursor.peek();
        if (trailing.type() != TokenType.EOF) {
            throw new ParseException("unexpected trailing input '" + trailing.text() + "'", trailing.position());
        }
        return new Predicate(clauses);
    }

    // ── AST ──────────────────────────────────────────────────────────────

    /** Comparison operator of one clause. */
    public enum Op {
        EQ("=="),
        NE("!="),
        GT(">"),
        GE(">="),
        LT("<"),
        LE("<=");

        private final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        /** Ordering operators require a numeric literal (enforced at parse). */
        public boolean isOrdering() {
            return this == GT || this == GE || this == LT || this == LE;
        }
    }

    /** Left-hand side of a clause. */
    public sealed interface Lhs permits EventTypeRef, PayloadPath {}

    /** The event's {@code eventType} string. */
    public record EventTypeRef() implements Lhs {}

    /** A dot-separated path into the event payload map. */
    public record PayloadPath(List<String> segments) implements Lhs {
        public PayloadPath {
            segments = List.copyOf(segments);
        }
    }

    /** Literal on the right-hand side of a clause. */
    public sealed interface Literal permits StringLiteral, NumberLiteral {}

    /** Single-quoted string literal (no escape sequences). */
    public record StringLiteral(String value) implements Literal {}

    /** Numeric literal, held exactly as a {@link BigDecimal}. */
    public record NumberLiteral(BigDecimal value) implements Literal {}

    /** One {@code lhs op literal} comparison. */
    public record Clause(Lhs lhs, Op op, Literal literal) {}

    /**
     * A parsed predicate: one or more clauses AND-combined.
     */
    public record Predicate(List<Clause> clauses) {
        public Predicate {
            clauses = List.copyOf(clauses);
        }

        /**
         * Evaluates this predicate against an event.
         *
         * <p>Clauses short-circuit: the first {@code false} clause stops
         * evaluation. Unresolvable or non-comparable left-hand values make
         * their clause {@code false} — evaluation never throws for any
         * event shape.
         *
         * @param eventType the event's type (may be {@code null})
         * @param payload   the event payload map (may be {@code null})
         * @return {@code true} if every clause matches
         */
        public boolean matches(@Nullable String eventType, @Nullable Map<String, Object> payload) {
            for (Clause clause : clauses) {
                if (!evaluateClause(clause, eventType, payload)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Thrown when a predicate does not conform to the whitelist grammar.
     * Carries the zero-based character position of the offending input.
     */
    public static final class ParseException extends RuntimeException {

        private final int position;

        ParseException(String message, int position) {
            super(message + " (at position " + position + ")");
            this.position = position;
        }

        public int getPosition() {
            return position;
        }
    }

    // ── Evaluation ───────────────────────────────────────────────────────

    private static boolean evaluateClause(
            Clause clause, @Nullable String eventType, @Nullable Map<String, Object> payload) {
        Object value = resolve(clause.lhs(), eventType, payload);
        if (value == null) {
            return false; // missing path / null value → safe non-match
        }
        return switch (clause.literal()) {
            case StringLiteral s -> compareString(value, clause.op(), s.value());
            case NumberLiteral n -> compareNumber(value, clause.op(), n.value());
        };
    }

    @Nullable
    private static Object resolve(Lhs lhs, @Nullable String eventType, @Nullable Map<String, Object> payload) {
        if (lhs instanceof EventTypeRef) {
            return eventType;
        }
        Object current = payload;
        for (String segment : ((PayloadPath) lhs).segments()) {
            if (!(current instanceof Map<?, ?> map)) {
                return null; // non-map mid-path → unresolvable
            }
            current = map.get(segment);
        }
        return current;
    }

    /**
     * String comparison: the resolved value must itself be a string;
     * anything else (numbers, booleans, maps, lists) is a non-match, even
     * for {@code !=} — the safe default is always "clause false".
     */
    private static boolean compareString(Object value, Op op, String literal) {
        if (!(value instanceof String actual)) {
            return false;
        }
        boolean equal = actual.equals(literal);
        return switch (op) {
            case EQ -> equal;
            case NE -> !equal;
            // Ordering ops never reach here (parser requires numeric
            // literals for them); defensively a non-match.
            case GT, GE, LT, LE -> false;
        };
    }

    /**
     * Numeric comparison via {@link BigDecimal#compareTo} (so {@code 100}
     * equals {@code 100.00}). Payload {@link Number}s and numeric strings
     * are coerced; a non-coercible value is a non-match.
     */
    private static boolean compareNumber(Object value, Op op, BigDecimal literal) {
        BigDecimal actual = coerceNumeric(value);
        if (actual == null) {
            return false;
        }
        int cmp = actual.compareTo(literal);
        return switch (op) {
            case EQ -> cmp == 0;
            case NE -> cmp != 0;
            case GT -> cmp > 0;
            case GE -> cmp >= 0;
            case LT -> cmp < 0;
            case LE -> cmp <= 0;
        };
    }

    @Nullable
    private static BigDecimal coerceNumeric(Object value) {
        try {
            if (value instanceof BigDecimal bd) {
                return bd;
            }
            if (value instanceof Number number) {
                // toString handles all JDK number types; NaN/Infinity
                // doubles fail the BigDecimal parse and become non-matches
                return new BigDecimal(number.toString());
            }
            if (value instanceof String str) {
                return new BigDecimal(str.trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    // ── Parser ───────────────────────────────────────────────────────────

    private enum TokenType {
        IDENT,
        DOT,
        STRING,
        NUMBER,
        OP,
        AND,
        EOF
    }

    private record Token(TokenType type, String text, int position) {}

    /** Mutable token cursor for the iterative clause loop. */
    private static final class Cursor {
        private final List<Token> tokens;
        private int index;

        Cursor(List<Token> tokens) {
            this.tokens = tokens;
        }

        Token peek() {
            return tokens.get(index);
        }

        Token next() {
            return tokens.get(index++);
        }
    }

    /** Parses one {@code lhs op literal} clause at the cursor position. */
    private static Clause parseClause(Cursor cursor) {
        Token first = cursor.next();
        if (first.type() != TokenType.IDENT) {
            throw new ParseException(
                    "expected 'eventType' or 'payload.<path>' but found " + describe(first), first.position());
        }

        Lhs lhs;
        if ("eventType".equals(first.text())) {
            lhs = new EventTypeRef();
        } else if ("payload".equals(first.text())) {
            List<String> segments = new ArrayList<>();
            while (cursor.peek().type() == TokenType.DOT) {
                cursor.next();
                Token segment = cursor.next();
                if (segment.type() != TokenType.IDENT) {
                    throw new ParseException(
                            "expected path segment after '.' but found " + describe(segment), segment.position());
                }
                segments.add(segment.text());
            }
            if (segments.isEmpty()) {
                throw new ParseException(
                        "'payload' requires a dot-separated path, e.g. payload.amount", first.position());
            }
            lhs = new PayloadPath(segments);
        } else {
            throw new ParseException(
                    "unknown identifier '" + first.text()
                            + "' — the left-hand side must be 'eventType' or 'payload.<path>'",
                    first.position());
        }

        Token opToken = cursor.next();
        if (opToken.type() != TokenType.OP) {
            throw new ParseException(
                    "expected comparison operator (== != > >= < <=) but found " + describe(opToken),
                    opToken.position());
        }
        Op op = opFromSymbol(opToken.text(), opToken.position());

        Token literalToken = cursor.next();
        Literal literal =
                switch (literalToken.type()) {
                    case STRING -> new StringLiteral(literalToken.text());
                    case NUMBER -> new NumberLiteral(parseNumber(literalToken));
                    default ->
                        throw new ParseException(
                                "expected single-quoted string or number literal but found " + describe(literalToken),
                                literalToken.position());
                };

        if (op.isOrdering() && literal instanceof StringLiteral) {
            throw new ParseException(
                    "ordering operator '" + op.symbol() + "' requires a numeric literal, not a quoted string",
                    literalToken.position());
        }

        return new Clause(lhs, op, literal);
    }

    private static Op opFromSymbol(String symbol, int position) {
        for (Op op : Op.values()) {
            if (op.symbol().equals(symbol)) {
                return op;
            }
        }
        throw new ParseException("unsupported operator '" + symbol + "'", position);
    }

    private static BigDecimal parseNumber(Token token) {
        try {
            return new BigDecimal(token.text());
        } catch (NumberFormatException e) {
            throw new ParseException("malformed number literal '" + token.text() + "'", token.position());
        }
    }

    private static String describe(Token token) {
        return token.type() == TokenType.EOF ? "end of input" : "'" + token.text() + "'";
    }

    // ── Tokenizer ────────────────────────────────────────────────────────

    private static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int length = input.length();

        while (i < length) {
            char c = input.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
            } else if (isIdentifierStart(c)) {
                int start = i;
                while (i < length && isIdentifierPart(input.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(TokenType.IDENT, input.substring(start, i), start));
            } else if (c == '.') {
                tokens.add(new Token(TokenType.DOT, ".", i));
                i++;
            } else if (c == '\'') {
                int start = i;
                int end = input.indexOf('\'', i + 1);
                if (end < 0) {
                    throw new ParseException("unterminated string literal", start);
                }
                tokens.add(new Token(TokenType.STRING, input.substring(i + 1, end), start));
                i = end + 1;
            } else if (Character.isDigit(c)
                    || ((c == '+' || c == '-') && i + 1 < length && Character.isDigit(input.charAt(i + 1)))) {
                i = lexNumber(input, i, tokens);
            } else if (c == '=' || c == '!') {
                if (i + 1 < length && input.charAt(i + 1) == '=') {
                    tokens.add(new Token(TokenType.OP, input.substring(i, i + 2), i));
                    i += 2;
                } else {
                    throw new ParseException("unsupported operator '" + c + "' (did you mean '" + c + "='?)", i);
                }
            } else if (c == '>' || c == '<') {
                if (i + 1 < length && input.charAt(i + 1) == '=') {
                    tokens.add(new Token(TokenType.OP, input.substring(i, i + 2), i));
                    i += 2;
                } else {
                    tokens.add(new Token(TokenType.OP, String.valueOf(c), i));
                    i++;
                }
            } else if (c == '&') {
                if (i + 1 < length && input.charAt(i + 1) == '&') {
                    tokens.add(new Token(TokenType.AND, "&&", i));
                    i += 2;
                } else {
                    throw new ParseException("single '&' is not a valid operator (did you mean '&&'?)", i);
                }
            } else {
                throw new ParseException("unexpected character '" + c + "'", i);
            }
        }

        tokens.add(new Token(TokenType.EOF, "", length));
        return tokens;
    }

    /** Lexes {@code ['+'|'-'] digits ['.' digits]} starting at {@code start}. */
    private static int lexNumber(String input, int start, List<Token> tokens) {
        int i = start;
        if (input.charAt(i) == '+' || input.charAt(i) == '-') {
            i++;
        }
        while (i < input.length() && Character.isDigit(input.charAt(i))) {
            i++;
        }
        if (i < input.length() && input.charAt(i) == '.') {
            int fractionStart = i + 1;
            int j = fractionStart;
            while (j < input.length() && Character.isDigit(input.charAt(j))) {
                j++;
            }
            if (j == fractionStart) {
                throw new ParseException(
                        "malformed number literal '" + input.substring(start, i + 1)
                                + "' — digits required after the decimal point",
                        start);
            }
            i = j;
        }
        tokens.add(new Token(TokenType.NUMBER, input.substring(start, i), start));
        return i;
    }

    private static boolean isIdentifierStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || (c >= '0' && c <= '9');
    }
}
