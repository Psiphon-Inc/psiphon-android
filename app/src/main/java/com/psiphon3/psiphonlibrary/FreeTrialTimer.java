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

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.psiphon3.util.AESObfuscator;
import com.psiphon3.util.Obfuscator;
import com.psiphon3.util.ValidationException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;


/**
 * FreeTrialTimer
 **/

public class FreeTrialTimer {
    private static final String FREE_TRIAL_TIME_FILENAME = "com.psiphon3.pro.FreeTrialTimer";
    private static final String KEY_FREE_TRIAL_TIME_SECONDS = "freeTrialTimeSeconds";
    private static final byte[] SALT = {19, -116, -92, -120, 30, 43, 79, -99, 125, -124, -41, -46, 67, -117, 39, 80, -33, -73, -6, 3};


    private static long getRemainingTimeSeconds(Context context) {
        FileInputStream in = null;
        try {
            in = context.openFileInput(FREE_TRIAL_TIME_FILENAME);
            long remainingSeconds = getRemainingTimeSeconds(context, in);
            Log.d("Psiphon-Pro", "got remainingSeconds from non-locked file: " + remainingSeconds);
            return remainingSeconds;

        } catch (FileNotFoundException e) {

        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {

            }
        }
        return 0;
    }


    private static long getRemainingTimeSeconds(Context context, FileInputStream in) {

        long remainingSeconds;

        String deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        Obfuscator obfuscator = new AESObfuscator(SALT, context.getPackageName(), deviceId);

        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String line;
            line = reader.readLine();

            reader.close();
            try {
                remainingSeconds = Long.parseLong(obfuscator.unobfuscate(line, KEY_FREE_TRIAL_TIME_SECONDS));
            } catch (ValidationException | NumberFormatException e) {
                remainingSeconds = 0;
            }
        } catch(IOException e) {
            remainingSeconds = 0;
        }

        return remainingSeconds;
    }

    private static void addTimeSyncSeconds(Context context, long seconds) {
        FileOutputStream out = null;
        FileInputStream in = null;
        java.nio.channels.FileLock lock = null;
        RandomAccessFile raf = null;
        long remainingSeconds;

        String deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        Obfuscator obfuscator = new AESObfuscator(SALT, context.getPackageName(), deviceId);

        try {
            String filePath = context.getFilesDir() + File.separator + FREE_TRIAL_TIME_FILENAME;

            raf = new RandomAccessFile(filePath, "rw");

            lock = raf.getChannel().lock();

            FileDescriptor fd = raf.getFD();

            out = new FileOutputStream(fd);
            in = new FileInputStream(fd);

            remainingSeconds = getRemainingTimeSeconds(context, in);
            remainingSeconds += seconds;
            remainingSeconds = Math.max(0, remainingSeconds);
            Log.d("Psiphon-Pro", "got remainingSeconds from locked file: " + remainingSeconds);


            // Overwrite file
            raf.setLength(0);

            String lineWrite = obfuscator.obfuscate(String.valueOf(remainingSeconds), KEY_FREE_TRIAL_TIME_SECONDS);

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out));

            writer.write(lineWrite);
            writer.close();
            out.flush();
        } catch (IOException e) {

        } finally {
            try {
                if(raf != null ) {
                    raf.close();
                }
            } catch (IOException e) {

            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {

            }
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {

            }
            try {
                if(lock != null) {
                    lock.release();
                }
            } catch (IOException e) {

            }
        }
    }

    public static class FreeTrialTimerCachingWrapper {
        private boolean m_initialized;
        private long m_remainingSeconds;

        public FreeTrialTimerCachingWrapper() {
            m_initialized = false;
            m_remainingSeconds = 0;
        }

        public void reset() {
            m_initialized = false;
            m_remainingSeconds = 0;
        }

        public synchronized long getRemainingTimeSeconds(Context context) {
            if (!m_initialized) {
                m_remainingSeconds = FreeTrialTimer.getRemainingTimeSeconds(context);
                m_initialized = true;
            }

            return m_remainingSeconds;
        }

        public synchronized void addTimeSyncSeconds(Context context, long seconds) {
            if (m_initialized) {
                m_remainingSeconds += seconds;
            }

            FreeTrialTimer.addTimeSyncSeconds(context, seconds);
        }
    }

    // Singleton pattern

    private static FreeTrialTimerCachingWrapper m_freeTrialTimerCachingWrapper;

    public static synchronized FreeTrialTimerCachingWrapper getFreeTrialTimerCachingWrapper() {
        if (m_freeTrialTimerCachingWrapper == null) {
            m_freeTrialTimerCachingWrapper = new FreeTrialTimerCachingWrapper();
        }

        return m_freeTrialTimerCachingWrapper;
    }
}
