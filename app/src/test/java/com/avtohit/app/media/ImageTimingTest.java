package com.avtohit.app.media;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class ImageTimingTest {
    @Test public void silentVideoUsesEachImagesTimeInOrder() {
        assertEquals(Arrays.asList(1000L, 3000L, 2000L),
                ImageTiming.durations(3, Arrays.asList(1, 3, 2), 0, false, 0, false));
    }
    @Test public void zeroInheritsTheSharedTime() {
        assertEquals(Arrays.asList(5000L, 12000L, 5000L),
                ImageTiming.durations(3, Arrays.asList(0, 12, 0), 5, false, 60000, true));
    }
    @Test public void existingZeroModeStillHoldsTheFirstImage() {
        assertEquals(Collections.singletonList(9000L),
                ImageTiming.durations(3, null, 0, false, 9000, true));
    }
    @Test public void autoSplitPreservesTheExactSongDuration() {
        assertEquals(Arrays.asList(3334L, 3334L, 3333L),
                ImageTiming.durations(3, null, 0, true, 10001, true));
    }
    @Test public void individualTimeOverridesItsAutomaticShare() {
        assertEquals(Arrays.asList(3000L, 2000L, 3000L),
                ImageTiming.durations(3, Arrays.asList(0, 2, 0), 0, true, 9000, true));
    }
    @Test public void visibleCountUsesVariableTimes() {
        List<Long> durations = Arrays.asList(1000L, 3000L, 2000L);
        assertEquals(1, ImageTiming.visibleImageCount(durations, 1000));
        assertEquals(2, ImageTiming.visibleImageCount(durations, 3500));
        assertEquals(3, ImageTiming.visibleImageCount(durations, 4001));
        assertEquals(3, ImageTiming.visibleImageCount(durations, 12000));
    }
    @Test(expected = IllegalArgumentException.class)
    public void silentVideoRequiresPositiveTimeForEveryImage() {
        ImageTiming.durations(3, Arrays.asList(1, 0, 3), 0, false, 0, false);
    }
    @Test(expected = IllegalArgumentException.class)
    public void audioWithPartialTimesRequiresSharedOrAutomaticTime() {
        ImageTiming.durations(3, Arrays.asList(1, 0, 3), 0, false, 9000, true);
    }
    @Test(expected = IllegalArgumentException.class)
    public void rejectsValuesAboveSixty() {
        ImageTiming.durations(1, Collections.singletonList(61), 0, false, 0, false);
    }
    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeValuesEvenInStillImageMode() {
        ImageTiming.durations(1, Collections.singletonList(-1), 0, false, 9000, true);
    }
    @Test public void missingSavedValuesUseTheSharedSetting() {
        assertEquals(Arrays.asList(2000L, 6000L, 6000L),
                ImageTiming.durations(3, Collections.singletonList(2), 6, false, 0, false));
    }
}
