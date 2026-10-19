#include "lhf2.h"
#include "sha256.h"

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
    static const uint8_t expected[32]={0xba,0x78,0x16,0xbf,0x8f,0x01,0xcf,0xea,0x41,0x41,0x40,0xde,0x5d,0xae,0x22,0x23,
        0xb0,0x03,0x61,0xa3,0x96,0x17,0x7a,0x9c,0xb4,0x10,0xff,0x61,0xf2,0x00,0x15,0xad};
    obd_sha256 hash; uint8_t digest[32];
    obd_sha256_init(&hash); obd_sha256_update(&hash,"ab",2); obd_sha256_update(&hash,"c",1); obd_sha256_final(&hash,digest);
    for(unsigned i=0;i<32;i++) if(digest[i]!=expected[i]) return 13;
    static const char message[] =
        "{\"probe\":\"OpenBYDDisplay\",\"target\":\"" OBD_TARGET_ID
        "\",\"crc32_ok\":true,\"lhf2_header_ok\":true,\"sha256_ok\":true,"
        "\"hardware_verified\":false,\"vehicle_visual_verified\":false}\n";
    unsigned offset = 0;
    while (offset < sizeof(message) - 1) {
        int count = write(1, message + offset, (unsigned)sizeof(message) - 1 - offset);
        if (count <= 0 || (unsigned)count > sizeof(message) - 1 - offset) return 12;
        offset += (unsigned)count;
    }
    return 0;
}
