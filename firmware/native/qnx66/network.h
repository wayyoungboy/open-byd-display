#ifndef OBD_NETWORK_H
#define OBD_NETWORK_H
/* Return one accepted video connection. No interface/USB configuration changes. */
int obd_video_accept(const char *address, unsigned port, void **library, const volatile int *cancel);
int obd_video_ipv4(const char *address, unsigned port, unsigned char bytes[16]);
void obd_video_network_close(void *library);
#endif
