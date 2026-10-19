#include "usb_plan.h"
#include <string.h>
extern int snprintf(char *,size_t,const char *,...);
static int runtime_valid(const char *target,const char *root) {
    if(!target || !root || (strcmp(target,"song-plus-dmi-2021") && strcmp(target,"tang-plus-dmi-2021"))) return 0;
    char prefix[96]; int n=snprintf(prefix,sizeof(prefix),"/fs/tmpfs/OpenBYDDisplay/%s/",target);
    unsigned length=0; while(length<112 && root[length]) length++;
    if(n<0 || n>=96 || length!=(unsigned)n+32 || memcmp(root,prefix,(unsigned)n)) return 0;
    for(unsigned i=0;i<32;i++) if(!((root[n+i]>='0' && root[n+i]<='9') || (root[n+i]>='a' && root[n+i]<='f'))) return 0;
    return 1;
}
static int text(char *out,size_t bytes,const char *pattern,const char *value) {
    int n=snprintf(out,bytes,pattern,value); return n<0 || (size_t)n>=bytes?-1:0;
}
int obd_usb_plan(obd_usb_command *out,const char *target,const char *root,unsigned action) {
    if(!out) return -1;
    memset(out,0,sizeof(*out));
    if(!runtime_valid(target,root)) return -1;
    if(text(out->path,sizeof(out->path),"%s/dcd/io-usb",root) || text(out->sock,sizeof(out->sock),"SOCK=%s/net",root)) return -1;
    switch(action) {
    case OBD_USB_UNLOAD:
        out->argv[0]="/base/bin/umount"; out->argv[1]="/dev/io-usb/devu-omap5-xhci.so"; out->argc=2; out->sock[0]=0; break;
    case OBD_USB_START_DCD:
        out->argv[0]="/base/sbin/io-usb-dcd"; out->argv[1]="-n"; out->argv[2]=out->path;
        out->argv[3]="-d"; out->argv[4]="usbncm-omap543x-dwcotg3"; out->argv[5]="ioport=0x488d0000,irq=110,linkdown";
        out->argc=6; out->sock[0]=0; break;
    case OBD_USB_START_NET:
        if(text(out->parameters,sizeof(out->parameters),"protocol=ncm,path=%s,mac=020000000701,usbdnet_mac=020000000702,wait=2",out->path) ||
           text(out->prefix,sizeof(out->prefix),"prefix=%s/net",root)) return -1;
        out->argv[0]="/base/sbin/io-pkt-v6-hc"; out->argv[1]="-i7"; out->argv[2]="-d"; out->argv[3]="usbdnet";
        out->argv[4]=out->parameters; out->argv[5]="-ptcpip"; out->argv[6]=out->prefix; out->argc=7; out->sock[0]=0; break;
    case OBD_USB_ADDRESS:
        out->argv[0]="/base/sbin/ifconfig"; out->argv[1]="ncm0"; out->argv[2]="inet"; out->argv[3]="192.168.77.1";
        out->argv[4]="netmask"; out->argv[5]="255.255.255.252"; out->argv[6]="up"; out->argc=7; break;
    case OBD_USB_LINK_UP: case OBD_USB_LINK_DOWN:
        out->argv[0]="/base/sbin/ulink_ctrl"; out->argv[1]="-s"; out->argv[2]=out->path; out->argv[3]="-w";
        out->argv[4]="2"; out->argv[5]="-l"; out->argv[6]=action==OBD_USB_LINK_UP?"1":"0"; out->argc=7; out->sock[0]=0; break;
    case OBD_USB_MOUNT_HOST:
        out->argv[0]="/base/bin/mount"; out->argv[1]="-Tio-usb"; out->argv[2]="-o";
        out->argv[3]="ioport=0x48890000,irq=108,ioport=0x488d0000,irq=110";
        out->argv[4]="/base/lib/dll/devu-omap5-xhci.so"; out->argv[5]="/dev/io-usb/io-usb"; out->argc=6; out->sock[0]=0; break;
    default: return -1;
    }
    return 0;
}
