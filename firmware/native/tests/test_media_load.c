#include "media.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(x) do { if(!(x)) { fprintf(stderr,"%d: %s\n",__LINE__,#x); exit(1); } } while(0)
static int tokens[4],opens,closes,badlib=-1,badclose;
static const char *missing;
void *obd_fixture_dlopen(const char *path,int flags) {
    CHECK(flags==2); CHECK((opens==3)==(path==0));
    int index=opens++; return index==badlib?0:&tokens[index];
}
void *obd_fixture_dlsym(void *library,const char *name) {
    CHECK(library); return missing && !strcmp(name,missing)?0:&tokens[0];
}
int obd_fixture_dlclose(void *library) { CHECK(library); closes++; return badclose; }
int main(void) {
    obd_media_api api; void *libraries[4]; obd_media_load_error error;
    for(badlib=0;badlib<4;badlib++) {
        opens=closes=0;
        CHECK(obd_media_load_diagnostic(&api,libraries,&error));
        CHECK(error.library==badlib && !error.symbol && !error.cleanup_failed && closes==badlib);
    }
    badlib=-1; missing="mmr_input_attach"; opens=closes=0;
    CHECK(obd_media_load_diagnostic(&api,libraries,&error));
    CHECK(error.library==1 && !strcmp(error.symbol,missing) && closes==4 && !api.mmr_connect);
    opens=closes=0; badclose=1;
    CHECK(obd_media_load_diagnostic(&api,libraries,&error) && error.cleanup_failed);
    missing=0; badclose=0; opens=closes=0;
    CHECK(!obd_media_load_diagnostic(&api,libraries,&error) && error.library==-1);
    obd_media_unload(libraries); CHECK(closes==4);
    CHECK(obd_media_load_diagnostic(0,libraries,&error));
    return 0;
}
