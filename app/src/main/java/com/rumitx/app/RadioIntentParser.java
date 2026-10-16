package com.rumitx.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict local parser for radio commands.
 * The LLM NEVER decides whether a real radio action was taken.
 * No external dependencies; tested on a plain JDK as well as Android.
 */
public final class RadioIntentParser {
    public enum Type { CHAT, FREQUENCY, CHANNEL_NUMBER, CHANNEL_NAME, UNSUPPORTED }
    public static final class Intent {
        public final Type type;
        public final double frequencyMHz;
        public final int channelNumber;
        public final String channelName;
        private Intent(Type type,double f,int n,String name){
            this.type=type;this.frequencyMHz=f;this.channelNumber=n;this.channelName=name;
        }
    }
    private static Intent of(Type t){return new Intent(t,0,0,"");}
    private static final Pattern PREFIX=Pattern.compile("^(?:(?:hey\\s+)?rumitx[,!]?\\s*)?(?:(?:please|can you|could you|would you)\\s+)*");
    private static final Pattern ACTION=Pattern.compile("^(tune|set|change|switch|go|program|select)\\b\\s*(.*)$");
    private static final Pattern NUMERIC=Pattern.compile("^([0-9]+(?:\\.[0-9]{1,6})?)(?:\\s*mhz)?$");
    private static final Pattern FORBIDDEN_ACTION=Pattern.compile("^(?:transmit|key|ptt|send|broadcast)\\b.*");
    private static final Pattern UNSUPPORTED_SETTING=Pattern.compile("^(?:volume|squelch|ctcss|dcs|tone|power|bandwidth|talkgroup|timeslot)\\b.*");

    private RadioIntentParser(){}

    public static Intent parse(String input) {
        if(input==null)return of(Type.CHAT);
        String s=input.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+"," ");
        s=s.replaceAll("[.!?]+$","").trim();
        s=PREFIX.matcher(s).replaceFirst("");
        if(FORBIDDEN_ACTION.matcher(s).matches())return of(Type.UNSUPPORTED);
        Matcher a=ACTION.matcher(s);
        if(!a.matches())return of(Type.CHAT);
        String tail=a.group(2);
        if(tail.isEmpty())return of(Type.UNSUPPORTED);
        if(UNSUPPORTED_SETTING.matcher(tail).matches())return of(Type.UNSUPPORTED);
        boolean channel=Pattern.compile("\\bchannel\\b").matcher(tail).find();
        // Only remove grammatical prefixes. Names like "BPD Dispatch" remain intact.
        for(int i=0;i<12;i++){
            String next=tail.replaceFirst("^(?:my|the|this|radio|receiver|to|on|at|frequency|freq|channel)\\s+","");
            if(next.equals(tail))break;
            tail=next;
        }
        tail=tail.trim();
        if(tail.isEmpty())return of(Type.UNSUPPORTED);
        if(UNSUPPORTED_SETTING.matcher(tail).matches())return of(Type.UNSUPPORTED);
        Matcher m=NUMERIC.matcher(tail);
        if(m.matches()){
            String raw=m.group(1);
            try{
                double n=Double.parseDouble(raw);
                if(channel && raw.matches("[0-9]+") && n>=1 && n<=999)
                    return new Intent(Type.CHANNEL_NUMBER,0,(int)n,"");
                // Bare small numbers mean channel indices; three-digit entries are MHz.
                if(!channel && raw.matches("[0-9]{1,2}") && n>=1)
                    return new Intent(Type.CHANNEL_NUMBER,0,(int)n,"");
                return new Intent(Type.FREQUENCY,n,0,"");
            }catch(NumberFormatException e){return of(Type.UNSUPPORTED);}
        }
        if(tail.matches("[a-z0-9][a-z0-9 _/-]{0,60}"))
            return new Intent(Type.CHANNEL_NAME,0,0,tail.trim());
        return of(Type.UNSUPPORTED);
    }
}
