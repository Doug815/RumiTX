// RumiTX S80 non-root JNI bridge — pinned to llama.cpp tag b11206.
// Runs entirely inside the app's native process on Android 8.1 ARMv8.0.
#include <jni.h>
#include <llama.h>
#include <ggml-backend.h>
#include <algorithm>
#include <mutex>
#include <atomic>
#include <memory>
#include <string>
#include <vector>
#include <stdexcept>
#include <cstring>
#include <android/log.h>

namespace {
struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::mutex mu;
    std::atomic<bool> cancelRequested{false};
    ~Engine() {if(ctx) llama_free(ctx);if(model) llama_model_free(model);}
};
void javaError(JNIEnv *env,const char *type,const std::string &text){
    jclass clazz=env->FindClass(type);
    if(clazz){env->ThrowNew(clazz,text.c_str());env->DeleteLocalRef(clazz);}
}
std::string jstr(JNIEnv *env,jstring s){
    if(!s) return {};
    const char *chars=env->GetStringUTFChars(s,nullptr);
    if(!chars)return {};
    std::string value(chars);
    env->ReleaseStringUTFChars(s,chars);
    return value;
}
void logErr(const char *str){__android_log_print(ANDROID_LOG_ERROR,"RumiTX-llama","%s",str);}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_rumitx_app_LlamaBridge_nativeInitModel(JNIEnv *env,jclass,jstring path){
    try {
        std::string p=jstr(env,path);
        if(p.empty()){javaError(env,"java/lang/IllegalArgumentException","Empty model path");return 0;}
        static std::once_flag initialized;
        std::call_once(initialized,[]{ggml_backend_load_all();});
        std::unique_ptr<Engine> e(new Engine());
        llama_model_params mp=llama_model_default_params();
        mp.n_gpu_layers=0;
        e->model=llama_model_load_from_file(p.c_str(),mp);
        if(!e->model)throw std::runtime_error("GGUF load failed or insufficient RAM");
        e->vocab=llama_model_get_vocab(e->model);
        llama_context_params cp=llama_context_default_params();
        cp.n_ctx=1024;cp.n_batch=256;cp.n_ubatch=128;
        // Reserve CPU for Android UI/audio. Four inference threads can starve the S80 display.
        cp.n_threads=2;cp.n_threads_batch=2;
        // Interrupt CPU graph evaluation promptly even during prompt prefill.
        cp.abort_callback=[](void *data)->bool{
            return reinterpret_cast<Engine*>(data)->cancelRequested.load(std::memory_order_relaxed);
        };
        cp.abort_callback_data=e.get();
        e->ctx=llama_init_from_model(e->model,cp);
        if(!e->ctx)throw std::runtime_error("Cannot allocate inference context");
        return (jlong)reinterpret_cast<intptr_t>(e.release());
    } catch(const std::exception &e) {
        logErr(e.what());javaError(env,"java/lang/IllegalStateException",e.what());return 0;
    } catch(...){
        javaError(env,"java/lang/IllegalStateException","Unknown native model loading error");return 0;
    }
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_rumitx_app_LlamaBridge_nativeGenerateStream(JNIEnv *env,jclass,jlong handle,jstring prompt,jint maxTokens,jobject listener){
    auto *e=reinterpret_cast<Engine*>(static_cast<intptr_t>(handle));
    if(!e){javaError(env,"java/lang/IllegalStateException","Model not loaded");return nullptr;}
    // The caller runs on a background executor; never call this method on Android's main thread.
    std::lock_guard<std::mutex> guard(e->mu);
    e->cancelRequested.store(false,std::memory_order_relaxed);
    try{
        jmethodID partialMethod=nullptr;
        if(listener){
            jclass cls=env->GetObjectClass(listener);
            partialMethod=env->GetMethodID(cls,"onPartial","([B)V");
            env->DeleteLocalRef(cls);
            if(!partialMethod)return nullptr;
        }
        std::string input=jstr(env,prompt);
        if(input.size()>12000)throw std::runtime_error("Prompt too long");
        llama_memory_clear(llama_get_memory(e->ctx),true);
        int maxOutput=std::max(1,std::min(128,(int)maxTokens));
        int need=llama_tokenize(e->vocab,input.data(),(int)input.size(),nullptr,0,true,true);
        if(need>=0)throw std::runtime_error("Unexpected tokenizer capacity result");
        need=-need;
        if(need<=0 || need>900)throw std::runtime_error("Prompt exceeds local 1024-token context");
        std::vector<llama_token> tokens((size_t)need);
        int got=llama_tokenize(e->vocab,input.data(),(int)input.size(),tokens.data(),need,true,true);
        if(got<=0)throw std::runtime_error("Tokenization failed");
        int pos=0;
        while(pos<got){
            if(e->cancelRequested.load(std::memory_order_relaxed))
                throw std::runtime_error("Generation cancelled by timeout");
            int count=std::min(256,got-pos);
            llama_batch batch=llama_batch_get_one(tokens.data()+pos,count);
            if(llama_decode(e->ctx,batch)!=0){
                if(e->cancelRequested.load(std::memory_order_relaxed))throw std::runtime_error("Generation cancelled by timeout");
                throw std::runtime_error("Prompt decode failed; memory pressure");
            }
            pos+=count;
        }
        llama_sampler_chain_params params=llama_sampler_chain_default_params();
        llama_sampler *sampler=llama_sampler_chain_init(params);
        if(!sampler)throw std::runtime_error("Sampler initialization failed");
        // Prefer concise stable responses on older 6GB S80 devices.
        llama_sampler_chain_add(sampler,llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler,llama_sampler_init_top_p(0.9f,1));
        llama_sampler_chain_add(sampler,llama_sampler_init_temp(0.6f));
        llama_sampler_chain_add(sampler,llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        std::string output;
        for(int n=0;n<maxOutput && pos<1023;n++){
            if(e->cancelRequested.load(std::memory_order_relaxed))break;
            llama_token next=llama_sampler_sample(sampler,e->ctx,-1);
            llama_sampler_accept(sampler,next);
            if(llama_vocab_is_eog(e->vocab,next))break;
            char local[256];
            int count=llama_token_to_piece(e->vocab,next,local,(int)sizeof(local),0,false);
            if(count<0){
                std::vector<char> extra((size_t)-count);
                count=llama_token_to_piece(e->vocab,next,extra.data(),(int)extra.size(),0,false);
                if(count>0)output.append(extra.data(),(size_t)count);
            }else if(count>0)output.append(local,(size_t)count);
            // Send the full UTF-8 byte prefix, not a potentially split Unicode token,
            // at a modest cadence; Java coalesces these onto the Android UI thread.
            // Notify Java every produced token (UI redraws are throttled there).
            if(partialMethod){
                jbyteArray bytes=env->NewByteArray((jsize)output.size());
                if(bytes){
                    env->SetByteArrayRegion(bytes,0,(jsize)output.size(),
                        reinterpret_cast<const jbyte*>(output.data()));
                    env->CallVoidMethod(listener,partialMethod,bytes);
                    env->DeleteLocalRef(bytes);
                    if(env->ExceptionCheck()){llama_sampler_free(sampler);return nullptr;}
                }
            }
            if(output.find("<|im_end|>")!=std::string::npos)break;
            llama_batch batch=llama_batch_get_one(&next,1);
            if(llama_decode(e->ctx,batch)!=0){
                if(!e->cancelRequested.load(std::memory_order_relaxed))logErr("Output decoding stopped");
                break;
            }
            pos++;
        }
        llama_sampler_free(sampler);
        if(e->cancelRequested.load(std::memory_order_relaxed))
            throw std::runtime_error("Generation cancelled by timeout");
        if(output.empty())output="I couldn't generate a response.";
        // Construct java.lang.String from UTF-8 bytes: llama tokens can contain
        // four-byte UTF-8 code points that NewStringUTF must NOT receive.
        jbyteArray bytes=env->NewByteArray((jsize)output.size());
        if(!bytes)return nullptr;
        env->SetByteArrayRegion(bytes,0,(jsize)output.size(),
            reinterpret_cast<const jbyte*>(output.data()));
        jclass stringClass=env->FindClass("java/lang/String");
        jmethodID ctor=env->GetMethodID(stringClass,"<init>","([BLjava/lang/String;)V");
        jstring utf8=env->NewStringUTF("UTF-8");
        jstring result=(jstring)env->NewObject(stringClass,ctor,bytes,utf8);
        env->DeleteLocalRef(bytes);env->DeleteLocalRef(utf8);env->DeleteLocalRef(stringClass);
        return result;
    }catch(const std::exception &ex){
        logErr(ex.what());javaError(env,"java/lang/IllegalStateException",ex.what());return nullptr;
    }catch(...){
        javaError(env,"java/lang/IllegalStateException","Native generation error");return nullptr;
    }
}
// No engine mutex here: cancellation must work while nativeGenerateStream holds it.
extern "C" JNIEXPORT void JNICALL
Java_com_rumitx_app_LlamaBridge_nativeCancelGeneration(JNIEnv *,jclass,jlong handle){
    auto *e=reinterpret_cast<Engine*>(static_cast<intptr_t>(handle));
    if(e)e->cancelRequested.store(true,std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rumitx_app_LlamaBridge_nativeFreeModel(JNIEnv *,jclass,jlong handle){
    auto *e=reinterpret_cast<Engine*>(static_cast<intptr_t>(handle));delete e;
}
