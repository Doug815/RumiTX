package com.rumitx.app;

import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded parser for Google Drive's large-file confirmation HTML.
 * It never follows download links outside Google-controlled HTTPS hosts.
 * Package-private so the same parser can be exercised by a plain JDK test.
 */
final class GoogleDriveConfirmation {
    private static final Pattern FORM=Pattern.compile("(?is)<form\\b([^>]*)>(.*?)</form\\s*>");
    private static final Pattern INPUT=Pattern.compile("(?is)<input\\b([^>]*)>");
    private static final Pattern LINK=Pattern.compile("(?is)<a\\b([^>]*)>");
    private static final Pattern ATTRIBUTE=Pattern.compile(
        "(?is)([a-zA-Z_][\\w:-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern TOKEN=Pattern.compile("(?i)(?:[?&]|&amp;)confirm=([A-Za-z0-9_-]+)");
    private GoogleDriveConfirmation(){}

    static boolean allowed(URL url){
        if(url==null || !"https".equalsIgnoreCase(url.getProtocol()))return false;
        String host=url.getHost().toLowerCase(Locale.US);
        return host.equals("drive.google.com") || host.equals("drive.usercontent.google.com") ||
               host.equals("docs.google.com") || host.equals("googleusercontent.com") ||
               host.endsWith(".googleusercontent.com");
    }

    static URL resolve(String html,URL current,String fileId) throws Exception {
        if(html==null||html.length()>262144||fileId==null||fileId.isEmpty())return null;
        Matcher forms=FORM.matcher(html);
        while(forms.find()){
            String action=attribute(forms.group(1),"action");
            if(action==null)continue;
            URL target=parseUrl(current,action);
            if(!allowed(target))continue;
            StringBuilder url=new StringBuilder(target.toExternalForm());
            Map<String,String> hidden=new LinkedHashMap<>();
            Matcher inputs=INPUT.matcher(forms.group(2));
            while(inputs.find()){
                String name=attribute(inputs.group(1),"name");
                String value=attribute(inputs.group(1),"value");
                if(name!=null&&value!=null&&name.length()<60&&value.length()<1024)
                    hidden.put(name,value);
            }
            if(!hidden.containsKey("id") && !hasId(target,fileId))continue;
            if(hidden.containsKey("id")&&!fileId.equals(hidden.get("id")))continue;
            for(Map.Entry<String,String> entry:hidden.entrySet()){
                url.append(url.indexOf("?")>=0?'&':'?');
                url.append(URLEncoder.encode(entry.getKey(),"UTF-8")).append('=')
                   .append(URLEncoder.encode(entry.getValue(),"UTF-8"));
            }
            URL result=new URL(url.toString());
            if(allowed(result) && hasId(result,fileId))return result;
        }
        Matcher links=LINK.matcher(html);
        while(links.find()){
            String href=attribute(links.group(1),"href");
            if(href==null||!href.contains("confirm"))continue;
            URL result=parseUrl(current,href);
            if(allowed(result)&&hasId(result,fileId))return result;
        }
        Matcher m=TOKEN.matcher(html);
        if(m.find()){
            String base="https://drive.google.com/uc?export=download&id="+
                URLEncoder.encode(fileId,"UTF-8")+"&confirm="+
                URLEncoder.encode(m.group(1),"UTF-8");
            return new URL(base);
        }
        return null;
    }

    private static boolean hasId(URL url,String fileId){
        if(url.getQuery()==null)return false;
        for(String part:url.getQuery().split("&")){
            if(part.equals("id="+fileId))return true;
        }
        return false;
    }

    private static URL parseUrl(URL current,String href) throws Exception {
        // Avoid javascript:, data:, HTTP downgrades, protocol-relative external URLs.
        String cleaned=unescape(href).trim();
        if(cleaned.startsWith("javascript:")||cleaned.startsWith("data:"))return null;
        URL u=new URL(current,cleaned);
        return allowed(u)?u:null;
    }

    private static String attribute(String tag,String name){
        Matcher matcher=ATTRIBUTE.matcher(tag);
        while(matcher.find()){
            if(!name.equalsIgnoreCase(matcher.group(1)))continue;
            for(int i=2;i<=4;i++)if(matcher.group(i)!=null)return unescape(matcher.group(i));
        }
        return null;
    }

    private static String unescape(String s){
        return s.replace("&amp;","&").replace("&#38;","&")
                .replace("&quot;","\"").replace("&#39;","'")
                .replace("&lt;","<").replace("&gt;",">");
    }
}
