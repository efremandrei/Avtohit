package com.avtohit.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import static org.junit.Assert.*;

public class MediaOrderTest {
    private ArrayList<String> items() {
        return new ArrayList<>(Arrays.asList("1.jpg", "2.jpg", "3.jpg"));
    }

    @Test public void moveFirstToEnd() {
        ArrayList<String> list = items();
        assertTrue(MediaOrder.move(list, "1.jpg", 3));
        assertEquals(Arrays.asList("2.jpg", "3.jpg", "1.jpg"), list);
    }

    @Test public void moveLastToStartOverridesFilenameOrder() {
        ArrayList<String> list = items();
        assertTrue(MediaOrder.move(list, "3.jpg", 0));
        assertEquals(Arrays.asList("3.jpg", "1.jpg", "2.jpg"), list);
    }

    @Test public void moveDownToGapAccountsForRemovedRow() {
        ArrayList<String> list = items();
        assertTrue(MediaOrder.move(list, "1.jpg", 2));
        assertEquals(Arrays.asList("2.jpg", "1.jpg", "3.jpg"), list);
    }

    @Test public void adjacentAndSelfDropsAreNoOps() {
        ArrayList<String> list = items();
        assertFalse(MediaOrder.move(list, "2.jpg", 1));
        assertFalse(MediaOrder.move(list, "2.jpg", 2));
        assertEquals(items(), list);
    }

    @Test public void cancelledOrUnknownItemDoesNotChangeOrder() {
        ArrayList<String> list = items();
        assertFalse(MediaOrder.move(list, "missing.mp4", 0));
        assertEquals(items(), list);
    }

    @Test public void dropsOutsideListClampToEnds() {
        ArrayList<String> list = items();
        assertTrue(MediaOrder.move(list, "3.jpg", -10));
        assertTrue(MediaOrder.move(list, "3.jpg", 100));
        assertEquals(items(), list);
    }

    @Test public void duplicatesKeepIndependentClipMetadata() {
        Object original = new Object();
        Object duplicate = new Object();
        Object otherClip = new Object();
        ArrayList<Object> list = new ArrayList<>(Arrays.asList(original, duplicate, otherClip));
        assertTrue(MediaOrder.move(list, duplicate, 3));
        assertSame(original, list.get(0));
        assertSame(otherClip, list.get(1));
        assertSame(duplicate, list.get(2));
    }

    @Test public void repeatedMovesRetainUserOrder() {
        ArrayList<String> list = items();
        MediaOrder.move(list, "3.jpg", 0);
        MediaOrder.move(list, "2.jpg", 1);
        assertEquals(Arrays.asList("3.jpg", "2.jpg", "1.jpg"), list);
    }
}
