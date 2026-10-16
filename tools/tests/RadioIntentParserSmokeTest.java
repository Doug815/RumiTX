package com.rumitx.app;

/** JDK-only radio-intent smoke tests; safe chat must never be treated as PTT. */
public final class RadioIntentParserSmokeTest {
    private static void check(boolean value,String text){
        if(!value)throw new AssertionError(text);
    }
    public static void main(String[] args){
        String hello=RumiChatRouter.greeting("hi");
        check(hello!=null && hello.contains("Hey there!") && !hello.contains("{"),"hi must respond as normal chat without JSON");
        check(RumiChatRouter.greeting("hi there")!=null,"hi there must reply immediately");
        check(RumiChatRouter.greeting("how are you")!=null,"casual small-talk must reply");
        check(RadioIntentParser.parse("hi").type==RadioIntentParser.Type.CHAT,"hi must be chat");
        check(RadioIntentParser.parse("hi there").type==RadioIntentParser.Type.CHAT,"hi there must be chat");
        check(RadioIntentParser.parse("how are you").type==RadioIntentParser.Type.CHAT,"casual chat must be chat");
        check(RadioIntentParser.parse("what's the frequency").type==RadioIntentParser.Type.CHAT,"question must be chat");
        RadioIntentParser.Intent channel=RadioIntentParser.parse("switch to channel 5");
        check(channel.type==RadioIntentParser.Type.CHANNEL_NUMBER && channel.channelNumber==5,"channel 5 parser");
        RadioIntentParser.Intent frequency=RadioIntentParser.parse("tune the radio to frequency 465.000 on receiving");
        check(frequency.type==RadioIntentParser.Type.FREQUENCY &&
            Math.abs(frequency.frequencyMHz-465)<0.00001,"fuzzy tune parser");
        check(RadioIntentParser.parse("transmit for 5 seconds").type==RadioIntentParser.Type.UNSUPPORTED,"PTT must stay denied");
        check(RadioIntentParser.parse("transmit on the current channel").type==RadioIntentParser.Type.UNSUPPORTED,"Voice TX not allowed");
        check(RadioIntentParser.parse("set frequency 462.5625, transmit on 467.5625, PL 141.3").type==RadioIntentParser.Type.FREQUENCY,"split repeater parser");
        System.out.println("PASS: casual chat and radio-intent parser safe routes (12 cases)");
    }
}
