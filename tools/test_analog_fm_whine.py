#!/usr/bin/env python3
"""Offline analog FM whine mitigation tests; NO radio transmissions."""
import contextlib
import io
from pathlib import Path
import tempfile
import unittest
import wave

from analyze_analog_fm_whine import analyze
from analog_fm_rx_capture import main as receiver_main, receiver_command
from make_analog_fm_test_wavs import generate


class AnalogWhineTests(unittest.TestCase):
    def test_generate_canonical_silence_and_tone(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            report = generate(root, duration=2.0)
            for item in report.values():
                path = Path(item["path"])
                data = path.read_bytes()
                self.assertTrue(data.startswith(b"RIFF"))
                self.assertEqual(len(data), 44 + 2*16000*2)
                with wave.open(str(path), "rb") as f:
                    self.assertEqual(f.getnchannels(), 1)
                    self.assertEqual(f.getframerate(), 16000)
                    self.assertEqual(f.getsampwidth(), 2)
                    self.assertEqual(f.getnframes(), 32000)
            self.assertEqual(set((root/"silence.wav").read_bytes()[44:]), {0})
            self.assertNotEqual(set((root/"tone_1khz.wav").read_bytes()[44:]), {0})

    def test_received_silence_is_not_falsely_whine(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            generate(root, duration=2.0)
            result = analyze(root/"silence.wav")
            self.assertEqual(result["assessment"], "no_usable_signal")
            self.assertIsNone(result["candidate_whine_hz"])

    def test_stationary_tone_is_detected_not_a_digital_injection_claim(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            generate(root, duration=2.0)
            result = analyze(root/"tone_1khz.wav")
            self.assertEqual(result["assessment"], "persistent_tone_candidate")
            self.assertAlmostEqual(result["candidate_whine_hz"], 1000, delta=20)
            self.assertGreater(result["candidate_peak_above_background_db"], 18)
            self.assertIn("not proof of feedback", result["limitations"])

    def test_receive_only_command_no_phone_control(self):
        cmd = receiver_command("446.00625", gain=None, ppm=0, device=0)
        self.assertEqual(cmd[cmd.index("-f")+1], "446006250")
        self.assertIn("rtl_fm", cmd)
        self.assertNotIn("-g", cmd)
        self.assertIn("-E", cmd)
        self.assertNotIn("adb", " ".join(cmd))
        with self.assertRaises(ValueError):
            receiver_command("446.00625", gain=60, ppm=0, device=0)
        with self.assertRaises(ValueError):
            receiver_command("399.99999", gain=None, ppm=0, device=0)

    def test_dry_run_does_not_open_sdr_or_key_radio(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = receiver_main(["--freq", "446.00625", "--dry-run"])
        self.assertEqual(status, 0)
        self.assertIn('"mode": "receive_only"', output.getvalue())
        self.assertIn('"rf_target_mhz": "446.00625"', output.getvalue())

    def test_ptt_only_is_analog_only_one_shot_and_signed_silent_wav(self):
        src = Path("app/src/main/java/com/rumitx/app/VoiceTxBridge.java").read_text()
        method = src.split("private boolean consumeAnalogPttOnlyDiagnostic(", 1)[1].split(
            "private void holdAnalogPttOnly()", 1)[0]
        self.assertIn('if (!"0".equals(r.digital)) return false;', method)
        self.assertIn('"analog_tx_diagnostic_mode.txt"', method)
        self.assertIn('if (!"ptt_only".equals(selected))', method)
        self.assertIn('wavMillis != ANALOG_PTT_ONLY_HOLD_MS', method)
        self.assertIn('ANALOG_PTT_ONLY_HOLD_MS = 4000;', src)
        self.assertIn('source.seek(44);', method)
        self.assertIn('if (block[i] != 0)', method)
        self.assertIn('if (!mode.delete())', method)
        self.assertLess(src.index('pttOnlyDiagnostic = consumeAnalogPttOnlyDiagnostic'),
                        src.index('if (!radio.programChannel(ch))'))
        self.assertIn('int millis = validateWav(r);', src)
        self.assertIn('if (!pttOnlyDiagnostic) {', src)
        player_path = src.split('            if (!pttOnlyDiagnostic) {', 1)[1].split(
            '            pttAttempted = true;', 1)[0]
        self.assertIn('player = new MediaPlayer();', player_path)
        self.assertIn('manager.setStreamVolume(streamType, target, 0);', player_path)
        self.assertIn('ANALOG_PTT_ONLY_NO_AUDIO_ENGINE', player_path)
        self.assertIn('holdAnalogPttOnly();', src)
        self.assertIn('ANALOG_PTT_ONLY_COMPLETE', src)

    def test_ptt_only_four_seconds_is_bounded_and_unkeyed_on_failure(self):
        src = Path("app/src/main/java/com/rumitx/app/VoiceTxBridge.java").read_text()
        hold = src.split('private void holdAnalogPttOnly()', 1)[1].split(
            'private void awaitMicReady()', 1)[0]
        self.assertIn('SystemClock.elapsedRealtime() + ANALOG_PTT_ONLY_HOLD_MS', hold)
        self.assertIn('if (closing.get())', hold)
        self.assertIn('if (!radio.isKeyed())', hold)
        self.assertIn('Thread.sleep(Math.min(50L', hold)
        cleanup = src.split('        } finally {', 1)[1]
        self.assertIn('radio.pttUp()', cleanup)
        self.assertIn('radio.programChannel(previousChannel)', cleanup)

    def test_ptt_only_adb_helper_shell_syntax(self):
        import subprocess
        helper = Path("tools/set_analog_fm_diag_mode.sh")
        result = subprocess.run(["bash", "-n", str(helper)], capture_output=True,
                                text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        code = helper.read_text()
        self.assertIn('analog_tx_diagnostic_mode.txt', code)
        self.assertIn('ptt_only', code)
        self.assertIn('reset', code)
        self.assertNotIn('am broadcast', code)

    def test_signed_exact_four_second_silence_source_for_ptt_only(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            generate(root, duration=4.0)
            silence = root / "silence.wav"
            with wave.open(str(silence), "rb") as f:
                self.assertEqual(f.getnframes(), 64000)
                self.assertEqual(f.getframerate(), 16000)
                self.assertEqual(f.readframes(64000), bytes(128000))

    def test_stock_rx_loopback_probe_changes_only_loopback_during_tx(self):
        route = Path("app/src/main/java/com/rumitx/app/StockAudioRoute.java").read_text()
        suspend = route.split("public synchronized boolean suspendForAnalogTxProbe()", 1)[1].split(
            "public synchronized boolean restoreAfterAnalogTxProbe()", 1)[0]
        restore = route.split("public synchronized boolean restoreAfterAnalogTxProbe()", 1)[1].split(
            "public synchronized void stop()", 1)[0]
        self.assertIn("lastRxCallbackOpen", suspend)
        self.assertIn('parameter("SET_LOOPBACK_TYPE=0")', suspend)
        self.assertIn('parameter("SET_LOOPBACK_TYPE=3, 3")', suspend)  # error rollback
        self.assertNotIn("setStockStoppedRoute()", suspend)
        self.assertNotIn('parameter("SET_MIC_NUM=', suspend)
        self.assertNotIn('parameter("SET_MIC_CHOOSE=', suspend)
        self.assertIn('parameter("SET_LOOPBACK_TYPE=3, 3")', restore)
        self.assertIn("lastRxCallbackOpen", restore)
        self.assertIn("setStockStoppedRoute()", restore)  # deferred RX-OFF callback only
        self.assertNotIn('parameter("SET_MIC_NUM=', restore)
        self.assertIn("if (temporarilySuspended)", route)
        self.assertIn("RX route callback deferred", route)

    def test_probe_requires_signed_analog_ptt_only_and_restores_after_unkey(self):
        bridge = Path("app/src/main/java/com/rumitx/app/VoiceTxBridge.java").read_text()
        method = bridge.split("private boolean consumeAnalogRxRouteProbe(", 1)[1].split(
            "private void holdAnalogPttOnly()", 1)[0]
        self.assertIn('if (!"0".equals(request.digital)) return false;', method)
        self.assertIn('if (!pttOnly)', method)
        self.assertIn('"suspend_stock_rx"', method)
        self.assertIn('if (!setting.delete())', method)
        self.assertLess(bridge.index("rxRouteProbeRequested = consumeAnalogRxRouteProbe"),
                        bridge.index("if (!radio.programChannel(ch))"))
        self.assertLess(bridge.index("suspendStockRxRouteForAnalogProbe()"),
                        bridge.index("if (!radio.pttDown())"))
        cleanup = bridge.split("        } finally {", 1)[1]
        self.assertLess(cleanup.index("radio.pttUp()"),
                        cleanup.index("restoreStockRxRouteAfterAnalogProbe()"))
        self.assertIn("if (radio.isKeyed())", cleanup)
        self.assertIn("ANALOG_RX_ROUTE_PROBE_RESTORE_DEFERRED", cleanup)
        self.assertIn("ANALOG_RX_ROUTE_PROBE_RESTORED", cleanup)

    def test_service_attaches_hidl_adapter_after_bridge_is_created(self):
        svc = Path("app/src/main/java/com/rumitx/app/RadioService.java").read_text()
        self.assertLess(svc.index("voiceBridge=new VoiceTxBridge(this,radio);"),
                        svc.index("lifecycle.execute(()->initialize(radio));"))
        self.assertIn("if(voiceBridge!=null)voiceBridge.attachHidlRxProbe(hidl);", svc)

    def test_rx_probe_helper_is_one_shot_and_matches_build_version(self):
        import subprocess
        script = Path("tools/set_analog_rx_route_probe.sh")
        check = subprocess.run(["bash", "-n", str(script)], capture_output=True,
                               text=True, check=False)
        self.assertEqual(check.returncode, 0, check.stderr)
        code = script.read_text()
        build = Path("app/build.gradle").read_text()
        self.assertIn("versionCode 20301037", build)
        self.assertIn("versionCode=20301037", code)
        self.assertIn("suspend_stock_rx", code)
        self.assertIn("analog_rx_route_probe.txt", code)
        self.assertIn("analog_tx_diagnostic_mode.txt", code)
        self.assertNotIn("am broadcast", code)

    def test_android_analog_gain_isolated_and_restored(self):
        code = Path("app/src/main/java/com/rumitx/app/VoiceTxBridge.java").read_text()
        self.assertIn("private int analogPlaybackPercent() throws IOException", code)
        self.assertIn("if (!setting.isFile()) return 40;", code)
        self.assertIn('if ("0".equals(r.digital))', code)
        self.assertIn("manager.setStreamVolume(streamType, target, 0);", code)
        self.assertIn('} else if ("acoustic".equals(outputProfile)) {', code)
        self.assertIn("manager.getStreamMaxVolume(streamType), 0);", code)
        self.assertIn("manager.setStreamVolume(streamType, oldVolume, 0);", code)
        self.assertIn("PRE_PLAYBACK_SETTLE_MS = 1000L;", code)
        self.assertNotIn('SET_LOOPBACK_TYPE=', code)
        self.assertIn("radio.pttUp()", code)


if __name__ == "__main__":
    unittest.main()
