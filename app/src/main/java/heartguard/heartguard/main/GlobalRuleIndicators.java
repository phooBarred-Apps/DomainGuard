package heartguard.heartguard.main;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Presentation of the active rule scope; this never changes the firewall decision. */
final class GlobalRuleIndicators {
    static final int LOCAL = 0, ALLOWED = 1, BLOCKED = 2;
    private final Set<String> active;
    private final List<String> blockedDomains = new ArrayList<>();

    GlobalRuleIndicators(Collection<String> rules) {
        active = new HashSet<>(rules);
        for (String text : rules) {
            String rule = TemporaryRuleText.base(text);
            if (rule.matches("blockdomain \\S+( try)?"))
                blockedDomains.add(rule.split(" ")[1].toLowerCase(Locale.ROOT));
        }
    }

    int scope(int block, String ruleText, String address) {
        if (block > 0 && address != null) {
            String host = address.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            for (String domain : blockedDomains)
                if (host.equals(domain) || host.endsWith("." + domain)) return BLOCKED;
        }
        String base = TemporaryRuleText.base(ruleText);
        if (block == 0 && base != null && active.contains(ruleText) && base.startsWith("allow ")) {
            for (String token : base.split("\\s+"))
                if (token.startsWith("package:") || token.startsWith("uid:")) return LOCAL;
            return ALLOWED;
        }
        return LOCAL;
    }
}
