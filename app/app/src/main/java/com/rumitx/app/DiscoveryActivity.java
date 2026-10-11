package com.rumitx.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.app.Activity;
import java.util.ArrayList;
import java.util.List;

/**
 * RumiTX Discovery Mode — band scanner + CTCSS tone decoder.
 *
 * Doug's vision: set a frequency range + scan duration, hit SCAN.
 * The app steps through the band, finds active frequencies, runs the
 * Goertzel tone detector on each, and builds a list. Tap any result
 * to save it as a channel with frequency + tone pre-filled.
 */
public class DiscoveryActivity extends Activity {

    private RadioController radio;
    private ChannelDatabase db;
    private Handler uiHandler;

    private volatile boolean scanning = false;

    // Scan parameters (user-configurable in UI)
    private double scanStartMHz = 440.0;
    private double scanEndMHz = 450.0;
    private double scanStepKHz = 12.5;
    private int dwellMs = 500; // time per step listening for activity

    public static class ScanResult {
        public double freqMHz;
        public double toneHz;      // 0 = none detected
        public double signalDb;    // estimated, TBD
        public long timestamp;
    }

    private List<ScanResult> results = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_discovery);
        uiHandler = new Handler(Looper.getMainLooper());
        radio = new RadioController();
        db = new ChannelDatabase(this);
        radio.connect();

        // Wire up SCAN button
        android.widget.Button scanBtn = findViewById(R.id.scan_button);
        scanBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (scanning) {
                    stopScan();
                    scanBtn.setText("START SCAN");
                } else {
                    // Read scan range from UI
                    try {
                        String s = ((android.widget.EditText)
                                findViewById(R.id.scan_start)).getText().toString();
                        String e = ((android.widget.EditText)
                                findViewById(R.id.scan_end)).getText().toString();
                        scanStartMHz = Double.parseDouble(s.trim());
                        scanEndMHz = Double.parseDouble(e.trim());
                    } catch (NumberFormatException ex) {
                        android.widget.Toast.makeText(DiscoveryActivity.this,
                                "Enter valid start/end frequencies",
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    scanBtn.setText("STOP SCAN");
                    startScan();
                }
            }
        });
    }

    /**
     * Start a band scan. Runs on a background thread.
     */
    private void startScan() {
        if (scanning) return;
        scanning = true;
        results.clear();

        new Thread(() -> {
            double freq = scanStartMHz;
            while (freq <= scanEndMHz && scanning) {
                // Tune the module to this frequency
                Channel probe = new Channel();
                probe.txFreqMHz = freq;
                probe.rxFreqMHz = freq;
                probe.txCtcssHz = 0; // carrier squelch for scan
                probe.rxCtcssHz = 0;
                radio.programChannel(probe);

                // Dwell and listen
                try { Thread.sleep(dwellMs); } catch (InterruptedException e) { break; }

                // Check for activity (squelch open / signal present)
                // TODO: query module for signal status
                boolean active = checkSignalPresent();

                if (active) {
                    ScanResult r = new ScanResult();
                    r.freqMHz = freq;
                    r.timestamp = System.currentTimeMillis();

                    // Run tone detection on received audio
                    r.toneHz = detectTone();

                    results.add(r);
                    final ScanResult fr = r;
                    uiHandler.post(() -> onResultFound(fr));
                }

                final double progress = (freq - scanStartMHz) / (scanEndMHz - scanStartMHz);
                uiHandler.post(() -> updateProgress(progress));

                freq += scanStepKHz / 1000.0;
            }

            scanning = false;
            uiHandler.post(this::onScanComplete);
        }).start();
    }

    private void stopScan() {
        scanning = false;
    }

    /**
     * Check if there's a signal on the current frequency.
     * TODO: implement via module RSSI query or squelch status.
     */
    private boolean checkSignalPresent() {
        // Placeholder — needs module RSSI/squelch query
        return false;
    }

    /**
     * Capture audio and run Goertzel CTCSS detection.
     * @return detected tone Hz, or 0 if none
     */
    private double detectTone() {
        int sampleRate = 8000;
        int bufSize = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        AudioRecord recorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize * 2);

        // Capture 1 second of audio
        int numSamples = sampleRate;
        short[] samples = new short[numSamples];

        try {
            recorder.startRecording();
            int read = 0;
            while (read < numSamples) {
                int r = recorder.read(samples, read, numSamples - read);
                if (r <= 0) break;
                read += r;
            }
        } finally {
            recorder.stop();
            recorder.release();
        }

        ToneDetector.DetectionResult dr = ToneDetector.detect(samples, sampleRate);
        return dr.reliable ? dr.toneHz : 0;
    }

    /**
     * One-tap "add channel" — save a scan result as a programmed channel.
     */
    private void saveAsChannel(ScanResult r) {
        Channel ch = new Channel();
        ch.name = String.format("Found %.4f", r.freqMHz);
        ch.txFreqMHz = r.freqMHz;
        ch.rxFreqMHz = r.freqMHz;
        ch.rxCtcssHz = r.toneHz; // detected tone on RX
        // TX tone left at 0 — user sets it via direct entry in editor

        long id = db.saveChannel(ch);
        if (id > 0) {
            Toast.makeText(this,
                    String.format("Saved: %.4f MHz%s", r.freqMHz,
                            r.toneHz > 0 ? String.format(" (PL %.1f)", r.toneHz) : ""),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void onResultFound(ScanResult r) {
        // TODO: add to results ListView
    }

    private void updateProgress(double fraction) {
        // TODO: update ProgressBar
    }

    private void onScanComplete() {
        Toast.makeText(this,
                String.format("Scan complete: %d signals found", results.size()),
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanning = false;
        if (radio != null) radio.disconnect();
        if (db != null) db.close();
    }
}
