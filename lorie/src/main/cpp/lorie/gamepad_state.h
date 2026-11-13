/* Per-device button and d-pad state. The X11 gamepad mapper uses buttons
 * 11..14 for the d-pad; direct buttons and hat input can overlap. */
#ifndef LORIE_GAMEPAD_STATE_H
#define LORIE_GAMEPAD_STATE_H
#include <stdint.h>

typedef struct LorieGamepadButtons {
    uint32_t direct;
    uint32_t hat;
} LorieGamepadButtons;

static inline uint32_t lorie_gamepad_button(LorieGamepadButtons *state,
                                             unsigned button, int down) {
    if (button < 1 || button > 16) return 0;
    uint32_t bit = 1u << (button - 1);
    uint32_t before = state->direct | state->hat;
    if (down) state->direct |= bit;
    else state->direct &= ~bit;
    return (before ^ (state->direct | state->hat)) & bit;
}

static inline uint32_t lorie_gamepad_hat(LorieGamepadButtons *state, int x, int y) {
    uint32_t before = state->direct | state->hat;
    uint32_t hat = 0;
    if (y < -127) hat |= 1u << 10;
    if (y > 127) hat |= 1u << 11;
    if (x < -127) hat |= 1u << 12;
    if (x > 127) hat |= 1u << 13;
    state->hat = hat;
    return (before ^ (state->direct | state->hat)) & (0xfu << 10);
}
#endif
