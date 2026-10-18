#ifndef OBD_IO_H
#define OBD_IO_H
#include <stdint.h>
typedef struct { int fd; short events,revents; } obd_pollfd;
typedef struct {
    int (*read)(int,void *,unsigned);
    int (*write)(int,const void *,unsigned);
    int (*poll)(obd_pollfd *,unsigned,int);
    int (*error)(void);
    uint64_t (*now)(void);
} obd_io_api;
typedef struct { obd_io_api api; uint64_t deadline; const volatile int *cancel; } obd_io;
void obd_io_platform(obd_io_api *);
int obd_io_retry(obd_io *);
int obd_io_nonblock(int fd, int *previous);
int obd_io_restore(int fd, int previous);
int obd_io_restore_pair(int first_fd, int first_flags, int second_fd, int second_flags);
int obd_io_wait(obd_io *, int fd, short events);
int obd_io_exact(obd_io *, int fd, void *data, unsigned bytes, int writing);
#endif
