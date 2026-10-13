package com.rumitx.app;
import android.os.*;
import android.util.Log;
/** Android 8.1 HIDL transport. Platform classes are compile-only, never bundled. */
public final class TalkieHidlClient {
 private static final String IFACE="vendor.hct.hardware.talkie@1.0::ITalkiePort";
 private IHwBinder service;
 private static boolean callbackPumpStarted;
 private static synchronized void startCallbackPump(){
  if(callbackPumpStarted)return;
  callbackPumpStarted=true;
  Thread pump=new Thread(() -> {
   RadioLog.i("RumiTX-HIDL","Callback RPC thread joining HwBinder pool");
   try {HwBinder.joinRpcThreadpool();RadioLog.e("RumiTX-HIDL","Callback RPC pool unexpectedly returned");}
   catch(Throwable e){RadioLog.e("RumiTX-HIDL","Callback RPC pool failed",e);}
  },"RumiTX-hwbinder-callback");
  pump.setDaemon(true);pump.start();
 }
 private volatile int callbackTransactions;
 public int callbackTransactions(){return callbackTransactions;}

 private vendor.hct.hardware.talkie.V1_0.ITalkiePortCallback.Stub retainedCallback;
 public boolean findService() {
  try { service=(IHwBinder)Class.forName("android.os.HwBinder").getMethod("getService",String.class,String.class).invoke(null,IFACE,"default"); if(service!=null){HwParcel req=new HwParcel(),rep=new HwParcel();try{req.writeInterfaceToken("android.hidl.base@1.0::IBase");service.transact(256067662,req,rep,0);rep.verifySuccess();java.util.ArrayList<String> chain=rep.readStringVector();RadioLog.i("RumiTX-HIDL","Vendor interfaceChain="+chain);if(!chain.contains(IFACE))throw new IllegalStateException("Wrong vendor interface");}finally{req.releaseTemporaryStorage();req.release();rep.release();}}return service!=null; }
  catch(Exception e){RadioLog.e("RumiTX-HIDL","Service lookup failed",e);return false;}
 }
 private Integer call(int code) {
  HwParcel request=new HwParcel(),reply=new HwParcel();
  try { if(service==null)throw new IllegalStateException("Service absent"); request.writeInterfaceToken(IFACE);service.transact(code,request,reply,0);reply.verifySuccess();request.releaseTemporaryStorage();int rc=reply.readInt32();RadioLog.i("RumiTX-HIDL","transaction "+code+" returned "+rc);return rc; }
  catch(Exception e){RadioLog.e("RumiTX-HIDL","transaction "+code+" failed",e);return null;}
  finally{request.release();reply.release();}
 }
 public Integer init(){return call(1);}
 public Integer appStart(){throw new UnsupportedOperationException("Helper owns APP_START");}
 public Integer speakDisable(){return call(7);}
 public Integer appExit(){throw new UnsupportedOperationException("Helper owns APP_EXIT");}
 public Integer deinit(){return call(2);}
 public interface SpeakEnableCallback {void onResult(int value);}
 public void submitSpeakEnable(SpeakEnableCallback callback) throws Exception {
  if(service==null)throw new IllegalStateException("Service absent");
  startCallbackPump();
  retainedCallback=new vendor.hct.hardware.talkie.V1_0.ITalkiePortCallback.Stub(){
   public void reportResults(int value){callback.onResult(value);}
   public void onTransact(int code,HwParcel request,HwParcel reply,int flags)throws RemoteException{callbackTransactions++;super.onTransact(code,request,reply,flags);}
  };
  RadioLog.i("RumiTX-HIDL","Callback retained, descriptor="+vendor.hct.hardware.talkie.V1_0.ITalkiePortCallback.kInterfaceName+" hashes match stock");
  HwParcel request=new HwParcel(),reply=new HwParcel();
  try {request.writeInterfaceToken(IFACE);request.writeStrongBinder(retainedCallback.asBinder());service.transact(9,request,reply,IHwBinder.FLAG_ONEWAY);request.releaseTemporaryStorage();RadioLog.i("RumiTX-HIDL","speak_enable submitted with ITalkiePortCallback");}
  finally{request.release();reply.release();}
 }
}
