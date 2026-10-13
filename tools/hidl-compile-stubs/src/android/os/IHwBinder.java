package android.os;
public interface IHwBinder {
    int FLAG_ONEWAY = 1;
    interface DeathRecipient {
        void serviceDied(long cookie);
    }
    void transact(int code, HwParcel request, HwParcel reply, int flags) throws RemoteException;
}
