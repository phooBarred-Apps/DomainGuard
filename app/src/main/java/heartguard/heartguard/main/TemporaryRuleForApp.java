package heartguard.heartguard.main;

import java.util.List;

/** Checks the saved deadline on every decision, even before expiry cleanup can run. */
final class TemporaryRuleForApp implements RuleForApp {
    private final MyRule owner;
    private final RuleForApp delegate;

    TemporaryRuleForApp(MyRule owner, RuleForApp delegate) {
        this.owner = owner;
        this.delegate = delegate;
    }

    public RuleAllowData isAllowed(String address, List<String> names) {
        if (owner.temporaryExpiresAt <= System.currentTimeMillis()) return null;
        RuleAllowData result = delegate.isAllowed(address, names);
        if (result != null) result.ruletext = owner.getRuleText();
        return result;
    }

    // Matching remains available after expiry so access-history decisions can be refreshed.
    public boolean matchesAddr(String name) { return delegate.matchesAddr(name); }
    public String getRuletext() { return owner.getRuleText(); }
}
