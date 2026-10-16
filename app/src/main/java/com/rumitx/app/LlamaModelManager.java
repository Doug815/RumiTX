package com.rumitx.app;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/**
 * Non-root GGUF provisioning. Existing GGUF is always SHA-256 checked before
 * any network or free-space check. Incomplete files NEVER replace the model.
 */
public final class LlamaModelManager {
    public static final long EXPECTED_BYTES=1107409344L;
    public static final String EXPECTED_SHA256=
        "a9d99ed72848f0c26d2c380490108102aa19b47d565b192b7719e0d1f8832ba1";
    private static final String MODEL_ID="1xVuMrUCNjA5W-OVy8lpBIHOlOCeOnwti";
    private static final String MODEL_URL="https://drive.google.com/uc?export=download&confirm=t&id="+MODEL_ID;
    private static final int BUFFER_BYTES=128*1024;
    private static final int MAX_HTML_BYTES=256*1024;
    public interface Progress {void update(long completed,long total,String status);}
    private LlamaModelManager(){}
    public static File file(Context context){return new File(context.getFilesDir(),"v3_model.gguf");}
    private static File marker(Context context){return new File(context.getFilesDir(),"v3_model.verified");}
    private static void progress(Progress p,long done,long total,String state){
        if(p!=null)p.update(done,total,state);
    }
    /** The marker is an optimization hint ONLY; it never proves file integrity. */
    public static boolean alreadyVerified(Context context) {
        try{return verifySha256(file(context),null);}catch(Exception e){return false;}
    }
    /** Stream verification: no loading the 1.1GB file into RAM. */
    public static boolean verifySha256(File modelFile,Progress callback) throws Exception {
        if(modelFile==null||!modelFile.isFile()||modelFile.length()!=EXPECTED_BYTES)return false;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        long n=0,next=0;
        progress(callback,0,EXPECTED_BYTES,"Checking existing model SHA-256…");
        try(InputStream in=new BufferedInputStream(new FileInputStream(modelFile),BUFFER_BYTES)){
            byte[] data=new byte[BUFFER_BYTES];int got;
            while((got=in.read(data))!=-1){
                if(got==0)continue;
                digest.update(data,0,got);n+=got;
                if(n>=next){
                    progress(callback,n,EXPECTED_BYTES,"Verifying existing model…");
                    next=n+16L*1024*1024;
                }
            }
        }
        if(n!=EXPECTED_BYTES)return false;
        StringBuilder hex=new StringBuilder(64);
        for(byte b:digest.digest())hex.append(String.format(Locale.US,"%02x",b&0xff));
        return EXPECTED_SHA256.equals(hex.toString());
    }
    private static void stamp(Context ctx)throws Exception{
        try(FileOutputStream out=new FileOutputStream(marker(ctx),false)){
            out.write(EXPECTED_SHA256.getBytes("US-ASCII"));
            out.getFD().sync();
        }
    }
    private static void verifyStorage(Context ctx){
        if(ctx.getFilesDir().getUsableSpace()<EXPECTED_BYTES+150L*1024*1024)
            throw new IllegalStateException("Not enough free internal storage for the 1.1 GB GGUF (need about 1.2 GB)");
    }

    /** Reuse any matching GGUF even if an earlier app never wrote the .verified marker. */
    public static synchronized File ensureDownloaded(Context ctx,Progress callback)throws Exception{
        File model=file(ctx);
        if(model.isFile() && verifySha256(model,callback)){
            stamp(ctx);
            progress(callback,EXPECTED_BYTES,EXPECTED_BYTES,"Existing model verified — no download needed");
            return model;
        }
        if(model.isFile())progress(callback,0,EXPECTED_BYTES,"Existing model failed verification; downloading a fresh copy");
        verifyStorage(ctx);
        File tmp=new File(ctx.getFilesDir(),"v3_model.download");
        if(tmp.exists()&&!tmp.delete())throw new IOException("Cannot clear previous partial download");
        try{
            streamGoogleDrive(tmp,callback);
            // Preserve any pre-existing model unless the replacement is independently valid.
            if(!verifySha256(tmp,callback))throw new IOException("Downloaded GGUF SHA-256 mismatch. Existing model preserved.");
            if(!tmp.renameTo(model))throw new IOException("Could not replace GGUF atomically in app storage");
            stamp(ctx);
            progress(callback,EXPECTED_BYTES,EXPECTED_BYTES,"Model downloaded and SHA-256 verified");
            return model;
        }finally{
            if(tmp.exists())tmp.delete();
        }
    }

    /** Manually choose the GGUF via Storage Access Framework — no root, no ADB. */
    public static synchronized File importFromUri(Context ctx,Uri uri,Progress callback)throws Exception{
        if(uri==null)throw new IOException("Choose a .gguf file");
        verifyStorage(ctx);
        File tmp=new File(ctx.getFilesDir(),"v3_model.importing");
        if(tmp.exists()&&!tmp.delete())throw new IOException("Cannot clear partial import");
        try{
            progress(callback,0,EXPECTED_BYTES,"Importing GGUF from phone storage…");
            long count=0,next=0;
            try(InputStream in=ctx.getContentResolver().openInputStream(uri);
                OutputStream out=new BufferedOutputStream(new FileOutputStream(tmp),BUFFER_BYTES)){
                if(in==null)throw new IOException("Cannot open selected model file");
                byte[] buffer=new byte[BUFFER_BYTES];int n;
                while((n=in.read(buffer))!=-1){
                    if(n==0)continue;
                    count+=n;
                    if(count>EXPECTED_BYTES)throw new IOException("Selected file is larger than expected GGUF");
                    if(count==n&&(n<4||buffer[0]!='G'||buffer[1]!='G'||buffer[2]!='U'||buffer[3]!='F'))
                        throw new IOException("Selected file is not a GGUF");
                    out.write(buffer,0,n);
                    if(count>=next){
                        progress(callback,count,EXPECTED_BYTES,"Copying selected GGUF…");
                        next=count+16L*1024*1024;
                    }
                }
            }
            if(count!=EXPECTED_BYTES)throw new IOException("Wrong GGUF file size: "+count+" bytes");
            if(!verifySha256(tmp,callback))throw new IOException("Selected GGUF SHA-256 mismatch; existing model preserved");
            File dest=file(ctx);
            if(!tmp.renameTo(dest))throw new IOException("Could not save verified GGUF to private storage");
            stamp(ctx);
            progress(callback,EXPECTED_BYTES,EXPECTED_BYTES,"Imported GGUF verified — loading without download");
            return dest;
        }finally{
            if(tmp.exists())tmp.delete();
        }
    }

    /** Manually follow Google redirects so confirmation cookies survive host changes. */
    private static void streamGoogleDrive(File tmp,Progress callback)throws Exception{
        CookieManager jar=new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        URL url=new URL(MODEL_URL);
        for(int redirects=0;redirects<10;redirects++){
            if(!GoogleDriveConfirmation.allowed(url))
                throw new IOException("Unsafe download redirect blocked");
            HttpsURLConnection conn=(HttpsURLConnection)url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(30000);conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 8.1) RumiTX-2030/2.3.2");
            conn.setRequestProperty("Accept","application/octet-stream,text/html;q=0.8,*/*;q=0.5");
            for(Map.Entry<String,List<String>> e:jar.get(url.toURI(),Collections.<String,List<String>>emptyMap()).entrySet()){
                if(e.getKey()!=null&&e.getKey().equalsIgnoreCase("Cookie")&&!e.getValue().isEmpty())
                    conn.setRequestProperty("Cookie",join(e.getValue()));
            }
            try{
                int status=conn.getResponseCode();
                jar.put(url.toURI(),conn.getHeaderFields());
                if(status==301||status==302||status==303||status==307||status==308){
                    String location=conn.getHeaderField("Location");
                    if(location==null)throw new IOException("Google Drive redirect lacks Location");
                    url=new URL(url,location);
                    continue;
                }
                if(status!=200)throw new IOException("Google Drive HTTP "+status+
                    "; use 'Load model from phone storage' if public download is unavailable");
                try(InputStream input=new BufferedInputStream(conn.getInputStream(),BUFFER_BYTES)){
                    input.mark(8);
                    byte[] magic=new byte[4];
                    int m=0;
                    while(m<4){int k=input.read(magic,m,4-m);if(k<0)break;m+=k;}
                    input.reset();
                    if(m<4||magic[0]!='G'||magic[1]!='G'||magic[2]!='U'||magic[3]!='F'){
                        ByteArrayOutputStream page=new ByteArrayOutputStream();
                        byte[] small=new byte[8192];int read;
                        while((read=input.read(small))!=-1){
                            if(page.size()+read>MAX_HTML_BYTES)
                                throw new IOException("Drive returned an oversized non-GGUF page; select local model instead");
                            page.write(small,0,read);
                        }
                        URL next=GoogleDriveConfirmation.resolve(page.toString("UTF-8"),url,MODEL_ID);
                        if(next==null||next.toExternalForm().equals(url.toExternalForm()))
                            throw new IOException("Google Drive returned a confirmation/access page without a usable download link; choose local GGUF");
                        progress(callback,0,EXPECTED_BYTES,"Following Google Drive large-file confirmation…");
                        url=next;continue;
                    }
                    progress(callback,0,EXPECTED_BYTES,"Downloading GGUF from Google Drive…");
                    long count=0,next=0;
                    try(OutputStream out=new BufferedOutputStream(new FileOutputStream(tmp),BUFFER_BYTES)){
                        byte[] buffer=new byte[BUFFER_BYTES];int n;
                        while((n=input.read(buffer))!=-1){
                            if(n==0)continue;
                            count+=n;
                            if(count>EXPECTED_BYTES)throw new IOException("Google Drive returned an unexpected file size");
                            out.write(buffer,0,n);
                            if(count>=next){
                                progress(callback,count,EXPECTED_BYTES,"Downloading model…");
                                next=count+16L*1024*1024;
                            }
                        }
                    }
                    if(count!=EXPECTED_BYTES)
                        throw new IOException("Incomplete GGUF download ("+count+" of "+EXPECTED_BYTES+" bytes)");
                    return;
                }
            }finally{conn.disconnect();}
        }
        throw new IOException("Too many Google Drive download confirmation redirects");
    }
    private static String join(List<String> cookies){
        StringBuilder b=new StringBuilder();
        for(String cookie:cookies){if(b.length()>0)b.append("; ");b.append(cookie);}
        return b.toString();
    }
}
