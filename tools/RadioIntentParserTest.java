import com.rumitx.app.RadioIntentParser;
import com.rumitx.app.RadioIntentParser.Type;
public final class RadioIntentParserTest {
    private static void check(String s,Type t,double rx,double tx,double pl,String dcs,int n,String name,String error){
        RadioIntentParser.Intent x=RadioIntentParser.parse(s);
        if(x.type!=t||(t==Type.FREQUENCY&&(Math.abs(x.frequencyMHz-rx)>0.0000001||Math.abs(x.txFrequencyMHz-tx)>0.0000001||Math.abs(x.plHz-pl)>0.001||!(dcs==null?x.dcsCode==null:dcs.equals(x.dcsCode))))||
           (t==Type.CHANNEL_NUMBER&&x.channelNumber!=n)||(t==Type.CHANNEL_NAME&&!name.equals(x.channelName))||
           (error!=null&&(x.error==null||!x.error.contains(error))))
            throw new AssertionError(s+" parsed "+x.type+" RX "+x.frequencyMHz+" TX "+x.txFrequencyMHz+" PL "+x.plHz+" DCS "+x.dcsCode+" channel "+x.channelName+" err "+x.error);
        System.out.println("PASS "+s+" => "+t);
    }
    private static void freq(String s,double rx,double tx,double pl,String dcs){check(s,Type.FREQUENCY,rx,tx,pl,dcs,0,"",null);}
    private static void deny(String s,String message){check(s,Type.UNSUPPORTED,0,0,0,null,0,"",message);}
    public static void main(String[] args){
        check("hi there",Type.CHAT,0,0,0,null,0,"",null);
        check("how to program 465.000 MHz?",Type.CHAT,0,0,0,null,0,"",null);
        freq("Tune the radio to frequency 465.000 on receiving",465,465,0,null);
        freq("Program the radio to 465.000",465,465,0,null);
        freq("Set 465.000 with PL 131.8",465,465,131.8,null);
        freq("Tune to frequency 460.0000",460,460,0,null);
        check("Switch to channel 5",Type.CHANNEL_NUMBER,0,0,0,null,5,"",null);
        deny("Transmit a test signal for 5 seconds","can't key");
        deny("Transmit on the current channel","can't key");
        freq("Set frequency 462.5625, transmit on 467.5625, PL 141.3",462.5625,467.5625,141.3,null);
        freq("Program frequency 465.000, transmit on 460.000, PL tone 131.8",465,460,131.8,null);
        freq("Go to 460.000",460,460,0,null);
        freq("please tune to 446.00625 MHz",446.00625,446.00625,0,null);
        freq("dial in 455.1000 for RX",455.1,455.1,0,null);
        freq("put on 460.300 on receiving",460.3,460.3,0,null);
        freq("program to frequency 465.000 DCS 023",465,465,0,"023");
        freq("program TX 460.000 RX 465.000",465,460,0,null);
        check("tune to bpd",Type.CHANNEL_NAME,0,0,0,null,0,"bpd",null);
        check("tune the radio to BPD Dispatch",Type.CHANNEL_NAME,0,0,0,null,0,"bpd dispatch",null);
        deny("set 465.000 with PL 131.9","not a standard PL");
        deny("set 465.000 with DCS 999","not supported");
        deny("set 465.000 with PL 131.8 DCS 023","Specify PL or DCS");
        deny("set frequency to 300 MHz","outside");
        deny("set frequency to 475.000","outside");
        deny("set frequency TX 460.000","receive frequency");
        check("set squelch to 3",Type.CHANNEL_NAME,0,0,0,null,0,"squelch to 3",null);
        System.out.println("All RumiTX fuzzy parser tests passed");
    }
}
