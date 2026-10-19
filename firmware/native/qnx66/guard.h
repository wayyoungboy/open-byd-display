#ifndef OBD_GUARD_H
#define OBD_GUARD_H
#include "media.h"
/* Dedicated inherited pipes, never TCP acknowledgements or a writable PID file. */
int obd_guard_start(int lease_fd,int health_fd);
void obd_guard_stop(void);
int obd_guard_tick(void);
void obd_guard_media(const obd_media *);
#endif
