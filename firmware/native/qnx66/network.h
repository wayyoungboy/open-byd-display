#ifndef OBD_NETWORK_H
#define OBD_NETWORK_H
typedef struct {
    void *library;
    int fd;
    int (*accept_fn)(int,void *,unsigned *);
} obd_video_listener;
int obd_video_listen(const char *, unsigned port, obd_video_listener *);
int obd_video_next(obd_video_listener *, const volatile int *cancel);
void obd_video_listener_close(obd_video_listener *);
/* Return one accepted video connection. No interface/USB configuration changes. */
int obd_video_accept(const char *address, unsigned port, void **library, const volatile int *cancel);
int obd_video_ipv4(const char *address, unsigned port, unsigned char bytes[16]);
void obd_video_network_close(void *library);
#endif
