/*
 * Copyright (c) 2016, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psiphonlibrary;

import android.os.SystemClock;
import java.util.ArrayList;

public class DataTransferStats {
    // Singleton pattern

    private static DataTransferStatsForService m_dataTransferStatsForService;
    private static DataTransferStatsForUI m_dataTransferStatsForUI;

    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    public static synchronized DataTransferStatsForService getDataTransferStatsForService() {
        if (m_dataTransferStatsForService == null) {
            m_dataTransferStatsForService = new DataTransferStatsForService();
        }
        return m_dataTransferStatsForService;
    }

    public static synchronized DataTransferStatsForUI getDataTransferStatsForUI() {
        if (m_dataTransferStatsForUI == null) {
            m_dataTransferStatsForUI = new DataTransferStatsForUI();
        }
        return m_dataTransferStatsForUI;
    }

    public static abstract class DataTransferStatsBase {
        private static final long SLOW_BUCKET_PERIOD_MILLISECONDS = 5 * 60 * 1000;
        private static final long FAST_BUCKET_PERIOD_MILLISECONDS = 1000;
        private static final int MAX_BUCKETS = 24 * 60 / 5;

        protected static class Bucket {
            public long m_bytesSent = 0;
            public long m_bytesReceived = 0;
        }

        protected long m_connectedTime;
        protected long m_totalBytesSent;
        protected long m_totalBytesReceived;
        protected ArrayList<Bucket> m_slowBuckets;
        protected long m_slowBucketsLastStartTime;
        protected ArrayList<Bucket> m_fastBuckets;
        protected long m_fastBucketsLastStartTime;

        private DataTransferStatsBase() {
            m_totalBytesSent = 0;
            m_totalBytesReceived = 0;

            stop();
        }

        public synchronized void stop() {
            m_connectedTime = 0;
            resetBytesTransferred();
        }

        protected void resetBytesTransferred() {
            long now = SystemClock.elapsedRealtime();
            m_slowBucketsLastStartTime = bucketStartTime(now, SLOW_BUCKET_PERIOD_MILLISECONDS);
            m_slowBuckets = newBuckets();
            m_fastBucketsLastStartTime = bucketStartTime(now, FAST_BUCKET_PERIOD_MILLISECONDS);
            m_fastBuckets = newBuckets();
        }

        private long bucketStartTime(long now, long period) {
            return period * (now / period);
        }

        private ArrayList<Bucket> newBuckets() {
            ArrayList<Bucket> buckets = new ArrayList<>();
            for (int i = 0; i < MAX_BUCKETS; i++) {
                buckets.add(new Bucket());
            }
            return buckets;
        }

        private void shiftBuckets(long diff, long period, ArrayList<Bucket> buckets) {
            for (int i = 0; i < diff / period + 1; i++) {
                buckets.add(buckets.size(), new Bucket());
                if (buckets.size() >= MAX_BUCKETS) {
                    buckets.remove(0);
                }
            }
        }

        protected void manageBuckets() {
            long now = SystemClock.elapsedRealtime();

            long diff = now - m_slowBucketsLastStartTime;
            if (diff >= SLOW_BUCKET_PERIOD_MILLISECONDS) {
                shiftBuckets(diff, SLOW_BUCKET_PERIOD_MILLISECONDS, m_slowBuckets);
                m_slowBucketsLastStartTime = bucketStartTime(now, SLOW_BUCKET_PERIOD_MILLISECONDS);
            }

            diff = now - m_fastBucketsLastStartTime;
            if (diff >= FAST_BUCKET_PERIOD_MILLISECONDS) {
                shiftBuckets(diff, FAST_BUCKET_PERIOD_MILLISECONDS, m_fastBuckets);
                m_fastBucketsLastStartTime = bucketStartTime(now, FAST_BUCKET_PERIOD_MILLISECONDS);
            }
        }
    }

    public static class DataTransferStatsForService extends DataTransferStatsBase {
        private DataTransferStatsForService() {

        }

        public synchronized void startSession() {
            resetBytesTransferred();
        }

        public synchronized void startConnected() {
            m_connectedTime = SystemClock.elapsedRealtime();
        }

        public synchronized void addBytesSent(long bytes) {
            m_totalBytesSent += bytes;

            manageBuckets();
            addSentToBuckets(bytes);
        }

        public synchronized void addBytesReceived(long bytes) {
            m_totalBytesReceived += bytes;

            manageBuckets();
            addReceivedToBuckets(bytes);
        }

        private void addSentToBuckets(long bytes) {
            m_slowBuckets.get(m_slowBuckets.size() - 1).m_bytesSent += bytes;
            m_fastBuckets.get(m_fastBuckets.size() - 1).m_bytesSent += bytes;
        }

        private void addReceivedToBuckets(long bytes) {
            m_slowBuckets.get(m_slowBuckets.size() - 1).m_bytesReceived += bytes;
            m_fastBuckets.get(m_fastBuckets.size() - 1).m_bytesReceived += bytes;
        }
    }

    public static class DataTransferStatsForUI extends DataTransferStatsBase {
        private DataTransferStatsForUI() {

        }

        private ArrayList<Long> getSentSeries(ArrayList<Bucket> buckets) {
            ArrayList<Long> series = new ArrayList<>();
            for (int i = 0; i < buckets.size(); i++) {
                series.add(buckets.get(i).m_bytesSent);
            }
            return series;
        }

        private ArrayList<Long> getReceivedSeries(ArrayList<Bucket> buckets) {
            ArrayList<Long> series = new ArrayList<>();
            for (int i = 0; i < buckets.size(); i++) {
                series.add(buckets.get(i).m_bytesReceived);
            }
            return series;
        }

        public synchronized long getElapsedTime() {
            long now = SystemClock.elapsedRealtime();

            return now - this.m_connectedTime;
        }

        public synchronized long getTotalBytesSent() {
            return this.m_totalBytesSent;
        }

        public synchronized long getTotalBytesReceived() {
            return this.m_totalBytesReceived;
        }

        public synchronized ArrayList<Long> getSlowSentSeries() {
            manageBuckets();
            return getSentSeries(this.m_slowBuckets);
        }

        public synchronized ArrayList<Long> getSlowReceivedSeries() {
            manageBuckets();
            return getReceivedSeries(this.m_slowBuckets);
        }

        public synchronized ArrayList<Long> getFastSentSeries() {
            manageBuckets();
            return getSentSeries(this.m_fastBuckets);
        }

        public synchronized ArrayList<Long> getFastReceivedSeries() {
            manageBuckets();
            return getReceivedSeries(this.m_fastBuckets);
        }
    }
}
