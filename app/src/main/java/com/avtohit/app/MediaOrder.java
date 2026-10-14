package com.avtohit.app;

import java.util.List;

/** Moves an existing selection to a gap between items, preserving item metadata. */
final class MediaOrder {
    static <T> boolean move(List<T> items, T draggedItem, int insertionIndex) {
        int from = items.indexOf(draggedItem);
        if (from < 0) {
            return false;
        }
        int to = Math.max(0, Math.min(insertionIndex, items.size()));
        // The destination is measured before removing the dragged row.
        if (from < to) {
            to--;
        }
        if (from == to) {
            return false;
        }
        items.remove(from);
        items.add(to, draggedItem);
        return true;
    }

    private MediaOrder() {}
}
