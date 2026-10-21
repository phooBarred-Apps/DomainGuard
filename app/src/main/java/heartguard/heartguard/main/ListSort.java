package heartguard.heartguard.main;

final class ListSort {
    static final String NAME = "name";
    static final String RECENT = "recent";
    static final String BLOCKED = "blocked";
    static final String ALLOWED = "allowed";
    static final String UID = "uid";
    static final String NAME_DESC = "name_desc";
    static final String OLDEST = "oldest";
    static final String UID_DESC = "uid_desc";
    static final String APP_DEFAULT = "app_sort_default";
    static final String DOMAIN_DEFAULT = "domain_sort_default";
    static final String[] APPS = {NAME, NAME_DESC, RECENT, OLDEST, BLOCKED, ALLOWED, UID, UID_DESC};
    static final String[] DOMAINS = {RECENT, OLDEST, NAME, NAME_DESC, BLOCKED, ALLOWED};

    static String criterion(String sort) {
        if (NAME_DESC.equals(sort)) return NAME;
        if (OLDEST.equals(sort)) return RECENT;
        if (UID_DESC.equals(sort)) return UID;
        if (ALLOWED.equals(sort)) return BLOCKED;
        return sort;
    }

    static String reverse(String criterion) {
        switch (criterion) {
            case NAME: return NAME_DESC;
            case RECENT: return OLDEST;
            case UID: return UID_DESC;
            default: return ALLOWED;
        }
    }

    static String valid(String value, String[] choices) {
        for (String choice : choices) if (choice.equals(value)) return value;
        return choices[0];
    }

    static int label(String sort) {
        switch (sort) {
            case NAME_DESC: return R.string.sort_name_desc;
            case OLDEST: return R.string.sort_oldest;
            case UID_DESC: return R.string.sort_highest;
            case RECENT: return R.string.list_sort_recent;
            case BLOCKED: return R.string.list_sort_blocked;
            case ALLOWED: return R.string.list_sort_allowed;
            case UID: return R.string.menu_sort_uid;
            default: return R.string.list_sort_name;
        }
    }
}
