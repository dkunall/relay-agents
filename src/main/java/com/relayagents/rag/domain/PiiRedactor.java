package com.relayagents.rag.domain;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrubs the obvious personal data from documents before they are stored or shown to a model: email
 * addresses, phone numbers and card numbers (only digit runs that pass the Luhn check, so order numbers
 * survive). Pattern-based, so it misses names and addresses; see the README.
 */
public final class PiiRedactor {

    private static final Pattern CARD = Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w-])(?:\\+\\d{1,3}[ -]?)?(?:\\d[ -]?){9,12}\\d(?![\\w-])");

    private PiiRedactor() {
    }

    public record Result(String text, int redactions) {
    }

    public static Result redact(String text) {
        int[] count = {0};
        String out = replace(CARD, text, m -> luhn(m.group()) ? "[card]" : null, count);
        out = replace(EMAIL, out, m -> "[email]", count);
        out = replace(PHONE, out, m -> digits(m.group()).length() >= 10 ? "[phone]" : null, count);
        return new Result(out, count[0]);
    }

    private static String replace(Pattern pattern, String text, Function<Matcher, String> mapper,
                                  int[] count) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = mapper.apply(matcher);
            if (replacement == null) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
            } else {
                count[0]++;
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static boolean luhn(String candidate) {
        String digits = digits(candidate);
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private static String digits(String text) {
        return text.replaceAll("\\D", "");
    }
}
