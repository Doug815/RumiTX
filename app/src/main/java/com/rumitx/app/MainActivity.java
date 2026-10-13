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
    private PttDispatcher commands;
    
    private volatile boolean destroyed;
    private ChannelDatabase db;
    private Channel activeChannel;
    private BroadcastReceiver programReceiver;
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
        radio.setIdentity(DmrIdentity.id(this));
        RadioService.start(this);
        if(com.rumitx.app.tak.TakSettings.active(this))com.rumitx.app.tak.TakSettings.refresh(this);
        ((TextView)findViewById(R.id.dmr_identity)).setText(DmrIdentity.label(this));
        findViewById(R.id.dmr_identity).setOnClickListener(v->IdentityDialog.show(this,()->{((TextView)findViewById(R.id.dmr_identity)).setText(DmrIdentity.label(this));commands.execute(()->radio.applyIdentityToCurrentChannel());}));
        commands=new PttDispatcher(new PttDispatcher.Transport(){
            public boolean down(){return radio.pttDown();}
            public boolean up(){return radio.pttUp();}
        },(keyed,error) -> runOnUiThread(() -> {
            if(destroyed)return;
            Button button=findViewById(R.id.ptt_button);
            button.setText(error!=null?"PTT FAILED":keyed?"TRANSMITTING...":"HOLD TO TALK");
            RadioLog.i("RumiTX-PTT","keyed="+keyed+" error="+error);
            if(error!=null)showToast(error);
        }));
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


        MessageStore.attach(getApplicationContext(),radio);
        // Test HIDL access (for RX audio via vendor service)
        testHidlAccess();

        findViewById(R.id.boot_status).setOnLongClickListener(v -> {RxDiagnostics.share(this);return true;});
        findViewById(R.id.repeaters_btn).setOnClickListener(v -> startActivity(new Intent(this,com.rumitx.app.repeaters.RepeaterActivity.class)));
        findViewById(R.id.tak_settings_btn).setOnClickListener(v->{v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);startActivity(new Intent(this,com.rumitx.app.tak.TakActivity.class));});
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
                    final Channel channel=activeChannel;
                    commands.execute(() -> radio.programChannel(channel));
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
                    commands.setTransmit(true);
                    return true;
                } else if (event.getAction() == MotionEvent.ACTION_UP
                        || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    commands.setTransmit(false);
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

                }
            }
        });

        channelList.setOnItemLongClickListener((parent,view,position,id)->{List<Channel> channels=db.getAllChannels();if(position>=channels.size())return false;view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);startActivity(new Intent(this,ChannelEditorActivity.class).putExtra("channel_id",channels.get(position).id));return true;});
        // Remote programming: adb shell am broadcast -a com.rumitx.app.PROGRAM_CHANNEL
        //   --es name "MyChan" --es tx "446.1000" --es rx "446.1000"
        //   --es digital "1" --es cc "1" --es tg "1" [--es activate "1"]
        programReceiver=new BroadcastReceiver() {
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

                    }
                    Toast.makeText(MainActivity.this,
                            "Programmed: " + ch.name, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "Remote program failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            }
        };
        registerReceiver(programReceiver, new IntentFilter("com.rumitx.app.PROGRAM_CHANNEL"));
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
                R.layout.channel_card, names));
    }

    private void updateActiveChannel(Channel ch) {
        TextView frequency=findViewById(R.id.frequency_display);
        frequency.setText(String.format(java.util.Locale.US,"%.4f MHz",ch.rxFreqMHz));
        frequency.setTranslationY(12);frequency.setAlpha(.3f);
        frequency.animate().translationY(0).alpha(1).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        frequency.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        ((TextView)findViewById(R.id.active_channel_info)).setText(ch.name+" / "+(ch.isDigital?"DMR · CC "+ch.colorCode:"ANALOG")+(ch.isSplitFrequency()?String.format(java.util.Locale.US," / TX %.4f",ch.txFreqMHz):""));
    }

    @Override
    protected void onResume() {
        super.onResume();
        statusUi.removeCallbacks(statusRefresh);statusUi.post(statusRefresh);
        refreshChannelList(); // in case channels were added/edited
        ((TextView)findViewById(R.id.dmr_identity)).setText(DmrIdentity.label(this));
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
        statusUi.removeCallbacksAndMessages(null);
        if(commands!=null)commands.close(() -> {});
        if(programReceiver!=null)unregisterReceiver(programReceiver);

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
            commands.setTransmit(true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (radio != null && RadioController.isPttKeyUp(keyCode)) {
            commands.setTransmit(false);
            return true;
        }
        // Volume keys: adjust module RX volume (1-8, default 5)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            rxVolume = Math.min(8, rxVolume + 1);
            final int volume=rxVolume;
            commands.execute(() -> radio.setVolume(volume));
            Toast.makeText(this, "Volume: " + rxVolume, Toast.LENGTH_SHORT).show();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            rxVolume = Math.max(1, rxVolume - 1);
            final int volume=rxVolume;
            commands.execute(() -> radio.setVolume(volume));
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

    /** Test HIDL access to the vendor talkie service. Uses reflection to avoid compile-time dependency. */
    private final android.os.Handler statusUi=new android.os.Handler();
    private final Runnable statusRefresh=new Runnable(){public void run(){if(destroyed)return;setBootStatus(RadioService.status());int unread=getSharedPreferences("message_state",0).getInt("unread",0);((Button)findViewById(R.id.tak_settings_btn)).setText(unread>0?"TAK ("+Math.min(unread,99)+")":"TAK");Channel ch=radio.currentChannel();if(ch!=null&&(activeChannel==null||activeChannel.id!=ch.id)){activeChannel=ch;updateActiveChannel(ch);((android.widget.SeekBar)findViewById(R.id.squelch_seek)).setProgress(ch.squelchLevel);}statusUi.postDelayed(this,1000);}};
    private void testHidlAccess(){statusUi.post(statusRefresh);}

    private void setBootStatus(String message) {
        runOnUiThread(() -> { if (!destroyed) { TextView status=findViewById(R.id.boot_status); status.setText(message); String level=message.toUpperCase(java.util.Locale.US);status.setTextColor(level.contains("FAIL")||level.contains("ERROR")||level.contains("TIMEOUT")?0xffff2244:level.contains("RECONNECT")?0xffffb000:level.contains("RX AUDIO")?0xff00ff88:0xffb5dae6); } });
    }

    /** Activate a channel: program TX/RX frequencies, tones, DMR settings. */
    private void activateChannel(Channel ch) {
        String gate=com.rumitx.app.repeaters.RepeaterProfiles.blockedChannel(ch);
        if(gate!=null){showToast(gate);return;}
        commands.execute(() -> {
            boolean ok;
            try{ok=radio.programChannel(ch);}catch(IllegalArgumentException e){showToast(e.getMessage());return;}
            runOnUiThread(() -> {if(!destroyed){if(ok){activeChannel=ch;updateActiveChannel(ch);getSharedPreferences("radio_session",0).edit().putLong("active_channel",ch.id).apply();((android.widget.SeekBar)findViewById(R.id.squelch_seek)).setProgress(ch.squelchLevel);}showToast(ok?"Channel programmed: "+ch.name:"Failed to program channel");}});
        });
    }
    @Override protected void onPause(){
        statusUi.removeCallbacks(statusRefresh);
        if(commands!=null&&radio.isKeyed())commands.setTransmit(false);
        super.onPause();
    }
}
