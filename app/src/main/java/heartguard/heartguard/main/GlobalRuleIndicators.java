package heartguard.heartguard.main;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import android.content.Context;

/** Presentation of active rule scope and explicit blocks; never used for firewall decisions. */
final class GlobalRuleIndicators {
    static final int LOCAL = 0, ALLOWED = 1, BLOCKED = 2;
    private final Set<String> active;
    private final List<String> blockedDomains = new ArrayList<>();
    private final Map<Integer, List<ScopedBlock>> appBlocks = new HashMap<>();
    private RulesManager manager;

    GlobalRuleIndicators(Collection<String> rules) {
        active = new HashSet<>(rules);
        for (String text : rules) {
            String rule = TemporaryRuleText.base(text);
            if (rule.matches("blockdomain \\S+( try)?"))
                blockedDomains.add(rule.split(" ")[1].toLowerCase(Locale.ROOT));
        }
    }

    GlobalRuleIndicators(Context context, Collection<String> rules, Map<String, Long> expirations) {
        this(rules);
        manager = RulesManager.getInstance(context);
        for (String text : rules) {
            String base = TemporaryRuleText.base(text);
            if (!base.startsWith("block ")) continue;
            // Reuse the firewall's parser and host/IP matchers, once per rule snapshot.
            MyRule parsed = MyRule.getRuleFromText(context, base);
            if (!(parsed instanceof RuleAndPackage)) continue;
            RuleAndPackage rule = (RuleAndPackage) parsed;
            if (rule.getUid() < 0) continue;
            List<ScopedBlock> blocks = appBlocks.get(rule.getUid());
            if (blocks == null) { blocks = new ArrayList<>(); appBlocks.put(rule.getUid(), blocks); }
            blocks.add(new ScopedBlock(text, rule.rule, expirations.containsKey(text) ? expirations.get(text) : Long.MAX_VALUE));
        }
    }

    boolean explicitlyBlocked(int uid, String address, String ruleText) {
        List<ScopedBlock> blocks = appBlocks.get(uid);
        if (blocks == null) return false;
        long now = System.currentTimeMillis();
        for (ScopedBlock block : blocks)
            if (block.expiresAt > now && (block.text.equals(ruleText) || block.matcher.matchesAddr(address))) return true;
        return false;
    }

    private static final class ScopedBlock {
        final String text;
        final RuleForApp matcher;
        final long expiresAt;
        ScopedBlock(String text, RuleForApp matcher, long expiresAt) {
            this.text = text; this.matcher = matcher; this.expiresAt = expiresAt;
        }
    }

    int scope(int block, String ruleText, String address) {
        return scope(block, ruleText, address, -1);
    }

    int scope(int block, String ruleText, String address, int uid) {
        if (block > 0 && address != null) {
            if (manager != null) {
                if (!manager.isUidPaused(uid) && manager.isGlobalDomainBlocked(address, uid)) return BLOCKED;
            } else {
                String host = address.toLowerCase(Locale.ROOT);
                if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
                for (String domain : blockedDomains)
                    if (host.equals(domain) || host.endsWith("." + domain)) return BLOCKED;
            }
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
