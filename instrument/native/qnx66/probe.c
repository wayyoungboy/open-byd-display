#include "lhf2.h"

#ifndef OBD_TARGET_ID
#error OBD_TARGET_ID must identify the research target
#endif

extern int write(int fd, const void *data, unsigned bytes);

int main(int argc, char **argv) {
    (void)argc;
    (void)argv;
    if (obd_crc32((const uint8_t *)"123456789", 9) != UINT32_C(0xcbf43926)) return 10;
    const uint8_t header[24] = {'L','H','F','2',0,0,0,0,255,255,255,255};
    obd_lhf2_frame frame;
    if (obd_lhf2_header(header, sizeof(header), &frame)) return 11;
    static const char message[] =
        "{\"probe\":\"OpenBYDDisplay\",\"target\":\"" OBD_TARGET_ID
        "\",\"crc32_ok\":true,\"lhf2_header_ok\":true,"
        "\"hardware_verified\":false,\"vehicle_visual_verified\":false}\n";
    unsigned offset = 0;
    while (offset < sizeof(message) - 1) {
        int count = write(1, message + offset, (unsigned)sizeof(message) - 1 - offset);
        if (count <= 0 || (unsigned)count > sizeof(message) - 1 - offset) return 12;
        offset += (unsigned)count;
    }
    return 0;
}
