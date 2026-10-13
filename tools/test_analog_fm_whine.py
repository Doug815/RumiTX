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
