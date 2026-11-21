package com.copymylife.copymylife.assistant;

// Planning preferences as given in one place (the saved preferences or one request), each null
// when not given. Times are minutes since midnight; restFrequency is FREQUENT, NORMAL or RARE.
public record PreferenceInput(
        Integer wake,
        Integer sleep,
        Integer availableFrom,
        Integer availableTo,
        Integer breakfast,
        Integer lunch,
        Integer dinner,
        Integer mealMinutes,
        Integer exercise,
        Integer focusBlockMinutes,
        Integer breakEveryMinutes,
        Integer breakMinutes,
        String restFrequency,
        Intensity intensity
) {

    public static final PreferenceInput NONE = new PreferenceInput(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    public boolean isEmpty() {
        return this.equals(NONE);
    }
}
