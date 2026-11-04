package heartguard.heartguard.main;

import android.database.Cursor;

/** An immutable row snapshot also used as the target of a tap. */
final class AccessEntry {
    final long id, time, sent, received;
    final int version, protocol, port, allowed, block, aliases, connections, pending, globalScope;
    final String address, policyAddress;
    final boolean explicitBlock;
    final int nameSource;
    final String destinationIp, nameCandidates;

    AccessEntry(Cursor cursor) {
        this(cursor, null);
    }

    AccessEntry(Cursor cursor, GlobalRuleIndicators indicators) {
        id = cursor.getLong(cursor.getColumnIndexOrThrow("ID"));
        time = cursor.getLong(cursor.getColumnIndexOrThrow("time"));
        version = cursor.getInt(cursor.getColumnIndexOrThrow("version"));
        protocol = cursor.getInt(cursor.getColumnIndexOrThrow("protocol"));
        port = cursor.getInt(cursor.getColumnIndexOrThrow("dport"));
        allowed = cursor.getInt(cursor.getColumnIndexOrThrow("allowed"));
        block = cursor.getInt(cursor.getColumnIndexOrThrow("block"));
        aliases = cursor.getInt(cursor.getColumnIndexOrThrow("count"));
        connections = cursor.getInt(cursor.getColumnIndexOrThrow("connections"));
        pending = cursor.getInt(cursor.getColumnIndexOrThrow("pending_allow"));
        sent = cursor.getLong(cursor.getColumnIndexOrThrow("sent"));
        received = cursor.getLong(cursor.getColumnIndexOrThrow("received"));
        String originalAddress = cursor.getString(cursor.getColumnIndexOrThrow("daddr"));
        policyAddress = originalAddress;
        String observed = optionalString(cursor, "observed_name");
        address = observed.isEmpty() ? originalAddress : observed;
        int sourceColumn = cursor.getColumnIndex("name_source");
        nameSource = sourceColumn < 0 ? DomainAttribution.LEGACY : cursor.getInt(sourceColumn);
        destinationIp = optionalString(cursor, "destination_ip");
        nameCandidates = optionalString(cursor, "name_candidates");
        globalScope = indicators == null ? GlobalRuleIndicators.LOCAL
                : indicators.scope(block, cursor.getString(cursor.getColumnIndexOrThrow("ruletext")), originalAddress);
        explicitBlock = indicators != null && block > 0 && globalScope == GlobalRuleIndicators.LOCAL
                && indicators.explicitlyBlocked(cursor.getInt(cursor.getColumnIndexOrThrow("uid")), originalAddress,
                cursor.getString(cursor.getColumnIndexOrThrow("ruletext")));
    }

    private static String optionalString(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? "" : cursor.getString(index);
    }

    boolean sameContents(AccessEntry other) {
        return time == other.time && version == other.version && protocol == other.protocol
                && port == other.port && allowed == other.allowed && block == other.block
                && aliases == other.aliases && connections == other.connections && pending == other.pending
                && sent == other.sent && received == other.received && address.equals(other.address)
                && policyAddress.equals(other.policyAddress)
                && globalScope == other.globalScope && explicitBlock == other.explicitBlock
                && nameSource == other.nameSource && destinationIp.equals(other.destinationIp)
                && nameCandidates.equals(other.nameCandidates);
    }
}
