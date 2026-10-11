package com.rumitx.recovery;

public final class BootCoordinatorTest {
    static final class HelperFake implements BootCoordinator.Helper {
        boolean startOK=true, serialOK=true, queryOK=true;
        int startCount=0;
        public void openTcp() {}
        public String readGreeting(){ return "READY"; }
        public boolean appStart(){ startCount++;return startOK; }
        public boolean configureSerial(){return serialOK;}
        public void openUart(){}
        public boolean queryModule(){return queryOK;}
    }
    static final class HidlFake implements BootCoordinator.Hidl {
        int starts=0;
        boolean callbackEnabled=true;
        public boolean findService(){return true;}
        public int init(){return 0;}
        public int appStart(){starts++; return 0;}
        public void submitSpeakEnable(BootCoordinator.Callback cb){if(callbackEnabled) cb.reportResults(42);}
        public int speakDisable(){return 0;}
        public int appExit(){return 0;}
        public int deinit(){return 0;}
    }
    static void ck(boolean b,String message){if(!b)throw new AssertionError(message);}
    public static void main(String[] args){
        { HelperFake helper=new HelperFake(); helper.startOK=false;
          BootCoordinator bc=new BootCoordinator(null);
          BootCoordinator.Outcome o=bc.verifyHelper(helper,false);
          ck(o.stage==BootCoordinator.Stage.FAILED,"bad start must abort");
          ck(helper.startCount==1,"start attempted once");
        }
        { HelperFake helper=new HelperFake(); helper.serialOK=false;
          BootCoordinator bc=new BootCoordinator(null);
          ck(bc.verifyHelper(helper,false).stage==BootCoordinator.Stage.FAILED,"bad serial must abort");
        }
        { HelperFake helper=new HelperFake(); HidlFake h=new HidlFake();
          BootCoordinator bc=new BootCoordinator(null);
          ck(bc.verifyHelper(helper,true).stage==BootCoordinator.Stage.MODULE_QUERY_ACK,"transport init");
          BootCoordinator.Outcome r=bc.enableRx(h,BootCoordinator.AppStartOwner.HELPER,1);
          ck(r.stage==BootCoordinator.Stage.RX_CALLBACK,"RX callback observed");
          ck(r.callbackValue==42,"callback value preserved");
          ck(h.starts==0,"never double APP_START");
          bc.shutdownOwnedHidl(h);
        }
        { HelperFake helper=new HelperFake(); HidlFake h=new HidlFake(); h.callbackEnabled=false;
          BootCoordinator bc=new BootCoordinator(null);
          ck(bc.verifyHelper(helper,false).stage==BootCoordinator.Stage.UART_OPEN,"transport no probe");
          ck(bc.enableRx(h,BootCoordinator.AppStartOwner.HELPER,1).stage==BootCoordinator.Stage.RX_TIMEOUT,"timeout");
          bc.shutdownOwnedHidl(h);
        }
        { BootCoordinator bc=new BootCoordinator(null); HidlFake h=new HidlFake();
          ck(bc.enableRx(h,BootCoordinator.AppStartOwner.HELPER,1).stage==BootCoordinator.Stage.FAILED,"no RX before transport");
        }
        System.out.println("PASS: five BootCoordinator host tests");
    }
}
