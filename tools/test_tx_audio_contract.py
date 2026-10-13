#!/usr/bin/env python3
"""No-radio unit tests for the ThinkPad-to-Android canonical TX_AUDIO contract."""
import hashlib
import hmac
import tempfile
import unittest
import wave
from pathlib import Path
from tx_audio_client import canonical, make_request, validate_mhz, sha_wav


class BridgeContractTests(unittest.TestCase):
    def test_frequency_bounds(self):
        for text in ["399.99999", "470.00001", "NaN", "446", "4.46e2"]:
            with self.assertRaises(ValueError):
                validate_mhz(text)
        self.assertEqual(validate_mhz("446.00625"), "446.00625")

    def test_canonical_signature(self):
        with tempfile.TemporaryDirectory() as folder:
            wav = Path(folder) / "clip.wav"
            with wave.open(str(wav), "wb") as f:
                f.setnchannels(1)
                f.setsampwidth(2)
                f.setframerate(16000)
                f.writeframes(b"\x00\x00" * 16000)
            self.assertEqual(len(sha_wav(wav)), 64)
            k = "ab" * 32
            fields = make_request(wav, "446.00625", "446.00625", 1, 99, k,
                                  "/data/user/0/com.rumitx.app/files/voice_bridge/audio/abc.wav")
            self.assertEqual(len(canonical(fields).split(b"\n")), 10)
            actual = hmac.new(bytes.fromhex(k), canonical(fields), hashlib.sha256).hexdigest()
            self.assertTrue(hmac.compare_digest(actual, fields["mac"]))
            fields["tg"] = "98"
            forged = hmac.new(bytes.fromhex(k), canonical(fields), hashlib.sha256).hexdigest()
            self.assertNotEqual(forged, actual)


    def test_ptt_keys_before_one_second_playback_wait(self):
        # Structural regression test complements Android CI compilation.
        # A real stopwatch/on-air verification remains required on the S80.
        src = Path("app/src/main/java/com/rumitx/app/VoiceTxBridge.java").read_text()
        self.assertIn("PRE_PLAYBACK_SETTLE_MS = 1000L;", src)
        body = src.split("if (!radio.pttDown())", 1)[1].split("player.start();", 1)[0]
        self.assertIn("awaitMicReady();", body)
        self.assertNotIn("Thread.sleep(650)", body)
        self.assertIn("if (closing.get())", src.split("private void awaitMicReady()", 1)[1].split("private void transmitExclusive", 1)[0])
        # PTT-UP belongs to the finally cleanup, not a post-playback tail.
        cleanup = src.split("} finally {", 1)[1]
        self.assertIn("radio.pttUp()", cleanup)

    def test_audio_profile_is_opt_in(self):
        src = Path("app/src/main/java/com/rumitx/app/VoiceTxBridge.java").read_text()
        self.assertIn('if (!profile.isFile()) return "acoustic";', src)
        self.assertIn('"voice_call_probe"', src)
        self.assertIn("AudioManager.STREAM_VOICE_CALL", src)
        self.assertNotIn("SET_LOOPBACK_TYPE=", src)


if __name__ == "__main__":
    unittest.main()
