#include <assert.h>
#include "../lorie/src/main/cpp/lorie/gamepad_state.h"
int main(void) {
    LorieGamepadButtons a = {0}, b = {0};
    assert(lorie_gamepad_button(&a, 1, 1) == 1);
    assert(lorie_gamepad_button(&a, 1, 1) == 0);
    assert(b.direct == 0 && b.hat == 0);
    assert(lorie_gamepad_hat(&a, 0, -32767) == (1u << 10));
    assert(lorie_gamepad_button(&a, 11, 1) == 0);
    assert(lorie_gamepad_hat(&a, 0, 0) == 0);
    assert(lorie_gamepad_button(&a, 11, 0) == (1u << 10));
    assert(lorie_gamepad_hat(&b, 32767, 32767) == ((1u << 11) | (1u << 13)));
    assert(a.hat == 0 && a.direct == 1);
    assert(lorie_gamepad_button(&b, 0, 1) == 0);
    assert(lorie_gamepad_button(&b, 17, 1) == 0);
    return 0;
}
