package com.rumitx.app;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.List;

/** Durable named conversation threads in the existing unified rumitx.db.
 * Never clears repeater data, saved channels, Project Nomad, or user facts.
 */
public final class RumiChatStore {
    public static final class Thread {
        public final long id;
        public final String title;
        public final long updatedAt;
        public final int count;
        Thread(long i,String t,long u,int n){id=i;title=t;updatedAt=u;count=n;}
    }
    public static final class Turn {
        public final long id;
        public final String user,assistant;
        Turn(long i,String u,String a){id=i;user=u;assistant=a;}
    }
    private RumiChatStore(){}

    static void createTables(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_threads ("+
                "_id INTEGER PRIMARY KEY AUTOINCREMENT,"+
                "title TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_messages ("+
                "_id INTEGER PRIMARY KEY AUTOINCREMENT,"+
                "thread_id INTEGER NOT NULL,user_text TEXT NOT NULL,"+
                "assistant_text TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_thread_time ON chat_threads(updated_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_messages_thread ON chat_messages(thread_id,_id)");
    }

    /** Existing v3 saved chat pairs become a recoverable thread, never deleted. */
    static void migrateLegacy(SQLiteDatabase db){
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM ai_chat_pairs",null)){
            if(!c.moveToFirst()||c.getInt(0)<1)return;
        }
        long thread=create(db,"Recovered earlier conversation");
        try(Cursor old=db.rawQuery("SELECT question,answer,created_at FROM ai_chat_pairs ORDER BY _id ASC",null)){
            while(old.moveToNext()){
                ContentValues row=new ContentValues();
                row.put("thread_id",thread);
                row.put("user_text",old.getString(0));
                row.put("assistant_text",old.getString(1));
                row.put("created_at",old.getLong(2));
                db.insertOrThrow("chat_messages",null,row);
            }
        }
    }
    private static long create(SQLiteDatabase db,String title){
        long now=System.currentTimeMillis();
        ContentValues v=new ContentValues();
        v.put("title",title);v.put("created_at",now);v.put("updated_at",now);
        long id=db.insertOrThrow("chat_threads",null,v);
        if(id<=0)throw new IllegalStateException("Unable to create chat");
        return id;
    }
    public static long createThread(SQLiteDatabase db){return create(db,"New conversation");}
    public static boolean exists(SQLiteDatabase db,long id){
        if(id<=0)return false;
        try(Cursor c=db.rawQuery("SELECT 1 FROM chat_threads WHERE _id=?",new String[]{String.valueOf(id)})){
            return c.moveToFirst();
        }
    }
    public static long resolveThread(SQLiteDatabase db,long requested){
        if(exists(db,requested))return requested;
        try(Cursor c=db.rawQuery("SELECT _id FROM chat_threads ORDER BY updated_at DESC,_id DESC LIMIT 1",null)){
            if(c.moveToFirst())return c.getLong(0);
        }
        return createThread(db);
    }
    public static List<Thread> threads(SQLiteDatabase db){
        List<Thread> list=new ArrayList<>();
        try(Cursor c=db.rawQuery(
            "SELECT t._id,t.title,t.updated_at,COUNT(m._id) "+
            "FROM chat_threads t LEFT JOIN chat_messages m ON m.thread_id=t._id "+
            "GROUP BY t._id ORDER BY t.updated_at DESC,t._id DESC LIMIT 80",null)){
            while(c.moveToNext())list.add(new Thread(c.getLong(0),c.getString(1),c.getLong(2),c.getInt(3)));
        }
        return list;
    }
    public static List<Turn> turns(SQLiteDatabase db,long thread,int limit){
        List<Turn> list=new ArrayList<>();
        int safeLimit=Math.min(250,Math.max(1,limit));
        try(Cursor c=db.rawQuery("SELECT _id,user_text,assistant_text FROM ("+
            "SELECT _id,user_text,assistant_text FROM chat_messages WHERE thread_id=? "+
            "ORDER BY _id DESC LIMIT ?) ORDER BY _id ASC",
            new String[]{Long.toString(thread),Integer.toString(safeLimit)})){
            while(c.moveToNext())list.add(new Turn(c.getLong(0),c.getString(1),c.getString(2)));
        }
        return list;
    }
    public static void loadContext(SQLiteDatabase db,long thread,RumiChatContext ctx){
        ctx.clear();
        List<Turn> list=turns(db,thread,6);
        for(Turn t:list)if(t.assistant!=null&&!t.assistant.isEmpty())ctx.add(t.user,t.assistant);
    }
    /** Save the question BEFORE inference; interrupted answers remain visibly recoverable. */
    public static long beginTurn(SQLiteDatabase db,long thread,String user){
        if(!exists(db,thread))throw new IllegalArgumentException("Unknown conversation");
        ContentValues v=new ContentValues();
        v.put("thread_id",thread);
        v.put("user_text",user.length()>3000?user.substring(0,3000):user);
        v.put("assistant_text","");
        long now=System.currentTimeMillis();v.put("created_at",now);
        long id=db.insertOrThrow("chat_messages",null,v);
        db.execSQL("UPDATE chat_threads SET updated_at=? WHERE _id=?",new Object[]{now,thread});
        try(Cursor c=db.rawQuery("SELECT title FROM chat_threads WHERE _id=?",new String[]{Long.toString(thread)})){
            if(c.moveToFirst()&&"New conversation".equals(c.getString(0)))rename(db,thread,titleFor(user));
        }
        return id;
    }
    public static void finishTurn(SQLiteDatabase db,long id,String answer){
        ContentValues v=new ContentValues();
        v.put("assistant_text",answer.length()>6000?answer.substring(0,6000):answer);
        int count=db.update("chat_messages",v,"_id=?",new String[]{Long.toString(id)});
        if(count!=1)throw new IllegalStateException("Conversation turn not found");
        db.execSQL("UPDATE chat_threads SET updated_at=? WHERE _id=(SELECT thread_id FROM chat_messages WHERE _id=?)",
            new Object[]{System.currentTimeMillis(),id});
    }
    private static String titleFor(String user){
        String title=user==null?"New conversation":user.replace('\n',' ').trim();
        if(title.length()>52)title=title.substring(0,52)+"…";
        return title.isEmpty()?"New conversation":title;
    }
    public static void rename(SQLiteDatabase db,long thread,String newTitle){
        String t=titleFor(newTitle);
        ContentValues v=new ContentValues();v.put("title",t);
        db.update("chat_threads",v,"_id=?",new String[]{Long.toString(thread)});
    }
    public static void delete(SQLiteDatabase db,long thread){
        db.beginTransaction();
        try{
            db.delete("chat_messages","thread_id=?",new String[]{Long.toString(thread)});
            db.delete("chat_threads","_id=?",new String[]{Long.toString(thread)});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
}
