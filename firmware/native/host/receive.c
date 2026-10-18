#define _POSIX_C_SOURCE 200809L
#include "lhf2.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

/* Host-only session harness. No vehicle network, renderer or update backend. */
#define IO_TIMEOUT_MS 5000
#define SESSION_TIMEOUT_MS 30000
#define MAX_FRAMES 60u
#define MAX_SESSION_BYTES (64u * 1024u * 1024u)

static int64_t now_ms(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t)) return -1;
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

static int nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL);
    return flags < 0 ? -1 : fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static int wait_ready(int fd, short events, int64_t deadline) {
    for (;;) {
        int64_t now = now_ms();
        if (now < 0 || now >= deadline) return -1;
        int64_t left = deadline - now;
        struct pollfd p = {fd, events, 0};
        int rc = poll(&p, 1, left > INT_MAX ? INT_MAX : (int)left);
        if (rc > 0) return p.revents & (events | POLLHUP) ? 0 : -1;
        if (rc < 0 && errno == EINTR) continue;
        return -1;
    }
}

/* 0 = exact read, 1 = EOF before any bytes, -1 = partial/timeout/error. */
static int read_exact(int fd, void *buffer, size_t size, int64_t session_end) {
    int64_t now = now_ms();
    if (now < 0) return -1;
    int64_t deadline = now + IO_TIMEOUT_MS;
    if (deadline > session_end) deadline = session_end;
    uint8_t *p = buffer;
    size_t offset = 0;
    while (offset < size) {
        if (wait_ready(fd, POLLIN, deadline)) return -1;
        ssize_t n = recv(fd, p + offset, size - offset, 0);
        if (n > 0) offset += (size_t)n;
        else if (!n) return offset ? -1 : 1;
        else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) return -1;
    }
    return 0;
}

static int reply(int fd, const char *text, int64_t session_end) {
    int64_t now = now_ms();
    if (now < 0) return -1;
    int64_t deadline = now + IO_TIMEOUT_MS;
    if (deadline > session_end) deadline = session_end;
    size_t size = strlen(text), offset = 0;
    while (offset < size) {
        if (wait_ready(fd, POLLOUT, deadline)) return -1;
        ssize_t n = send(fd, text + offset, size - offset, 0);
        if (n > 0) offset += (size_t)n;
        else if (!n || (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)) return -1;
    }
    return 0;
}

static uint32_t be32(const uint8_t *p) {
    return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3];
}

static int handshake(int fd, int64_t deadline) {
    uint8_t header[8], metadata[16];
    /* Deliberately narrow independent contract; not a general JSON parser. */
    static const char operation[] = "{\"op\":\"stream\"}";
    char body[sizeof(operation) - 1];
    if (read_exact(fd, header, sizeof(header), deadline) || memcmp(header, "BVL1", 4) ||
        be32(header + 4) != sizeof(body) || read_exact(fd, body, sizeof(body), deadline) ||
        memcmp(body, operation, sizeof(body)) || read_exact(fd, metadata, sizeof(metadata), deadline)) return -1;
    uint32_t width = be32(metadata), height = be32(metadata + 4), fps = be32(metadata + 8);
    if (!width || width > 1920 || (width & 1) || !height || height > 1080 ||
        (height & 1) || !fps || fps > 60 || be32(metadata + 12)) return -1;
    /* Metadata is only a declaration. The host harness does not parse SPS dimensions. */
    return 0;
}

static int receive_stream(int fd, const char *path, int64_t deadline) {
    if (handshake(fd, deadline)) return 1;
    int output_fd = open(path, O_WRONLY | O_CREAT | O_EXCL, 0600);
    if (output_fd < 0) return 1;
    FILE *output = fdopen(output_fd, "wb");
    if (!output) { close(output_fd); unlink(path); return 1; }
    uint8_t *payload = malloc(OBD_LHF2_MAX_PAYLOAD);
    obd_lhf2_state state = {0};
    unsigned frames = 0;
    uint64_t bytes = 0;
    const char *termination = "eof";
    int result = !payload || reply(fd,
        "{\"ok\":true,\"ready\":true,\"protocol\":\"LHF2\",\"backend\":\"host-file\",\"decoded\":false,\"visual_verified\":false}\n", deadline);
    while (!result) {
        uint8_t header[OBD_LHF2_HEADER_SIZE];
        int read_result = read_exact(fd, header, sizeof(header), deadline);
        if (read_result == 1) break;
        obd_lhf2_frame frame;
        if (read_result || obd_lhf2_header(header, sizeof(header), &frame)) { result = 1; break; }
        if (!frame.bytes) { termination = "eos"; break; }
        if (frames >= MAX_FRAMES || bytes + frame.bytes > MAX_SESSION_BYTES ||
            read_exact(fd, payload, frame.bytes, deadline) ||
            obd_lhf2_validate(&state, &frame, payload, frame.bytes) ||
            fwrite(payload, 1, frame.bytes, output) != frame.bytes || fflush(output)) { result = 1; break; }
        obd_lhf2_commit(&state, &frame);
        ++frames;
        bytes += frame.bytes;
        char ack[180];
        snprintf(ack, sizeof(ack),
            "{\"ok\":true,\"accepted_sequence\":%" PRIu32 ",\"backend\":\"host-file\",\"decoded\":false,\"visual_verified\":false}\n", frame.sequence);
        if (reply(fd, ack, deadline)) result = 1;
    }
    free(payload);
    if (fclose(output) || !frames) result = 1;
    if (result) { unlink(path); return 1; }
    char done[224];
    snprintf(done, sizeof(done),
        "{\"ok\":true,\"done\":true,\"cleanup\":true,\"frames\":%u,\"termination\":\"%s\",\"backend\":\"host-file\",\"decoded\":false,\"visual_verified\":false}\n", frames, termination);
    if (reply(fd, done, deadline)) { unlink(path); return 1; }
    return 0;
}

int main(int argc, char **argv) {
    char *end = NULL;
    if (argc != 3 || !argv[1][0]) {
        fprintf(stderr, "Usage: obd-bvl1-receive PORT_OR_0 NEW_OUTPUT.h264\n"); return 2;
    }
    errno = 0;
    long port = strtol(argv[1], &end, 10);
    if (errno || *end || port < 0 || port > 65535) return 2;
    if (signal(SIGPIPE, SIG_IGN) == SIG_ERR) return 2;
    int listener = socket(AF_INET, SOCK_STREAM, 0);
    if (listener < 0) return 2;
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    if (inet_pton(AF_INET, "127.0.0.1", &address.sin_addr) != 1) { close(listener); return 2; }
    address.sin_port = htons((uint16_t)port);
    socklen_t address_size = sizeof(address);
    if (nonblocking(listener) || bind(listener, (struct sockaddr *)&address, sizeof(address)) ||
        listen(listener, 1) || getsockname(listener, (struct sockaddr *)&address, &address_size)) {
        perror("loopback listener"); close(listener); return 2;
    }
    printf("{\"host\":\"127.0.0.1\",\"port\":%u,\"backend\":\"host-file\"}\n", (unsigned)ntohs(address.sin_port));
    fflush(stdout);
    int64_t now = now_ms();
    if (now < 0 || wait_ready(listener, POLLIN, now + IO_TIMEOUT_MS)) { close(listener); return 1; }
    int client = accept(listener, NULL, NULL);
    close(listener); /* One exclusive session; no background accept loop. */
    now = now_ms();
    if (client < 0) return 1;
    int result = now < 0 || nonblocking(client) ? 1 : receive_stream(client, argv[2], now + SESSION_TIMEOUT_MS);
    if (result) {
        reply(client, "{\"ok\":false,\"error\":\"session_rejected\",\"visual_verified\":false}\n", now_ms() + 1000);
        fprintf(stderr, "Rejected invalid, incomplete or timed-out host session\n");
    }
    close(client);
    return result;
}
