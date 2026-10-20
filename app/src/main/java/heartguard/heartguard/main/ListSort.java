package heartguard.heartguard.main;

final class ListSort {
    static final String NAME = "name";
    static final String RECENT = "recent";
    static final String BLOCKED = "blocked";
    static final String ALLOWED = "allowed";
    static final String UID = "uid";
    static final String APP_DEFAULT = "app_sort_default";
    static final String DOMAIN_DEFAULT = "domain_sort_default";
    static final String[] APPS = {NAME, RECENT, BLOCKED, ALLOWED, UID};
    static final String[] DOMAINS = {RECENT, NAME, BLOCKED, ALLOWED};

    static String valid(String value, String[] choices) {
        for (String choice : choices) if (choice.equals(value)) return value;
        return choices[0];
    }

    static int label(String sort) {
        switch (sort) {
            case RECENT: return R.string.list_sort_recent;
            case BLOCKED: return R.string.list_sort_blocked;
            case ALLOWED: return R.string.list_sort_allowed;
            case UID: return R.string.menu_sort_uid;
            default: return R.string.list_sort_name;
        }
    }
}
