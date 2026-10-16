package com.rumitx.app;

/**
 * On-device llama.cpp JNI bridge. The linker loads libllama and libggml* through
 * librumitx_llama_jni.so DT_NEEDED dependencies in the app's own nativeLibraryDir.
 * No root, su, shell commands, extracted executables or ADB required.
 */
public final class LlamaBridge implements AutoCloseable {
    static {
        System.loadLibrary("rumitx_llama_jni");
    }
    private long handle;
    public synchronized void initModel(String modelPath) {
        if(handle!=0) return;
        if(modelPath==null||modelPath.isEmpty())throw new IllegalArgumentException("Model file missing");
        long instance=nativeInitModel(modelPath);
        if(instance==0)throw new IllegalStateException("Unable to load model; check free RAM and GGUF file");
        handle=instance;
    }
    public synchronized String generate(String prompt,int maxTokens) {
        if(handle==0)throw new IllegalStateException("Load the model before sending a message");
        if(prompt==null||prompt.isEmpty())throw new IllegalArgumentException("Prompt cannot be blank");
        return nativeGenerate(handle,prompt,Math.max(1,Math.min(maxTokens,256)));
    }
    @Override public synchronized void close() {
        if(handle!=0){nativeFreeModel(handle);handle=0;}
    }
    public synchronized boolean isLoaded(){return handle!=0;}
    private static native long nativeInitModel(String path);
    private static native String nativeGenerate(long handle,String prompt,int maxTokens);
    private static native void nativeFreeModel(long handle);
}