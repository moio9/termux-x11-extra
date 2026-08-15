#ifndef LORIE_CONTROLLER_PROTOCOL_H
#define LORIE_CONTROLLER_PROTOCOL_H

#include <X11/Xmd.h>

#define LORIE_CONTROLLER_NAME "LORIE-CONTROLLER"

#define LORIE_CONTROLLER_MAJOR_VERSION 1
#define LORIE_CONTROLLER_MINOR_VERSION 1

#define X_LorieControllerQueryVersion      0
#define X_LorieControllerQueryCapabilities 1
#define X_LorieControllerRumble            2
#define X_LorieControllerStopRumble        3
#define LorieControllerNumberRequests      4

#define LORIE_CONTROLLER_CAP_RUMBLE         (1U << 0)
#define LORIE_CONTROLLER_CAP_TRIGGER_RUMBLE (1U << 1)

#define LORIE_CONTROLLER_EFFECT_MAIN     0
#define LORIE_CONTROLLER_EFFECT_TRIGGERS 1

#define LORIE_CONTROLLER_MAPPING_STANDARD 1

typedef struct {
    CARD8 reqType;
    CARD8 lorieControllerReqType;
    CARD16 length;
    CARD16 majorVersion;
    CARD16 minorVersion;
} xLorieControllerQueryVersionReq;
#define sz_xLorieControllerQueryVersionReq 8

typedef struct {
    CARD8 type;
    CARD8 pad0;
    CARD16 sequenceNumber;
    CARD32 length;
    CARD16 majorVersion;
    CARD16 minorVersion;
    CARD32 pad1[5];
} xLorieControllerQueryVersionReply;
#define sz_xLorieControllerQueryVersionReply 32

typedef struct {
    CARD8 reqType;
    CARD8 lorieControllerReqType;
    CARD16 length;
    CARD16 deviceId;
    CARD16 pad0;
} xLorieControllerQueryCapabilitiesReq;
#define sz_xLorieControllerQueryCapabilitiesReq 8

typedef struct {
    CARD8 type;
    CARD8 pad0;
    CARD16 sequenceNumber;
    CARD32 length;
    CARD16 deviceId;
    CARD16 capabilities;
    CARD8 present;
    CARD8 numAxes;
    CARD8 numButtons;
    CARD8 numHats;
    CARD32 mapping;
    CARD32 vendorId;
    CARD32 productId;
    CARD32 pad1;
} xLorieControllerQueryCapabilitiesReply;
#define sz_xLorieControllerQueryCapabilitiesReply 32

typedef struct {
    CARD8 reqType;
    CARD8 lorieControllerReqType;
    CARD16 length;
    CARD16 deviceId;
    CARD8 effect;
    CARD8 pad0;
    CARD16 lowFrequency;
    CARD16 highFrequency;
    CARD32 durationMs;
} xLorieControllerRumbleReq;
#define sz_xLorieControllerRumbleReq 16

typedef struct {
    CARD8 reqType;
    CARD8 lorieControllerReqType;
    CARD16 length;
    CARD16 deviceId;
    CARD16 pad0;
} xLorieControllerStopRumbleReq;
#define sz_xLorieControllerStopRumbleReq 8

typedef char lorie_controller_query_version_req_size[
    sizeof(xLorieControllerQueryVersionReq) == 8 ? 1 : -1];
typedef char lorie_controller_query_version_reply_size[
    sizeof(xLorieControllerQueryVersionReply) == 32 ? 1 : -1];
typedef char lorie_controller_query_capabilities_req_size[
    sizeof(xLorieControllerQueryCapabilitiesReq) == 8 ? 1 : -1];
typedef char lorie_controller_query_capabilities_reply_size[
    sizeof(xLorieControllerQueryCapabilitiesReply) == 32 ? 1 : -1];
typedef char lorie_controller_rumble_req_size[
    sizeof(xLorieControllerRumbleReq) == 16 ? 1 : -1];
typedef char lorie_controller_stop_rumble_req_size[
    sizeof(xLorieControllerStopRumbleReq) == 8 ? 1 : -1];

#endif /* LORIE_CONTROLLER_PROTOCOL_H */
