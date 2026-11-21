package com.copymylife.copymylife.exception;

// US-19: an adapted copy was refused because of one of its activities; activity is its place in
// the routine, counting from 1, so the page can point at it.
public class AdaptedActivityException extends IllegalArgumentException {

    private final int activity;

    public AdaptedActivityException(int activity, String message) {
        super(message);
        this.activity = activity;
    }

    public int getActivity() {
        return activity;
    }
}
