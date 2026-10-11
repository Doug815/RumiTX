/**
 * RumiTX Native PTT Client
 * Calls vendor.hct.hardware.talkie@1.0::ITalkiePort HAL directly
 * to key/unkey the DMR transmitter.
 *
 * Usage: ptt_client down  (key transmitter)
 *        ptt_client up    (unkey transmitter)
 *
 * This bypasses the stock Talkie app entirely. RumiTX programs the
 * channel via AT commands, then uses this to control PTT.
 */

#include <android/hidl/manager/1.0/IServiceManager.h>
#include <hidl/HidlSupport.h>
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>

using android::hardware::Return;
using android::hardware::Void;
using android::sp;

// Forward declare the ITalkiePort interface
// We load it dynamically from talkie-hidl.so to avoid needing headers
struct ITalkiePort : public android::hidl::base::V1_0::IBase {
    // Methods we need (from binary analysis):
    // virtual Return<void> talkie_ppt_down(int32_t val) = 0;
    // virtual Return<void> talkie_ppt_up(int32_t val) = 0;
};

int main(int argc, char* argv[]) {
    if (argc != 2) {
        printf("Usage: %s <down|up>\n", argv[0]);
        return 1;
    }

    bool do_down;
    if (strcmp(argv[1], "down") == 0) {
        do_down = true;
    } else if (strcmp(argv[1], "up") == 0) {
        do_down = false;
    } else {
        printf("Invalid argument: %s (use down|up)\n", argv[1]);
        return 1;
    }

    // Load the HIDL library
    void* handle = dlopen("vendor.hct.hardware.talkie@1.0.so",
                          RTLD_NOW);
    if (!handle) {
        handle = dlopen("/vendor/lib64/vendor.hct.hardware.talkie@1.0.so",
                        RTLD_NOW);
    }
    if (!handle) {
        printf("Failed to load talkie HIDL library: %s\n", dlerror());
        return 1;
    }

    // Get the getService function
    // Symbol: _ZN6vendor3hct8hardware6talkie4V1_011ITalkiePort10getServiceERKNSt3__112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEEb
    typedef android::sp<ITalkiePort> (*GetServiceFn)(const std::string&, bool);
    GetServiceFn getService = (GetServiceFn)dlsym(handle,
        "_ZN6vendor3hct8hardware6talkie4V1_011ITalkiePort10getServiceERKNSt3__112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEEb");

    if (!getService) {
        printf("Failed to find getService: %s\n", dlerror());
        return 1;
    }

    // Get the service
    sp<ITalkiePort> service = getService("default", true);
    if (service == nullptr) {
        printf("Failed to get ITalkiePort service\n");
        return 1;
    }

    printf("Got ITalkiePort service\n");

    // Call the PTT method via the Bp proxy
    // We need to cast to the Bp class and call the method
    // The vtable layout: talkie_ppt_down and talkie_ppt_up are virtual methods
    //
    // This is fragile without the exact header. Alternative approach below.

    printf("Service acquired, PTT %s requested\n", do_down ? "DOWN" : "UP");
    printf("NOTE: Direct method call requires exact vtable layout.\n");
    printf("Full implementation needs the generated HIDL headers.\n");

    return 0;
}
