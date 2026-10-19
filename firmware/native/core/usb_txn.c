#include "usb_txn.h"
#include <string.h>
static int nonzero(const uint8_t *v) { unsigned x=0; if(!v) return 0; for(unsigned i=0;i<16;i++) x|=v[i]; return x!=0; }
int obd_usb_validate(const obd_usb_state *s) {
    return !s || !s->revision || s->revision==UINT32_MAX || s->phase<OBD_USB_HOST || s->phase>OBD_USB_UNCERTAIN ||
        s->peer_possible>1 || s->poisoned>1 || !nonzero(s->owner) || (s->phase==OBD_USB_HOST && s->peer_possible)?-1:0;
}
static int api_valid(const obd_usb_api *a) { return a && a->probe && a->command && a->commit && a->cancelled; }
static int persist(obd_usb_state *s,obd_usb_state *next,const obd_usb_api *a) {
    next->revision=s->revision+1;
    if(obd_usb_validate(next) || a->commit(a->context,s->revision,next)) { s->poisoned=1; return -1; }
    *s=*next; return 0;
}
static int mark(obd_usb_state *s,unsigned phase,unsigned peer,const obd_usb_api *a) {
    obd_usb_state n=*s; n.phase=phase; n.peer_possible=peer; return persist(s,&n,a);
}
static int request_valid(obd_usb_state *s,const uint8_t *owner,const uint8_t *request,const obd_usb_api *a) {
    return obd_usb_validate(s) || s->poisoned || !api_valid(a) || !nonzero(owner) || !nonzero(request) ||
        memcmp(s->owner,owner,16) || !memcmp(s->last_request,request,16)?-1:0;
}
static int command(const obd_usb_api *a,unsigned action) { return a->command(a->context,action); }
static int probe(const obd_usb_api *a,unsigned stage) { return a->probe(a->context,stage); }
static int uncertain(obd_usb_state *s,const obd_usb_api *a) { (void)mark(s,OBD_USB_UNCERTAIN,s->peer_possible,a); return -1; }
static int restore_prelink(obd_usb_state *s,const obd_usb_api *a) {
    /* This path is reachable only after HOST_EMPTY was confirmed and before
     * committing peer_possible. The adapter only stops its owned processes. */
    if(s->peer_possible || command(a,OBD_USB_LINK_DOWN) || command(a,OBD_USB_STOP_NET) ||
       command(a,OBD_USB_STOP_DCD) || command(a,OBD_USB_MOUNT_HOST) || probe(a,OBD_USB_HOST_READY)) return uncertain(s,a);
    return mark(s,OBD_USB_HOST,0,a)?-1:-2; /* Failed request, Host verified restored. */
}
int obd_usb_to_ncm(obd_usb_state *s,const uint8_t owner[16],const uint8_t request[16],const obd_usb_api *a) {
    if(request_valid(s,owner,request,a) || s->phase!=OBD_USB_HOST || a->cancelled(a->context) || probe(a,OBD_USB_PREFLIGHT)) return -1;
    obd_usb_state n=*s; n.phase=OBD_USB_SWITCHING; memcpy(n.last_request,request,16);
    if(persist(s,&n,a)) return -1;
    if(command(a,OBD_USB_UNLOAD) || probe(a,OBD_USB_HOST_EMPTY)) return uncertain(s,a);
    if(a->cancelled(a->context) || command(a,OBD_USB_START_DCD) || probe(a,OBD_USB_DEVICE_DOWN) ||
       command(a,OBD_USB_START_NET) || probe(a,OBD_USB_NET_READY) || command(a,OBD_USB_ADDRESS) ||
       probe(a,OBD_USB_IP_READY) || a->cancelled(a->context)) return restore_prelink(s,a);
    /* Durable barrier MUST precede the first possible physical soft-connect. */
    if(mark(s,OBD_USB_SWITCHING,1,a)) return -1;
    if(a->cancelled(a->context)) return uncertain(s,a);
    if(command(a,OBD_USB_LINK_UP) || probe(a,OBD_USB_LINK_READY) || a->cancelled(a->context)) return uncertain(s,a);
    return mark(s,OBD_USB_NCM,1,a);
}
int obd_usb_to_host(obd_usb_state *s,const uint8_t owner[16],const uint8_t request[16],const obd_usb_api *a) {
    if(request_valid(s,owner,request,a) || s->phase!=OBD_USB_NCM || a->cancelled(a->context) ||
       probe(a,OBD_USB_PHYSICALLY_DISCONNECTED)) return -1;
    obd_usb_state n=*s; n.phase=OBD_USB_SWITCHING; memcpy(n.last_request,request,16);
    if(persist(s,&n,a)) return -1;
    if(command(a,OBD_USB_LINK_DOWN) || command(a,OBD_USB_STOP_NET) || command(a,OBD_USB_STOP_DCD) ||
       command(a,OBD_USB_MOUNT_HOST) || probe(a,OBD_USB_HOST_READY)) return uncertain(s,a);
    return mark(s,OBD_USB_HOST,0,a);
}
int obd_usb_interrupted(obd_usb_state *s,const obd_usb_api *a) {
    if(obd_usb_validate(s) || s->poisoned || !api_valid(a)) return -1;
    if(s->phase!=OBD_USB_SWITCHING) return 0;
    return mark(s,OBD_USB_UNCERTAIN,s->peer_possible,a);
}
