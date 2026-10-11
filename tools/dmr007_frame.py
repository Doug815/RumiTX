#!/usr/bin/env python3
"""DMR007 binary frame builder for the DOOGEE S80 (RumiTX project).

Exact port of com.android.talkie.ATCommond.getATSetGroup (DMR007 branch)
and getATSetSend, plus com.android.talkie.common.StringUtils helpers,
reverse-engineered from HcTTalkie.vdex (oatdump), 2026-10-11.

Frame format: space-separated hex bytes, 0x68 ... 0x10,
checksum = Internet checksum (one's complement) over the frame with
a "00 00" placeholder where the checksum goes.
"""

HEX = "0123456789ABCDEF"


def decimal_to_hex(value: int, space: bool, reverse: bool) -> str:
    """StringUtils.decimalToHex(int, boolean space, boolean reverse)."""
    s = ""
    v = value
    while v != 0:
        s = HEX[v % 16] + s
        v //= 16
    if len(s) % 2 == 1:
        s = "0" + s
    if s == "":
        return "00"
    if space and len(s) > 2:
        pairs = [s[i:i + 2] for i in range(0, len(s), 2)]
        if reverse:
            pairs = pairs[::-1]
        s = " ".join(pairs)
    return s


def string_to_hex(s: str, space: bool, reverse: bool) -> str:
    """StringUtils.stringToHex(String, boolean, boolean): decimal string -> hex."""
    return decimal_to_hex(int(s), space, reverse)


def string_to_hex_width_byte(s: str, space: bool, reverse: bool, width: int) -> str:
    """StringUtils.stringToHexWidthByte(String, boolean, boolean, int).

    Parses the decimal string, formats hex (see decimal_to_hex), then
    left-pads with "00" bytes up to `width` bytes.
    """
    h = decimal_to_hex(int(s), space, reverse)
    if space:
        toks = h.split(" ")
        pad = ["00"] * max(0, width - len(toks))
        return " ".join(pad + toks)
    else:
        return "00" * max(0, width - len(h)) + h


def check_space_sum(frame: str) -> int:
    """ATCommond.checkSpaceSum: Internet checksum over space-separated hex bytes."""
    toks = frame.split(" ")
    total = 0
    i, n = 0, len(toks)
    while n > 1:
        total += ((int(toks[i], 16) << 8) | int(toks[i + 1], 16))
        n -= 2
        i += 2
    if n != 0:  # odd trailing byte -> high byte of word
        total += (int(toks[i], 16) & 0xFF) << 8
    while (total >> 16) != 0:  # carry fold
        total = (total & 0xFFFF) + (total >> 16)
    return total ^ 0xFFFF


def checksum_hex(total: int) -> str:
    """Frame checksum field: ALWAYS 2 bytes big-endian ("00 AA").

    NOTE: the stock StringUtils.decimalToHex(sum, true, false) has a quirk --
    values < 0x100 (hex digit count <= 2) skip the pair-spacing and come out
    as a single token ("AA" instead of "00 AA"), producing a 1-byte-short
    frame the module answers with sr=0x01 (observed live on 68 35,
    2026-10-11 run 11/16). RumiTX emits the correct 2-byte form.
    """
    return "%02X %02X" % ((total >> 8) & 0xFF, total & 0xFF)


def _finalize(header: str, payload: str) -> str:
    """Assemble: header + checksum + length + payload + end."""
    n = len(payload.split(" "))
    v8 = "00 " + decimal_to_hex(n, True, False) if n <= 255 else decimal_to_hex(n, True, False)
    pre = "%s 00 00 %s %s 10" % (header, v8, payload)
    csum = checksum_hex(check_space_sum(pre))
    return "%s %s %s %s 10" % (header, csum, v8, payload)


def build_send(send="01", calltype="0", callnumber="0") -> str:
    """getATSetSend: PTT start/stop. send="01" start, "FF" stop.

    dex arg audit (2026-10-11 run 13/16): stringToHex(calltype, 0, 0),
    stringToHexWidthByte(callnumber, 1, 0, 3) -- the 3-byte ID is
    big-endian digits, left-padded ("12345" -> "00 30 39"), NOT LE.
    (Our zero-guard in decimal_to_hex emits "00" for "0"; the dex
    decimalToHex returns "" for 0 -- a latent stock quirk never hit in
    practice since the DB uses "16777215" for unset. Our form is the
    live-verified working one.)
    """
    payload = "00 04 %s %s" % (
        string_to_hex(calltype, False, False),
        string_to_hex_width_byte(callnumber, True, False, 3),
    )
    pre = "68 06 01 %s 00 00 %s 10" % (send, payload)
    csum = checksum_hex(check_space_sum(pre))
    return "68 06 01 %s %s %s 10" % (send, csum, payload)


def build_set_group_digital(rfv, flag="1", phoneid="1", colorcode="1",
                            calltype="0", callnumber="1", encry="0",
                            secretkey="0", recgroup="01") -> str:
    """68 36 channel frame, DMR digital channel (talkie == "1").

    rfv: "446.5555" -> Hz digits "446555500", little-endian 4 bytes on wire.
    flag: 1 byte, Channel.getFlag() via stringToHex(flag,1,0); semantics
          not fully determined (DB-driven; editor not in this vdex) --
          "1" live-verified working, factory stock channel used 0x01.
    phoneid: radio's own DMR ID, 4 bytes BE (big-endian digits,
             left-padded: "12345" -> "00 00 30 39").
    colorcode: DMR color code 0-15.
    calltype/callnumber: TX call type (0=group) + talkgroup/ID, 4 bytes BE.
    encry: "0" -> byte 0x01, anything else -> 0xFF (as coded in the app).
    secretkey: 8 bytes BE (zeros when encry == "0").
    recgroup: ";"-separated RX group list, up to 32 slots x 4 bytes BE;
              single-char entries are SKIPPED by the app (pad "1" -> "01").
    """
    freq = "".join(rfv.split(".")) + "00"  # MHz string -> Hz digits
    v9 = "01" if encry == "0" else "FF"
    parts = recgroup.split(";")
    slots = []
    for i in range(32):
        if i < len(parts) and len(parts[i]) != 1:
            # dex: stringToHexWidthByte(slot, 1, 0, 4) = BE, left-padded
            slots += string_to_hex_width_byte(parts[i], True, False, 4).split(" ")
        else:
            slots += ["00", "00", "00", "00"]
    # dex arg audit (run 13/16): frequencies use (space=1, reverse=1) = LE;
    # ALL ID fields use (space=1, reverse=0) = big-endian digits,
    # left-padded. 1-byte fields use (1, 0).
    payload = " ".join([
        string_to_hex(flag, True, False),
        string_to_hex_width_byte(freq, True, True, 4),   # TX freq (Hz, LE)
        string_to_hex_width_byte(freq, True, True, 4),   # RX freq (Hz, LE)
        string_to_hex_width_byte(phoneid, True, False, 4),
        string_to_hex(colorcode, True, False),
        string_to_hex(calltype, True, False),
        string_to_hex_width_byte(callnumber, True, False, 4),
        v9,
        string_to_hex_width_byte(secretkey, True, False, 8),
    ] + slots)
    return _finalize("68 36 01 01", payload)


# Tone type names, from the stock app's array/csstype resource
# (res/values in stock-talkie.apk). Index = csstype/txtype/rxtype value.
CSSTYPE_NAMES = ["Carrier", "CTCSS", "CDCSS", "Reverse CDCSS"]


def build_set_group_analog(rfv, gwb="0", flag="0", sq="1", rxtype="0",
                           rxcxcss="0", txtype="0", txcxcss="0") -> str:
    """68 35 channel frame, analog channel (talkie != "1", DMR007 mode).

    gwb: "0" -> 0x00 (narrow), anything else -> 0x80 (wide).
    sq: squelch level.
    rxtype/txtype: tone type, index into array/csstype =
        "0" Carrier (no tone), "1" CTCSS, "2" CDCSS (DCS normal),
        "3" Reverse CDCSS (DCS inverted). The stock app feeds BOTH from
        Channel.getCsstype(), so it always programs rxtype == txtype.
    rxcxcss/txcxcss: tone code INDEX as decimal string -> 1 byte each.
        Stock UI offers 0-50 ("CTCSS"-labeled list) when csstype == "3",
        else 0-82 ("CDCSS"-labeled list); factory default "1".
        The module maps the index via its own internal tone tables
        (physical tone per index NOT yet verified live).
    """
    freq = "".join(rfv.split(".")) + "00"
    bw = "00" if gwb == "0" else "80"
    # dex: 1-byte fields all stringToHex(x, 1, 0); freqs (1, 1, 4) = LE.
    payload = " ".join([
        bw,
        string_to_hex(flag, True, False),
        string_to_hex_width_byte(freq, True, True, 4),
        string_to_hex_width_byte(freq, True, True, 4),
        string_to_hex(sq, True, False),
        string_to_hex(rxtype, True, False),
        string_to_hex(rxcxcss, True, False),
        string_to_hex(txtype, True, False),
        string_to_hex(txcxcss, True, False),
    ])
    return _finalize("68 35 01 01", payload)


def string_2_unicode(s: str, space_first: bool) -> str:
    """StringUtils.string2Unicode(String, boolean).

    Per char: Integer.toHexString(c), left-padded to 4 hex digits
    ("41"->"0041"). If space_first, hex digits are pair-spaced
    ("0041"->"00 41") and each char is followed by a space, so the
    result ends with a trailing space: "hi" -> "00 68 00 69 ".
    QUIRK (verified in dex): chars whose hex is already >= 4 digits
    are appended as-is with NO spacing and NO trailing space.
    """
    parts = []
    for ch in s:
        h = "%x" % ord(ch)
        orig_len = len(h)
        if orig_len == 1:
            h = "0" + h
        elif orig_len == 2:
            h = "00" + h
        elif orig_len == 3:
            h = "0" + h
        if space_first and orig_len <= 3:
            h = " ".join(h[i:i + 2] for i in range(0, len(h), 2))
            parts.append(h + " ")
        else:
            parts.append(h)
    return "".join(parts)


def build_sms(msgtype="2", callnumber="1", content="hi") -> str:
    """getATSendMessage (DMR007 branch): 68 07 SMS-send frame.

    Layout: 68 07 01 01 <CS:2B> <LEN:2B BE> <payload> 10,
    LEN = payload token count (2-byte big-endian).
    payload = stringToHex(msgType,1,0)            -- Channel.getMsgType(),
                 stock DB value is "2" for every channel
               | stringToHexWidthByte(callnumber,1,0,3)  -- Channel.getCallNumber(),
                 destination talkgroup/ID, 3 bytes BE (big-endian digits,
                 left-padded: "12345" -> "00 30 39"; dex-verified run 13/16)
               | string2Unicode(content,1)         -- UTF-16BE-ish hex, trailing
                 space per char (no double-space before the trailer)
               | "00 00 00 00"                    -- 4 zero bytes
    vtable calibration (ChatActivity HashMap-put sequence vs Channel method
    list): vtable slot = class-list index + 10 (Object vtable = 10 slots).
    sendText uses vtable@21 -> getMsgType, vtable@12 -> getCallNumber.
    """
    payload = "%s %s %s00 00 00 00" % (
        string_to_hex(msgtype, True, False),
        string_to_hex_width_byte(callnumber, True, False, 3),
        string_2_unicode(content, True),
    )
    n = len(payload.split(" "))
    length = ("00 " if n <= 255 else "") + decimal_to_hex(n, True, False)
    pre = "68 07 01 01 00 00 %s %s 10" % (length, payload)
    csum = checksum_hex(check_space_sum(pre))
    return "68 07 01 01 %s %s %s 10" % (csum, length, payload)


def build_volume(level=5) -> str:
    """getAtSetvolume (DMR007 branch): 68 02 01 01 <CS> 00 01 <VV> 10.

    dex: decimalToHex(volume, 0, 0) -- plain hex, no spaces (e.g. 8 -> "08").
    Non-DMR007 text form: "AT+DMOSETVOLUME=<v>". Module reply not observed
    live yet (fire-and-forget in RumiTX's RadioController.setVolume()).
    """
    level = max(0, min(10, level))
    payload = decimal_to_hex(level, False, False)
    pre = "68 02 01 01 00 00 00 01 %s 10" % payload
    csum = checksum_hex(check_space_sum(pre))
    return "68 02 01 01 %s 00 01 %s 10" % (csum, payload)


def build_query(cmd=0x25) -> str:
    """10-byte query/status frames: 68 <cmd> 01 01 <CS> 00 01 01 10.

    cmd: 0x25 VERQ (getAtVerq), 0x37 CHECK-AC (getATCheckACGroup),
         0x38 CHECK-DC (getATCheckDCGroup), 0x11 REC-MSG (getATRecMessage).
    All four verified live 2026-10-11 (module replies with the same
    Internet checksum; see DMR007-PROTOCOL.txt MODULE REPLY FRAME FORMAT).
    """
    return _finalize("68 %02X 01 01" % cmd, "01")


def build_autopower(enable=True) -> str:
    """getAtSetAutoPower (DMR007 branch): 68 0C 01 01 <CS> 00 03 <en> 1E 02 10.

    dex-verified 2026-10-11 run 14/16: the enable byte is "01" (arg==1)
    or "00"; the trailing "1E 02" are hardcoded constants (0x1E = 30,
    presumably a fixed timeout in minutes; 0x02 a mode byte -- exact
    module semantics unverified). Non-DMR007 text form is
    "AT+DMOAUTOPOWCONTR=<0|1>". Called from the Fragment_Profile
    auto-power toggle switch (onCheckedChanged). Stock computes the
    checksum with decimalToHex(sum, true, false), so its own sub-0x100
    quirk applies; RumiTX emits the correct 2-byte form via checksum_hex.
    """
    payload = ("01" if enable else "00") + " 1E 02"
    return _finalize("68 0C 01 01", payload)


def to_bytes(frame: str) -> bytes:
    """Frame string -> raw bytes for the serial port."""
    return bytes(int(t, 16) for t in frame.split(" "))


def decode_channel_dump(payload_hex: str) -> dict:
    """Decode a 156-byte 68 38 (CHECK-DC) channel dump payload.

    Layout mirrors build_set_group_digital exactly:
      [0]     flag (1B)
      [1:5]   TX freq, LE u32 Hz
      [5:9]   RX freq, LE u32 Hz
      [9:13]  phoneid, ID u32: BE digits LEFT-padded with 00 to 4 B
              (stringToHexWidthByte(s,1,0,4); "1" -> 00 00 00 01,
              "12345" -> 00 00 30 39 -- dex-verified run 13/16, was
              misread as LE in runs 6-12)
      [13]    color code (1B)
      [14]    calltype (1B; 0 = group call)
      [15:19] callnumber, ID u32, same BE encoding (stock "unset" =
              16777215 -> 00 FF FF FF)
      [19]    encry flag (1B; 0x01 = off per the app's inverted coding:
              "01" if encry=="0" else "FF", dex-verified)
      [20:28] secretkey (8B BE)
      [28:156] recgroup: 32 slots x 4B BE u32; empty = 00 00 00 00
    """
    toks = payload_hex.split(" ")
    assert len(toks) == 156, "expected 156 payload bytes, got %d" % len(toks)
    b = bytes(int(t, 16) for t in toks)
    le32 = lambda o: int.from_bytes(b[o:o + 4], "little")

    def id32(o):
        """ID fields: stock app writes BE digits LEFT-padded with 00 to the
        field width (stringToHexWidthByte(s, 1, 0, 4)). E.g. "1" ->
        00 00 00 01, "12345" -> 00 00 30 39, "16777215" -> 00 FF FF FF.
        Strip the leading zero padding, then read the rest big-endian."""
        raw = b[o:o + 4].lstrip(b"\x00")
        return int.from_bytes(raw or b"\x00", "big")

    return {
        "flag": b[0],
        "tx_hz": le32(1), "tx_mhz": le32(1) / 1e6,
        "rx_hz": le32(5), "rx_mhz": le32(5) / 1e6,
        "phoneid": id32(9),
        "colorcode": b[13],
        "calltype": b[14],
        "callnumber": id32(15),
        "encry": b[19],
        "secretkey": b[20:28].hex(" "),
        "recgroup": [id32(28 + 4 * i) for i in range(32)],
    }


if __name__ == "__main__":
    # Sanity check against the known-good send frames from the stock app.
    start = build_send("01", "0", "0")
    stop = build_send("FF", "0", "0")
    print("start:", start)
    print("stop: ", stop)
    assert start == "68 06 01 01 86 F4 00 04 00 00 00 00 10", "send-start mismatch!"
    assert stop == "68 06 01 FF 85 F6 00 04 00 00 00 00 10", "send-stop mismatch!"
    print("send-frame checksums verified OK")

    # Target: 446.5555 MHz DMR simplex channel.
    ch = build_set_group_digital(
        rfv="446.5555", flag="1", phoneid="1", colorcode="1",
        calltype="0", callnumber="1", encry="0", secretkey="0",
        recgroup="01",
    )
    print("ch36: ", ch)
    print("bytes:", len(ch.split(" ")))

    # ID byte order (dex-verified run 13/16): big-endian digits,
    # left-padded -- "12345" -> "00 00 30 39", NOT "00 00 39 30".
    assert string_to_hex_width_byte("12345", True, False, 4) == "00 00 30 39", \
        "ID BE encoding mismatch!"
    assert string_to_hex_width_byte("16777215", True, False, 4) == "00 FF FF FF", \
        "ID BE encoding mismatch (unset)!"
    assert string_to_hex_width_byte("12345", True, False, 3) == "00 30 39", \
        "SMS ID BE encoding mismatch!"
    ch_multi = build_set_group_digital(
        rfv="446.5555", flag="1", phoneid="26596", colorcode="1",
        calltype="0", callnumber="12345", encry="0", secretkey="0",
        recgroup="01",
    )
    toks_multi = ch_multi.split(" ")
    # payload offsets: [9:13] phoneid, [15:19] callnumber (frame offset +8)
    assert toks_multi[8 + 9:8 + 13] == ["00", "00", "67", "E4"], \
        "phoneid 26596 bytes wrong: %s" % toks_multi[17:21]
    assert toks_multi[8 + 15:8 + 19] == ["00", "00", "30", "39"], \
        "callnumber 12345 bytes wrong: %s" % toks_multi[23:27]
    # decode round-trip through the fixed decoder
    dec = decode_channel_dump(" ".join(toks_multi[8:8 + 156]))
    assert dec["phoneid"] == 26596, "decode phoneid: %r" % dec["phoneid"]
    assert dec["callnumber"] == 12345, "decode callnumber: %r" % dec["callnumber"]
    print("ID BE encoding + decode round-trip verified OK")

    # 68 07 SMS frame (getATSendMessage, DMR007 branch). Verified live
    # 2026-10-11 run 7/16: module replies 68 07 00 00 87 F8 00 00 10
    # (SUCCESS) and transmits the SMS on RF.
    sms = build_sms(msgtype="2", callnumber="1", content="hi")
    print("sms:  ", sms)
    assert sms == "68 07 01 01 84 19 00 0C 02 00 00 01 00 68 00 69 00 00 00 00 10", \
        "sms frame mismatch!"
    assert string_2_unicode("hi", True) == "00 68 00 69 ", "unicode mismatch!"
    print("sms frame verified OK")

    # 10-byte query frames (getAtVerq / getATCheckACGroup / getATCheckDCGroup
    # / getATRecMessage). Vectors are the live-verified module queries from
    # the protocol doc (checksums validate with check_space_sum).
    assert build_query(0x25) == "68 25 01 01 95 C8 00 01 01 10", "VERQ mismatch!"
    assert build_query(0x37) == "68 37 01 01 95 B6 00 01 01 10", "CHECK-AC mismatch!"
    assert build_query(0x38) == "68 38 01 01 95 B5 00 01 01 10", "CHECK-DC mismatch!"
    assert build_query(0x11) == "68 11 01 01 95 DC 00 01 01 10", "REC-MSG mismatch!"
    print("query frames verified OK")

    # 68 02 volume-set (getAtSetvolume). Cross-check: the checksum of the
    # emitted frame must validate when re-computed over the 00-00 form.
    vol = build_volume(5)
    print("vol:  ", vol)
    toks = vol.split(" ")
    pre = " ".join(toks[:4] + ["00", "00"] + toks[6:])
    assert checksum_hex(check_space_sum(pre)) == " ".join(toks[4:6]), \
        "volume checksum mismatch!"
    print("volume frame verified OK")

    # 68 0C auto-power (getAtSetAutoPower). Layout dex-verified run 14/16:
    # "68 0C 01 01 <CS> 00 03 <01|00> 1E 02 10".
    ap = build_autopower(True)
    print("apwr: ", ap)
    toks = ap.split(" ")
    assert toks[:4] == ["68", "0C", "01", "01"], "autopower header mismatch!"
    assert toks[6:] == ["00", "03", "01", "1E", "02", "10"], \
        "autopower payload mismatch: %s" % toks[6:]
    pre = " ".join(toks[:4] + ["00", "00"] + toks[6:])
    assert checksum_hex(check_space_sum(pre)) == " ".join(toks[4:6]), \
        "autopower checksum mismatch!"
    ap_off = build_autopower(False)
    assert ap_off.split(" ")[8] == "00", "autopower off-byte mismatch!"
    print("autopower frames verified OK")
