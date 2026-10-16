package com.rumitx.app;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local deterministic parser: NO model-generated text may execute radio commands. */
public final class RadioIntentParser {
    public enum Type { CHAT, FREQUENCY, CHANNEL_NUMBER, CHANNEL_NAME, UNSUPPORTED }
    public static final class Intent {
        public final Type type;
        public final double frequencyMHz,txFrequencyMHz,plHz;
        public final String dcsCode,channelName,error;
        public final int channelNumber;
        public final boolean toneSpecified,txSpecified;
        private Intent(Type t,double rx,double tx,double pl,String dcs,int n,String name,String err,boolean tone,boolean split){
            type=t;frequencyMHz=rx;txFrequencyMHz=tx;plHz=pl;dcsCode=dcs;channelNumber=n;channelName=name;error=err;toneSpecified=tone;txSpecified=split;
        }
    }
    private static Intent of(Type type){return new Intent(type,0,0,0,null,0,"",null,false,false);}
    private static Intent invalid(String error){return new Intent(Type.UNSUPPORTED,0,0,0,null,0,"",error,false,false);}
    private static final Pattern ACTION=Pattern.compile("\\b(?:tune|set|change|program|switch(?:\\s+to)?|go\\s+to|put\\s+on|dial\\s+in|select)\\b");
    private static final Pattern FREQ=Pattern.compile("(?<![0-9.])([0-9]{3}\\.[0-9]{1,6})(?![0-9.])");
    private static final Pattern TX=Pattern.compile("\\b(?:transmit(?:ting)?\\s+(?:on|frequency|freq)(?:\\s+to)?|tx(?:\\s+frequency|\\s+freq)?(?:\\s+on|\\s+to)?)\\s*[:=]?\\s*([0-9]{3}\\.[0-9]{1,6})\\s*(?:mhz)?");
    private static final Pattern PL=Pattern.compile("\\b(?:pl(?:\\s+tone)?|ctcss|(?<!dcs )tone)\\s*(?:of|at|is|=|:)?\\s*([0-9]{2,3}(?:\\.[0-9]+)?)\\s*(?:hz)?\\b");
    private static final Pattern DCS=Pattern.compile("\\b(?:dcs(?:\\s+tone)?)\\s*(?:of|at|is|=|:)?\\s*(?:d)?([0-9]{2,3})\\b");
    private static final Pattern DENY_TX=Pattern.compile("^(?:please\\s+)?(?:transmit|key(?:\\s+up)?|ptt|send\\s+(?:a\\s+)?(?:test\\s+)?signal|broadcast)\\b.*");
    private static final Pattern CHANNEL=Pattern.compile("\\bchannel\\s+(?:number\\s+)?(?:to\\s+)?([0-9]{1,3})\\b");
    private static final String[] VALID_DCS=("023 025 026 031 032 036 043 047 051 053 054 065 071 072 073 074 114 115 116 122 125 127 131 132 134 143 145 152 155 156 162 165 172 174 205 223 225 226 243 244 245 246 251 252 255 261 263 265 266 271 274 306 311 315 325 331 332 343 346 351 356 364 365 371 411 412 413 423 431 432 445 446 452 454 455 462 464 465 466 503 506 516").split(" ");
    private static final double[] VALID_PL={67.0,69.3,71.9,74.4,77.0,79.7,82.5,85.4,88.5,91.5,94.8,97.4,100.0,103.5,107.2,110.9,114.8,118.8,123.0,127.3,131.8,136.5,141.3,146.2,151.4,156.7,159.8,162.2,165.5,167.9,171.3,173.8,177.3,179.9,183.5,186.2,189.9,192.8,196.6,199.5,203.5,206.5,210.7,218.1,225.7,229.1,233.6,241.8,250.3,254.1};
    private RadioIntentParser(){}
    private static boolean validFreq(double f){return Double.isFinite(f)&&f>=400.0&&f<=470.0;}
    private static String clean(String s){
        return s.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+"," ").replaceAll("[?!]+$","").replaceAll("\\.$","");
    }
    public static Intent parse(String text){
        if(text==null||text.trim().isEmpty())return of(Type.CHAT);
        String s=clean(text);
        s=s.replaceFirst("^(?:hey\\s+)?rumitx[,!]?\\s*","");
        s=s.replaceFirst("^(?:(?:please|can you|could you|would you)\\s+)+","");
        if(DENY_TX.matcher(s).matches())return invalid("I can't key the transmitter by voice command.");
        // Questions about operating the radio are not commands to operate it.
        if(s.matches("^(?:how|why|what|when|where|should i|is it|do i)\\b.*"))return of(Type.CHAT);
        if(s.matches("^(?:please\\s+)?(?:tune|set|change|program|switch|go|select)\\s+(?:the\\s+)?(?:squelch|volume|bandwidth|power|talkgroup|timeslot)\\b.*"))return invalid("This radio setting is not supported by voice yet. No change made.");
        Matcher verb=ACTION.matcher(s);
        if(!verb.find())return of(Type.CHAT);
        Matcher channel=CHANNEL.matcher(s);
        if(channel.find()){
            int n=Integer.parseInt(channel.group(1));
            return new Intent(Type.CHANNEL_NUMBER,0,0,0,null,n,"",null,false,false);
        }
        // Stage 2: parameters have independent spans; TX frequency is excluded from the RX candidate.
        Matcher txM=TX.matcher(s);
        Double tx=null;int txStart=-1,txEnd=-1;
        if(txM.find()){
            tx=Double.parseDouble(txM.group(1));txStart=txM.start(1);txEnd=txM.end(1);
            if(!validFreq(tx))return invalid(String.format(Locale.US,"TX frequency %.4f MHz is outside 400–470 MHz. No change made.",tx));
        }
        Matcher frequency=FREQ.matcher(s);
        Double rx=null;
        while(frequency.find()){
            if(tx!=null&&frequency.start(1)>=txStart&&frequency.end(1)<=txEnd)continue;
            rx=Double.parseDouble(frequency.group(1));break;
        }
        Matcher plM=PL.matcher(s),dcsM=DCS.matcher(s);
        boolean plPresent=plM.find(),dcsPresent=dcsM.find();
        if(plPresent&&dcsPresent)return invalid("Specify PL or DCS, not both. No change made.");
        double pl=0;String dcs=null;
        if(plPresent){
            pl=Double.parseDouble(plM.group(1));
            boolean okay=false;for(double value:VALID_PL){if(Math.abs(pl-value)<0.001){okay=true;break;}}
            if(!okay)return invalid(String.format(Locale.US,"%.1f Hz is not a standard PL tone. No change made.",pl));
        }
        if(dcsPresent){
            dcs=String.format(Locale.US,"%03d",Integer.parseInt(dcsM.group(1)));
            boolean okay=false;for(String value:VALID_DCS){if(value.equals(dcs)){okay=true;break;}}
            if(!okay)return invalid("DCS "+dcs+" is not supported by the radio's tone table. No change made.");
        }
        if(rx!=null){
            if(!validFreq(rx))return invalid(String.format(Locale.US,"RX frequency %.4f MHz is outside 400–470 MHz. No change made.",rx));
            return new Intent(Type.FREQUENCY,rx,tx==null?rx:tx,pl,dcs,0,"",null,plPresent||dcsPresent,tx!=null);
        }
        if(tx!=null)return invalid("A receive frequency is required as well as TX. No change made.");
        if(plPresent||dcsPresent)return invalid("Specify a frequency with the tone. No change made.");
        String tail=s.substring(verb.end()).trim();
        tail=tail.replaceFirst("^(?:(?:the|my|this|radio|receiver|on|to|at|frequency|freq|channel|number)\\s+)*","");
        tail=tail.replaceAll("\\s+(?:please|now)$","").trim();
        if(tail.matches("[0-9]{1,2}")){
            int n=Integer.parseInt(tail);
            return new Intent(Type.CHANNEL_NUMBER,0,0,0,null,n,"",null,false,false);
        }
        if(tail.matches("[a-z0-9][a-z0-9 _/-]{0,60}"))
            return new Intent(Type.CHANNEL_NAME,0,0,0,null,0,tail,null,false,false);
        return invalid("Couldn't identify a valid channel or frequency. No change made.");
    }
}
