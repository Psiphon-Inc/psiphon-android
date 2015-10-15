package com.mopub.mobileads.util.vast;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class VastCompanionAd implements Serializable {
    private static final long serialVersionUID = 0L;

    private final Integer mWidth;
    private final Integer mHeight;
    private final String mImageUrl;
    private final String mClickThroughUrl;
    private final ArrayList<String> mClickTrackers;

    public VastCompanionAd(
            Integer width,
            Integer height,
            String imageUrl,
            String clickThroughUrl,
            ArrayList<String> clickTrackers) {
        mWidth = width;
        mHeight = height;
        mImageUrl = imageUrl;
        mClickThroughUrl = clickThroughUrl;
        mClickTrackers = clickTrackers;
    }

    public Integer getWidth() {
        return mWidth;
    }

    public Integer getHeight() {
        return mHeight;
    }

    public String getImageUrl() {
        return mImageUrl;
    }

    public String getClickThroughUrl() {
        return mClickThroughUrl;
    }

    public List<String> getClickTrackers() {
        return mClickTrackers;
    }
}
