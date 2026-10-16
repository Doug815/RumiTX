import com.rumitx.app.RadioIntentParser;
import com.rumitx.app.RadioIntentParser.Type;
public final class RadioIntentParserTest {
    private static void check(String s,Type t,double f,int n,String name){
        RadioIntentParser.Intent got=RadioIntentParser.parse(s);
        if(got.type!=t || (t==Type.FREQUENCY && Math.abs(got.frequencyMHz-f)>0.0000001) ||
           (t==Type.CHANNEL_NUMBER && got.channelNumber!=n) ||
           (t==Type.CHANNEL_NAME && !name.equals(got.channelName))) {
            throw new AssertionError(s+" => "+got.type+" "+got.frequencyMHz+" "+got.channelNumber+" "+got.channelName+" expected "+t);
        }
        System.out.println("PASS  "+s+" -> "+got.type);
    }
    public static void main(String[] args){
        check("hi there",Type.CHAT,0,0,"");
        check("what is CTCSS?",Type.CHAT,0,0,"");
        check("change the frequency to 460.0000",Type.FREQUENCY,460.0000,0,"");
        check("tune my radio to frequency 460.2500",Type.FREQUENCY,460.2500,0,"");
        check("please tune to 446.00625 MHz",Type.FREQUENCY,446.00625,0,"");
        check("set frequency 462.5625",Type.FREQUENCY,462.5625,0,"");
        check("set my radio to 460.0000",Type.FREQUENCY,460.0,0,"");
        check("switch to channel 5",Type.CHANNEL_NUMBER,0,5,"");
        check("set channel to 7",Type.CHANNEL_NUMBER,0,7,"");
        check("tune to bpd",Type.CHANNEL_NAME,0,0,"bpd");
        check("tune the radio to BPD Dispatch",Type.CHANNEL_NAME,0,0,"bpd dispatch");
        check("change to channel sheriff",Type.CHANNEL_NAME,0,0,"sheriff");
        check("transmit on 460.2500",Type.UNSUPPORTED,0,0,"");
        check("set squelch to 3",Type.UNSUPPORTED,0,0,"");
        check("tune",Type.UNSUPPORTED,0,0,"");
        check("set frequency to 300 MHz",Type.FREQUENCY,300,0,"");
        System.out.println("All parser assertions passed.");
    }
}
