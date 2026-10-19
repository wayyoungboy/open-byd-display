#ifndef OBD_READONLY_H
#define OBD_READONLY_H
#include <stdint.h>
typedef struct {
    uint64_t inode,size,device,mtime,ctime;
    uint32_t mode,uid,gid,links;
} obd_file_metadata;
typedef struct { int fd; const char *path; obd_file_metadata before; } obd_readonly_file;
int obd_readonly_open(void *,const char *,uint32_t expected_bytes);
int obd_readonly_read(void *,int,void *,unsigned);
int obd_readonly_close(void *,int);
#endif
