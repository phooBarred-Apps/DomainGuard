package heartguard.heartguard.main;

import android.content.Context;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A temporary exception. Existing allow/block rules stay intact underneath it. */
final class AppPauseRule extends MyRule {
    final String packageName;
    final int seconds;
    long expiresAt;

    AppPauseRule(String text) throws RuleParseNoMatchException {
        super(text, MAJOR_CATEGORY_ALLOW);
        Matcher match = Pattern.compile("pause(?: package:(\\S+))? seconds:([0-9]+)").matcher(text);
        if (!match.matches()) throw new RuleParseNoMatchException();
        packageName = match.group(1);
        try {
            seconds = Integer.parseInt(match.group(2));
            if (seconds < 1 || seconds > RulesManager.MAX_DELAY) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            throw new RuleParseNoMatchException();
        }
    }

    static String text(String packageName, int seconds) {
        if ((packageName != null && !packageName.matches("\\S+")) || seconds < 1 || seconds > RulesManager.MAX_DELAY)
            throw new IllegalArgumentException();
        return "pause" + (packageName == null ? "" : " package:" + packageName) + " seconds:" + seconds;
    }

    @Override public int getDelayToAdd(Context context, int mainDelay) {
        RulesManager rules = RulesManager.getInstance(context);
        return packageName == null ? rules.getGlobalProtectionDelay() : rules.getSharedUidDelay(context, packageName, false);
    }

    @Override public int getDelayToRemove(Context context, int mainDelay) { return 0; }
    @Override public Set<String> getActionsAfterAdd(Context context) { return Collections.singleton("reload"); }
    @Override public Set<String> getActionsAfterRemove(Context context) { return Collections.singleton("reload"); }
}
