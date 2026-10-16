package com.rumitx.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/**
 * RumiTX Channel Database.
 *
 * Key difference from stock app: SEPARATE tx_freq and rx_freq columns.
 * The stock Talkie app has one frequency column (simplex only).
 * RumiTX stores both for repeater-style split-frequency operation.
 */
public class ChannelDatabase extends SQLiteOpenHelper {

    private final Context featureContext;
    private static final String DB_NAME = "rumitx.db";
    private static final int DB_VERSION = 4; // Compatible with the existing V3 production rumitx.db
    private static final String TABLE = "channels";

    public ChannelDatabase(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
        featureContext=ctx.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "tx_freq REAL NOT NULL,"      // MHz, e.g., 446.00625
                + "rx_freq REAL NOT NULL,"      // MHz — SEPARATE from TX
                + "tx_ctcss REAL DEFAULT 0,"    // Hz, 0 = none (direct entry)
                + "rx_ctcss REAL DEFAULT 0,"
                + "tx_dcs TEXT,"                // e.g., "023", null = none
                + "rx_dcs TEXT,"
                + "is_digital INTEGER DEFAULT 0,"
                + "color_code INTEGER DEFAULT 1,"  // 0-15 (direct entry)
                + "talkgroup INTEGER DEFAULT 1,"
                + "timeslot INTEGER DEFAULT 1,"
                + "bandwidth INTEGER DEFAULT 12,"  // 12 or 25 (kHz)
                + "squelch INTEGER DEFAULT 4"
                + ")");
        createKnowledgeTables(db);
        createRepeaterCatalog(db);
        createChatTables(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // Android wraps onUpgrade in its own transaction. No channel rows are
        // updated/deleted, and no DROP TABLE or destructive onDowngrade exists.
        if (oldV < 2) createKnowledgeTables(db);
        if (oldV < 3) createRepeaterCatalog(db);
        if (oldV < 4) {
            createChatTables(db);
            recoverEarlierChats(db);
        }
    }

    // Schema mirrors version 4 from feature/v3-ondevice-ai-s80.
    // Keep these tables even when this app variant only uses channels;
    // a later switch back to the full AI build must retain all user data.
    private static void createKnowledgeTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_memories (_id INTEGER PRIMARY KEY AUTOINCREMENT, fact TEXT NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_chat_pairs (_id INTEGER PRIMARY KEY AUTOINCREMENT, question TEXT NOT NULL, answer TEXT NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS frequency_history (_id INTEGER PRIMARY KEY AUTOINCREMENT, rx REAL NOT NULL, tx REAL NOT NULL, pl REAL NOT NULL DEFAULT 0, dcs TEXT, label TEXT NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS nomad_chunks (_id INTEGER PRIMARY KEY AUTOINCREMENT, filename TEXT NOT NULL, source_uri TEXT NOT NULL, body TEXT NOT NULL, indexed_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_created ON ai_memories(created_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_created ON ai_chat_pairs(created_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_frequency_created ON frequency_history(created_at)");
    }

    private static void createRepeaterCatalog(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS repeater_catalog (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "source_key TEXT NOT NULL UNIQUE," +
                "callsign TEXT NOT NULL DEFAULT ''," +
                "frequency REAL NOT NULL," +
                "offset TEXT NOT NULL DEFAULT ''," +
                "pl_tone TEXT," +
                "mode TEXT NOT NULL DEFAULT ''," +
                "city TEXT NOT NULL," +
                "state TEXT NOT NULL," +
                "status TEXT NOT NULL," +
                "alt_modes TEXT NOT NULL DEFAULT ''," +
                "ctcss_hz REAL," +
                "dcs_code TEXT," +
                "dmr_color_code INTEGER," +
                "p25_nac TEXT," +
                "nxdn_ran INTEGER," +
                "import_source TEXT NOT NULL DEFAULT 'user_json'," +
                "CHECK(frequency BETWEEN 400 AND 470))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_repeater_state_city ON repeater_catalog(state,city)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_repeater_callsign ON repeater_catalog(callsign)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_repeater_frequency ON repeater_catalog(frequency)");
    }

    private static void createChatTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_threads (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_messages (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "thread_id INTEGER NOT NULL,user_text TEXT NOT NULL," +
                "assistant_text TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_thread_time ON chat_threads(updated_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_messages_thread ON chat_messages(thread_id,_id)");
    }

    private static void recoverEarlierChats(SQLiteDatabase db) {
        // Production V3 v4 copies legacy chat pairs into one recoverable thread.
        // This is an INSERT-only migration; the original ai_chat_pairs remain.
        long oldCount;
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM ai_chat_pairs", null)) {
            oldCount = c.moveToFirst() ? c.getLong(0) : 0;
        }
        if (oldCount == 0) return;
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM chat_messages", null)) {
            if (c.moveToFirst() && c.getLong(0) > 0) return;
        }
        long now = System.currentTimeMillis();
        ContentValues row = new ContentValues();
        row.put("title", "Recovered earlier conversation");
        row.put("created_at", now);
        row.put("updated_at", now);
        long id = db.insertOrThrow("chat_threads", null, row);
        db.execSQL("INSERT INTO chat_messages (thread_id,user_text,assistant_text,created_at) " +
                "SELECT ?,question,answer,created_at FROM ai_chat_pairs ORDER BY _id ASC",
                new Object[]{id});
    }

    public long saveChannel(Channel ch) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("name", ch.name);
        v.put("tx_freq", ch.txFreqMHz);
        v.put("rx_freq", ch.rxFreqMHz);
        v.put("tx_ctcss", ch.txCtcssHz);
        v.put("rx_ctcss", ch.rxCtcssHz);
        v.put("tx_dcs", ch.txDcsCode);
        v.put("rx_dcs", ch.rxDcsCode);
        v.put("is_digital", ch.isDigital ? 1 : 0);
        v.put("color_code", ch.colorCode);
        v.put("talkgroup", ch.talkgroup);
        v.put("timeslot", ch.timeslot);
        v.put("bandwidth", ch.bandwidthKhz);
        v.put("squelch", ch.squelchLevel);

        if (ch.id > 0) {
            db.update(TABLE, v, "_id=?", new String[]{String.valueOf(ch.id)});
            return ch.id;
        } else {
            return db.insert(TABLE, null, v);
        }
    }

    public List<Channel> getAllChannels() {
        List<Channel> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, null, null, null, null, null, "name ASC");
        while (c.moveToNext()) {
            Channel ch = new Channel();
            ch.id = c.getLong(c.getColumnIndexOrThrow("_id"));
            ch.name = c.getString(c.getColumnIndexOrThrow("name"));
            ch.txFreqMHz = c.getDouble(c.getColumnIndexOrThrow("tx_freq"));
            ch.rxFreqMHz = c.getDouble(c.getColumnIndexOrThrow("rx_freq"));
            ch.txCtcssHz = c.getDouble(c.getColumnIndexOrThrow("tx_ctcss"));
            ch.rxCtcssHz = c.getDouble(c.getColumnIndexOrThrow("rx_ctcss"));
            ch.txDcsCode = c.getString(c.getColumnIndexOrThrow("tx_dcs"));
            ch.rxDcsCode = c.getString(c.getColumnIndexOrThrow("rx_dcs"));
            ch.isDigital = c.getInt(c.getColumnIndexOrThrow("is_digital")) == 1;
            ch.colorCode = c.getInt(c.getColumnIndexOrThrow("color_code"));
            ch.talkgroup = c.getInt(c.getColumnIndexOrThrow("talkgroup"));
            ch.timeslot = c.getInt(c.getColumnIndexOrThrow("timeslot"));
            ch.bandwidthKhz = c.getInt(c.getColumnIndexOrThrow("bandwidth"));
            ch.squelchLevel = c.getInt(c.getColumnIndexOrThrow("squelch"));
            list.add(com.rumitx.app.repeaters.RepeaterProfiles.decorate(featureContext,ch));
        }
        c.close();
        return list;
    }

    public void deleteChannel(long id) {
        getWritableDatabase().delete(TABLE, "_id=?",
                new String[]{String.valueOf(id)});
    }
}
