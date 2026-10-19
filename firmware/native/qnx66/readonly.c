#ifdef OBD_IO_HOST
#define _POSIX_C_SOURCE 200809L
#endif
#include "readonly.h"
#include <string.h>
#ifdef OBD_IO_HOST
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
static int metadata(const char *path,int fd,obd_file_metadata *out) {
    struct stat info;
    if(path?lstat(path,&info):fstat(fd,&info)) return -1;
    if(!S_ISREG(info.st_mode) || info.st_size<=0) return -1;
    *out=(obd_file_metadata){(uint64_t)info.st_ino,(uint64_t)info.st_size,(uint64_t)info.st_dev,
        (uint64_t)info.st_mtime,(uint64_t)info.st_ctime,(uint32_t)info.st_mode,
        (uint32_t)info.st_uid,(uint32_t)info.st_gid,(uint32_t)info.st_nlink};
    return 0;
}
#else
extern int lstat(const char *,void *);
extern int fstat(int,void *);
extern int open(const char *,int,...);
extern int read(int,void *,unsigned);
extern int close(int);
enum { O_RDONLY=0, O_NONBLOCK=0x80 };
static uint32_t word(const uint8_t *p) { return (uint32_t)p[0]|(uint32_t)p[1]<<8|(uint32_t)p[2]<<16|(uint32_t)p[3]<<24; }
/* QNX6.6 ARM32 LE stat ABI, corroborated against the supplied libc and callers:
 * 72-byte reply; ino@0, size@8, dev@16, uid/gid@24/28, times@32/36/40,
 * mode@44, links@48. Neither a Linux stat layout nor a copied SDK header.
 * libc SHA72c66804..., fstat 0x1cc98 requests 0x48; lstat 0x20450.
 * See docs/model-firmware.md. Only the pinned runtime is a supported target. */
static int metadata(const char *path,int fd,obd_file_metadata *out) {
    uint64_t storage[9]={0}; uint8_t *raw=(uint8_t *)storage;
    if(path?lstat(path,raw):fstat(fd,raw)) return -1;
    uint32_t mode=word(raw+44);
    if((mode&0170000)!=0100000 || word(raw+12) || !word(raw+8)) return -1;
    *out=(obd_file_metadata){(uint64_t)word(raw)|((uint64_t)word(raw+4)<<32),word(raw+8),word(raw+16),
        word(raw+32),word(raw+40),mode,word(raw+24),word(raw+28),word(raw+48)};
    return 0;
}
#endif
static int same(const obd_file_metadata *a,const obd_file_metadata *b) {
    return a->inode==b->inode && a->size==b->size && a->device==b->device && a->mtime==b->mtime &&
        a->ctime==b->ctime && a->mode==b->mode && a->uid==b->uid && a->gid==b->gid && a->links==b->links;
}
int obd_readonly_open(void *context,const char *path,uint32_t expected_bytes) {
    obd_readonly_file *file=context; obd_file_metadata before,opened,after;
    if(!file || file->fd!=-1 || !path || !expected_bytes || expected_bytes>32u*1024*1024) return -1;
    if(metadata(path,-1,&before) || before.size!=expected_bytes) return -1;
    int fd=open(path,O_RDONLY|O_NONBLOCK);
    if(fd<0) return -1;
    if(metadata(0,fd,&opened) || metadata(path,-1,&after) || !same(&before,&opened) || !same(&opened,&after)) {
        (void)close(fd); return -1;
    }
    file->fd=fd; file->path=path; file->before=before; return fd;
}
int obd_readonly_read(void *context,int fd,void *data,unsigned bytes) {
    obd_readonly_file *file=context;
    if(!file || file->fd!=fd || fd<0 || !data || !bytes || bytes>4096) return -1;
    return (int)read(fd,data,bytes);
}
int obd_readonly_close(void *context,int fd) {
    obd_readonly_file *file=context; obd_file_metadata opened,after;
    if(!file || file->fd!=fd || fd<0) return -1;
    int invalid=metadata(0,fd,&opened) || metadata(file->path,-1,&after) ||
        !same(&file->before,&opened) || !same(&opened,&after);
    int closed=close(fd); file->fd=-1; file->path=0;
    return invalid || closed ? -1 : 0;
}
