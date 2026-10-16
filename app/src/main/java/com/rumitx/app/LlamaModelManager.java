package com.rumitx.app;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

/** One-time private-storage model provisioning. No root or external installer. */
public final class LlamaModelManager {
    public static final long EXPECTED_BYTES=1107409344L;
    public static final String EXPECTED_SHA256="a9d99ed72848f0c26d2c380490108102aa19b47d565b192b7719e0d1f8832ba1";
    private static final String MODEL_URL=
        "https://drive.google.com/uc?export=download&confirm=t&id=1xVuMrUCNjA5W-OVy8lpBIHOlOCeOnwti";
    public interface Progress {void update(long downloaded,long total,String status);}
    private LlamaModelManager(){}
    public static File file(Context context){return new File(context.getFilesDir(),"v3_model.gguf");}
    private static File marker(Context ctx){return new File(ctx.getFilesDir(),"v3_model.verified");}
    public static boolean alreadyVerified(Context context){
        File model=file(context);
        File marker=marker(context);
        return model.isFile()&&model.length()==EXPECTED_BYTES&&marker.isFile();
    }
    public static File ensureDownloaded(Context context,Progress progress) throws Exception {
        if(alreadyVerified(context))return file(context);
        File target=file(context);
        File tmp=new File(context.getFilesDir(),"v3_model.download");
        File flag=marker(context);
        flag.delete();
        if(context.getFilesDir().getUsableSpace()<EXPECTED_BYTES+150L*1024*1024)
            throw new IOException("At least 1.2 GB free internal storage is required");
        progress.update(0,EXPECTED_BYTES,"Downloading verified GGUF…");
        HttpsURLConnection conn=null;
        try{
            URL url=new URL(MODEL_URL);
            conn=(HttpsURLConnection)url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Accept","application/octet-stream");
            conn.setRequestProperty("User-Agent","RumiTX-2030-Android/8.1");
            int status=conn.getResponseCode();
            if(status!=200)throw new IOException("Google Drive returned HTTP "+status+
                ". File must be publicly downloadable without login.");
            String mime=conn.getContentType();
            if(mime!=null && (mime.toLowerCase(Locale.US).contains("html")||
               mime.toLowerCase(Locale.US).contains("json")))
                throw new IOException("Google Drive returned an access/confirmation page, not a GGUF file");
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            long count=0,last=0;
            try(InputStream in=new BufferedInputStream(conn.getInputStream(),128*1024);
                OutputStream out=new BufferedOutputStream(new FileOutputStream(tmp,false),128*1024)){
                byte[] buffer=new byte[128*1024];
                int n;
                while((n=in.read(buffer))!=-1){
                    if(n==0)continue;
                    count+=n;
                    if(count>EXPECTED_BYTES)throw new IOException("Unexpected GGUF payload exceeds expected length");
                    if(count<=n && (n<4 || buffer[0]!='G'||buffer[1]!='G'||buffer[2]!='U'||buffer[3]!='F'))
                        throw new IOException("Download isn't a GGUF model; check Drive sharing permissions");
                    out.write(buffer,0,n);
                    md.update(buffer,0,n);
                    if(System.currentTimeMillis()-last>=250){
                        last=System.currentTimeMillis();
                        progress.update(count,EXPECTED_BYTES,"Downloading model");
                    }
                }
                out.flush();
            }
            if(count!=EXPECTED_BYTES)throw new IOException("Incomplete GGUF download: "+count+" bytes");
            StringBuilder sb=new StringBuilder();
            for(byte b:md.digest())sb.append(String.format(Locale.US,"%02x",b&0xff));
            if(!EXPECTED_SHA256.equals(sb.toString()))throw new IOException(
                "GGUF SHA-256 mismatch: expected "+EXPECTED_SHA256+" got "+sb);
            if(!tmp.renameTo(target))throw new IOException("Cannot atomically save model to app private storage");
            try(FileOutputStream f=new FileOutputStream(flag)){f.write(EXPECTED_SHA256.getBytes("US-ASCII"));f.getFD().sync();}
            progress.update(EXPECTED_BYTES,EXPECTED_BYTES,"Verified model SHA-256 ✓");
            return target;
        }finally{
            if(conn!=null)conn.disconnect();
            if(tmp.exists())tmp.delete();
        }
    }
}