package heartguard.heartguard.main;

import android.content.Context;
import android.text.format.DateFormat;
import androidx.preference.PreferenceManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class DomainTimestamp {
    static String format(Context context, long time) {
        if ("elapsed".equals(PreferenceManager.getDefaultSharedPreferences(context).getString("domain_timestamp_format", "exact"))) {
            long seconds = Math.max(0, (System.currentTimeMillis() - time) / 1000);
            if (seconds < 60) return context.getString(R.string.elapsed_seconds, seconds);
            if (seconds < 3600) return context.getString(R.string.elapsed_minutes, seconds / 60);
            if (seconds < 86400) return context.getString(R.string.elapsed_hours, seconds / 3600);
            return context.getString(R.string.elapsed_days, seconds / 86400);
        }
        return new SimpleDateFormat(DateFormat.is24HourFormat(context) ? "d · HH:mm:ss" : "d · h:mm:ss a", Locale.getDefault()).format(new Date(time));
    }

    static String full(long time) {
        return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.LONG, java.text.DateFormat.MEDIUM).format(new Date(time));
    }
}
