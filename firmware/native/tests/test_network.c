#include "network.h"
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#include <poll.h>
#define CHECK(x) do { if(!(x)) { fprintf(stderr,"network check failed at %d\n",__LINE__); return 1; } } while(0)
int main(void) {
    unsigned char bytes[18]; memset(bytes,0xa5,sizeof(bytes));
    CHECK(obd_video_ipv4("192.168.77.1",47782,bytes+1)==0);
    const unsigned char expected[16]={16,2,0xba,0xa6,192,168,77,1,0,0,0,0,0,0,0,0};
    CHECK(!memcmp(bytes+1,expected,16) && bytes[0]==0xa5 && bytes[17]==0xa5);
    const char *bad[]={"0.0.0.0","8.8.8.8","172.15.0.1","172.32.0.1","192.169.0.1",
        "127.0.0.1evil","127.0.0","127.0.0.256","127.0.0.1.2","127..0.1","127.0.0.-1",""};
    for(unsigned i=0;i<sizeof(bad)/sizeof(bad[0]);i++) CHECK(obd_video_ipv4(bad[i],47782,bytes+1)<0);
    CHECK(obd_video_ipv4("127.0.0.1",0,bytes+1)<0);
    CHECK(obd_video_ipv4("10.1.2.3",65536,bytes+1)<0);
    CHECK(obd_video_ipv4("172.31.0.1",1024,bytes+1)==0);
    void *library=(void *)1;
    CHECK(obd_video_accept("0.0.0.0",47782,&library,0)<0 && !library);
    obd_video_listener listener;
    CHECK(obd_video_listen("127.0.0.1",0,&listener)==0);
    struct sockaddr_in address; socklen_t length=sizeof(address);
    CHECK(getsockname(listener.fd,(struct sockaddr *)&address,&length)==0);
    for(int i=0;i<2;i++) {
        int client=socket(AF_INET,SOCK_STREAM,0);
        CHECK(client>=0 && connect(client,(struct sockaddr *)&address,length)==0);
        int accepted=obd_video_next(&listener,0);
        CHECK(accepted>=0);
        CHECK(write(client,"X",1)==1);
        struct pollfd pending={accepted,POLLIN,0}; CHECK(poll(&pending,1,3000)==1);
        char value=0; CHECK(read(accepted,&value,1)==1 && value=='X');
        close(client); close(accepted);
    }
    volatile int cancelled=1;
    CHECK(obd_video_next(&listener,&cancelled)==-3);
    obd_video_listener_close(&listener); obd_video_listener_close(&listener);
    return 0;
}
