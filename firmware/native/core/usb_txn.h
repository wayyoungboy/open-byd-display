#ifndef OBD_USB_TXN_H
#define OBD_USB_TXN_H
#include <stdint.h>
enum { OBD_USB_HOST=1,OBD_USB_SWITCHING=2,OBD_USB_NCM=3,OBD_USB_UNCERTAIN=4 };
enum { OBD_USB_PREFLIGHT=1,OBD_USB_HOST_EMPTY,OBD_USB_DEVICE_DOWN,OBD_USB_NET_READY,
       OBD_USB_IP_READY,OBD_USB_LINK_READY,OBD_USB_PHYSICALLY_DISCONNECTED,OBD_USB_HOST_READY };
enum { OBD_USB_UNLOAD=1,OBD_USB_START_DCD,OBD_USB_START_NET,OBD_USB_ADDRESS,
       OBD_USB_LINK_UP,OBD_USB_LINK_DOWN,OBD_USB_STOP_NET,OBD_USB_STOP_DCD,OBD_USB_MOUNT_HOST };
typedef struct {
    uint32_t revision,phase,peer_possible,poisoned;
    uint8_t owner[16],last_request[16];
} obd_usb_state;
typedef struct {
    void *context;
    /* Every probe must revalidate OEM identity + unique own process/resources.
     * DISCONNECTED requires independent physical evidence, not missing IP. */
    int (*probe)(void *,unsigned);
    int (*command)(void *,unsigned);
    int (*commit)(void *,uint32_t expected,const obd_usb_state *);
    int (*cancelled)(void *);
} obd_usb_api;
int obd_usb_validate(const obd_usb_state *);
int obd_usb_to_ncm(obd_usb_state *,const uint8_t owner[16],const uint8_t request[16],const obd_usb_api *);
int obd_usb_to_host(obd_usb_state *,const uint8_t owner[16],const uint8_t request[16],const obd_usb_api *);
/* Restart with a nonterminal journal must be recorded as uncertain. */
int obd_usb_interrupted(obd_usb_state *,const obd_usb_api *);
#endif
