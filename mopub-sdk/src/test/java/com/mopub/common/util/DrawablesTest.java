package com.mopub.common.util;

import android.app.Activity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.fest.assertions.api.Assertions.assertThat;

@RunWith(RobolectricTestRunner.class)
public class DrawablesTest {
    @Test
    public void createDrawable_shouldNotCacheDrawables() throws Exception {
        assertThat(Drawables.BACKGROUND.createDrawable(new Activity()))
                .isNotSameAs(Drawables.BACKGROUND.createDrawable(new Activity()));
    }

    @Test
    public void getBitmap_shouldCacheBitmap() throws Exception {
        assertThat(Drawables.BACKGROUND.getBitmap())
                .isSameAs(Drawables.BACKGROUND.getBitmap());
    }
}
