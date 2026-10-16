package com.rumitx.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Strictly read-only lookup of repeaters.db (primary) and legacy rumitx.db
 * repeater_catalog (fallback for the user-imported JSON).
 * NO SQLiteOpenHelper getReadableDatabase(): that could create/migrate DBs.
 */
public final class RepeaterLocalSearch {
    public static final class Answer {
        public final String reply, promptContext;
        public final int count;
        private Answer(String r,String context,int n){reply=r;promptContext=context;count=n;}
    }
    private static final int LIMIT=10;
    private static final int OPEN_FLAGS=SQLiteDatabase.OPEN_READONLY|
        SQLiteDatabase.NO_LOCALIZED_COLLATORS;
    private RepeaterLocalSearch(){}
    private static String safe(String s){return s==null?"":s.trim();}
    private static String freq(double f){
        return String.format(Locale.US,"%.4f",f);
    }
    private static final class Row {
        String call,city,state,mode,status,extra="";
        double output;
        String summary(){
            StringBuilder b=new StringBuilder();
            b.append(call.isEmpty()?"(no callsign)":call);
            b.append(" · ").append(freq(output)).append(" MHz RX");
            if(!extra.isEmpty())b.append(" · ").append(extra);
            if(!mode.isEmpty())b.append(" · ").append(mode);
            b.append(" · ").append(city);
            if(!state.isEmpty())b.append(", ").append(state);
            if(!status.isEmpty() && !"OPEN".equalsIgnoreCase(status))
                b.append(" · ").append(status);
            return b.toString();
        }
    }
    /** Never read or mutate a SQLite DB on Android's main thread. */
    public static Answer search(Context ctx,RepeaterQuestion q){
        if(q==null)return null;
        if(q.city.isEmpty()&&q.callsign==null&&q.frequency==null){
            return new Answer("Which city, callsign, or frequency should I search in the local repeater database?","",0);
        }
        List<Row> results=new ArrayList<>();
        String source="repeaters.db";
        boolean dbPresent=false;
        File primary=ctx.getDatabasePath("repeaters.db");
        if(primary.isFile()){
            dbPresent=true;
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(primary.getAbsolutePath(),null,OPEN_FLAGS)){
                if(hasTable(db,"repeaters")) queryPrimary(db,q,results);
            }catch(Exception e){
                android.util.Log.w("RumiTX-Repeaters","Read-only repeaters.db lookup",e);
            }
        }
        if(results.isEmpty()){
            File catalog=ctx.getDatabasePath("rumitx.db");
            if(catalog.isFile()){
                dbPresent=true;
                try(SQLiteDatabase db=SQLiteDatabase.openDatabase(catalog.getAbsolutePath(),null,OPEN_FLAGS)){
                    if(hasTable(db,"repeater_catalog")){
                        source="rumitx.db / repeater_catalog";
                        queryCatalog(db,q,results);
                    }
                }catch(Exception e){
                    android.util.Log.w("RumiTX-Repeaters","Read-only repeater_catalog lookup",e);
                }
            }
        }
        if(results.isEmpty()){
            String msg=dbPresent?
                "I don't have repeater data for "+q.label+" in my local database.":
                "The local repeater database is not available. Import repeater data first.";
            return new Answer(msg,"",0);
        }
        int found=Math.min(results.size(),LIMIT);
        StringBuilder text=new StringBuilder("Local repeaters for ").append(q.label).append(" (");
        text.append(source).append("):\n");
        StringBuilder context=new StringBuilder(
            "LOCAL REPEATER DATABASE RESULTS ONLY (not model memory). "+
            "Do not invent other frequencies, tones or callsigns.\n");
        for(int i=0;i<found;i++){
            String line=results.get(i).summary();
            text.append(i+1).append(". ").append(line).append('\n');
            context.append(i+1).append(". ").append(line).append('\n');
        }
        if(results.size()>LIMIT)text.append("More results exist. Narrow the city, callsign, or frequency.");
        else text.append("Results are from stored data, not live repeater availability.");
        return new Answer(text.toString().trim(),context.toString(),found);
    }
    private static boolean hasTable(SQLiteDatabase db,String table){
        try(Cursor c=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            new String[]{table})){return c.moveToFirst();}
    }
    private static String where(RepeaterQuestion q,String cityField,String callField,String freqField,String txField,List<String> args){
        StringBuilder w=new StringBuilder("1=1");
        if(!q.city.isEmpty()){
            w.append(" AND (LOWER(TRIM(").append(cityField).append("))=? OR LOWER(TRIM(")
             .append(cityField).append(")) LIKE ?)");
            args.add(q.city.toLowerCase(Locale.US));
            args.add(q.city.toLowerCase(Locale.US)+",%");
        }
        if(!q.state.isEmpty()){
            w.append(" AND UPPER(TRIM(state))=?");args.add(q.state);
        }
        if(q.callsign!=null){
            w.append(" AND UPPER(TRIM(").append(callField).append("))=?");args.add(q.callsign);
        }
        if(q.frequency!=null){
            w.append(" AND (ABS(").append(freqField).append("-?)<0.00001");
            args.add(String.format(Locale.US,"%.6f",q.frequency));
            if(txField!=null){
                w.append(" OR ABS(").append(txField).append("-?)<0.00001");
                args.add(String.format(Locale.US,"%.6f",q.frequency));
            }
            w.append(')');
        }
        return w.toString();
    }
    private static String val(Cursor c,int i){return c.isNull(i)?"":safe(c.getString(i));}
    private static void queryPrimary(SQLiteDatabase db,RepeaterQuestion q,List<Row> out){
        List<String> args=new ArrayList<>();
        String sql="SELECT callsign,freq_out,freq_in,offset_mhz,tone_in,tone_out,dcs_in,dcs_out,mode,dmr_cc,city,state,status "+
            "FROM repeaters WHERE "+where(q,"city","callsign","freq_out","freq_in",args)+
            " ORDER BY callsign COLLATE NOCASE, freq_out ASC LIMIT "+(LIMIT+1);
        try(Cursor c=db.rawQuery(sql,args.toArray(new String[0]))){
            while(c.moveToNext()){
                Row r=new Row();
                r.call=val(c,0);r.output=c.getDouble(1);
                StringBuilder extra=new StringBuilder();
                if(!c.isNull(2))extra.append("TX ").append(freq(c.getDouble(2))).append(" MHz");
                else if(!c.isNull(3))extra.append(String.format(Locale.US,"offset %+.4f MHz",c.getDouble(3)));
                String toneIn=val(c,4),toneOut=val(c,5),dcsIn=val(c,6),dcsOut=val(c,7);
                if(!toneIn.isEmpty())add(extra,"TX PL "+toneIn);
                if(!toneOut.isEmpty())add(extra,"RX PL "+toneOut);
                if(!dcsIn.isEmpty())add(extra,"TX DCS "+dcsIn);
                if(!dcsOut.isEmpty())add(extra,"RX DCS "+dcsOut);
                if(!c.isNull(9))add(extra,"DMR CC "+c.getInt(9));
                r.extra=extra.toString();
                r.mode=val(c,8);r.city=val(c,10);r.state=val(c,11);r.status=val(c,12);
                out.add(r);
            }
        }
    }
    private static void queryCatalog(SQLiteDatabase db,RepeaterQuestion q,List<Row> out){
        List<String> args=new ArrayList<>();
        String sql="SELECT callsign,frequency,offset,pl_tone,mode,alt_modes,city,state,status "+
            "FROM repeater_catalog WHERE "+where(q,"city","callsign","frequency",null,args)+
            " ORDER BY callsign COLLATE NOCASE,frequency ASC LIMIT "+(LIMIT+1);
        try(Cursor c=db.rawQuery(sql,args.toArray(new String[0]))){
            while(c.moveToNext()){
                Row r=new Row();
                r.call=val(c,0);r.output=c.getDouble(1);
                StringBuilder extra=new StringBuilder();
                String offset=val(c,2),tone=val(c,3),other=val(c,5);
                if(!offset.isEmpty())extra.append("offset ").append(offset);
                if(!tone.isEmpty())add(extra,"tone "+tone);
                if(!other.isEmpty())add(extra,"alt "+other);
                r.extra=extra.toString();
                r.mode=val(c,4);r.city=val(c,6);r.state=val(c,7);r.status=val(c,8);
                out.add(r);
            }
        }
    }
    private static void add(StringBuilder b,String fragment){
        if(b.length()>0)b.append(", ");
        b.append(fragment);
    }
}
