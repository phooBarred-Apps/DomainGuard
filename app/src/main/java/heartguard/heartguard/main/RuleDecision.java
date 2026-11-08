package heartguard.heartguard.main;

import java.util.List;

final class RuleDecision {
    interface Eligibility { boolean allows(RuleForApp rule); }
    private RuleDecision() {}

    static RuleAllowData resolve(List<RuleForApp> appRules, List<RuleForApp> globalRules,
                                String address, List<String> names) {
        return resolve(appRules, globalRules, address, names, rule -> true);
    }

    static RuleAllowData resolve(List<RuleForApp> appRules, List<RuleForApp> globalRules,
                                String address, List<String> names, Eligibility eligibility) {
        RuleAllowData appAllow = null;
        if (appRules != null) {
            for (RuleForApp rule : appRules) {
                RuleAllowData result = rule.isAllowed(address, names);
                if (result != null) {
                    if (result.allowed == 0)
                        return result;
                    appAllow = result;
                }
            }
        }
        if (appAllow != null)
            return appAllow;
        for (RuleForApp rule : globalRules) {
            RuleAllowData result = rule.isAllowed(address, names);
            if (result != null && eligibility.allows(rule))
                return result;
        }
        return null;
    }
}
