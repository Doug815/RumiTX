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

    private static final String DB_NAME = "rumitx.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "channels";

    public ChannelDatabase(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // v1: no upgrades yet
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
            list.add(ch);
        }
        c.close();
        return list;
    }

    public void deleteChannel(long id) {
        getWritableDatabase().delete(TABLE, "_id=?",
                new String[]{String.valueOf(id)});
    }
}
