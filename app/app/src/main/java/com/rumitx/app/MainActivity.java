package com.rumitx.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    private Channel activeChannel;
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

        radio = RadioController.getInstance();
        db = new ChannelDatabase(this);

        // Seed a default test channel on first run so the radio is
        // usable (and remotely testable) without manual entry.
        if (db.getAllChannels().isEmpty()) {
            Channel seed = new Channel();
            seed.name = "Test 446.5555";
            seed.txFreqMHz = 446.5555;
            seed.rxFreqMHz = 446.5555;
            seed.isDigital = true;
            seed.colorCode = 1;
            seed.talkgroup = 1;
            db.saveChannel(seed);
        }

        // Connect to DMR module
        if (!radio.connect()) {
            Toast.makeText(this, "Could not open radio — is the module ready?",
                    Toast.LENGTH_LONG).show();
        }

        // Test HIDL access (for RX audio via vendor service)
        testHidlAccess();

        // Wire up buttons
        Button pttBtn = findViewById(R.id.ptt_button);
        Button addBtn = findViewById(R.id.add_channel_btn);
        Button discBtn = findViewById(R.id.discovery_btn);
        ListView channelList = findViewById(R.id.channel_list);

        // Squelch control (0-9, default 1 for open squelch)
        android.widget.SeekBar sqSeek = findViewById(R.id.squelch_seek);
        android.widget.TextView sqVal = findViewById(R.id.squelch_value);
        sqSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                sqVal.setText(String.valueOf(progress));
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                int level = sb.getProgress();
                // Update active channel's squelch and reprogram
                if (activeChannel != null) {
                    activeChannel.squelchLevel = level;
                    db.saveChannel(activeChannel);
                    new Thread(() -> radio.programChannel(activeChannel)).start();
                    Toast.makeText(MainActivity.this,
                            "Squelch set to " + level, Toast.LENGTH_SHORT).show();
                }
            }
        });

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

        // Remote programming: adb shell am broadcast -a com.rumitx.app.PROGRAM_CHANNEL
        //   --es name "MyChan" --es tx "446.1000" --es rx "446.1000"
        //   --es digital "1" --es cc "1" --es tg "1" [--es activate "1"]
        registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                try {
                    Channel ch = new Channel();
                    ch.name = intent.getStringExtra("name");
                    if (ch.name == null) ch.name = "Remote";
                    ch.txFreqMHz = Double.parseDouble(
                            intent.getStringExtra("tx"));
                    String rx = intent.getStringExtra("rx");
                    ch.rxFreqMHz = (rx != null) ? Double.parseDouble(rx)
                                               : ch.txFreqMHz;
                    ch.isDigital = !"0".equals(intent.getStringExtra("digital"));
                    String cc = intent.getStringExtra("cc");
                    if (cc != null) ch.colorCode = Integer.parseInt(cc);
                    String tg = intent.getStringExtra("tg");
                    if (tg != null) ch.talkgroup = Integer.parseInt(tg);
                    db.saveChannel(ch);
                    refreshChannelList();
                    if (!"0".equals(intent.getStringExtra("activate"))) {
                        activateChannel(ch);
                        updateActiveChannel(ch);
                    }
                    Toast.makeText(MainActivity.this,
                            "Programmed: " + ch.name, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "Remote program failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            }
        }, new IntentFilter("com.rumitx.app.PROGRAM_CHANNEL"));
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
        // Volume keys: adjust module RX volume (1-8, default 5)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            rxVolume = Math.min(8, rxVolume + 1);
            if (radio != null) radio.setVolume(rxVolume);
            Toast.makeText(this, "Volume: " + rxVolume, Toast.LENGTH_SHORT).show();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            rxVolume = Math.max(1, rxVolume - 1);
            if (radio != null) radio.setVolume(rxVolume);
            Toast.makeText(this, "Volume: " + rxVolume, Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /** Current RX volume level (1-8, stock default 5). */
    private int rxVolume = 5;

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    /** Full HIDL RX init: get service, call talkie_init and talkie_app_start via transact. */
    private void testHidlAccess() {
        new Thread(() -> {
            try {
                Class<?> hwBinderClass = Class.forName("android.os.HwBinder");
                java.lang.reflect.Method getService = hwBinderClass.getMethod(
                    "getService", String.class, String.class);
                Object service = getService.invoke(null,
                    "vendor.hct.hardware.talkie@1.0::ITalkiePort", "default");
                if (service == null) {
                    showToast("HIDL service not found");
                    return;
                }

                // Use IHwBinder.transact() for raw HIDL calls
                // Transaction IDs: 1=init, 3=app_start (from stock vdex)
                Class<?> ihwBinder = Class.forName("android.os.IHwBinder");
                Class<?> hwParcelClass = Class.forName("android.os.HwParcel");
                java.lang.reflect.Method transact = ihwBinder.getMethod(
                    "transact", int.class, hwParcelClass, hwParcelClass, int.class);
                java.lang.reflect.Method writeToken =
                    hwParcelClass.getMethod("writeInterfaceToken", String.class);

                String iface = "vendor.hct.hardware.talkie@1.0::ITalkiePort";

                // talkie_init() - transaction 1
                Object req1 = hwParcelClass.newInstance();
                writeToken.invoke(req1, iface);
                Object rep1 = hwParcelClass.newInstance();
                transact.invoke(service, 1, req1, rep1, 0);
                android.util.Log.i("RumiTX", "talkie_init done");

                // talkie_app_start() - transaction 3
                Object req3 = hwParcelClass.newInstance();
                writeToken.invoke(req3, iface);
                Object rep3 = hwParcelClass.newInstance();
                transact.invoke(service, 3, req3, rep3, 0);
                android.util.Log.i("RumiTX", "talkie_app_start done");

                showToast("HIDL RX ready - speaker should chirp");
            } catch (Exception e) {
                final String err = "HIDL failed: " + e.getMessage();
                showToast(err);
                android.util.Log.e("RumiTX", err, e);
            }
        }).start();
    }

    /** Activate a channel: program TX/RX frequencies, tones, DMR settings. */
    private void activateChannel(Channel ch) {
        activeChannel = ch;
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
