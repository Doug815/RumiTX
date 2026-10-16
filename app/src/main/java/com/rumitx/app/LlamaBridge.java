package com.rumitx.app;

/** In-process ARM64 llama.cpp. All calls must be made from an app worker thread. */
public final class LlamaBridge implements AutoCloseable {
    static { System.loadLibrary("rumitx_llama_jni"); }
    /** The native engine reports the complete UTF-8 prefix after each few decoded tokens. */
    public interface TokenListener { void onPartial(byte[] utf8); }
    private long handle;
    public synchronized void initModel(String path) {
        if(handle!=0)return;
        if(path==null||path.isEmpty())throw new IllegalArgumentException("Model path missing");
        long nativeHandle=nativeInitModel(path);
        if(nativeHandle==0)throw new IllegalStateException("Cannot load model or allocate inference context");
        handle=nativeHandle;
    }
    public synchronized String generate(String prompt,int maxTokens) {
        return generateStreaming(prompt,maxTokens,null);
    }
    public synchronized String generateStreaming(String prompt,int maxTokens,TokenListener listener){
        if(handle==0)throw new IllegalStateException("Model not loaded");
        if(prompt==null||prompt.trim().isEmpty())throw new IllegalArgumentException("Prompt is empty");
        return nativeGenerateStream(handle,prompt,Math.max(1,Math.min(maxTokens,128)),listener);
    }
    public synchronized boolean isLoaded(){return handle!=0;}
    @Override public synchronized void close(){
        if(handle!=0){nativeFreeModel(handle);handle=0;}
    }
    private static native long nativeInitModel(String path);
    private static native String nativeGenerateStream(long handle,String prompt,int maxTokens,TokenListener listener);
    private static native void nativeFreeModel(long handle);
}