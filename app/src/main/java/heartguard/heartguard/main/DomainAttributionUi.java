package heartguard.heartguard.main;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.TextView;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.drawable.DrawableCompat;

final class DomainAttributionUi {
    private DomainAttributionUi() { }
    static boolean uncertain(int source) {
        return source != DomainAttribution.TLS && source != DomainAttribution.HTTP;
    }
    static void bind(TextView label, int source) {
        Context context = label.getContext();
        Drawable hint = uncertain(source) ? AppCompatResources.getDrawable(context, R.drawable.ic_domain_uncertain) : null;
        if (hint != null) {
            hint = DrawableCompat.wrap(hint.mutate());
            DrawableCompat.setTint(hint, CompactUi.color(context, android.R.attr.textColorSecondary));
            hint.setBounds(0, 0, CompactUi.dp(context, 16), CompactUi.dp(context, 16));
        }
        label.setCompoundDrawablesRelative(null, null, hint, null);
        label.setCompoundDrawablePadding(CompactUi.dp(context, 4));
        label.setContentDescription(label.getText() + ". " + context.getString(label(source)));
    }
    static int label(int source) {
        switch (source) {
            case DomainAttribution.DNS_BLOCKED: return R.string.domain_dns_blocked;
            case DomainAttribution.DNS_SERVICE_BINDING: return R.string.domain_dns_service_binding;
            case DomainAttribution.TLS: case DomainAttribution.HTTP: return R.string.domain_name_observed;
            case DomainAttribution.DNS_APP: case DomainAttribution.DNS_SHARED: return R.string.domain_name_inferred;
            default: return R.string.domain_name_uncertain;
        }
    }
    static String explanation(Context context, String address, String ip, int source, String candidates) {
        int description;
        switch (source) {
            case DomainAttribution.DNS_BLOCKED: description = R.string.domain_evidence_dns_blocked; break;
            case DomainAttribution.DNS_SERVICE_BINDING: description = R.string.domain_evidence_dns_service_binding; break;
            case DomainAttribution.TLS: description = R.string.domain_evidence_tls; break;
            case DomainAttribution.HTTP: description = R.string.domain_evidence_http; break;
            case DomainAttribution.DNS_APP: description = R.string.domain_evidence_app_dns; break;
            case DomainAttribution.DNS_SHARED: description = R.string.domain_evidence_shared_dns; break;
            case DomainAttribution.LEGACY: description = R.string.domain_evidence_legacy; break;
            case DomainAttribution.ENCRYPTED: description = R.string.domain_evidence_encrypted; break;
            case DomainAttribution.AMBIGUOUS: description = R.string.domain_evidence_ambiguous; break;
            default: description = R.string.domain_evidence_unknown;
        }
        String text = address + "\n\n" + context.getString(description);
        if (ip != null && !ip.isEmpty()) text += "\n\n" + context.getString(
                DomainAttribution.dnsEvent(source) ? R.string.domain_dns_server : R.string.domain_destination_ip, ip);
        if (candidates != null && !candidates.isEmpty())
            text += "\n\n" + context.getString(R.string.domain_candidate_names) + "\n" + candidates;
        return text + "\n\n" + context.getString(R.string.domain_evidence_policy);
    }
    static void show(Context context, AccessEntry entry) {
        CompactUi.help(context, R.string.domain_identification,
                explanation(context, entry.address, entry.destinationIp, entry.nameSource, entry.nameCandidates));
    }
}
