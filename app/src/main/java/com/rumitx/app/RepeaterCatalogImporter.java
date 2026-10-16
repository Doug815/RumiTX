package com.rumitx.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One-time or repeatable user-controlled import of the IL/IN/WI JSON catalog.
 * Adds repeater_catalog records to rumitx.db, NOT to transmit-ready channels.
 * Every entry is validated before the first database write; writes are transactional.
 */
public final class RepeaterCatalogImporter {
    private static final int MAX_BYTES=3000000;
    private static final int MAX_RECORDS=5000;
    private RepeaterCatalogImporter(){}

    public static String importJson(Context context,Uri uri)throws Exception{
        byte[] data;
        try(InputStream stream=context.getContentResolver().openInputStream(uri)){
            if(stream==null)throw new IllegalArgumentException("Cannot read the chosen JSON file.");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            byte[] buf=new byte[16384];int n;
            while((n=stream.read(buf))!=-1){
                if(bytes.size()+n>MAX_BYTES)throw new IllegalArgumentException("Catalog exceeds the 3 MB import limit.");
                bytes.write(buf,0,n);
            }
            data=bytes.toByteArray();
        }
        JSONArray array=new JSONArray(new String(data,StandardCharsets.UTF_8));
        if(array.length()<1||array.length()>MAX_RECORDS)
            throw new IllegalArgumentException("Expected 1–5000 repeater records.");
        ContentValues[] records=new ContentValues[array.length()];
        for(int i=0;i<array.length();i++)
            records[i]=parse(array.getJSONObject(i),i+1);
        int inserted=0;
        try(ChannelDatabase helper=new ChannelDatabase(context)){
            SQLiteDatabase db=helper.getWritableDatabase();
            db.beginTransaction();
            try{
                for(ContentValues r:records){
                    long id=db.insertWithOnConflict("repeater_catalog",null,r,SQLiteDatabase.CONFLICT_IGNORE);
                    if(id!=-1)inserted++;
                }
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            return "Repeaters imported: "+array.length()+" valid, "+inserted+" added, "+
                 (array.length()-inserted)+" already present. Total catalog: "+helper.repeaterCount()+
                 ". No saved radio channels or AI memories were replaced.";
        }
    }

    private static ContentValues parse(JSONObject obj,int n)throws Exception{
        for(String key:new String[]{"callsign","frequency","offset","pl_tone","mode","city","state","status","alt_modes"})
            if(!obj.has(key))throw new IllegalArgumentException("Entry "+n+" missing field "+key);
        String call=obj.isNull("callsign")?"":obj.getString("callsign");
        double freq=obj.getDouble("frequency");
        String offset=obj.isNull("offset")?"":obj.getString("offset");
        String tone=obj.isNull("pl_tone")?null:obj.getString("pl_tone");
        String mode=obj.isNull("mode")?"":obj.getString("mode");
        String city=obj.getString("city"),state=obj.getString("state"),
               status=obj.getString("status");
        String alternate=obj.isNull("alt_modes")?"":obj.getString("alt_modes");
        if(!Double.isFinite(freq)||freq<400||freq>470)
            throw new IllegalArgumentException("Entry "+n+" frequency out of range.");
        if(!(offset.equals("+")||offset.equals("-")||offset.isEmpty()))
            throw new IllegalArgumentException("Entry "+n+" has unsupported offset flag.");
        if(city.isEmpty()||state.isEmpty()||call.length()>40||city.length()>200||
            mode.length()>40||status.length()>40||(tone!=null&&tone.length()>100))
            throw new IllegalArgumentException("Entry "+n+" has invalid text metadata.");
        String[] fields={call,String.format(Locale.US,"%.5f",freq),offset,
                         tone==null?"":tone,mode,city,state,status,alternate};
        String canonical=android.text.TextUtils.join("\u001f",fields);
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder key=new StringBuilder();
        for(int i=0;i<16;i++)key.append(String.format(Locale.US,"%02x",hash[i]&0xff));

        ContentValues v=new ContentValues();
        v.put("source_key",key.toString());
        v.put("callsign",call);v.put("frequency",freq);v.put("offset",offset);
        if(tone==null)v.putNull("pl_tone");else v.put("pl_tone",tone);
        v.put("mode",mode);v.put("city",city);v.put("state",state);
        v.put("status",status);v.put("alt_modes",alternate);
        if(tone!=null){
            Matcher pl=Pattern.compile("(?:^|\\s)(\\d{2,3}\\.\\d+)\\b").matcher(tone);
            if(pl.find())v.put("ctcss_hz",Double.parseDouble(pl.group(1)));
            Matcher dcs=Pattern.compile("\\bD(\\d{3})\\b").matcher(tone);
            if(dcs.find())v.put("dcs_code",dcs.group(1));
            Matcher cc=Pattern.compile("\\bCC\\s+(\\d{1,2})\\b").matcher(tone);
            if(cc.find())v.put("dmr_color_code",Integer.parseInt(cc.group(1)));
            Matcher nac=Pattern.compile("\\bNAC\\s+([0-9A-Fa-f]{3})\\b").matcher(tone);
            if(nac.find())v.put("p25_nac",nac.group(1));
            Matcher ran=Pattern.compile("\\bRAN\\s+(\\d{1,2})\\b").matcher(tone);
            if(ran.find())v.put("nxdn_ran",Integer.parseInt(ran.group(1)));
        }
        v.put("import_source","user_json_2026_10_15");
        return v;
    }
}
