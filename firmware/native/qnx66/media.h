#ifndef OBD_MEDIA_H
#define OBD_MEDIA_H
#include "lhf2.h"
/* Independent declarations of the public opaque-handle ABI; no SDK header copy. */
typedef struct { const void *base; unsigned bytes; } obd_iov;
typedef struct {
    int (*screen_create_context)(void **, int);
    int (*screen_destroy_context)(void *);
    int (*screen_get_context_property_iv)(void *, int, int *);
    int (*screen_get_context_property_pv)(void *, int, void **);
    int (*screen_get_display_property_iv)(void *, int, int *);
    int (*screen_create_window_type)(void **, void *, int);
    int (*screen_destroy_window)(void *);
    int (*screen_create_window_group)(void *, const char *);
    int (*screen_create_window_buffers)(void *, int);
    int (*screen_get_window_property_iv)(void *, int, int *);
    int (*screen_get_window_property_pv)(void *, int, void **);
    int (*screen_get_window_property_cv)(void *, int, int, char *);
    int (*screen_set_window_property_iv)(void *, int, const int *);
    int (*screen_set_window_property_pv)(void *, int, void **);
    int (*screen_get_buffer_property_iv)(void *, int, int *);
    int (*screen_get_buffer_property_pv)(void *, int, void **);
    int (*screen_post_window)(void *, void *, int, const int *, int);
    int (*screen_flush_context)(void *, int);
    int (*screen_create_event)(void **);
    int (*screen_destroy_event)(void *);
    int (*screen_get_event)(void *, void *, uint64_t);
    int (*screen_get_event_property_iv)(void *, int, int *);
    int (*screen_get_event_property_pv)(void *, int, void **);
    void *(*mmr_connect)(const char *);
    void (*mmr_disconnect)(void *);
    void *(*mmr_context_create)(void *, const char *, unsigned, unsigned);
    int (*mmr_context_destroy)(void *);
    int (*mmr_context_parameters)(void *, void *);
    int (*mmr_output_attach)(void *, const char *, const char *);
    int (*mmr_output_parameters)(void *, unsigned, void *);
    int (*mmr_output_detach)(void *, unsigned);
    int (*mmr_input_parameters)(void *, void *);
    int (*mmr_input_attach)(void *, const char *, const char *);
    int (*mmr_input_detach)(void *);
    int (*mmr_play)(void *);
    int (*mmr_stop)(void *);
    void *(*strm_dict_new)(void);
    void *(*strm_dict_set)(void *, const char *, const char *);
    void (*strm_dict_destroy)(void *);
    int (*name_open)(const char *, int);
    int (*name_close)(int);
    int (*TimerTimeout)(int, int, const void *, const uint64_t *, uint64_t *);
    int (*MsgSendvs_r)(int, const obd_iov *, int, void *, int);
} obd_media_api;
typedef struct {
    const char *session_id; /* Unique 1..32 ASCII letters/digits, supplied by caller. */
    int display_id, physical_width, physical_height;
    int x, y, width, height, opacity, zorder;
    unsigned encoded_width, encoded_height, fps;
} obd_media_config;
typedef struct {
    obd_media_api api;
    obd_media_config config;
    void *screen, *parent, *child, *event, *connection, *renderer;
    int output, channel, attached, playing, ready, failed, readback, actual_visible;
    unsigned submitted, posts;
    obd_lhf2_state sequence;
    char group[64], video[64], channel_name[64];
} obd_media;
/* Caller serializes access; close after success and failure. No pixel/vehicle claim. */
int obd_media_open(obd_media *, const obd_media_api *, const obd_media_config *);
int obd_media_submit(obd_media *, const obd_lhf2_frame *, const uint8_t *, size_t);
int obd_media_events(obd_media *);
int obd_media_window(obd_media *, int x, int y, int width, int height, int opacity);
int obd_media_close(obd_media *);
int obd_media_load(obd_media_api *, void *libraries[4]);
void obd_media_unload(void *libraries[4]);
#endif
