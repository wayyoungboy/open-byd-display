#ifndef OBD_DISK_H
#define OBD_DISK_H
#include <stdint.h>
#include <stddef.h>
#include "readonly.h"
int obd_disk_metadata(const char *path,int fd,obd_file_metadata *);
int obd_disk_private(const char *,int directory,unsigned modes,obd_file_metadata *);
int obd_disk_join(char out[384],const char *,const char *);
int obd_disk_read(const char *,uint8_t **,size_t *,unsigned maximum,int owned);
int obd_disk_create(const char *,const uint8_t *,unsigned,unsigned mode);
int obd_disk_sync(const char *);
int obd_disk_exists(const char *);
int obd_disk_sync_parent(const char *);
int obd_disk_parents(const char *);
int obd_disk_names(const char *,char names[20][64],unsigned *count);
void obd_disk_checkpoint(const char *); /* Host fault injection only; target is no-op. */
#endif
