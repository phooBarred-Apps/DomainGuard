package heartguard.heartguard.main;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A duration travels with the rule; its activation time is stored in the rules database. */
final class TemporaryRuleText {
    static final int MAX_SECONDS = 7 * 24 * 60 * 60;
    private static final Pattern FORMAT = Pattern.compile("temporary seconds:(\\d+) ((?:allow|block|blockdomain) .+)");
    final int seconds;
    final String rule;

    private TemporaryRuleText(int seconds, String rule) {
        this.seconds = seconds;
        this.rule = rule;
    }

    static TemporaryRuleText parse(String text) {
        Matcher match = FORMAT.matcher(text == null ? "" : text);
        if (!match.matches()) throw new IllegalArgumentException("Invalid temporary rule");
        int seconds = Integer.parseInt(match.group(1));
        if (seconds < 1 || seconds > MAX_SECONDS) throw new IllegalArgumentException("Duration must be 1 second to 7 days");
        return new TemporaryRuleText(seconds, match.group(2));
    }

    static boolean isTemporary(String text) {
        return text != null && text.startsWith("temporary ");
    }

    static String base(String text) {
        return isTemporary(text) ? parse(text).rule : text;
    }

    static String text(String rule, int seconds) {
        String text = "temporary seconds:" + seconds + " " + rule;
        parse(text);
        return text;
    }

    long expiresAt(long activatedAt) {
        return activatedAt + seconds * 1000L;
    }
}
