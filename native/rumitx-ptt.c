/*
 * rumitx-ptt: PTT helper for the DOOGEE S80 HCT intercom module.
 * ioctl codes recovered from /vendor/lib64/hw/talkie.default.so (64-bit).
 *
 * Run as root (su). Reads one command per line on stdin, replies "OK <cmd>" or "ERR <cmd> <errno>".
 *   start        -> INTERCOM_APP_START
 *   down [val]   -> INTERCOM_KEY_DOWN (val default 0), arms TX watchdog
 *   up [val]     -> INTERCOM_KEY_UP
 *   speak_on     -> INTERCOM_SPEAK_ENABLE (prints returned int)
 *   speak_off    -> INTERCOM_SPEAK_DISABLE
 *   update       -> INTERCOM_MODULE_UPDATE
 *   serial       -> configure /dev/ttyMT1 as 57600 8N1 raw
 *   exit         -> INTERCOM_APP_EXIT
 *   quit         -> unkey if keyed, then exit the program
 * One-shot mode: rumitx-ptt <cmd> [val]
 * Safety: auto-unkeys after TX_TIMEOUT seconds, and on EOF, SIGTERM, SIGINT, or SIGHUP.
 */
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define NODE "/dev/hctintercom_node"
#define INTERCOM_APP_START      0x6901
#define INTERCOM_APP_EXIT       0x6902
#define INTERCOM_KEY_DOWN       0x40046903 /* _IOW('i',3,int) */
#define INTERCOM_KEY_UP         0x40046904 /* _IOW('i',4,int) */
#define INTERCOM_SPEAK_ENABLE   0x80046905 /* _IOR('i',5,int) */
#define INTERCOM_SPEAK_DISABLE  0x6906
#define INTERCOM_MODULE_UPDATE  0x6907

static int fd = -1;
static int keyed = 0;
static int key_val = 0;
static time_t key_time = 0;
static int tx_timeout = 120;

/* Configure /dev/ttyMT1 as 57600 8N1 raw, so Java streams work. */
static int setup_serial(void) {
    int s = open("/dev/ttyMT1", O_RDWR | O_NOCTTY);
    if (s < 0) return -errno;
    struct termios tio;
    if (tcgetattr(s, &tio) < 0) { int e = errno; close(s); return -e; }
    cfmakeraw(&tio);
    cfsetispeed(&tio, B57600); cfsetospeed(&tio, B57600);
    tio.c_cflag &= ~(CSTOPB | PARENB | CRTSCTS);
    tio.c_cflag |= CS8 | CLOCAL | CREAD;
    int r = tcsetattr(s, TCSANOW, &tio) < 0 ? -errno : 0;
    close(s);
    return r;
}

static int do_ioctl(unsigned long req, int *arg) {
    int r = ioctl(fd, req, arg);
    return r < 0 ? -errno : 0;
}

static void unkey(void) {
    if (keyed) {
        int v = key_val;
        do_ioctl(INTERCOM_KEY_UP, &v);
        keyed = 0;
    }
}

static void on_signal(int s) {
    (void)s;
    unkey();
    if (fd >= 0) close(fd);
    _exit(0);
}

static int run(const char *cmd, int val, int have_val) {
    int arg = 0, r;
    if (!strcmp(cmd, "start"))        { r = do_ioctl(INTERCOM_APP_START, &arg);
                                      if (!r) {
                                          /* Enable RX speaker audio on startup */
                                          int sarg = 0;
                                          do_ioctl(INTERCOM_SPEAK_ENABLE, &sarg);
                                      } }
    else if (!strcmp(cmd, "exit"))    { unkey(); r = do_ioctl(INTERCOM_APP_EXIT, &arg); }
    else if (!strcmp(cmd, "down"))    { arg = have_val ? val : 0; r = do_ioctl(INTERCOM_KEY_DOWN, &arg);
                                        if (!r) { keyed = 1; key_val = arg; key_time = time(NULL); } }
    else if (!strcmp(cmd, "up"))      { arg = have_val ? val : key_val; r = do_ioctl(INTERCOM_KEY_UP, &arg);
                                        if (!r) keyed = 0; }
    else if (!strcmp(cmd, "speak_on")) { r = do_ioctl(INTERCOM_SPEAK_ENABLE, &arg);
                                        if (!r) { printf("OK speak_on %d\n", arg); fflush(stdout); return 0; } }
    else if (!strcmp(cmd, "speak_off")) { r = do_ioctl(INTERCOM_SPEAK_DISABLE, &arg); }
    else if (!strcmp(cmd, "serial"))  { r = setup_serial(); }
    else if (!strcmp(cmd, "update"))  { r = do_ioctl(INTERCOM_MODULE_UPDATE, &arg); }
    else { printf("ERR %s unknown\n", cmd); fflush(stdout); return -1; }
    if (r) printf("ERR %s %d\n", cmd, -r); else printf("OK %s\n", cmd);
    fflush(stdout);
    return r;
}

int main(int argc, char **argv) {
    const char *t = getenv("RUMITX_TX_TIMEOUT");
    if (t && atoi(t) > 0) tx_timeout = atoi(t);
    fd = open(NODE, O_RDWR);
    if (fd < 0) { printf("ERR open %d\n", errno); return 1; }
    signal(SIGTERM, on_signal); signal(SIGINT, on_signal);
    signal(SIGHUP, on_signal);  signal(SIGPIPE, on_signal);

    /* TCP mode: listen on 127.0.0.1:45921, accept one client, dup to stdin/stdout.
     * Allows the Android app to talk to the daemon via TCP socket
     * (apps cannot exec binaries on Android 8+). */
    if (getenv("RUMITX_TCP")) {
        int srv = socket(AF_INET, SOCK_STREAM, 0);
        if (srv < 0) { printf("ERR socket %d\n", errno); return 1; }
        int one = 1;
        setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        struct sockaddr_in a;
        memset(&a, 0, sizeof(a));
        a.sin_family = AF_INET;
        a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        a.sin_port = htons(45921);
        if (bind(srv, (struct sockaddr *)&a, sizeof(a)) < 0) {
            printf("ERR bind %d\n", errno); return 1;
        }
        if (listen(srv, 1) < 0) { printf("ERR listen %d\n", errno); return 1; }
        printf("TCP_LISTEN 45921\n"); fflush(stdout);
        /* Single-client mode: accept one connection, serve it until
         * disconnect, then exit. The watcher script restarts us.
         * (Multi-client accept loops have stdio buffering issues.) */
        int cli = accept(srv, NULL, NULL);
        if (cli < 0) { printf("ERR accept %d\n", errno); return 1; }
        close(srv);
        dup2(cli, 0);
        dup2(cli, 1);
        if (cli > 1) close(cli);
        setvbuf(stdout, NULL, _IONBF, 0);
    }

    if (argc >= 2) { /* one-shot mode */
        int r = run(argv[1], argc >= 3 ? atoi(argv[2]) : 0, argc >= 3);
        if (keyed) { /* one-shot down: hold until timeout or signal, never leave keyed */
            sleep(tx_timeout); unkey(); printf("WATCHDOG unkey\n");
        }
        close(fd); return r ? 1 : 0;
    }

    printf("READY\n"); fflush(stdout);
    char line[128];
    for (;;) {
        fd_set rf; FD_ZERO(&rf); FD_SET(0, &rf);
        struct timeval tv = {1, 0};
        int n = select(1, &rf, NULL, NULL, &tv);
        if (keyed && time(NULL) - key_time >= tx_timeout) {
            unkey(); printf("WATCHDOG unkey\n"); fflush(stdout);
        }
        if (n <= 0) continue;
        if (!fgets(line, sizeof line, stdin)) break; /* EOF: parent died */
        char cmd[32] = {0}; int val = 0;
        int k = sscanf(line, "%31s %d", cmd, &val);
        if (k < 1) continue;
        if (!strcmp(cmd, "quit")) break;
        run(cmd, val, k >= 2);
    }
    unkey();
    close(fd);
    return 0;
}
