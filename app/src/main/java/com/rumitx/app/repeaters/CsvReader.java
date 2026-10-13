package com.rumitx.app.repeaters;
import java.io.*;import java.util.*;
/** Streaming RFC 4180 reader: BOM, quoted commas/newlines, escaped quotes and CRLF. */
public final class CsvReader implements Closeable {
 private final PushbackReader in;private boolean first=true;
 public CsvReader(Reader r){in=new PushbackReader(r,2);}
 public java.util.List<String> next()throws IOException {List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false,afterQuote=false,seen=false;
  while(true){int n=in.read();if(first){first=false;if(n==0xfeff)n=in.read();}if(n<0){if(quoted)throw new IOException("Unclosed CSV quote");if(!seen&&row.isEmpty()&&cell.length()==0)return null;row.add(cell.toString());return row;}seen=true;char c=(char)n;
   if(quoted){if(c=='"'){int peek=in.read();if(peek=='"')cell.append('"');else{quoted=false;afterQuote=true;if(peek>=0)in.unread(peek);}}else cell.append(c);}
   else if(c==','){row.add(cell.toString());cell.setLength(0);afterQuote=false;if(row.size()>200)throw new IOException("Too many CSV columns");}
   else if(c=='\n'||c=='\r'){if(c=='\r'){int peek=in.read();if(peek!='\n'&&peek>=0)in.unread(peek);}row.add(cell.toString());return row;}
   else if(c=='"'&&cell.length()==0&&!afterQuote)quoted=true;
   else if(afterQuote){if(c!=' '&&c!='\t')throw new IOException("Unexpected text after quoted CSV cell");}
   else cell.append(c);
   if(cell.length()>32768)throw new IOException("CSV cell exceeds limit");
  }
 }
 public void close()throws IOException{in.close();}
}
