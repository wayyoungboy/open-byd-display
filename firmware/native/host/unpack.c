#define _POSIX_C_SOURCE 200809L
#include "lhf2.h"
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

/* File-only host harness, not a network daemon or a QNX display backend. */
int main(int argc, char **argv) {
    if (argc != 3) { fprintf(stderr, "Usage: obd-lhf2-unpack INPUT.lhf2 NEW_OUTPUT.h264\n"); return 2; }
    FILE *input = fopen(argv[1], "rb");
    if (!input) { perror("input"); return 2; }
    int fd = open(argv[2], O_WRONLY | O_CREAT | O_EXCL, 0600);
    if (fd < 0) { perror("new output"); fclose(input); return 2; }
    FILE *output = fdopen(fd, "wb");
    if (!output) { close(fd); unlink(argv[2]); fclose(input); return 2; }
    uint8_t *payload = malloc(OBD_LHF2_MAX_PAYLOAD);
    obd_lhf2_state state = {0};
    unsigned long frames = 0;
    const char *termination = "eof";
    int result = payload ? 0 : 1;
    while (!result) {
        uint8_t header[OBD_LHF2_HEADER_SIZE];
        size_t n = fread(header, 1, sizeof(header), input);
        if (!n && feof(input)) break;
        obd_lhf2_frame frame;
        if (n != sizeof(header) || obd_lhf2_header(header, n, &frame) ||
            fread(payload, 1, frame.bytes, input) != frame.bytes ||
            obd_lhf2_validate(&state, &frame, payload, frame.bytes)) { result = 1; break; }
        if (!frame.bytes) {
            termination = "eos";
            if (fgetc(input) != EOF || ferror(input)) result = 1;
            break;
        }
        if (fwrite(payload, 1, frame.bytes, output) != frame.bytes) { result = 1; break; }
        obd_lhf2_commit(&state, &frame);
        ++frames;
    }
    if (ferror(input) || !frames) result = 1;
    free(payload);
    if (fclose(input)) result = 1;
    if (fclose(output)) result = 1;
    if (result) { unlink(argv[2]); fprintf(stderr, "Rejected incomplete or invalid LHF2 stream\n"); return 1; }
    printf("{\"frames\":%lu,\"termination\":\"%s\",\"decoded\":false,\"visual_verified\":false}\n", frames, termination);
    return 0;
}
