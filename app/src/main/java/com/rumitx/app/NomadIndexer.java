package com.rumitx.app;

import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SAF-based Project Nomad text-document indexer.
 * Source documents remain on removable SD storage; only short searchable text
 * excerpts and their original URIs are written to the unified SQLite DB.
 * Does not request raw SD filesystem permissions.
 */
public final class NomadIndexer {
    private static final int MAX_FILES=80;
    private static final int MAX_CHUNKS=900;
    private static final int MAX_FILE_BYTES=220000;
    private static final int CHUNK_SIZE=650;
    private static final int CHUNK_OVERLAP=100;
    private NomadIndexer(){}
    public static String index(Context context,Uri treeUri)throws IOException{
        DocumentFile root=DocumentFile.fromTreeUri(context,treeUri);
        if(root==null||!root.isDirectory())throw new IOException("Selected Nomad folder is not accessible");
        List<DocumentFile> files=new ArrayList<>();
        walk(root,files,0);
        if(files.isEmpty())return "No supported text files found. Supported: .txt, .md, .csv, .json, .yaml, .log. PDFs need text export.";
        List<String[]> records=new ArrayList<>();
        int indexed=0,skipped=0;
        for(DocumentFile doc:files){
            if(records.size()>=MAX_CHUNKS)break;
            if(doc.length()>MAX_FILE_BYTES){skipped++;continue;}
            try(InputStream in=context.getContentResolver().openInputStream(doc.getUri())){
                if(in==null){skipped++;continue;}
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                byte[] buf=new byte[8192];int total=0,n;
                while((n=in.read(buf))!=-1){
                    total+=n;if(total>MAX_FILE_BYTES)throw new IOException("File exceeds text indexing cap");
                    out.write(buf,0,n);
                }
                String body=out.toString("UTF-8").replace('\u0000',' ').trim();
                if(body.isEmpty())continue;
                indexed++;
                for(int pos=0;pos<body.length()&&records.size()<MAX_CHUNKS;pos+=CHUNK_SIZE-CHUNK_OVERLAP){
                    int end=Math.min(pos+CHUNK_SIZE,body.length());
                    records.add(new String[]{doc.getName()==null?"Untitled Nomad document":doc.getName(),doc.getUri().toString(),body.substring(pos,end)});
                    if(end==body.length())break;
                }
            }catch(Exception e){skipped++;}
        }
        if(records.isEmpty())return "No readable text excerpts indexed; existing Nomad index unchanged.";
        try(ChannelDatabase db=new ChannelDatabase(context)){db.replaceNomadChunks(records);}
        return "Project Nomad indexed: "+indexed+" documents, "+records.size()+" searchable passages. "+skipped+" skipped; source files remain on SD.";
    }
    private static void walk(DocumentFile dir,List<DocumentFile> found,int depth){
        if(depth>5||found.size()>=MAX_FILES)return;
        for(DocumentFile file:dir.listFiles()){
            if(found.size()>=MAX_FILES)break;
            if(file.isDirectory())walk(file,found,depth+1);
            else if(file.isFile()){
                String name=file.getName();
                if(name==null)continue;
                name=name.toLowerCase(Locale.US);
                if(name.endsWith(".txt")||name.endsWith(".md")||name.endsWith(".csv")||name.endsWith(".json")||
                    name.endsWith(".yaml")||name.endsWith(".yml")||name.endsWith(".log"))
                    found.add(file);
            }
        }
    }
}
