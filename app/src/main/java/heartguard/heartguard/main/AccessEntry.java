package heartguard.heartguard.main;

import android.database.Cursor;

/** An immutable row snapshot also used as the target of a tap. */
final class AccessEntry {
    final long id, time, sent, received;
    final int version, protocol, port, allowed, block, aliases, connections, pending, globalScope;
    final String address;

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
        address = cursor.getString(cursor.getColumnIndexOrThrow("daddr"));
        globalScope = indicators == null ? GlobalRuleIndicators.LOCAL
                : indicators.scope(block, cursor.getString(cursor.getColumnIndexOrThrow("ruletext")), address);
    }

    boolean sameContents(AccessEntry other) {
        return time == other.time && version == other.version && protocol == other.protocol
                && port == other.port && allowed == other.allowed && block == other.block
                && aliases == other.aliases && connections == other.connections && pending == other.pending
                && sent == other.sent && received == other.received && address.equals(other.address)
                && globalScope == other.globalScope;
    }
}
