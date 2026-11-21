package com.copymylife.copymylife.assistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// One day's taken minutes, as half-open intervals [start, end). The planner and the review both
// look for free room in it; nothing here ever lets two intervals share a minute.
final class Timeline {

    record Slot(int start, int end, ActivityKind kind, String title) {

        int length() {
            return end - start;
        }

        boolean overlaps(int from, int to) {
            return start < to && from < end;
        }
    }

    private final List<Slot> slots = new ArrayList<>();

    List<Slot> slots() {
        slots.sort(Comparator.comparingInt(Slot::start).thenComparingInt(Slot::end));
        return List.copyOf(slots);
    }

    void add(Slot slot) {
        if (!free(slot.start(), slot.end())) {
            throw new IllegalStateException("\"" + slot.title() + "\" would overlap another activity");
        }
        slots.add(slot);
    }

    // Commitments are fixed by the user and may touch each other; they are added without the check.
    void block(Slot slot) {
        slots.add(slot);
    }

    boolean free(int from, int to) {
        return slots.stream().noneMatch(slot -> slot.overlaps(from, to));
    }

    // Free, and at least gap minutes away from anything but a break.
    boolean freeWithGap(int from, int to, int gap) {
        return slots.stream().noneMatch(slot -> slot.overlaps(from, to)
                || (gap > 0 && slot.kind() != ActivityKind.BREAK && slot.overlaps(from - gap, to + gap)));
    }

    // The free start closest to preferred, at most maxShift away and within [lo, hi]. Ties go to
    // the later time, which keeps "after" rather than "before" the user's preference.
    Integer nearestFree(int preferred, int length, int lo, int hi, int maxShift, int gap) {
        for (int shift = 0; shift <= maxShift; shift += 5) {
            for (int start : new int[]{preferred + shift, preferred - shift}) {
                if (start >= lo && start + length <= hi && freeWithGap(start, start + length, gap)) {
                    return start;
                }
            }
        }
        return null;
    }

    // The end of the latest interval that ends at or before minute, or null.
    Slot before(int minute) {
        Slot found = null;
        for (Slot slot : slots) {
            if (slot.end() <= minute && (found == null || slot.end() > found.end())) {
                found = slot;
            }
        }
        return found;
    }

    Slot after(int minute) {
        Slot found = null;
        for (Slot slot : slots) {
            if (slot.start() >= minute && (found == null || slot.start() < found.start())) {
                found = slot;
            }
        }
        return found;
    }

    // Minutes of activity without rest that end right before minute: intervals closer together
    // than a break count as one run; a meal or a break, or a long enough gap, ends it.
    int runBefore(int minute, int breakMinutes) {
        int cursor = minute;
        int total = 0;

        while (true) {
            Slot previous = before(cursor);
            if (previous == null || cursor - previous.end() >= breakMinutes || previous.kind().isRest()) {
                return total;
            }
            total += previous.length();
            cursor = previous.start();
        }
    }

    int runAfter(int minute, int breakMinutes) {
        int cursor = minute;
        int total = 0;

        while (true) {
            Slot next = after(cursor);
            if (next == null || next.start() - cursor >= breakMinutes || next.kind().isRest()) {
                return total;
            }
            total += next.length();
            cursor = next.end();
        }
    }
}
