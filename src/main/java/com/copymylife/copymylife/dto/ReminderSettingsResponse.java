package com.copymylife.copymylife.dto;

import java.util.List;

// US-22: the user's reminder choice and the lead times they can pick from.
public record ReminderSettingsResponse(boolean enabled, int leadMinutes, List<Integer> leadOptions) {

    // What the user sends: enabled is required; leadMinutes may be left out to keep the current one.
    public record Request(Boolean enabled, Integer leadMinutes) {
    }
}
