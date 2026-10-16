package com.rumitx.app;

import java.util.ArrayDeque;
import java.util.Deque;

/** Compact rolling conversation context for a 1024-token on-device S80 model. */
public final class RumiChatContext {
    private final Deque<String[]> turns=new ArrayDeque<>();
    private static final int MAX_TURNS=4;
    private static final int MAX_HISTORY_CHARS=1300;
    private static final int MAX_USER_CHARS=220;
    private static final int MAX_ASSISTANT_CHARS=340;

    private static String safe(String s,int limit){
        if(s==null)return "";
        String trimmed=s.trim().replace("<|im_","[im_").replace("\u0000","");
        return trimmed.length()>limit?trimmed.substring(0,limit):trimmed;
    }
    public synchronized void add(String user,String assistant){
        turns.addLast(new String[]{safe(user,MAX_USER_CHARS),safe(assistant,MAX_ASSISTANT_CHARS)});
        while(turns.size()>MAX_TURNS||historySize()>MAX_HISTORY_CHARS)turns.removeFirst();
    }
    private int historySize(){
        int size=0;
        for(String[] pair:turns)size+=pair[0].length()+pair[1].length();
        return size;
    }
    public synchronized int size(){return turns.size();}
    public synchronized void clear(){turns.clear();}
    public synchronized String render(){
        StringBuilder s=new StringBuilder();
        for(String[] pair:turns){
            s.append("<|im_start|>user\n").append(pair[0]).append("<|im_end|>\n");
            s.append("<|im_start|>assistant\n").append(pair[1]).append("<|im_end|>\n");
        }
        return s.toString();
    }
}
