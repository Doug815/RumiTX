/*
 * rumitx-ptt: PTT helper for the DOOGEE S80 HCT intercom module.
 * ioctl codes recovered from /vendor/lib64/hw/talkie.default.so (64-bit).
 *
 * Run as root (su). Reads one command per line, replies "OK <cmd>" or "ERR <cmd> <errno>".
 *   start        -> INTERCOM_APP_START
 *   down [val]   -> INTERCOM_KEY_DOWN (val default 0), arms TX watchdog
 *   up [val]     -> INTERCOM_KEY_UP
 *   speak_on     -> INTERCOM_SPEAK_ENABLE (prints returned int)
 *   speak_off    -> INTERCOM_SPEAK_DISABLE
 *   update       -> INTERCOM_MODULE_UPDATE
 *   serial       -> configure /dev/ttyMT1 as 57600 8N1 raw
 *   ping         -> OK ping (no ioctl, no UART, no RF)
 *   status       -> OK status keyed=0|1 timeout=<seconds>
 *   exit         -> INTERCOM_APP_EXIT
 *   quit         -> unkey if keyed, then drop this client
 * One-shot mode: rumitx-ptt <cmd> [val]
 * TCP mode (RUMITX_TCP): listen on 127.0.0.1:45921 (override with RUMITX_PORT).
 *   Accept a client, serve it, unkey when it disconnects, then accept again.
 *   Do not compile with -DRUMITX_HOST_TEST for the phone binary. That flag
 *   skips the intercom device and is only for the host protocol test.
 * Safety: auto-unkeys after TX_TIMEOUT seconds, and on EOF, SIGTERM, SIGINT, or SIGHUP.
 */
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
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
static int client_fd = -1;
static int keyed = 0;
static int key_val = 0;
static time_t key_time = 0;
static int tx_timeout = 120;

static void reply(const char *s) {
    size_t n = strlen(s);
    if (client_fd >= 0) {
        const char *p = s;
        while (n > 0) {
            ssize_t w = write(client_fd, p, n);
            if (w < 0) {
                if (errno == EINTR) continue;
                return;
            }
            p += w;
            n -= (size_t)w;
        }
        return;
    }
    fputs(s, stdout);
    fflush(stdout);
}

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
#ifdef RUMITX_HOST_TEST
    (void)req;
    (void)arg;
    return 0;
#else
    int r = ioctl(fd, req, arg);
    return r < 0 ? -errno : 0;
#endif
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
    char buf[96];
    if (!strcmp(cmd, "ping")) { reply("OK ping\n"); return 0; }
    if (!strcmp(cmd, "status")) {
        snprintf(buf, sizeof buf, "OK status keyed=%d timeout=%d\n", keyed, tx_timeout);
        reply(buf);
        return 0;
    }
    if (!strcmp(cmd, "start"))        { r = do_ioctl(INTERCOM_APP_START, &arg); }
    else if (!strcmp(cmd, "exit"))    { unkey(); r = do_ioctl(INTERCOM_APP_EXIT, &arg); }
    else if (!strcmp(cmd, "down"))    { arg = have_val ? val : 0; r = do_ioctl(INTERCOM_KEY_DOWN, &arg);
                                        if (!r) { keyed = 1; key_val = arg; key_time = time(NULL); } }
    else if (!strcmp(cmd, "up"))      { arg = have_val ? val : key_val; r = do_ioctl(INTERCOM_KEY_UP, &arg);
                                        if (!r) keyed = 0; }
    else if (!strcmp(cmd, "speak_on")) { r = do_ioctl(INTERCOM_SPEAK_ENABLE, &arg);
                                        if (!r) { snprintf(buf, sizeof buf, "OK speak_on %d\n", arg); reply(buf); return 0; } }
    else if (!strcmp(cmd, "speak_off")) { r = do_ioctl(INTERCOM_SPEAK_DISABLE, &arg); }
    else if (!strcmp(cmd, "serial"))  { r = setup_serial(); }
    else if (!strcmp(cmd, "update"))  { r = do_ioctl(INTERCOM_MODULE_UPDATE, &arg); }
    else { snprintf(buf, sizeof buf, "ERR %s unknown\n", cmd); reply(buf); return -1; }
    if (r) snprintf(buf, sizeof buf, "ERR %s %d\n", cmd, -r);
    else snprintf(buf, sizeof buf, "OK %s\n", cmd);
    reply(buf);
    return r;
}

/* Returns 1 on a full line, 0 on EOF, -1 on error. Watchdog runs while blocked. */
static int read_line(char *buf, int cap) {
    int i = 0;
    int fdn = client_fd >= 0 ? client_fd : 0;
    while (i < cap - 1) {
        fd_set rf;
        FD_ZERO(&rf);
        FD_SET(fdn, &rf);
        struct timeval tv;
        tv.tv_sec = 1;
        tv.tv_usec = 0;
        int n = select(fdn + 1, &rf, NULL, NULL, &tv);
        if (keyed && time(NULL) - key_time >= tx_timeout) {
            unkey();
            reply("WATCHDOG unkey\n");
        }
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) continue;
        char c;
        ssize_t r = read(fdn, &c, 1);
        if (r == 0) return 0;
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (c == '\n') { buf[i] = 0; return 1; }
        if (c != '\r') buf[i++] = c;
    }
    buf[i] = 0;
    return 1;
}

static void serve(void) {
    char line[128];
    reply("READY\n");
    for (;;) {
        int rc = read_line(line, (int)sizeof line);
        if (rc <= 0) break;
        char cmd[32] = {0};
        int val = 0;
        int k = sscanf(line, "%31s %d", cmd, &val);
        if (k < 1) continue;
        if (!strcmp(cmd, "quit")) break;
        run(cmd, val, k >= 2);
    }
    unkey();
}

static int tcp_loop(void) {
    int port = 45921;
    const char *envp = getenv("RUMITX_PORT");
    if (envp && atoi(envp) > 0 && atoi(envp) < 65536) port = atoi(envp);
    int srv = socket(AF_INET, SOCK_STREAM, 0);
    if (srv < 0) { fprintf(stderr, "ERR socket %d\n", errno); return 1; }
    int one = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    struct sockaddr_in a;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    a.sin_port = htons((unsigned short)port);
    if (bind(srv, (struct sockaddr *)&a, sizeof a) < 0) {
        fprintf(stderr, "ERR bind %d\n", errno);
        return 1;
    }
    if (listen(srv, 4) < 0) { fprintf(stderr, "ERR listen %d\n", errno); return 1; }
    fprintf(stderr, "TCP_LISTEN %d\n", port);
    fflush(stderr);
    for (;;) {
        int cli = accept(srv, NULL, NULL);
        if (cli < 0) {
            if (errno == EINTR) continue;
            fprintf(stderr, "ERR accept %d\n", errno);
            break;
        }
        int nodelay = 1;
        setsockopt(cli, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof nodelay);
        client_fd = cli;
        serve();
        client_fd = -1;
        close(cli);
    }
    close(srv);
    return 0;
}

int main(int argc, char **argv) {
    const char *t = getenv("RUMITX_TX_TIMEOUT");
    if (t && atoi(t) > 0) tx_timeout = atoi(t);
#ifdef RUMITX_HOST_TEST
    fd = -1;
#else
    fd = open(NODE, O_RDWR);
    if (fd < 0) { printf("ERR open %d\n", errno); return 1; }
#endif
    signal(SIGTERM, on_signal); signal(SIGINT, on_signal);
    signal(SIGHUP, on_signal);  signal(SIGPIPE, SIG_IGN);

    if (getenv("RUMITX_TCP")) {
        int rc = tcp_loop();
        unkey();
        if (fd >= 0) close(fd);
        return rc;
    }

    if (argc >= 2) {
        int r = run(argv[1], argc >= 3 ? atoi(argv[2]) : 0, argc >= 3);
        if (keyed) {
            sleep((unsigned)tx_timeout);
            unkey();
            reply("WATCHDOG unkey\n");
        }
        if (fd >= 0) close(fd);
        return r ? 1 : 0;
    }

    serve();
    if (fd >= 0) close(fd);
    return 0;
}
