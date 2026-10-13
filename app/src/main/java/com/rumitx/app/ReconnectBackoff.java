package com.rumitx.app;
/** Retry intervals after consecutive failures, reset on recovery. */
public final class ReconnectBackoff {private int seconds=2;public long nextMillis(){int current=seconds;seconds=Math.min(30,seconds*2);return current*1000L;}public void reset(){seconds=2;}}
