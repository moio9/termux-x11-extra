#ifdef HAVE_DIX_CONFIG_H
#include <dix-config.h>
#endif

#include <X11/Xproto.h>

#include "dix.h"
#include "dixstruct.h"
#include "extnsionst.h"
#include "inputstr.h"
#include "misc.h"
#include "swaprep.h"

#include "lorie.h"
#include "lorie_controller_protocol.h"

extern DeviceIntPtr lorieGamepad;

static int
ProcLorieControllerQueryVersion(ClientPtr client)
{
    xLorieControllerQueryVersionReply reply = {
        .type = X_Reply,
        .sequenceNumber = client->sequence,
        .length = 0,
        .majorVersion = LORIE_CONTROLLER_MAJOR_VERSION,
        .minorVersion = LORIE_CONTROLLER_MINOR_VERSION,
    };

    REQUEST(xLorieControllerQueryVersionReq);
    REQUEST_SIZE_MATCH(xLorieControllerQueryVersionReq);

    if (stuff->majorVersion < reply.majorVersion ||
        (stuff->majorVersion == reply.majorVersion &&
         stuff->minorVersion < reply.minorVersion)) {
        reply.majorVersion = stuff->majorVersion;
        reply.minorVersion = stuff->minorVersion;
    }

    if (client->swapped) {
        swaps(&reply.sequenceNumber);
        swapl(&reply.length);
        swaps(&reply.majorVersion);
        swaps(&reply.minorVersion);
    }
    WriteToClient(client, sizeof(reply), &reply);
    return Success;
}

static int
ProcLorieControllerQueryCapabilities(ClientPtr client)
{
    DeviceIntPtr device = NULL;
    xLorieControllerQueryCapabilitiesReply reply = {
        .type = X_Reply,
        .sequenceNumber = client->sequence,
        .length = 0,
    };

    REQUEST(xLorieControllerQueryCapabilitiesReq);
    REQUEST_SIZE_MATCH(xLorieControllerQueryCapabilitiesReq);

    reply.deviceId = stuff->deviceId;
    if (dixLookupDevice(&device, stuff->deviceId, client,
                        DixGetAttrAccess) == Success &&
        device == lorieGamepad) {
        reply.present = 1;
        reply.numAxes = 6;
        reply.numButtons = 16;
        reply.numHats = 1;
        reply.mapping = LORIE_CONTROLLER_MAPPING_STANDARD;
        reply.vendorId = lorieGamepadVendorId;
        reply.productId = lorieGamepadProductId;
        reply.inputMode = lorieGamepadInputMode;
        if (lorieConnectionAlive() && lorieGamepadHasRumble) {
            reply.capabilities = LORIE_CONTROLLER_CAP_RUMBLE |
                                 LORIE_CONTROLLER_CAP_TRIGGER_RUMBLE;
        }
    }

    if (client->swapped) {
        swaps(&reply.sequenceNumber);
        swapl(&reply.length);
        swaps(&reply.deviceId);
        swaps(&reply.capabilities);
        swapl(&reply.mapping);
        swapl(&reply.vendorId);
        swapl(&reply.productId);
    }
    WriteToClient(client, sizeof(reply), &reply);
    return Success;
}

static int
LorieControllerVerifyDevice(ClientPtr client, CARD16 deviceId)
{
    DeviceIntPtr device = NULL;
    int rc = dixLookupDevice(&device, deviceId, client, DixUseAccess);

    if (rc != Success)
        return rc;
    if (device != lorieGamepad) {
        client->errorValue = deviceId;
        return BadMatch;
    }
    return Success;
}

static int
ProcLorieControllerRumble(ClientPtr client)
{
    int rc;

    REQUEST(xLorieControllerRumbleReq);
    REQUEST_SIZE_MATCH(xLorieControllerRumbleReq);

    rc = LorieControllerVerifyDevice(client, stuff->deviceId);
    if (rc != Success)
        return rc;
    if (stuff->effect > LORIE_CONTROLLER_EFFECT_TRIGGERS) {
        client->errorValue = stuff->effect;
        return BadValue;
    }

    lorieSetControllerRumble(stuff->effect, stuff->lowFrequency,
                             stuff->highFrequency, stuff->durationMs);
    return Success;
}

static int
ProcLorieControllerStopRumble(ClientPtr client)
{
    int rc;

    REQUEST(xLorieControllerStopRumbleReq);
    REQUEST_SIZE_MATCH(xLorieControllerStopRumbleReq);

    rc = LorieControllerVerifyDevice(client, stuff->deviceId);
    if (rc != Success)
        return rc;

    lorieSetControllerRumble(LORIE_CONTROLLER_EFFECT_MAIN, 0, 0, 0);
    return Success;
}

static int
ProcLorieControllerDispatch(ClientPtr client)
{
    REQUEST(xReq);

    switch (stuff->data) {
    case X_LorieControllerQueryVersion:
        return ProcLorieControllerQueryVersion(client);
    case X_LorieControllerQueryCapabilities:
        return ProcLorieControllerQueryCapabilities(client);
    case X_LorieControllerRumble:
        return ProcLorieControllerRumble(client);
    case X_LorieControllerStopRumble:
        return ProcLorieControllerStopRumble(client);
    default:
        return BadRequest;
    }
}

static int _X_COLD
SProcLorieControllerQueryVersion(ClientPtr client)
{
    REQUEST(xLorieControllerQueryVersionReq);
    swaps(&stuff->length);
    REQUEST_SIZE_MATCH(xLorieControllerQueryVersionReq);
    swaps(&stuff->majorVersion);
    swaps(&stuff->minorVersion);
    return ProcLorieControllerQueryVersion(client);
}

static int _X_COLD
SProcLorieControllerQueryCapabilities(ClientPtr client)
{
    REQUEST(xLorieControllerQueryCapabilitiesReq);
    swaps(&stuff->length);
    REQUEST_SIZE_MATCH(xLorieControllerQueryCapabilitiesReq);
    swaps(&stuff->deviceId);
    return ProcLorieControllerQueryCapabilities(client);
}

static int _X_COLD
SProcLorieControllerRumble(ClientPtr client)
{
    REQUEST(xLorieControllerRumbleReq);
    swaps(&stuff->length);
    REQUEST_SIZE_MATCH(xLorieControllerRumbleReq);
    swaps(&stuff->deviceId);
    swaps(&stuff->lowFrequency);
    swaps(&stuff->highFrequency);
    swapl(&stuff->durationMs);
    return ProcLorieControllerRumble(client);
}

static int _X_COLD
SProcLorieControllerStopRumble(ClientPtr client)
{
    REQUEST(xLorieControllerStopRumbleReq);
    swaps(&stuff->length);
    REQUEST_SIZE_MATCH(xLorieControllerStopRumbleReq);
    swaps(&stuff->deviceId);
    return ProcLorieControllerStopRumble(client);
}

static int _X_COLD
SProcLorieControllerDispatch(ClientPtr client)
{
    REQUEST(xReq);

    switch (stuff->data) {
    case X_LorieControllerQueryVersion:
        return SProcLorieControllerQueryVersion(client);
    case X_LorieControllerQueryCapabilities:
        return SProcLorieControllerQueryCapabilities(client);
    case X_LorieControllerRumble:
        return SProcLorieControllerRumble(client);
    case X_LorieControllerStopRumble:
        return SProcLorieControllerStopRumble(client);
    default:
        return BadRequest;
    }
}

void
LorieControllerExtensionInit(void)
{
    AddExtension(LORIE_CONTROLLER_NAME, 0, 0,
                 ProcLorieControllerDispatch,
                 SProcLorieControllerDispatch,
                 NULL, StandardMinorOpcode);
}
