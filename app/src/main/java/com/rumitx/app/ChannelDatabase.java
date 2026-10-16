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
    private static final int DB_VERSION = 3;
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
    }

    private static void createKnowledgeTables(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_memories (_id INTEGER PRIMARY KEY AUTOINCREMENT, fact TEXT NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_chat_pairs (_id INTEGER PRIMARY KEY AUTOINCREMENT, question TEXT NOT NULL, answer TEXT NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS frequency_history (_id INTEGER PRIMARY KEY AUTOINCREMENT, rx REAL NOT NULL, tx REAL NOT NULL, pl REAL NOT NULL DEFAULT 0, dcs TEXT, label TEXT NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS nomad_chunks (_id INTEGER PRIMARY KEY AUTOINCREMENT, filename TEXT NOT NULL, source_uri TEXT NOT NULL, body TEXT NOT NULL, indexed_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_created ON ai_memories(created_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_created ON ai_chat_pairs(created_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_frequency_created ON frequency_history(created_at)");
    }
    private static void createRepeaterCatalog(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS repeater_catalog ("+
                "_id INTEGER PRIMARY KEY AUTOINCREMENT,"+
                "source_key TEXT NOT NULL UNIQUE,"+
                "callsign TEXT NOT NULL DEFAULT '',"+
                "frequency REAL NOT NULL,"+
                "offset TEXT NOT NULL DEFAULT '',"+
                "pl_tone TEXT,"+
                "mode TEXT NOT NULL DEFAULT '',"+
                "city TEXT NOT NULL,"+
                "state TEXT NOT NULL,"+
                "status TEXT NOT NULL,"+
                "alt_modes TEXT NOT NULL DEFAULT '',"+
                "ctcss_hz REAL,"+
                "dcs_code TEXT,"+
                "dmr_color_code INTEGER,"+
                "p25_nac TEXT,"+
                "nxdn_ran INTEGER,"+
                "import_source TEXT NOT NULL DEFAULT 'user_json',"+
                "CHECK(frequency BETWEEN 400 AND 470))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_repeater_state_city ON repeater_catalog(state,city)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_repeater_callsign ON repeater_catalog(callsign)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_repeater_frequency ON repeater_catalog(frequency)");
    }
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if(oldV<2)createKnowledgeTables(db); // Preserve existing channels unmodified.
        if(oldV<3)createRepeaterCatalog(db); // Add catalog; never modify channel rows.
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
    /** Explicit, durable user facts. No automatic extraction from casual chat. */
    public void addMemory(String fact){
        if(fact==null||fact.trim().isEmpty())return;
        ContentValues cv=new ContentValues();
        cv.put("fact",fact.trim().substring(0,Math.min(500,fact.trim().length())));
        cv.put("created_at",System.currentTimeMillis());
        getWritableDatabase().insert("ai_memories",null,cv);
    }
    public int forgetMatching(String phrase){
        if(phrase==null||phrase.trim().isEmpty())return 0;
        return getWritableDatabase().delete("ai_memories","fact LIKE ?",new String[]{"%"+phrase.trim().replace("%","").replace("_","")+"%"});
    }
    public String listMemories(){
        StringBuilder out=new StringBuilder();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT fact FROM ai_memories ORDER BY created_at DESC LIMIT 15",null)){
            while(c.moveToNext()){if(out.length()>0)out.append("\n");out.append("• ").append(c.getString(0));}
        }
        return out.length()==0?"No explicit memories saved yet. Say: remember that ...":out.toString();
    }
    public void saveChatPair(String q,String a){
        ContentValues v=new ContentValues();v.put("question",q.substring(0,Math.min(800,q.length())));v.put("answer",a.substring(0,Math.min(1200,a.length())));v.put("created_at",System.currentTimeMillis());
        SQLiteDatabase db=getWritableDatabase();
        db.insert("ai_chat_pairs",null,v);
        db.execSQL("DELETE FROM ai_chat_pairs WHERE _id NOT IN (SELECT _id FROM ai_chat_pairs ORDER BY _id DESC LIMIT 300)");
    }
    public void loadRecentChat(RumiChatContext history){
        java.util.ArrayList<String[]> pairs=new java.util.ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT question,answer FROM ai_chat_pairs ORDER BY _id DESC LIMIT 4",null)){
            while(c.moveToNext())pairs.add(new String[]{c.getString(0),c.getString(1)});
        }
        for(int i=pairs.size()-1;i>=0;i--)history.add(pairs.get(i)[0],pairs.get(i)[1]);
    }
    public void clearChat(){getWritableDatabase().delete("ai_chat_pairs",null,null);}
    public void logFrequency(double rx,double tx,double pl,String dcs,String label){
        ContentValues v=new ContentValues();v.put("rx",rx);v.put("tx",tx);v.put("pl",pl);v.put("dcs",dcs);v.put("label",label);v.put("created_at",System.currentTimeMillis());
        SQLiteDatabase db=getWritableDatabase();
        db.insert("frequency_history",null,v);
        db.execSQL("DELETE FROM frequency_history WHERE _id NOT IN (SELECT _id FROM frequency_history ORDER BY _id DESC LIMIT 300)");
    }
    public String frequencyHistory(){
        StringBuilder out=new StringBuilder();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT rx,tx,pl,dcs,label FROM frequency_history ORDER BY _id DESC LIMIT 8",null)){
            while(c.moveToNext()){
                if(out.length()>0)out.append("\n");
                out.append(String.format(java.util.Locale.US,"%s: RX %.4f TX %.4f MHz",c.getString(4),c.getDouble(0),c.getDouble(1)));
                if(c.getDouble(2)>0)out.append(String.format(java.util.Locale.US," PL %.1f",c.getDouble(2)));
                if(c.getString(3)!=null)out.append(" DCS ").append(c.getString(3));
            }
        }
        return out.length()==0?"No successful tuning events recorded yet.":out.toString();
    }
    public void replaceNomadChunks(java.util.List<String[]> docs){
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            db.delete("nomad_chunks",null,null);
            for(String[] doc:docs){
                ContentValues v=new ContentValues();v.put("filename",doc[0]);v.put("source_uri",doc[1]);v.put("body",doc[2]);v.put("indexed_at",System.currentTimeMillis());
                db.insertOrThrow("nomad_chunks",null,v);
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public int nomadChunkCount(){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM nomad_chunks",null)){return c.moveToFirst()?c.getInt(0):0;}
    }
    /** Lexical retrieval: facts and SD-index excerpts are inserted into chat context only on relevant queries. */
    public String retrieveContext(String question){
        String normalized=question.toLowerCase(java.util.Locale.US);
        String[] words=normalized.replaceAll("[^a-z0-9 ]"," ").split("\\s+");
        java.util.LinkedHashSet<String> terms=new java.util.LinkedHashSet<>();
        for(String w:words)if(w.length()>=4&&!java.util.Arrays.asList("what","when","where","have","that","this","with","from","there","about","tell","please","would","could","should","does","your","know","some","make","into","then","need","give","much","many").contains(w))terms.add(w);
        int best1=0,best2=0;String text1="",text2="";
        try(Cursor c=getReadableDatabase().rawQuery("SELECT fact FROM ai_memories ORDER BY _id DESC LIMIT 150",null)){
            while(c.moveToNext()){
                String fact=c.getString(0);int score=score(fact,terms);
                if(score>best1){best1=score;text1="Saved user fact: "+fact;}
            }
        }
        try(Cursor c=getReadableDatabase().rawQuery("SELECT filename,body FROM nomad_chunks LIMIT 1500",null)){
            while(c.moveToNext()){
                int score=score(c.getString(0),terms)*3+score(c.getString(1),terms);
                if(score>best2){best2=score;text2="Project Nomad SD source "+c.getString(0)+": "+c.getString(1);}
            }
        }
        StringBuilder out=new StringBuilder();
        if(best1>0)out.append(text1.substring(0,Math.min(text1.length(),320)));
        if(best2>0){if(out.length()>0)out.append("\n");out.append(text2.substring(0,Math.min(text2.length(),680)));}
        return out.toString();
    }
    private static int score(String s,java.util.Set<String> terms){
        if(s==null||terms.isEmpty())return 0;
        String lower=s.toLowerCase(java.util.Locale.US);int score=0;
        for(String term:terms)if(lower.contains(term))score++;
        return score;
    }
    public String savedChannelSummary(String requested){
        String q=requested==null?"":requested.toLowerCase(java.util.Locale.US).trim();
        if(q.length()<2)return "Specify a saved channel name.";
        StringBuilder out=new StringBuilder();
        for(Channel c:getAllChannels())if(c.name!=null&&c.name.toLowerCase(java.util.Locale.US).contains(q)){
            if(out.length()>0)out.append("\n");
            out.append(String.format(java.util.Locale.US,"%s: RX %.4f, TX %.4f MHz",c.name,c.rxFreqMHz,c.txFreqMHz));
            if(c.rxCtcssHz>0)out.append(String.format(java.util.Locale.US,", RX PL %.1f Hz",c.rxCtcssHz));
            if(c.rxDcsCode!=null)out.append(", RX DCS ").append(c.rxDcsCode);
            if(out.length()>750)break;
        }
        return out.length()==0?"No saved channel matches "+requested+".":out.toString();
    }

    public int repeaterCount(){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM repeater_catalog",null)){
            return c.moveToFirst()?c.getInt(0):0;
        }
    }
    /** Metadata query only: no offset is inferred and no radio is programmed. */
    public String findRepeaters(String query){
        String q=query==null?"":query.trim();
        if(q.length()<2||q.length()>100)return "Specify a city, callsign, or frequency to search.";
        String term="%"+q.replace("%","").replace("_","")+"%";
        StringBuilder out=new StringBuilder();
        int hits=0;
        try(Cursor c=getReadableDatabase().rawQuery(
           "SELECT callsign,frequency,offset,pl_tone,mode,city,state,status,alt_modes "+
           "FROM repeater_catalog WHERE callsign LIKE ? OR city LIKE ? OR state LIKE ? "+
           "OR CAST(frequency AS TEXT) LIKE ? ORDER BY CASE WHEN city LIKE ? THEN 0 ELSE 1 END,city,callsign LIMIT 15",
           new String[]{term,term,term,term,term})){
            while(c.moveToNext()){
                if(out.length()>0)out.append("\n");
                String call=c.getString(0),tone=c.getString(3),alt=c.getString(8);
                out.append(String.format(java.util.Locale.US,"%s %.5f MHz, %s %s, %s",
                    (call==null||call.length()==0)?"Unlisted call":call,c.getDouble(1),c.getString(5),c.getString(6),c.getString(7)));
                out.append(" · offset ").append(c.getString(2).length()==0?"not specified":c.getString(2));
                if(tone!=null&&!tone.isEmpty())out.append(" · access ").append(tone);
                if(alt!=null&&!alt.isEmpty())out.append(" · ").append(alt);
                hits++;
            }
        }
        return hits==0?"No repeaters match '"+q+"' in the imported catalog.":
            out.toString()+(hits==15?"\nShowing the first 15. Narrow the search.":"")+
            "\nPublished repeater listings; TX offset magnitude is not supplied, and transmit setup is NOT inferred.";
    }

}
