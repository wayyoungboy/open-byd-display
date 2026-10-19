#define _POSIX_C_SOURCE 200809L
#ifdef __APPLE__
#define _DARWIN_C_SOURCE
#endif
#include "readonly.h"
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(x) do { if(!(x)) { fprintf(stderr,"readonly failed %d: %s\n",__LINE__,#x); exit(1); } } while(0)
int main(void) {
    char root[]="/tmp/obd-readonly-XXXXXX"; CHECK(mkdtemp(root));
    char regular[128],fifo[128],link[128],replacement[128];
    CHECK(snprintf(regular,sizeof(regular),"%s/file",root)>0);
    CHECK(snprintf(fifo,sizeof(fifo),"%s/fifo",root)>0);
    CHECK(snprintf(link,sizeof(link),"%s/link",root)>0);
    CHECK(snprintf(replacement,sizeof(replacement),"%s/new",root)>0);
    int fd=open(regular,O_WRONLY|O_CREAT|O_EXCL,0600); CHECK(fd>=0); CHECK(write(fd,"abc",3)==3); CHECK(!close(fd));
    CHECK(!mkfifo(fifo,0600)); CHECK(!symlink(regular,link));
    obd_readonly_file file={-1,0,{0}}; char data[8];
    CHECK(obd_readonly_open(&file,fifo,3)==-1); CHECK(file.fd==-1);
    CHECK(obd_readonly_open(&file,link,3)==-1); CHECK(file.fd==-1);
    CHECK(obd_readonly_open(&file,root,3)==-1);
    CHECK(obd_readonly_open(&file,regular,2)==-1);
    fd=obd_readonly_open(&file,regular,3); CHECK(fd>=0);
    CHECK((fcntl(fd,F_GETFL)&O_NONBLOCK)!=0);
    CHECK(obd_readonly_read(&file,fd,data,3)==3 && !memcmp(data,"abc",3));
    CHECK(obd_readonly_read(&file,fd,data,1)==0); CHECK(!obd_readonly_close(&file,fd));
    fd=obd_readonly_open(&file,regular,3); CHECK(fd>=0);
    int other=open(replacement,O_WRONLY|O_CREAT|O_EXCL,0600); CHECK(other>=0); CHECK(write(other,"abc",3)==3); CHECK(!close(other));
    CHECK(!rename(replacement,regular)); CHECK(obd_readonly_close(&file,fd)==-1); CHECK(file.fd==-1);
    CHECK(fcntl(fd,F_GETFL)==-1); /* Failed verification still closes our FD. */
    fd=obd_readonly_open(&file,regular,3); CHECK(fd>=0);
    CHECK(!unlink(regular)); CHECK(!symlink(link,regular));
    CHECK(obd_readonly_close(&file,fd)==-1); CHECK(file.fd==-1);
    CHECK(!unlink(regular)); CHECK(!unlink(link)); CHECK(!unlink(fifo)); CHECK(!rmdir(root));
    puts("real host file/FIFO/link/inode replacement checks; no QNX filesystem execution"); return 0;
}
