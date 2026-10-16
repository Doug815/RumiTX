#!/usr/bin/env bash
# Generate compileOnly Android 8.1 hidden HIDL signature types from the verified RumiTX source.
# NEVER include these classes in APK runtime: the S80 firmware provides them.
set -euo pipefail
cd "$(dirname "$0")/.."
PLATFORM="$ANDROID_HOME/platforms/android-33/android.jar"
[[ -r "$PLATFORM" ]] || { echo "Android SDK platform 33 missing" >&2; exit 2; }
SRC="$(mktemp -d)"
trap 'rm -rf "$SRC"' EXIT
mkdir -p "$SRC/classes" app/libs
mkdir -p "$SRC/src/android/hidl/base/V1_0"
cat > "$SRC/src/android/hidl/base/V1_0/DebugInfo.java" <<'RUMITX_HIDL_STUB_END'
package android.hidl.base.V1_0;
/** Android 8.1 HIDL base compile stub; never package this type. */
public class DebugInfo {
    public int pid;
    public long ptr;
    public int arch;
    public void writeToParcel(android.os.HwParcel parcel) { throw new AssertionError("compile-only"); }
}
RUMITX_HIDL_STUB_END
mkdir -p "$SRC/src/android/hidl/base/V1_0"
cat > "$SRC/src/android/hidl/base/V1_0/IBase.java" <<'RUMITX_HIDL_STUB_END'
package android.hidl.base.V1_0;
public interface IBase extends android.os.IHwInterface {}
RUMITX_HIDL_STUB_END
mkdir -p "$SRC/src/android/os"
cat > "$SRC/src/android/os/HwBinder.java" <<'RUMITX_HIDL_STUB_END'
package android.os;
/** Signature stub for the device's hidden Android 8.1 class; COMPILE ONLY. */
public class HwBinder implements IHwBinder {
    public static void joinRpcThreadpool() { throw new AssertionError("compile-only"); }
    public void transact(int code, HwParcel req, HwParcel reply, int flags)
            throws RemoteException { throw new AssertionError("compile-only"); }
    public void onTransact(int code, HwParcel req, HwParcel reply, int flags)
            throws RemoteException { throw new AssertionError("compile-only"); }
    public IHwInterface queryLocalInterface(String descriptor) { throw new AssertionError("compile-only"); }
    public boolean linkToDeath(IHwBinder.DeathRecipient recipient, long cookie) { throw new AssertionError("compile-only"); }
    public boolean unlinkToDeath(IHwBinder.DeathRecipient recipient) { throw new AssertionError("compile-only"); }
}
RUMITX_HIDL_STUB_END
mkdir -p "$SRC/src/android/os"
cat > "$SRC/src/android/os/HwBlob.java" <<'RUMITX_HIDL_STUB_END'
package android.os;
/** Signature stub for the device's hidden Android 8.1 class; COMPILE ONLY. */
public class HwBlob {
    public HwBlob(int n) { throw new AssertionError("compile-only"); }
    public void putInt32(long offset, int value) { throw new AssertionError("compile-only"); }
    public void putBool(long offset, boolean value) { throw new AssertionError("compile-only"); }
    public void putInt8(long offset, byte value) { throw new AssertionError("compile-only"); }
    public void putBlob(long offset, HwBlob blob) { throw new AssertionError("compile-only"); }
}
RUMITX_HIDL_STUB_END
mkdir -p "$SRC/src/android/os"
cat > "$SRC/src/android/os/HwParcel.java" <<'RUMITX_HIDL_STUB_END'
package android.os;
import java.util.ArrayList;
/** Signature stub for the device's hidden Android 8.1 class; COMPILE ONLY. */
public class HwParcel {
    public void writeInterfaceToken(String iface) { throw new AssertionError("compile-only"); }
    public void enforceInterface(String iface) { throw new AssertionError("compile-only"); }
    public void verifySuccess() { throw new AssertionError("compile-only"); }
    public int readInt32() { throw new AssertionError("compile-only"); }
    public void writeStrongBinder(IHwBinder binder) { throw new AssertionError("compile-only"); }
    public void releaseTemporaryStorage() { throw new AssertionError("compile-only"); }
    public void release() { throw new AssertionError("compile-only"); }
    public ArrayList<String> readStringVector() { throw new AssertionError("compile-only"); }
    public void writeStringVector(ArrayList<String> v) { throw new AssertionError("compile-only"); }
    public void writeString(String s) { throw new AssertionError("compile-only"); }
    public void writeStatus(int status) { throw new AssertionError("compile-only"); }
    public void writeBuffer(HwBlob value) { throw new AssertionError("compile-only"); }
    public void send() { throw new AssertionError("compile-only"); }
}
RUMITX_HIDL_STUB_END
mkdir -p "$SRC/src/android/os"
cat > "$SRC/src/android/os/IHwBinder.java" <<'RUMITX_HIDL_STUB_END'
package android.os;
public interface IHwBinder {
    int FLAG_ONEWAY = 1;
    interface DeathRecipient {
        void serviceDied(long cookie);
    }
    void transact(int code, HwParcel request, HwParcel reply, int flags) throws RemoteException;
}
RUMITX_HIDL_STUB_END
mkdir -p "$SRC/src/android/os"
cat > "$SRC/src/android/os/IHwInterface.java" <<'RUMITX_HIDL_STUB_END'
package android.os;
public interface IHwInterface {
    IHwBinder asBinder();
}
RUMITX_HIDL_STUB_END
find "$SRC/src" -name '*.java' -print0 | xargs -0 javac -source 8 -target 8 -cp "$PLATFORM" -d "$SRC/classes"
jar cf app/libs/hidl-compile-stubs.jar -C "$SRC/classes" .
echo "Created compile-only HIDL signatures. No HIDL stubs are packaged in APK."
