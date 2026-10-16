package com.rumitx.app;

import java.net.URL;

/** Plain Java regression tests for Drive's HTML confirmation and redirect safety. */
public final class GoogleDriveConfirmationTest {
    private static final String ID="1xVuMrUCNjA5W-OVy8lpBIHOlOCeOnwti";
    private static final URL ORIGIN;
    static{
        try{ORIGIN=new URL("https://drive.google.com/uc?export=download&confirm=t&id="+ID);}
        catch(Exception e){throw new RuntimeException(e);}
    }
    private static void verify(boolean b,String message){
        if(!b)throw new AssertionError(message);
    }
    public static void main(String[] ignored)throws Exception{
        String form="<html><form id=\"download-form\" action=\"https://drive.usercontent.google.com/download\" method=\"get\">"+
                "<input type=\"hidden\" name=\"id\" value=\""+ID+"\">"+
                "<input type=\"hidden\" name=\"export\" value=\"download\">"+
                "<input name='confirm' type='hidden' value='t'>"+
                "<input type=\"hidden\" name=\"uuid\" value=\"a-unique-token\"></form></html>";
        URL action=GoogleDriveConfirmation.resolve(form,ORIGIN,ID);
        verify(action!=null,"Drive confirmation form must be parsed");
        verify(action.toString().contains("confirm=t"),"Form confirmation token missing");
        verify(action.toString().contains("uuid=a-unique-token"),"Form UUID missing");
        verify(action.toString().contains("id="+ID),"Form file ID missing");
        String anchor="<a href='https://drive.google.com/uc?export=download&amp;confirm=hello123&amp;id="+ID+"'>download</a>";
        URL link=GoogleDriveConfirmation.resolve(anchor,ORIGIN,ID);
        verify(link!=null&&link.toString().contains("confirm=hello123"),"Encoded HTML href not decoded");
        verify(GoogleDriveConfirmation.resolve("x?confirm=abc123",ORIGIN,ID).toString().contains("confirm=abc123"),"Token fallback");
        String hostile="<form action='https://evil.example/upload'>"+
                "<input name='id' value='"+ID+"'></form>";
        verify(GoogleDriveConfirmation.resolve(hostile,ORIGIN,ID)==null,"Untrusted host accepted");
        String other="<form action='https://drive.usercontent.google.com/download'>"+
                "<input name='id' value='different'></form>";
        verify(GoogleDriveConfirmation.resolve(other,ORIGIN,ID)==null,"Wrong file ID accepted");
        verify(!GoogleDriveConfirmation.allowed(new URL("http://drive.google.com/uc")),"HTTP downgrade accepted");
        verify(!GoogleDriveConfirmation.allowed(new URL("https://drive.google.com.evil.example/uc")),"Host suffix spoof accepted");
        verify(GoogleDriveConfirmation.allowed(new URL("https://doc-5.googleusercontent.com/download")),"Google content CDN blocked");
        System.out.println("GoogleDriveConfirmation: 9 regression assertions PASS");
    }
}
