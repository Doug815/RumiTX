package com.rumitx.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java classification of repeater/location lookups, not AI inference. */
public final class RepeaterQuestion {
    public final String city;
    public final String state;
    public final String callsign;
    public final Double frequency;
    public final String label;
    private RepeaterQuestion(String c,String s,String call,Double f,String l) {
        city=c;state=s;callsign=call;frequency=f;label=l;
    }
    private static final Pattern TRIGGER=Pattern.compile(
        "(?i)\\b(?:repeaters?|frequenc(?:y|ies)|what(?:'|’| i)?s\\s+on)\\b");
    private static final Pattern PLACE=Pattern.compile(
        "(?i)\\b(?:in|near|around|at|for)\\s+([a-z][a-z .,'’-]{0,65})");
    private static final Pattern BEFORE_REPEATER=Pattern.compile(
        "(?i)\\b([a-z][a-z .,'’-]{1,60}?)\\s+repeaters?\\b");
    private static final Pattern CALLSIGN=Pattern.compile(
        "(?i)\\b(?:[aknw][a-z]?[0-9][a-z]{1,4})\\b");
    private static final Pattern FREQ=Pattern.compile(
        "(?<![\\d.])(?:4[0-6][0-9](?:\\.[0-9]{1,6})?|470(?:\\.0+)?)(?![\\d.])");
    private RepeaterQuestion() {this("","",null,null,"");}

    public static RepeaterQuestion parse(String raw) {
        if(raw==null)return null;
        String q=raw.trim();
        if(q.isEmpty()||!TRIGGER.matcher(q).find())return null;
        Matcher freq=FREQ.matcher(q);
        Double target=freq.find()?Double.valueOf(freq.group()):null;
        Matcher call=CALLSIGN.matcher(q);
        String callsign=call.find()?call.group().toUpperCase(Locale.US):null;
        Matcher place=PLACE.matcher(q);
        String city="";
        if(place.find()) city=place.group(1).trim();
        else{
            Matcher before=BEFORE_REPEATER.matcher(q);
            if(before.find()) {
                city=before.group(1).replaceFirst(
                    "(?i)^(?:(?:hey|please|show|list|find|tell|me|the|all|what|which|are|is|any|some|local|nearby|about)\\s+)*","").trim();
            }
        }
        city=city.replaceFirst("(?i)\\s+(?:repeaters?|frequenc(?:y|ies)|on\\s+the\\s+radio).*?$","")
                 .replaceFirst("(?i)\\s+(?:please|right\\s+now|currently|today|available|near\\s+me)$","")
                 .replaceAll("[\\s,?!.'’]+$","").trim();
        String state="";
        String[] suffixes={"Illinois:IL","Wisconsin:WI","Indiana:IN","IL:IL","WI:WI","IN:IN"};
        for(String suffix:suffixes){
            String[] seg=suffix.split(":");
            if(city.matches("(?i).*\\b"+seg[0]+"$")){
                state=seg[1];city=city.replaceFirst("(?i)[, ]*\\b"+seg[0]+"$","").trim();break;
            }
        }
        if(city.length()>65)city=city.substring(0,65);
        String label=!city.isEmpty()?city+(state.isEmpty()?"":", "+state):
            callsign!=null?callsign:target!=null?String.format(Locale.US,"%.4f MHz",target):"the requested area";
        return new RepeaterQuestion(city,state,callsign,target,label);
    }
}
