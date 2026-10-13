package vendor.hct.hardware.talkie.V1_0;
import android.os.*;
import com.rumitx.app.RadioLog;
/** Reconstructed stock Android 8.1 callback binding, including stock hash chain. */
public interface ITalkiePortCallback extends android.hidl.base.V1_0.IBase {
 String kInterfaceName="vendor.hct.hardware.talkie@1.0::ITalkiePortCallback";
 void reportResults(int value) throws RemoteException;
 public abstract static class Stub extends HwBinder implements ITalkiePortCallback {
  private static final String DESC="vendor.hct.hardware.talkie@1.0::ITalkiePortCallback", BASE="android.hidl.base@1.0::IBase";

  public IHwBinder asBinder(){return this;}
  public java.util.ArrayList<String> interfaceChain(){return new java.util.ArrayList<>(java.util.Arrays.asList(DESC,BASE));}
  public String interfaceDescriptor(){return DESC;}
  public java.util.ArrayList<byte[]> getHashChain(){return new java.util.ArrayList<>(java.util.Arrays.asList(hex("b1769433c02574323dc641d27439e112e62e97a774d1c6ea82197dd622d5c663"),hex("bddab6184d7a346da6a07dc0828cf19a696f4caa3611c51f2e14565a14b40fd9")));}
  public void setHALInstrumentation(){}
  public void ping(){}
  public android.hidl.base.V1_0.DebugInfo getDebugInfo(){android.hidl.base.V1_0.DebugInfo info=new android.hidl.base.V1_0.DebugInfo();info.pid=android.os.Process.myPid();info.ptr=0;info.arch=0;return info;}
  public void notifySyspropsChanged(){}

  public IHwInterface queryLocalInterface(String descriptor){return DESC.equals(descriptor)?this:null;}
  public boolean linkToDeath(IHwBinder.DeathRecipient r,long cookie){return true;}
  public boolean unlinkToDeath(IHwBinder.DeathRecipient r){return true;}
  private void done(HwParcel reply){reply.writeStatus(0);reply.send();}
  public void onTransact(int code,HwParcel request,HwParcel reply,int flags) throws RemoteException {
   try { dispatch(code,request,reply,flags); }
   catch(RuntimeException e){RadioLog.e("RumiTX-HIDL","Callback decode failed code="+code+" flags="+flags,e);if((flags&1)==0){reply.writeStatus(Integer.MIN_VALUE);reply.send();}}
  }
  private void dispatch(int code,HwParcel request,HwParcel reply,int flags) throws RemoteException {
   RadioLog.i("RumiTX-HIDL","Callback onTransact code="+code+" flags="+flags+" thread="+Thread.currentThread().getName());
   boolean one=(flags&1)!=0;
   if(code==1){request.enforceInterface(DESC);int value=request.readInt32();RadioLog.i("RumiTX-HIDL","Callback received: reportResults("+value+")");try{reportResults(value);}finally{if(!one)done(reply);}return;}
   request.enforceInterface(BASE);
   if(one){if(code==256462420||code==257120595)return;return;}
   switch(code){
    case 256067662: reply.writeStatus(0);reply.writeStringVector(interfaceChain());reply.send();break;
    case 256131655: done(reply);break;
    case 256136003: reply.writeStatus(0);reply.writeString(interfaceDescriptor());reply.send();break;
    case 256398152:
     byte[][] hashes={hex("b1769433c02574323dc641d27439e112e62e97a774d1c6ea82197dd622d5c663"),hex("bddab6184d7a346da6a07dc0828cf19a696f4caa3611c51f2e14565a14b40fd9")};
     HwBlob head=new HwBlob(16),data=new HwBlob(64);head.putInt32(8,2);head.putBool(12,false);for(int i=0;i<2;i++)for(int k=0;k<32;k++)data.putInt8(i*32+k,hashes[i][k]);head.putBlob(0,data);reply.writeStatus(0);reply.writeBuffer(head);reply.send();break;
    case 256921159: done(reply);break;
    case 257049926: reply.writeStatus(0);getDebugInfo().writeToParcel(reply);reply.send();break;
    default:reply.writeStatus(Integer.MIN_VALUE);reply.send();
   }
  }
  private static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
 }
}
