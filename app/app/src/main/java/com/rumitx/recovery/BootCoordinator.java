package com.rumitx.recovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pure-Java Oreo-compatible recovery coordinator.
 * Integrate using the existing RumiTX helper/TCP, UART, and HIDL adapters.
 * It never issues PTT or raw GPIO commands.
 *
 * IMPORTANT: This code does not assert that a binder handle, helper greeting,
 * or HIDL callback is proof of audible RX. Vendor result semantics are unknown.
 */
public final class BootCoordinator {
    public enum AppStartOwner { HELPER, HIDL }
    public enum Stage {
        NEW, HELPER_CONNECTED, GREETING_READY, APP_START_ACK,
        SERIAL_SETUP_ACK, UART_OPEN, MODULE_QUERY_ACK,
        HIDL_FOUND, HIDL_INIT_ACK, RX_SUBMITTED, RX_CALLBACK,
        RX_TIMEOUT, FAILED
    }
    public interface LogSink { void log(String message); }
    public interface Helper {
        void openTcp() throws Exception;
        String readGreeting() throws Exception;
        boolean appStart() throws Exception; // TCP "start" -> actual ACK, not READY greeting
        boolean configureSerial() throws Exception; // TCP "serial" -> actual ACK
        void openUart() throws Exception; // /dev/ttyMT1, via current app logic
        boolean queryModule() throws Exception; // supported AT/DMR007 query with response
    }
    public interface Hidl {
        boolean findService() throws Exception; // service handle only; NO hardware state claim
        int init() throws Exception;
        int appStart() throws Exception;
        void submitSpeakEnable(Callback callback) throws Exception; // must be oneway
        int speakDisable() throws Exception;
        int appExit() throws Exception;
        int deinit() throws Exception;
    }
    public interface Callback { void reportResults(int value); }
    public static final class Outcome {
        private final List<String> events;
        public final Stage stage;
        public final String failure;
        public final Integer callbackValue;
        Outcome(Stage stage, String failure, Integer callbackValue, List<String> events) {
            this.stage=stage; this.failure=failure; this.callbackValue=callbackValue;
            this.events=Collections.unmodifiableList(new ArrayList<String>(events));
        }
        public List<String> events() { return events; }
        public boolean isComplete() { return stage == Stage.RX_CALLBACK; }
        public String toString() {
            return "Outcome{stage=" + stage + ",failure=" + failure
                    + ",callbackValue=" + callbackValue + "}";
        }
    }
    private final List<String> events = new ArrayList<String>();
    private final LogSink log;
    private Stage stage = Stage.NEW;
    private String failure;
    private Integer callbackValue;
    private boolean hidlInitOwned;
    private boolean hidlAppStartOwned;
    private boolean rxSubmitted;

    public BootCoordinator(LogSink log) { this.log=log; }
    private void stage(Stage next, String info) {
        this.stage=next;
        String line=next.name() + " | " + info;
        events.add(line);
        if(log != null) log.log(line);
    }
    private Outcome result() { return new Outcome(stage,failure,callbackValue,events); }
    private Outcome fail(String explanation) {
        failure=explanation;
        stage(Stage.FAILED,explanation);
        return result();
    }
    private void require(boolean ok,String what) throws Exception {
        if(!ok) throw new IllegalStateException(what);
    }

    /**
     * Independent transport health check. Separate from RX; never touches HIDL
     * if the existing helper or module isn't functional.
     *
     * Important: don't call again against an already-started helper unless
     * its APP_START operation is documented idempotent. One connection cycle
     * should have only one lifecycle owner.
     */
    public Outcome verifyHelper(Helper helper, boolean querySupported) {
        try {
            helper.openTcp();
            stage(Stage.HELPER_CONNECTED,"TCP transport open");
            String greeting=helper.readGreeting();
            require(greeting!=null && greeting.startsWith("READY"),"No READY greeting");
            stage(Stage.GREETING_READY,"READY is a greeting; NOT APP_START success");
            require(helper.appStart(),"TCP start command was not acknowledged");
            stage(Stage.APP_START_ACK,"Helper owns APP_START");
            require(helper.configureSerial(),"TCP serial command was not acknowledged");
            stage(Stage.SERIAL_SETUP_ACK,"UART configuration command acknowledged");
            helper.openUart();
            stage(Stage.UART_OPEN,"App UART descriptor opened; NOT module-response proof");
            if(querySupported) {
                require(helper.queryModule(),"No valid radio-module response to supported query");
                stage(Stage.MODULE_QUERY_ACK,"Module actually replied");
            } else {
                events.add("MODULE_QUERY_SKIPPED | No safe supported query established");
            }
            return result();
        } catch(Exception e) { return fail(e.getClass().getSimpleName()+": "+e.getMessage()); }
    }

    /**
     * Controlled *experiment*, only after verifyHelper succeeded. In HELPER
     * mode, DO NOT send a second appStart through HIDL. In HIDL mode, DO NOT
     * have helper.appStart already called. Use distinct experiment sessions.
     *
     * The result is a HIDL callback observation, NOT proof of RX audio.
     * Leave RX running on callback success; teardown explicitly on shutdown.
     */
    public Outcome enableRx(Hidl hidl, AppStartOwner owner, int seconds) {
        if (seconds < 1 || seconds > 120) return fail("timeout must be 1..120 seconds");
        if (stage != Stage.UART_OPEN && stage != Stage.MODULE_QUERY_ACK)
            return fail("Transport not validated: " + stage);
        if (owner != AppStartOwner.HELPER)
            return fail("HIDL ownership requires separate transport-only bootstrap; helper already did APP_START");
        try {
            require(hidl.findService(),"HIDL service absent");
            stage(Stage.HIDL_FOUND,"Binder reference found; NO automatic RX setup");
            int initRc=hidl.init();
            if(initRc<0) return fail("HIDL init vendor code="+initRc);
            hidlInitOwned=true;
            stage(Stage.HIDL_INIT_ACK,"init returned "+initRc+"; vendor semantics unverified");
            // Do NOT call hidl.appStart() here: helper already issued APP_START.
            final CountDownLatch latch=new CountDownLatch(1);
            final int[] val=new int[1];
            hidl.submitSpeakEnable(new Callback() {
                public void reportResults(int value) {
                    val[0]=value;
                    latch.countDown();
                }
            });
            rxSubmitted=true;
            stage(Stage.RX_SUBMITTED,"oneway request submitted; no proof of amplifier yet");
            if(latch.await(seconds,TimeUnit.SECONDS)) {
                callbackValue=val[0];
                stage(Stage.RX_CALLBACK,"reportResults("+val[0]+") received; listen/check kernel logs");
            } else {
                stage(Stage.RX_TIMEOUT,"callback missing; vendor service might still be waiting in ioctl");
            }
            return result();
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt(); return fail("Interrupted while awaiting HIDL callback");
        } catch(Exception e) { return fail(e.getClass().getSimpleName()+": "+e.getMessage()); }
    }

    /**
     * Only call for an isolated RX test or explicit shutdown, on a background
     * executor with a watchdog. A vendor service may still block in kernel.
     * Never run cleanup in the application's UI thread.
     */
    public void shutdownOwnedHidl(Hidl hidl) {
        if (rxSubmitted) {
            try { hidl.speakDisable(); } catch(Exception e) { events.add("DISABLE_ERROR "+e); }
            rxSubmitted=false;
        }
        if (hidlAppStartOwned) {
            try { hidl.appExit(); } catch(Exception e) { events.add("APP_EXIT_ERROR "+e); }
            hidlAppStartOwned=false;
        }
        if (hidlInitOwned) {
            try { hidl.deinit(); } catch(Exception e) { events.add("DEINIT_ERROR "+e); }
            hidlInitOwned=false;
        }
    }
}
