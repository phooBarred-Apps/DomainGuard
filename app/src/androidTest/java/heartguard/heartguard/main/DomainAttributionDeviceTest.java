package heartguard.heartguard.main;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DomainAttributionDeviceTest {
    private static final int UID=990291;
    private DatabaseHelper db;
    private Packet original;
    @Before public void setup() {
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext(); db=DatabaseHelper.getInstance(context);
        try(Cursor rows=db.getReadableDatabase().rawQuery("SELECT ID FROM access WHERE uid=?",new String[]{""+UID})) { assertEquals(0,rows.getCount()); }
        original=new Packet(); original.uid=UID; original.time=System.currentTimeMillis(); original.version=4;
        original.protocol=6; original.saddr="10.0.0.9"; original.sport=49091; original.daddr="192.0.2.91"; original.dport=443; original.allowed=true;
    }
    @After public void cleanup() {
        db.getWritableDatabase().delete("access","uid=?",new String[]{""+UID});
        db.getWritableDatabase().delete("log","uid=?",new String[]{""+UID});
        db.getWritableDatabase().delete("dns_attribution","ip=?",new String[]{original.daddr});
        db.getWritableDatabase().delete("dns","resource=?",new String[]{original.daddr});
    }
    private void insert() {
        ContentValues row=new ContentValues(); row.put("uid",UID); row.put("time",original.time); row.put("version",4); row.put("protocol",6);
        row.put("dport",443); row.put("daddr",original.daddr); row.put("destination_ip",original.daddr); row.put("allowed",1);
        row.put("block",0); row.put("pending_allow",0); row.put("name_source",DomainAttribution.UNKNOWN); row.put("flow",ConnectionAttribution.identity(original));
        assertTrue(db.getWritableDatabase().insertOrThrow("access",null,row)>0);
        original.attribution=new DomainAttribution(null,DomainAttribution.UNKNOWN,""); db.insertLog(original,null,1,false);
    }
    private AccessEntry entry() {
        try(Cursor rows=db.getAccess(UID)) { assertTrue(rows.moveToFirst()); return new AccessEntry(rows); }
    }
    // Test: Enrichment preserves ID, time, result and policy identity while both searches find the observed name.
    // Why: Better labels must not change rules, reorder history or disconnect main/per-app searches.
    @Test public void enrichWithoutChangingPolicyAndSearchObservedName() {
        insert(); AccessEntry before=entry(); db.refineConnectionName(original,new DomainAttribution("mail.observed.invalid",DomainAttribution.TLS,""));
        AccessEntry after=entry(); assertEquals(before.id,after.id); assertEquals(before.time,after.time); assertEquals(before.allowed,after.allowed);
        assertEquals("mail.observed.invalid",after.address); assertEquals(original.daddr,after.destinationIp);
        assertEquals("Rule reset must retain the original policy target", before.policyAddress, after.policyAddress);
        try(Cursor rows=db.getReadableDatabase().rawQuery("SELECT daddr,block FROM access WHERE uid=?",new String[]{""+UID})) {
            assertTrue(rows.moveToFirst()); assertEquals(original.daddr,rows.getString(0)); assertEquals(0,rows.getInt(1));
        }
        try(Cursor rows=db.getAccess(UID,false,"observed",ListSort.RECENT,"mail")) { assertEquals(1,rows.getCount()); }
        try(Cursor rows=db.searchAccessDomains(new DomainSearchQuery("mail.observed.invalid"))) { assertTrue(rows.moveToFirst()); assertEquals("mail.observed.invalid",rows.getString(1)); }
        try(Cursor rows=db.getReadableDatabase().rawQuery("SELECT dname,daddr FROM log WHERE uid=?",new String[]{""+UID})) {
            assertTrue(rows.moveToFirst()); assertEquals("mail.observed.invalid",rows.getString(0)); assertEquals(original.daddr,rows.getString(1));
        }
    }
    // Test: Clearing traffic wins over a late parser result. Why: Identification must never resurrect deleted history.
    @Test public void clearedHistoryStaysDeleted() {
        insert(); db.getWritableDatabase().delete("access","uid=?",new String[]{""+UID}); db.getWritableDatabase().delete("log","uid=?",new String[]{""+UID});
        db.refineConnectionName(original,new DomainAttribution("late.invalid",DomainAttribution.HTTP,""));
        try(Cursor rows=db.getAccess(UID)) { assertEquals(0,rows.getCount()); }
        try(Cursor rows=db.getReadableDatabase().rawQuery("SELECT ID FROM log WHERE uid=?",new String[]{""+UID})) { assertEquals(0,rows.getCount()); }
    }
    // Test: A later connection cannot inherit the old flow's name. Why: Two simultaneous connections can share an app and destination IP.
    @Test public void newerFlowWins() {
        insert(); Packet newer=new Packet(); newer.uid=UID; newer.time=original.time+1; newer.version=4; newer.protocol=6;
        newer.saddr=original.saddr; newer.sport=49092; newer.daddr=original.daddr; newer.dport=443;
        ContentValues cv=new ContentValues();cv.put("flow",ConnectionAttribution.identity(newer));cv.put("time",newer.time);
        db.getWritableDatabase().update("access",cv,"uid=?",new String[]{""+UID});
        db.refineConnectionName(original,new DomainAttribution("old.invalid",DomainAttribution.TLS,""));
        assertEquals(original.daddr,entry().address); assertEquals(newer.time,entry().time);
    }
    // Test: Per-app DNS survives the database bridge, and real TTL controls evidence expiry.
    // Why: The firewall compatibility minimum TTL must not make the display evidence live for three days.
    @Test public void dnsOwnershipAndActualTtl() {
        ResourceRecord rr=new ResourceRecord();rr.Time=original.time;rr.QName="mail.dns.invalid";rr.AName=rr.QName;rr.Resource=original.daddr;rr.TTL=1;rr.Uid=UID;
        assertTrue(db.insertDns(rr));
        assertEquals(DomainAttribution.DNS_APP,db.getAttribution(UID,rr.Resource,rr.Time+1).source);
        assertEquals(DomainAttribution.DNS_SHARED,db.getAttribution(UID+1,rr.Resource,rr.Time+1).source);
        assertEquals(DomainAttribution.UNKNOWN,db.getAttribution(UID,rr.Resource,rr.Time+1000).source);
    }
    // Test: Upgrade adds evidence columns without discarding existing rules or traffic.
    // Why: Historical names cannot be verified retroactively, but the history must still be retained and labelled legacy.
    @Test public void migrationPreservesHistory() {
        try(SQLiteDatabase legacy=SQLiteDatabase.create(null)) {
            legacy.execSQL("CREATE TABLE access (ID INTEGER PRIMARY KEY, daddr TEXT)");
            legacy.execSQL("CREATE TABLE log (ID INTEGER PRIMARY KEY,dname TEXT)");
            legacy.execSQL("CREATE TABLE rules (ruletext TEXT)");
            legacy.execSQL("INSERT INTO access VALUES(7,'old.invalid')"); legacy.execSQL("INSERT INTO log VALUES(8,'old.invalid')");
            legacy.execSQL("INSERT INTO rules VALUES('allow host:old.invalid')");
            db.onUpgrade(legacy,25,26);
            try(Cursor rows=legacy.rawQuery("SELECT ID,daddr,name_source FROM access",null)) {
                assertTrue(rows.moveToFirst());assertEquals(7,rows.getInt(0));assertEquals("old.invalid",rows.getString(1));assertEquals(DomainAttribution.LEGACY,rows.getInt(2));
            }
            try(Cursor rows=legacy.rawQuery("SELECT ruletext FROM rules",null)) {assertTrue(rows.moveToFirst());assertEquals("allow host:old.invalid",rows.getString(0));}
            assertEquals(26,legacy.getVersion());
        }
    }
}
