#!/usr/bin/env python3
"""Offline tests for DMR RX evidence tool; NEVER keys S80 radio."""
import contextlib
import io
from pathlib import Path
import tempfile
import unittest
import wave

from analyze_dmr_voice import envelope, examine, centered_similarity
from dmr_rx_capture import (build_decoder_command, main as capture_main,
                            rtl_dsp_tuning_plan, dmr_sync_summary)


def write_pcm(path, rate=8000, seconds=1.0, samples=None):
    count = int(rate*seconds)
    if samples is None:
        samples = [0]*count
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(rate)
        # Deterministic PCM with no third-party dependencies.
        import struct
        out.writeframes(struct.pack("<" + "h"*len(samples), *samples))


class RxVoiceTests(unittest.TestCase):
    def test_decoder_has_real_per_call_audio_output(self):
        cmd = build_decoder_command("dsd-fme", "446.00625", 8, 0, 0,
                                    Path("/tmp/probe"))
        self.assertIn("-fs", cmd)
        self.assertEqual(cmd[cmd.index("-i")+1],
                         "rtl:0:446.00625M:8:0:12:0:2")
        self.assertEqual(cmd[cmd.index("-o")+1], "null")
        self.assertLess(cmd.index("-7"), cmd.index("-P"))
        self.assertIn("decoded_wavs", cmd[cmd.index("-7")+1])
        self.assertIn("-J", cmd)

    def test_dsd_fme_252k_offset_does_not_change_desired_rf_channel(self):
        plan = rtl_dsp_tuning_plan("446.00625", 12)
        self.assertEqual(plan["dsp_target_hz"], 446006250)
        self.assertEqual(plan["internal_center_offset_hz"], 252000)
        self.assertEqual(plan["expected_rtl_center_hz"], 446258250)
        self.assertEqual(plan["capture_sample_rate_hz"], 1008000)
        # Users should NEVER compensate the internal tuner frequency manually.
        mistaken = rtl_dsp_tuning_plan("445.75425", 12)
        self.assertEqual(mistaken["expected_rtl_center_hz"], 446006250)
        self.assertEqual(mistaken["dsp_target_hz"], 445754250)

    def test_decoder_performs_internal_offset_automatically(self):
        cmd = build_decoder_command("dsd-fme", "446.00625", 8, 0, 0,
                                    Path("/tmp/test"))
        self.assertEqual(cmd[cmd.index("-i") + 1],
                         "rtl:0:446.00625M:8:0:12:0:2")
        # The 446.25825 MHz RTL hardware tuning is the decoder's
        # implementation detail, NOT a user-supplied channel setting.
        self.assertNotIn("446.25825", " ".join(cmd))

    def test_dmr_fallback_modes_and_bandwidth(self):
        cmd = build_decoder_command("dsd-fme", "446.00625", 0, -2, 0,
                                    Path("/tmp/test"), rtl_bw_khz=24,
                                    decoder_mode="auto", invert_dmr=True,
                                    rtl_volume=1)
        self.assertIn("-fa", cmd)
        self.assertNotIn("-fs", cmd)
        self.assertIn("-xr", cmd)
        self.assertEqual(cmd[cmd.index("-i") + 1],
                         "rtl:0:446.00625M:0:-2:24:0:1")
        with self.assertRaises(ValueError):
            build_decoder_command("dsd-fme", "446.00625", 0, 0, 0,
                                  Path("/tmp/test"), rtl_bw_khz=15)

    def test_sync_summary_requires_explicit_dmr_sync(self):
        candidate = dmr_sync_summary(
            "RF activity detected\nTEMP wav created\nCenter Frequency: 446258250 Hz\n")
        self.assertFalse(candidate["dmr_sync_observed"])
        self.assertEqual(candidate["dmr_sync_count"], 0)
        confirmed = dmr_sync_summary(
            "Sync: +DMR [slot1]\nSync: -DMR [slot2]\n")
        self.assertTrue(confirmed["dmr_sync_observed"])
        self.assertEqual(confirmed["dmr_sync_count"], 2)

    def test_tuning_plan_visible_in_receive_only_dry_run(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = capture_main(["--freq", "446.00625", "--dry-run"])
        self.assertEqual(rc, 0)
        self.assertIn('"expected_rtl_center_hz": 446258250', out.getvalue())
        self.assertIn('"dsp_target_hz": 446006250', out.getvalue())
        self.assertIn('"internal_center_offset_hz": 252000', out.getvalue())

    def test_optional_discriminator_wav_is_not_decoded_voice(self):
        cmd = build_decoder_command("dsd-fme", "446.00625", 8, 0, 0,
                                    Path("/tmp/probe"), save_demod=True)
        self.assertIn("-6", cmd)
        self.assertTrue(cmd[cmd.index("-6")+1].endswith("dmr_discriminator_48k.wav"))
        self.assertLess(cmd.index("-6"), cmd.index("-7"))
        self.assertLess(cmd.index("-7"), cmd.index("-P"))
        # Default must stay light on disk.
        normal = build_decoder_command("dsd-fme", "446.00625", 8, 0, 0,
                                       Path("/tmp/probe"))
        self.assertNotIn("-6", normal)

    def test_rejects_invalid_sdr_calibration(self):
        for freq, gain, ppm, device in [
            ("399.99999", 8, 0, 0),
            ("446.00625", -1, 0, 0),
            ("446.00625", 8, 101, 0),
            ("446.00625", 8, 0, -1)]:
            with self.assertRaises(ValueError):
                build_decoder_command("dsd-fme", freq, gain, ppm, device, Path("/tmp"))

    def test_default_dry_run_has_no_transmitter(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = capture_main(["--freq", "446.00625", "--dry-run"])
        self.assertEqual(rc, 0)
        self.assertIn('"mode": "receive_only"', out.getvalue())
        self.assertIn('"sender_command": null', out.getvalue())

    def test_live_mode_fails_closed_without_authorization(self):
        out = io.StringIO()
        with contextlib.redirect_stderr(out):
            rc = capture_main(["--freq", "446.00625", "--send", "--dry-run"])
        self.assertEqual(rc, 2)
        self.assertIn("--ack-authorized", out.getvalue())

    def test_silence_does_not_count_as_decoded_voice(self):
        with tempfile.TemporaryDirectory() as folder:
            wav = Path(folder)/"silent.wav"
            write_pcm(wav)
            data = envelope(wav)
            self.assertFalse(data["non_silent_candidate"])
            report = examine([wav])
            self.assertEqual(report["assessment"], "decoded_wave_silent")
            self.assertFalse(report["claims"]["intelligible_words_verified"])
            self.assertFalse(report["claims"]["true_digital_audio_injection_verified"])

    def test_distinct_audio_is_only_voice_candidate(self):
        with tempfile.TemporaryDirectory() as folder:
            wav = Path(folder)/"received.wav"
            # Variable loudness approximates syllabic speech envelope.
            samples = []
            for frame in range(50):
                amplitude = 5000 if frame % 5 in (0, 1, 2) else 1200
                samples.extend([amplitude if i % 2 else -amplitude for i in range(320)])
            write_pcm(wav, seconds=2.0, samples=samples)
            report = examine([wav], reference=wav)
            self.assertEqual(report["assessment"], "decoded_audio_candidate")
            self.assertFalse(report["claims"]["intelligible_words_verified"])
            self.assertGreater(report["files"][0]["max_envelope_correlation"], 0.9)

    def test_envelope_flat_signal_has_no_comparison(self):
        result = centered_similarity([0]*100, [0]*100)
        self.assertIsNone(result["max_envelope_correlation"])


if __name__ == "__main__":
    unittest.main()
