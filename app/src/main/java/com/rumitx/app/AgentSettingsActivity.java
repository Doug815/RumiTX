package com.rumitx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.rumitx.app.agent.AgentStore;
import com.rumitx.app.agent.BearerAuth;
import com.rumitx.app.agent.TxPolicy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * On-device transmit gate. Tokens can be copied to the PC. The allowlist cannot
 * be changed from the HTTP API, and public-safety frequencies cannot be saved.
 */
public final class AgentSettingsActivity extends Activity {
    private CheckBox txEnabled;
    private CheckBox tailscale;
    private EditText maxKey;
    private EditText allowlist;
    private TextView readToken;
    private TextView commandToken;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(16), dp(16), dp(16));
        txEnabled = new CheckBox(this);
        txEnabled.setText("Transmit enabled");
        column.addView(txEnabled);
        column.addView(note("Off on a fresh install. Turn this on only when you want agents to key this radio."));
        column.addView(label("Max key seconds (1–120)"));
        maxKey = new EditText(this);
        maxKey.setInputType(InputType.TYPE_CLASS_NUMBER);
        column.addView(maxKey);
        tailscale = new CheckBox(this);
        tailscale.setText("Also listen on Tailscale");
        column.addView(tailscale);
        column.addView(note("Loopback is always on. Tailscale adds that interface only. There is no wildcard bind."));
        column.addView(label("Allowed channels, one TX frequency per line"));
        allowlist = new EditText(this);
        allowlist.setMinLines(6);
        allowlist.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        column.addView(allowlist);
        column.addView(note("Beloit PD and other public-safety frequencies are refused here. Listen-only programming still uses the radio screen."));
        Button save = new Button(this);
        save.setText("SAVE TRANSMIT SETTINGS");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveSettings(); }
        });
        column.addView(save);
        column.addView(label("Read token"));
        readToken = tokenView();
        column.addView(readToken);
        column.addView(label("Command token"));
        commandToken = tokenView();
        column.addView(commandToken);
        Button regen = new Button(this);
        regen.setText("REGENERATE TOKENS");
        regen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmRegenerate(); }
        });
        column.addView(regen);
        ScrollView scroller = new ScrollView(this);
        scroller.addView(column);
        setContentView(scroller);
        load();
    }

    private void load() {
        File dir = AgentRuntime.directory(this);
        AgentStore.Loaded loaded = AgentStore.load(dir);
        TxPolicy policy = AgentRuntime.policy();
        if (policy == null) policy = loaded.policy;
        txEnabled.setChecked(policy.enabled());
        maxKey.setText(Integer.toString(policy.maxKeySeconds()));
        tailscale.setChecked(AgentRuntime.policy() == null ? loaded.tailscale : AgentRuntime.tailscaleEnabled());
        StringBuilder lines = new StringBuilder();
        for (String item : policy.allowlist()) lines.append(item).append('\n');
        allowlist.setText(lines.toString());
        BearerAuth auth = AgentRuntime.auth();
        if (auth == null) auth = loaded.auth;
        readToken.setText(auth.readToken());
        commandToken.setText(auth.commandToken());
    }

    private void saveSettings() {
        int seconds;
        try { seconds = Integer.parseInt(maxKey.getText().toString().trim()); }
        catch (RuntimeException e) {
            toast("Max key seconds must be a number from 1 to 120");
            return;
        }
        List<String> items = new ArrayList<String>();
        String[] lines = allowlist.getText().toString().split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String problem = TxPolicy.allowlistEntryProblem(line);
            if (problem != null) {
                toast(line + ": " + problem);
                return;
            }
            items.add(line);
        }
        TxPolicy next = new TxPolicy();
        next.setEnabled(txEnabled.isChecked());
        next.setMaxKeySeconds(seconds);
        try { next.setAllowlist(items); }
        catch (RuntimeException e) {
            toast(e.getMessage());
            return;
        }
        AgentRuntime.apply(this, next, tailscale.isChecked());
        toast(txEnabled.isChecked() ? "Transmit enabled for allowlisted channels" : "Transmit disabled");
        load();
    }

    private void confirmRegenerate() {
        new AlertDialog.Builder(this)
                .setTitle("Regenerate tokens")
                .setMessage("The PC will need the new read and command tokens. Old tokens stop working immediately.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Regenerate", (dialog, which) -> regenerate())
                .show();
    }

    private void regenerate() {
        File dir = AgentRuntime.directory(this);
        BearerAuth auth = AgentRuntime.auth();
        if (auth == null) {
            AgentStore.Loaded loaded = AgentStore.load(dir);
            auth = loaded.auth;
        }
        AgentStore.regenerate(dir, auth);
        readToken.setText(auth.readToken());
        commandToken.setText(auth.commandToken());
        toast("Tokens replaced. Copy them to the PC.");
    }

    private TextView tokenView() {
        TextView view = new TextView(this);
        view.setTextIsSelectable(true);
        view.setTextSize(12);
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setPadding(0, dp(12), 0, dp(4));
        return view;
    }

    private TextView note(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        return view;
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
