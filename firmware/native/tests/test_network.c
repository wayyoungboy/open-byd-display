#include "network.h"
#include <stdio.h>
#include <string.h>
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
    return 0;
}
