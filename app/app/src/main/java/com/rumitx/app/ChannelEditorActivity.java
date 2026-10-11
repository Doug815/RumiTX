package com.rumitx.app;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;
import android.app.Activity;

/**
 * RumiTX Channel Editor — DIRECT ENTRY for everything.
 *
 * Doug's rule: type the real value, no chart lookups.
 *  - Frequency: type "446.00625"
 *  - CTCSS: type "100.0"
 *  - DCS: type "023"
 *  - Color code: type "1"
 *  - Talkgroup: type the number
 *
 * Every field validates against standard tables. Invalid input is
 * rejected with a clear message — never silently accepted.
 */
public class ChannelEditorActivity extends Activity {

    private ChannelDatabase db;
    private Channel editing; // null = new channel

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_editor);
        db = new ChannelDatabase(this);

        long editId = getIntent().getLongExtra("channel_id", -1);
        // TODO: load channel if editId >= 0

        // Wire up SAVE button — reads all fields, validates, saves
        android.widget.Button saveBtn = findViewById(R.id.save_button);
        saveBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                String name = ((android.widget.EditText)
                        findViewById(R.id.edit_name)).getText().toString();
                String txF = ((android.widget.EditText)
                        findViewById(R.id.edit_tx_freq)).getText().toString();
                String rxF = ((android.widget.EditText)
                        findViewById(R.id.edit_rx_freq)).getText().toString();
                String txT = ((android.widget.EditText)
                        findViewById(R.id.edit_tx_tone)).getText().toString();
                String rxT = ((android.widget.EditText)
                        findViewById(R.id.edit_rx_tone)).getText().toString();
                boolean dig = ((android.widget.Switch)
                        findViewById(R.id.switch_digital)).isChecked();
                String cc = ((android.widget.EditText)
                        findViewById(R.id.edit_color_code)).getText().toString();
                String tg = ((android.widget.EditText)
                        findViewById(R.id.edit_talkgroup)).getText().toString();
                saveChannel(name, txF, rxF, txT, rxT, dig, cc, tg);
            }
        });
    }

    /**
     * Validate and save channel from direct-entry fields.
     * Called when user taps SAVE.
     */
    private void saveChannel(
            String name,
            String txFreqStr, String rxFreqStr,
            String txToneStr, String rxToneStr,
            boolean isDigital,
            String colorCodeStr, String talkgroupStr) {

        Channel ch = (editing != null) ? editing : new Channel();
        ch.name = name.trim();
        if (ch.name.isEmpty()) {
            error("Channel name is required");
            return;
        }

        // Frequencies — direct MHz entry
        try {
            ch.txFreqMHz = parseFreq(txFreqStr);
            ch.rxFreqMHz = parseFreq(rxFreqStr);
        } catch (NumberFormatException e) {
            error("Invalid frequency. Enter MHz like 446.00625");
            return;
        }
        if (!inBand(ch.txFreqMHz) || !inBand(ch.rxFreqMHz)) {
            error("Frequency must be 400-480 MHz (S80 UHF band)");
            return;
        }

        // CTCSS tones — direct Hz entry, validated against standard table
        try {
            ch.txCtcssHz = parseTone(txToneStr);
            ch.rxCtcssHz = parseTone(rxToneStr);
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return;
        }

        // DMR digital settings
        ch.isDigital = isDigital;
        if (isDigital) {
            try {
                ch.colorCode = Integer.parseInt(colorCodeStr.trim());
                if (ch.colorCode < 0 || ch.colorCode > 15) {
                    error("Color code must be 0-15");
                    return;
                }
            } catch (NumberFormatException e) {
                error("Color code must be a number 0-15");
                return;
            }
            try {
                ch.talkgroup = Integer.parseInt(talkgroupStr.trim());
                if (ch.talkgroup < 1) {
                    error("Talkgroup must be 1 or higher");
                    return;
                }
            } catch (NumberFormatException e) {
                error("Talkgroup must be a number");
                return;
            }
        }

        long id = db.saveChannel(ch);
        if (id > 0) {
            String msg = "Saved: " + ch.name;
            if (ch.isSplitFrequency()) {
                msg += String.format(" (TX %.5f / RX %.5f)",
                        ch.txFreqMHz, ch.rxFreqMHz);
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            finish();
        } else {
            error("Failed to save channel");
        }
    }

    /** Parse "446.00625" — direct MHz entry. */
    private double parseFreq(String s) throws NumberFormatException {
        return Double.parseDouble(s.trim());
    }

    /** S80 UHF: 400-480 MHz. */
    private boolean inBand(double mhz) {
        return mhz >= 400.0 && mhz <= 480.0;
    }

    /**
     * Parse tone field. Accepts:
     *  - "" or "0" or "none" → 0 (no tone)
     *  - "100.0" → 100.0 Hz (validated against standard CTCSS table)
     *  - "D023" or "023N" → DCS code (stored separately, returns 0 here)
     */
    private double parseTone(String s) throws IllegalArgumentException {
        s = s.trim().toUpperCase();
        if (s.isEmpty() || s.equals("0") || s.equals("NONE")) {
            return 0;
        }
        // DCS codes handled separately — this is CTCSS only
        if (s.startsWith("D")) {
            throw new IllegalArgumentException(
                    "DCS codes go in the DCS field, not the tone field");
        }
        try {
            double hz = Double.parseDouble(s);
            if (!ToneDetector.isValidTone(hz)) {
                double closest = ToneDetector.snapToStandard(hz);
                throw new IllegalArgumentException(
                        String.format("%.1f is not a standard CTCSS tone. " +
                                "Did you mean %.1f?", hz, closest));
            }
            return hz;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Enter a tone like 100.0, or leave blank for none");
        }
    }

    private void error(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (db != null) db.close();
    }
}
