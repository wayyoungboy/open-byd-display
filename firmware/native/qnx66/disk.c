#ifdef OBD_IO_HOST
#define _POSIX_C_SOURCE 200809L
#endif
#include "disk.h"
#include <string.h>
#ifdef OBD_IO_HOST
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <dirent.h>
#include <errno.h>
#else
extern int lstat(const char *,void *);
extern int fstat(int,void *);
extern int open(const char *,int,...);
extern int read(int,void *,unsigned);
extern int write(int,const void *,unsigned);
extern int close(int);
extern int fsync(int);
extern unsigned geteuid(void);
extern void *malloc(size_t);
extern void free(void *);
extern void *opendir(const char *);
extern void *readdir(void *);
extern int closedir(void *);
extern int *__get_errno_ptr(void);
enum { O_RDONLY=0,O_WRONLY=1,O_CREAT=0x100,O_EXCL=0x400,O_NONBLOCK=0x80 };
#endif
extern int snprintf(char *,size_t,const char *,...);
static int error_value(void) {
#ifdef OBD_IO_HOST
    return errno;
#else
    return *__get_errno_ptr();
#endif
}
static void reset_error(void) {
#ifdef OBD_IO_HOST
    errno=0;
#else
    *__get_errno_ptr()=0;
#endif
}
void obd_disk_checkpoint(const char *phase) {
#ifdef OBD_STORE_FAULT_TEST
    const char *requested=getenv("OBD_STORE_TEST_CRASH");
    if(requested && !strcmp(requested,phase)) _exit(93);
#else
    (void)phase;
#endif
}
int obd_disk_metadata(const char *path,int fd,obd_file_metadata *out) {
    if(!out) return -1;
#ifdef OBD_IO_HOST
    struct stat info;
    if(path?lstat(path,&info):fstat(fd,&info)) return -1;
    if(info.st_size<0) return -1;
    *out=(obd_file_metadata){(uint64_t)info.st_ino,(uint64_t)info.st_size,(uint64_t)info.st_dev,
        (uint64_t)info.st_mtime,(uint64_t)info.st_ctime,(uint32_t)info.st_mode,
        (uint32_t)info.st_uid,(uint32_t)info.st_gid,(uint32_t)info.st_nlink};
#else
    /* Same independently checked 72-byte ARM32-LE stat contract as readonly.c. */
    uint64_t storage[9]={0}; const uint32_t *w=(const uint32_t *)storage;
    if(path?lstat(path,storage):fstat(fd,storage)) return -1;
    *out=(obd_file_metadata){(uint64_t)w[0]|((uint64_t)w[1]<<32),(uint64_t)w[2]|((uint64_t)w[3]<<32),w[4],w[8],w[10],w[11],w[6],w[7],w[12]};
#endif
    return 0;
}
int obd_disk_private(const char *path,int directory,unsigned modes,obd_file_metadata *out) {
    obd_file_metadata m;
    if(obd_disk_metadata(path,-1,&m) || (m.mode&0170000)!=(directory?0040000:0100000) ||
       m.uid!=geteuid() || (m.mode&07777)!=modes || (!directory && m.links!=1)) return -1;
    if(out) *out=m;
    return 0;
}
int obd_disk_exists(const char *path) {
    obd_file_metadata info;
    if(!obd_disk_metadata(path,-1,&info)) return 1;
    return error_value()==2?0:-1;
}
int obd_disk_parents(const char *path) {
    if(!path || path[0]!='/') return -1;
    char prefix[384];
    for(unsigned i=0;path[i];i++) {
        if(i>=383) return -1;
        prefix[i]=path[i];
        if(path[i]=='/' && i) {
            prefix[i]=0; obd_file_metadata info;
            if(obd_disk_metadata(prefix,-1,&info) || (info.mode&0170000)!=0040000) return -1;
            prefix[i]='/';
        }
    }
    return 0;
}
int obd_disk_join(char out[384],const char *root,const char *leaf) {
    if(!out || !root || !leaf || root[0]!='/') return -1;
    int n=snprintf(out,384,"%s/%s",root,leaf);
    return n<=0 || n>=384?-1:0;
}
static int same(const obd_file_metadata *a,const obd_file_metadata *b) {
    return a->inode==b->inode && a->device==b->device && a->size==b->size && a->mode==b->mode &&
        a->uid==b->uid && a->gid==b->gid && a->links==b->links && a->mtime==b->mtime && a->ctime==b->ctime;
}
int obd_disk_read(const char *path,uint8_t **out,size_t *bytes,unsigned maximum,int owned) {
    if(!out || !bytes) return -1;
    *out=0; *bytes=0; obd_file_metadata before,opened,after;
    if(obd_disk_metadata(path,-1,&before) || (before.mode&0170000)!=0100000 || !before.size || before.size>maximum ||
       (owned && (before.uid!=geteuid() || before.links!=1 || (before.mode&07777)!=0600))) return -1;
    int fd=open(path,O_RDONLY|O_NONBLOCK),result=-1; uint8_t *buffer=0;
    if(fd<0) return -1;
    if(obd_disk_metadata(0,fd,&opened) || !same(&before,&opened)) goto done;
    buffer=malloc((size_t)before.size); if(!buffer) goto done;
    unsigned at=0;
    while(at<(unsigned)before.size) {
        unsigned wanted=(unsigned)before.size-at; if(wanted>65536) wanted=65536;
        int n=(int)read(fd,buffer+at,wanted);
        if(n<=0 || (unsigned)n>wanted) goto done;
        at+=(unsigned)n;
    }
    uint8_t tail;
    if(read(fd,&tail,1)!=0 || obd_disk_metadata(0,fd,&opened) || obd_disk_metadata(path,-1,&after) ||
       !same(&before,&opened) || !same(&opened,&after)) goto done;
    result=0;
done:
    if(close(fd)) result=-1;
    if(result) free(buffer); else { *out=buffer; *bytes=(size_t)before.size; }
    return result;
}
int obd_disk_create(const char *path,const uint8_t *data,unsigned bytes,unsigned mode) {
    if(!data || (mode!=0600 && mode!=0700)) return -1;
    int fd=open(path,O_WRONLY|O_CREAT|O_EXCL|O_NONBLOCK,mode),result=-1;
    if(fd<0) return -1;
    obd_file_metadata info;
    if(obd_disk_metadata(0,fd,&info) || (info.mode&0170000)!=0100000 || info.uid!=geteuid() || info.links!=1 ||
       (info.mode&07777)!=mode) goto done;
    unsigned at=0;
    while(at<bytes) {
        unsigned wanted=bytes-at; if(wanted>65536) wanted=65536;
        int n=(int)write(fd,data+at,wanted); if(n<=0 || (unsigned)n>wanted) goto done;
        at+=(unsigned)n;
    }
    if(fsync(fd)) goto done;
    result=0;
done:
    if(close(fd)) result=-1;
    return result;
}
int obd_disk_sync(const char *path) {
    obd_file_metadata before,after;
    if(obd_disk_metadata(path,-1,&before) || (before.mode&0170000)!=0040000 || before.uid!=geteuid() || (before.mode&0022)) return -1;
    int fd=open(path,O_RDONLY|O_NONBLOCK); if(fd<0) return -1;
    int result=obd_disk_metadata(0,fd,&after) || before.inode!=after.inode || before.device!=after.device || fsync(fd);
    if(close(fd)) result=-1;
    return result?-1:0;
}
int obd_disk_sync_parent(const char *path) {
    char parent[384]; unsigned n=0,last=0;
    while(path[n]) { if(n>=383) return -1; parent[n]=path[n]; if(path[n]=='/') last=n; n++; }
    if(!last) { parent[0]='/'; parent[1]=0; } else parent[last]=0;
    obd_file_metadata before,after;
    if(obd_disk_metadata(parent,-1,&before) || (before.mode&0170000)!=0040000) return -1;
    int fd=open(parent,O_RDONLY|O_NONBLOCK); if(fd<0) return -1;
    int invalid=obd_disk_metadata(0,fd,&after) || before.inode!=after.inode || before.device!=after.device || fsync(fd);
    if(close(fd)) invalid=1;
    return invalid?-1:0;
}
int obd_disk_names(const char *path,char names[20][64],unsigned *count) {
    if(!names || !count) return -1;
    *count=0;
    void *directory=opendir(path); if(!directory) return -1;
    int result=-1;
    for(;;) {
        reset_error(); void *entry=readdir(directory);
        if(!entry) { if(!error_value()) result=0; break; }
#ifdef OBD_IO_HOST
        const char *name=((struct dirent *)entry)->d_name;
        unsigned length=0; while(length<64 && name[length]) length++;
#else
        const uint8_t *raw=entry;
        unsigned reclen=(unsigned)raw[16]|(unsigned)raw[17]<<8;
        unsigned length=(unsigned)raw[18]|(unsigned)raw[19]<<8;
        if(reclen<21 || reclen>8192 || !length || length>63 || length+21>reclen) break;
        const char *name=(const char *)raw+20;
        if(name[length]) break;
#endif
        if(!strcmp(name,".") || !strcmp(name,"..")) continue;
        if(!length || length>63 || *count>=20) break;
        memcpy(names[*count],name,length); names[*count][length]=0; ++*count;
    }
    if(closedir(directory)) result=-1;
    return result;
}
