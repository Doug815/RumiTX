package com.rumitx.app;

/** Android-free tests for repeater retrieval intent and radio command priority. */
public final class RepeaterQuestionTest {
    private static void check(boolean b,String message){
        if(!b)throw new AssertionError(message);
    }
    public static void main(String[] args){
        RepeaterQuestion rockford=RepeaterQuestion.parse("What repeaters are in Rockford Illinois");
        check(rockford!=null,"Rockford repeater question not detected");
        check("Rockford".equalsIgnoreCase(rockford.city),"Rockford city incorrectly parsed as "+rockford.city);
        check("IL".equals(rockford.state),"Illinois state missing");
        check(rockford.callsign==null&&rockford.frequency==null,"Phantom callsign/frequency");

        RepeaterQuestion beloit=RepeaterQuestion.parse("show me repeaters in Beloit, Wisconsin");
        check(beloit!=null&&"Beloit".equalsIgnoreCase(beloit.city)&&"WI".equals(beloit.state),
            "Beloit, Wisconsin city/state parser");
        RepeaterQuestion rockton=RepeaterQuestion.parse("repeaters near Rockton IL");
        check(rockton!=null&&"Rockton".equalsIgnoreCase(rockton.city),"Near-city parser");

        RepeaterQuestion callsign=RepeaterQuestion.parse("What's the frequency for K9RFD?");
        check(callsign!=null&&"K9RFD".equals(callsign.callsign),"Callsign lookup parser");
        check(callsign.city.isEmpty(),"Callsign must not be misread as a city");
        RepeaterQuestion freq=RepeaterQuestion.parse("what's on 442.650");
        check(freq!=null&&freq.frequency!=null&&Math.abs(freq.frequency-442.650)<.00001,
            "Numeric repeater frequency parser");

        check(RepeaterQuestion.parse("how are you") == null,"Casual chat must bypass repeater RAG");
        check(RepeaterQuestion.parse("hi there") == null,"Hello must bypass repeater RAG");
        check(RepeaterQuestion.parse("switch to channel 5")==null,"Channel switch must bypass repeater RAG");
        check(RadioIntentParser.parse("set frequency 462.5625, transmit on 467.5625, PL 141.3").type
            == RadioIntentParser.Type.FREQUENCY,"Tune command must be parsed before any lookup");
        check(RadioIntentParser.parse("transmit a test signal for 5 seconds").type
            == RadioIntentParser.Type.UNSUPPORTED,"Voice TX must stay forbidden");

        System.out.println("PASS: 12 repeater request and command-priority assertions");
    }
}
