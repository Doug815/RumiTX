package com.rumitx.app;

import java.util.Locale;

/** Pure-Java deterministic casual-chat routing; no JSON radio command parser invoked. */
public final class RumiChatRouter {
    private RumiChatRouter(){}
    public static String greeting(String question){
        if(question==null)return null;
        String q=question.toLowerCase(Locale.US).trim().replaceAll("[.!? ]+$","");
        if(q.matches("(?:hi|hello|hey|hi there|hello there|hey there|good morning|good afternoon|good evening)"))
            return "Hey there! I'm RumiTX. What would you like to talk about?";
        if(q.matches("(?:how are you|how's it going|hows it going|how are you doing)"))
            return "I'm here and ready! How's your day going?";
        return null;
    }
}
