package heartguard.heartguard.main;

/** Display-only rule filtering; never parses or applies a firewall decision. */
final class RawRuleFilter {
    static final int BLOCK = 1, ALLOW = 2, OTHER = 4, ALL = BLOCK | ALLOW | OTHER;
    boolean globalOnly;
    int allTypes = ALL;
    int globalTypes = ALL;

    int types() { return globalOnly ? globalTypes : allTypes; }

    void setEnabled(int type, boolean enabled) {
        int types = enabled ? types() | type : types() & ~type;
        if (globalOnly) globalTypes = types;
        else allTypes = types;
    }

    boolean matches(String text) {
        String rule = base(text);
        int type = type(rule);
        return (types() & type) != 0 && (!globalOnly || isGlobal(rule));
    }

    private static String base(String text) {
        String rule = text == null ? "" : text.trim();
        if (rule.startsWith("- ")) rule = rule.substring(2).trim();
        try { return TemporaryRuleText.base(rule); }
        catch (IllegalArgumentException invalid) { return rule; }
    }

    private static int type(String rule) {
        if (rule.startsWith("block ") || rule.startsWith("blockdomain ")) return BLOCK;
        if (rule.startsWith("allow ")) return ALLOW;
        return OTHER;
    }

    private static boolean isGlobal(String rule) {
        // Comments and unknown syntax have no global scope. Package/UID rules apply to apps.
        if (!rule.matches("(?:allow|blockdomain|delay|pause|feature|partner|ignore|dns) .+")) return false;
        for (String token : rule.split("\\s+"))
            if (token.startsWith("package:") || token.startsWith("uid:")) return false;
        return true;
    }
}
