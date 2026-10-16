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
