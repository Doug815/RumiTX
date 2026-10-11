package com.rumitx.app;

import java.util.ArrayList;
import java.util.List;

/**
 * DMR007 binary frame builder for the DOOGEE S80 HCT intercom module.
 *
 * Exact Java port of dmr007_frame.py, reverse-engineered from the
 * HcTTalkie.vdex decompilation (com.android.talkie.ATCommond DMR007
 * branch + com.android.talkie.common.StringUtils), verified live on
 * the module 2026-10-11 (runs 4-7/16).
 *
 * Frame format: space-separated hex bytes "68 ... 10".
 * Checksum: Internet checksum (one's complement) over the frame with a
 * "00 00" placeholder where the checksum goes.
 */
public final class Dmr007Frame {

    private Dmr007Frame() { }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    // ------------------------------------------------------------------
    // StringUtils ports
    // ------------------------------------------------------------------

    /** StringUtils.decimalToHex(long, boolean space, boolean reverse). */
    public static String decimalToHex(long v, boolean space, boolean reverse) {
        StringBuilder s = new StringBuilder();
        long x = v;
        while (x != 0) {
            s.insert(0, HEX[(int) (x % 16)]);
            x /= 16;
        }
        if (s.length() % 2 == 1) s.insert(0, '0');
        if (s.length() == 0) return "00";
        String out = s.toString();
        if (space && out.length() > 2) {
            List<String> pairs = new ArrayList<String>();
            for (int i = 0; i < out.length(); i += 2)
                pairs.add(out.substring(i, i + 2));
            if (reverse) {
                StringBuilder r = new StringBuilder();
                for (int i = pairs.size() - 1; i >= 0; i--) {
                    if (r.length() > 0) r.append(' ');
                    r.append(pairs.get(i));
                }
                return r.toString();
            }
            StringBuilder r = new StringBuilder();
            for (String p : pairs) {
                if (r.length() > 0) r.append(' ');
                r.append(p);
            }
            return r.toString();
        }
        return out;
    }

    /** StringUtils.stringToHex(String, boolean, boolean). */
    public static String stringToHex(String s, boolean space, boolean reverse) {
        return decimalToHex(Long.parseLong(s), space, reverse);
    }

    /**
     * StringUtils.stringToHexWidthByte: hex of the decimal string, then
     * LEFT-padded with "00" tokens to {@code width} bytes. With
     * reverse=true the digit pairs are LE-ordered but padding stays left
     * ("12345" -> "00 00 39 30"); with reverse=false they stay big-endian
     * ("12345" -> "00 00 30 39"). Dex audit (run 13/16): frequencies use
     * reverse=true (LE); ALL DMR ID fields (phoneid, callnumber, recgroup,
     * secretkey, SMS/PTT destination) use reverse=false (BE).
     */
    public static String stringToHexWidthByte(String s, boolean space,
                                              boolean reverse, int width) {
        String h = decimalToHex(Long.parseLong(s), space, reverse);
        if (space) {
            String[] toks = h.split(" ");
            StringBuilder r = new StringBuilder();
            for (int i = 0; i < width - toks.length; i++) r.append("00 ");
            for (String t : toks) r.append(t).append(' ');
            return r.toString().trim();
        }
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < width - h.length(); i++) r.append("00");
        return r + h;
    }

    /**
     * StringUtils.string2Unicode(String, boolean spaceFirst).
     * Per char: Integer.toHexString, left-padded to 4 hex digits; with
     * spaceFirst each char's byte-pairs are spaced and followed by a
     * trailing space ("hi" -> "00 68 00 69 ").
     * QUIRK (verified in dex): chars whose hex is already >= 4 digits are
     * appended as-is with NO spacing and NO trailing space.
     */
    public static String string2Unicode(String s, boolean spaceFirst) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            String h = Integer.toHexString(s.charAt(i));
            int origLen = h.length();
            if (origLen == 1) h = "0" + h;
            else if (origLen == 2) h = "00" + h;
            else if (origLen == 3) h = "0" + h;
            if (spaceFirst && origLen <= 3) {
                StringBuilder spaced = new StringBuilder();
                for (int j = 0; j < h.length(); j += 2) {
                    if (spaced.length() > 0) spaced.append(' ');
                    spaced.append(h, j, j + 2);
                }
                out.append(spaced).append(' ');
            } else {
                out.append(h);
            }
        }
        return out.toString();
    }

    /** StringUtils.unicode2String: inverse of string2Unicode -- 4 hex
     * chars per char, parsed base-16. (Spaceless input.) */
    public static String unicodeToString(String hex) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i + 4 <= hex.length(); i += 4) {
            out.append((char) Integer.parseInt(hex.substring(i, i + 4), 16));
        }
        return out.toString();
    }

    /** Inbound SMS (68 11) decode, mirroring ReceiveDataPort.onReceive:
     * len = BE u16 of payload-length field, number = BE int of
     * payload[0:3], content = unicodeToString(payload[3:len]).
     * Returns {number, content} or null if the frame is not a 68 11.
     * NOTE: the 3-byte header's internal layout (msgtype? sender ID?)
     * is unverified -- no live inbound-SMS sample exists yet. */
    public static String[] parseSmsNotification(byte[] raw) {
        if (raw == null || raw.length < 10
                || (raw[0] & 0xFF) != 0x68
                || (raw[1] & 0xFF) != 0x11
                || (raw[raw.length - 1] & 0xFF) != 0x10) return null;
        int len = ((raw[6] & 0xFF) << 8) | (raw[7] & 0xFF);
        if (raw.length < 8 + len + 1) return null;
        int number = ((raw[8] & 0xFF) << 16)
                | ((raw[9] & 0xFF) << 8) | (raw[10] & 0xFF);
        StringBuilder hex = new StringBuilder();
        for (int i = 11; i < 8 + len; i++) {
            hex.append(String.format("%02x", raw[i] & 0xFF));
        }
        return new String[]{String.valueOf(number), unicodeToString(hex.toString())};
    }

    /** ATCommond.checkSpaceSum: Internet checksum over "XX XX ..." bytes. */
    public static int checkSpaceSum(String frame) {
        String[] toks = frame.trim().split("\\s+");
        long total = 0;
        int i = 0, n = toks.length;
        while (n > 1) {
            total += ((Integer.parseInt(toks[i], 16) << 8)
                    | Integer.parseInt(toks[i + 1], 16));
            n -= 2;
            i += 2;
        }
        if (n != 0) total += (Integer.parseInt(toks[i], 16) & 0xFF) << 8;
        while ((total >> 16) != 0)
            total = (total & 0xFFFF) + (total >> 16);
        return (int) (total ^ 0xFFFF);
    }

    // ------------------------------------------------------------------
    // Frame assembly
    // ------------------------------------------------------------------

    /**
     * Frame checksum field: ALWAYS 2 bytes big-endian ("00 AA").
     *
     * NOTE: stock StringUtils.decimalToHex(sum, true, false) has a quirk --
     * values &lt; 0x100 (hex digit count &lt;= 2) skip the pair-spacing and come
     * out as a single token ("AA" instead of "00 AA"), producing a 1-byte-short
     * frame the module answers with sr=0x01 (observed live on 68 35,
     * 2026-10-11). RumiTX emits the correct 2-byte form.
     */
    private static String checksumHex(int sum) {
        return String.format("%02X %02X", (sum >> 8) & 0xFF, sum & 0xFF);
    }

    /**
     * Assemble a headed frame: header + checksum + length + payload + 0x10.
     * Length is "00 XX" (1 byte) for payloads <= 255 bytes, else big-endian.
     */
    private static String finalize(String header, String payload) {
        int n = payload.trim().split("\\s+").length;
        String v8 = (n <= 255 ? "00 " : "") + decimalToHex(n, true, false);
        String pre = header + " 00 00 " + v8 + " " + payload + " 10";
        String csum = checksumHex(checkSpaceSum(pre));
        return header + " " + csum + " " + v8 + " " + payload + " 10";
    }

    /**
     * getATSetSend: PTT start/stop. send="01" start, "FF" stop.
     * Layout: 68 06 01 &lt;send&gt; &lt;CS:2B&gt; 00 04 &lt;calltype&gt;
     * &lt;callnumber:3B&gt; 10 (no length field on this frame type).
     */
    public static String buildSend(String send, String calltype,
                                   String callnumber) {
        // dex: stringToHex(calltype, 0, 0),
        // stringToHexWidthByte(callnumber, 1, 0, 3) = BE digits, left-padded.
        String payload = "00 04 " + stringToHex(calltype, false, false) + " "
                + stringToHexWidthByte(callnumber, true, false, 3);
        String pre = "68 06 01 " + send + " 00 00 " + payload + " 10";
        String csum = checksumHex(checkSpaceSum(pre));
        return "68 06 01 " + send + " " + csum + " " + payload + " 10";
    }

    /**
     * 68 36 channel frame, DMR digital channel (talkie == "1").
     *
     * @param txMhz TX frequency MHz (e.g. 446.5555); rxMhz may differ
     *              for split operation -- the frame carries both fields
     *              (the stock app always wrote the same value: simplex)
     * @param rxMhz RX frequency MHz
     * @param flag      1-byte flag (stock passes channel flag; "1" verified)
     * @param phoneid   radio's own DMR ID (decimal string)
     * @param colorcode DMR color code 0-15 (decimal string)
     * @param calltype  TX call type: "0" = group, "4" = private
     * @param callnumber TX talkgroup / ID (decimal string)
     * @param encry     "0" -> byte 0x01 (off); anything else -> 0xFF
     * @param secretkey encryption key (decimal string; zeros when off)
     * @param recgroup  ";"-separated RX group list, up to 32 slots.
     *                  QUIRK: single-char entries are SKIPPED by the app,
     *                  so pad one-digit groups ("1" -> "01").
     */
    public static String buildSetGroupDigital(double txMhz, double rxMhz,
            String flag, String phoneid, String colorcode, String calltype,
            String callnumber, String encry, String secretkey,
            String recgroup) {
        // MHz -> Hz digits: "446.5555" -> "446555500" (app uses 4 decimals).
        String txf = String.format("%.4f", txMhz).replace(".", "") + "00";
        String rxf = String.format("%.4f", rxMhz).replace(".", "") + "00";
        String v9 = "0".equals(encry) ? "01" : "FF";
        String[] parts = recgroup.split(";");
        StringBuilder slots = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            if (slots.length() > 0) slots.append(' ');
            if (i < parts.length && parts[i].length() != 1)
                // dex: stringToHexWidthByte(slot, 1, 0, 4) = BE, left-padded
                slots.append(stringToHexWidthByte(parts[i], true, false, 4));
            else
                slots.append("00 00 00 00");
        }
        // dex arg audit (run 13/16): frequencies use (space=1, reverse=1)
        // = LE; ALL ID fields use (space=1, reverse=0) = big-endian digits,
        // left-padded. 1-byte fields use (1, 0).
        String payload = stringToHex(flag, true, false) + " "
                + stringToHexWidthByte(txf, true, true, 4) + " "
                + stringToHexWidthByte(rxf, true, true, 4) + " "
                + stringToHexWidthByte(phoneid, true, false, 4) + " "
                + stringToHex(colorcode, true, false) + " "
                + stringToHex(calltype, true, false) + " "
                + stringToHexWidthByte(callnumber, true, false, 4) + " "
                + v9 + " "
                + stringToHexWidthByte(secretkey, true, false, 8) + " "
                + slots;
        return finalize("68 36 01 01", payload);
    }

    /**
     * 68 35 channel frame, analog channel (DMR007 mode, talkie != "1").
     * The stock app wrote the same rfv to both fields (simplex; the tfv
     * map key is NOT read in this path). The frame carries separate
     * TX/RX fields, so split operation is expressible but UNTESTED --
     * the 68 35 path has never been live-tested, only decoded.
     */
    public static String buildSetGroupAnalog(double txMhz, double rxMhz,
            String gwb, String flag, String sq, String rxtype,
            String rxcxcss, String txtype, String txcxcss) {
        String txf = String.format("%.4f", txMhz).replace(".", "") + "00";
        String rxf = String.format("%.4f", rxMhz).replace(".", "") + "00";
        String bw = "0".equals(gwb) ? "00" : "80";
        // dex: 1-byte fields all stringToHex(x, 1, 0); freqs (1, 1, 4) = LE.
        String payload = bw + " "
                + stringToHex(flag, true, false) + " "
                + stringToHexWidthByte(rxf, true, true, 4) + " "
                + stringToHexWidthByte(txf, true, true, 4) + " "
                + stringToHex(sq, true, false) + " "
                + stringToHex(rxtype, true, false) + " "
                + stringToHex(rxcxcss, true, false) + " "
                + stringToHex(txtype, true, false) + " "
                + stringToHex(txcxcss, true, false);
        return finalize("68 35 01 01", payload);
    }

    /**
     * getATSendMessage (DMR007 branch): 68 07 SMS-send frame.
     * Layout: 68 07 01 01 &lt;CS:2B&gt; &lt;LEN:2B BE&gt; &lt;payload&gt; 10.
     * payload = stringToHex(msgType) | stringToHexWidthByte(callnumber,3)
     *         | string2Unicode(content) | "00 00 00 00".
     * The module transmits the SMS itself; no PTT cycle needed.
     */
    public static String buildSms(String msgtype, String callnumber,
                                  String content) {
        // dex: stringToHexWidthByte(callnumber, 1, 0, 3) = BE digits.
        String payload = stringToHex(msgtype, true, false) + " "
                + stringToHexWidthByte(callnumber, true, false, 3) + " "
                + string2Unicode(content, true) + "00 00 00 00";
        int n = payload.trim().split("\\s+").length;
        String length = (n <= 255 ? "00 " : "") + decimalToHex(n, true, false);
        String pre = "68 07 01 01 00 00 " + length + " " + payload + " 10";
        String csum = checksumHex(checkSpaceSum(pre));
        return "68 07 01 01 " + csum + " " + length + " " + payload + " 10";
    }

    /** getAtSetvolume: 68 02 01 01 &lt;CS&gt; 00 01 &lt;VV&gt; 10. */
    public static String buildVolume(int level) {
        // dex: decimalToHex(volume, 0, 0) -- plain hex, no spaces.
        return finalize("68 02 01 01", stringToHex(
                String.valueOf(Math.max(0, Math.min(10, level))), false, false));
    }

    /**
     * 10-byte query/status frames (getAtVerq etc.):
     * 68 &lt;cmd&gt; 01 01 &lt;CS&gt; 00 01 01 10.
     */
    public static String buildQuery(int cmd) {
        return finalize(String.format("68 %02X 01 01", cmd),
                stringToHex("1", true, true));
    }

    /**
     * getAtSetAutoPower: 68 0C 01 01 &lt;CS&gt; 00 03 &lt;en&gt; 1E 02 10.
     * dex-verified run 14/16: enable byte "01"/"00" only; the trailing
     * "1E 02" are hardcoded stock constants. Non-DMR007 text form is
     * "AT+DMOAUTOPOWCONTR=&lt;0|1&gt;", driven by the Fragment_Profile
     * auto-power toggle. RumiTX emits the correct 2-byte checksum form.
     */
    public static String buildAutoPower(boolean enable) {
        return finalize("68 0C 01 01", (enable ? "01" : "00") + " 1E 02");
    }

    // ------------------------------------------------------------------
    // Byte conversion + reply parsing
    // ------------------------------------------------------------------

    /** Frame string -> raw bytes for the serial port. */
    public static byte[] toBytes(String frame) {
        String[] toks = frame.trim().split("\\s+");
        byte[] b = new byte[toks.length];
        for (int i = 0; i < toks.length; i++)
            b[i] = (byte) Integer.parseInt(toks[i], 16);
        return b;
    }

    /** Raw bytes -> "XX XX ..." uppercase hex string. */
    public static String toHexString(byte[] b, int off, int len) {
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) r.append(' ');
            r.append(HEX[(b[off + i] >> 4) & 0xF]).append(HEX[b[off + i] & 0xF]);
        }
        return r.toString();
    }

    public static String toHexString(byte[] b) {
        return toHexString(b, 0, b.length);
    }

    /**
     * Validate a received frame's checksum. Reply frames use the short
     * form 68 &lt;cmd&gt; 00 00 &lt;CS:2B&gt; 00 00 10, so the checksum is
     * computed over the frame with the CS bytes zeroed.
     */
    public static boolean validateReply(String frame) {
        String[] t = frame.trim().split("\\s+");
        if (t.length < 8 || !"68".equals(t[0]) || !"10".equals(t[t.length - 1]))
            return false;
        StringBuilder pre = new StringBuilder();
        for (int i = 0; i < t.length; i++) {
            if (i > 0) pre.append(' ');
            pre.append((i == 4 || i == 5) ? "00" : t[i]);
        }
        int sum = checkSpaceSum(pre.toString());
        return checksumHex(sum).equals(t[4] + " " + t[5]);
    }

    /** Command byte of a frame ("68 36 ..." -> 0x36). -1 if malformed. */
    public static int commandOf(String frame) {
        String[] t = frame.trim().split("\\s+");
        if (t.length < 2 || !"68".equals(t[0])) return -1;
        return Integer.parseInt(t[1], 16);
    }

    // ------------------------------------------------------------------
    // Self-test (run: javac Dmr007Frame.java && java
    // com.rumitx.app.Dmr007Frame)
    // ------------------------------------------------------------------

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + what);
        System.out.println("ok: " + what);
    }

    public static void main(String[] args) {
        // Stock-verified send frames (byte-identical to the stock app).
        check(buildSend("01", "0", "0").equals(
                "68 06 01 01 86 F4 00 04 00 00 00 00 10"), "send-start frame");
        check(buildSend("FF", "0", "0").equals(
                "68 06 01 FF 85 F6 00 04 00 00 00 00 10"), "send-stop frame");

        // 68 07 SMS (verified live 2026-10-11 run 7/16).
        check(buildSms("2", "1", "hi").equals(
                "68 07 01 01 84 19 00 0C 02 00 00 01 "
                + "00 68 00 69 00 00 00 00 10"), "sms frame");
        check(string2Unicode("hi", true).equals("00 68 00 69 "),
                "string2Unicode");

        // 10-byte query frames (decompiled vectors).
        check(buildQuery(0x25).equals("68 25 01 01 95 C8 00 01 01 10"),
                "VERQ frame");
        check(buildQuery(0x37).equals("68 37 01 01 95 B6 00 01 01 10"),
                "CHECK-AC frame");
        check(buildQuery(0x38).equals("68 38 01 01 95 B5 00 01 01 10"),
                "CHECK-DC frame");
        check(buildQuery(0x11).equals("68 11 01 01 95 DC 00 01 01 10"),
                "REC-MSG frame");

        // Volume frame (computed by dmr007_frame.py; not yet live-tested).
        check(buildVolume(5).equals("68 02 01 01 91 EB 00 01 05 10"),
                "volume frame");

        // Auto-power frame (getAtSetAutoPower; computed by dmr007_frame.py,
        // layout dex-verified run 14/16; not yet live-tested).
        check(buildAutoPower(true).equals(
                "68 0C 01 01 93 C1 00 03 01 1E 02 10"),
                "autopower-on frame");
        check(buildAutoPower(false).split("\\s+")[8].equals("00"),
                "autopower-off byte");

        // 68 36 digital channel: 446.5555 MHz simplex (verified live).
        String ch = buildSetGroupDigital(446.5555, 446.5555, "1", "1", "1",
                "0", "1", "0", "0", "01");
        String[] toks = ch.trim().split("\\s+");
        check(toks.length == 165, "ch36 length 165 (got " + toks.length + ")");
        String head = String.join(" ",
                java.util.Arrays.copyOfRange(toks, 0, 30));
        check(head.equals("68 36 01 01 83 15 00 9C 01 6C E5 9D 1A 6C E5 9D 1A "
                + "00 00 00 01 01 00 00 00 00 01 01 00 00"),
                "ch36 header/payload");
        check(validateReply("68 36 00 00 87 C9 00 00 10"),
                "ch36 SUCCESS reply checksum");
        check(validateReply("68 07 00 00 87 F8 00 00 10"),
                "sms SUCCESS reply checksum");

        // ID encoding (dex-verified run 13/16): BE digits, left-padded.
        // Frequencies alone use LE; every ID field uses reverse=false.
        check(stringToHexWidthByte("12345", true, false, 4)
                .equals("00 00 30 39"), "ID encoding 12345 (BE)");
        check(stringToHexWidthByte("16777215", true, false, 4)
                .equals("00 FF FF FF"), "ID encoding 16777215 (unset)");
        check(stringToHexWidthByte("12345", true, false, 3)
                .equals("00 30 39"), "SMS ID encoding 12345 (BE)");
        // Multi-byte ID inside a real 68 36 frame: phoneid=26596,
        // callnumber=12345 -> BE bytes at the right payload offsets.
        String chm = buildSetGroupDigital(446.5555, 446.5555, "1", "26596",
                "1", "0", "12345", "0", "0", "01");
        String[] tm = chm.trim().split("\\s+");
        check(tm.length == 165, "ch36 multi-ID length");
        // payload starts at token 8: [9:13]=phoneid, [15:19]=callnumber
        check((tm[17] + " " + tm[18] + " " + tm[19] + " " + tm[20])
                .equals("00 00 67 E4"), "phoneid 26596 bytes");
        check((tm[23] + " " + tm[24] + " " + tm[25] + " " + tm[26])
                .equals("00 00 30 39"), "callnumber 12345 bytes");

        // RX path: unicode2String round-trip + 68 11 inbound-SMS parse
        // (parse mirrors ReceiveDataPort.onReceive; no live sample yet).
        check(unicodeToString("00680069").equals("hi"), "unicodeToString");
        check(unicodeToString(string2Unicode("Hello!", true)
                .replace(" ", "")).equals("Hello!"), "unicode round-trip");
        // Synthetic inbound SMS: 68 11 00 00 <CS> 00 07 | 00 00 01 |
        // 00 68 00 69 ("hi") | 10   (len=7: 3 header + 4 unicode bytes)
        byte[] rx = toBytes("68 11 00 00 00 00 00 07 00 00 01 "
                + "00 68 00 69 10");
        // fix the checksum bytes (4,5) so the frame is well-formed
        int cs = checkSpaceSum("68 11 00 00 00 00 00 07 00 00 01 "
                + "00 68 00 69 10");
        String csHex = checksumHex(cs);
        byte[] csb = toBytes(csHex);
        rx[4] = csb[0]; rx[5] = csb[1];
        // Checksum field is ALWAYS 2 bytes, even for sums < 0x100
        // (stock decimalToHex quirk would emit a 1-byte "AA" here).
        check(checksumHex(0x00AA).equals("00 AA"), "checksumHex sub-0x100");
        check(buildSetGroupAnalog(446.5555, 446.5555, "1", "0", "2", "0",
                "0", "0", "0").equals("68 35 01 01 00 AA 00 0F 80 00 6C E5 9D "
                + "1A 6C E5 9D 1A 02 00 00 00 00 10"),
                "analog 68 35 2-byte checksum");
        String[] sms = parseSmsNotification(rx);
        check(sms != null && sms[0].equals("1") && sms[1].equals("hi"),
                "parseSmsNotification (got "
                + (sms == null ? "null" : sms[0] + "/" + sms[1]) + ")");

        System.out.println("ALL SELF-TESTS PASSED");
    }
}
