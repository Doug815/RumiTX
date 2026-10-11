package com.rumitx.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.Activity;
import java.util.List;

/**
 * RumiTX Main Activity — channel list + PTT.
 *
 * Design: clean, direct, no chart lookups. Every value shown is the
 * real value (frequency, tone Hz, color code number). Tap a channel
 * to activate. Big PTT button. Discovery mode button for band scanning.
 */
public class MainActivity extends Activity {

    private RadioController radio;
    private ChannelDatabase db;
    private static final int REQ_AUDIO = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Audio permission for tone detection (Discovery Mode)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }

        radio = new RadioController();
        db = new ChannelDatabase(this);

        // Connect to DMR module
        if (!radio.connect()) {
            Toast.makeText(this, "Could not open radio — is the module ready?",
                    Toast.LENGTH_LONG).show();
        }

        // Wire up buttons
        Button pttBtn = findViewById(R.id.ptt_button);
        Button addBtn = findViewById(R.id.add_channel_btn);
        Button discBtn = findViewById(R.id.discovery_btn);
        ListView channelList = findViewById(R.id.channel_list);

        // PTT: hold to talk
        pttBtn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    boolean ok = radio.pttDown();
                    pttBtn.setText(ok ? "TRANSMITTING..." : "PTT FAILED");
                    return true;
                } else if (event.getAction() == MotionEvent.ACTION_UP
                        || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    radio.pttUp();
                    pttBtn.setText("HOLD TO TALK");
                    return true;
                }
                return false;
            }
        });

        // + CHANNEL: open editor
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this,
                        ChannelEditorActivity.class));
            }
        });

        // DISCOVERY: open band scanner
        discBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this,
                        DiscoveryActivity.class));
            }
        });

        // Channel list: tap to activate
        refreshChannelList();
        channelList.setOnItemClickListener(
                new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent,
                    View view, int position, long id) {
                List<Channel> channels = db.getAllChannels();
                if (position < channels.size()) {
                    activateChannel(channels.get(position));
                    updateActiveChannel(channels.get(position));
                }
            }
        });
    }

    private void refreshChannelList() {
        ListView channelList = findViewById(R.id.channel_list);
        List<Channel> channels = db.getAllChannels();
        String[] names = new String[channels.size()];
        for (int i = 0; i < channels.size(); i++) {
            Channel c = channels.get(i);
            names[i] = c.name + "  " + String.format("%.4f", c.txFreqMHz);
            if (c.isSplitFrequency()) {
                names[i] += " / " + String.format("%.4f", c.rxFreqMHz);
            }
        }
        channelList.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, names));
    }

    private void updateActiveChannel(Channel ch) {
        TextView info = findViewById(R.id.active_channel_info);
        if (ch.isSplitFrequency()) {
            info.setText(String.format("%s: TX %.5f / RX %.5f",
                    ch.name, ch.txFreqMHz, ch.rxFreqMHz));
        } else {
            info.setText(String.format("%s: %.5f MHz", ch.name, ch.txFreqMHz));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshChannelList(); // in case channels were added/edited
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (radio != null) radio.disconnect();
        if (db != null) db.close();
    }

    /**
     * Physical PTT button: the stock HcTTalkie app maps the side PTT key
     * to KEYCODE_F9 (139). Mirror its behavior -- key on FLAG_LONG_PRESS,
     * release on key up.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (radio != null
                && RadioController.isPttKeyDown(keyCode, event.getFlags())) {
            Button pttBtn = findViewById(R.id.ptt_button);
            boolean ok = radio.pttDown();
            if (pttBtn != null)
                pttBtn.setText(ok ? "TRANSMITTING..." : "PTT FAILED");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (radio != null && RadioController.isPttKeyUp(keyCode)) {
            Button pttBtn = findViewById(R.id.ptt_button);
            radio.pttUp();
            if (pttBtn != null) pttBtn.setText("HOLD TO TALK");
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /** Activate a channel: program TX/RX frequencies, tones, DMR settings. */
    private void activateChannel(Channel ch) {
        if (radio.programChannel(ch)) {
            String msg = ch.name + ": ";
            if (ch.isSplitFrequency()) {
                msg += String.format("TX %.5f / RX %.5f (SPLIT)",
                        ch.txFreqMHz, ch.rxFreqMHz);
            } else {
                msg += String.format("%.5f MHz", ch.txFreqMHz);
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Failed to program channel", Toast.LENGTH_SHORT).show();
        }
    }
}
