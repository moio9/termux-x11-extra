#ifndef LORIE_GAMEPAD_DEVICE_H
#define LORIE_GAMEPAD_DEVICE_H
#include <stdint.h>
#include "inputstr.h"
#include "gamepad_state.h"
#ifdef __cplusplus
extern "C" {
#endif
#define LORIE_MAX_GAMEPADS 4
typedef struct LorieGamepadSlot {
    DeviceIntPtr device;
    int32_t android_id;
    uint32_t vendor_id, product_id;
    uint8_t input_mode;
    Bool has_rumble;
    LorieGamepadButtons buttons;
} LorieGamepadSlot;
extern LorieGamepadSlot lorieGamepads[LORIE_MAX_GAMEPADS];
LorieGamepadSlot *lorieGamepadForAndroid(int32_t android_id);
LorieGamepadSlot *lorieGamepadForDevice(DeviceIntPtr device);
#ifdef __cplusplus
}
#endif
#endif
