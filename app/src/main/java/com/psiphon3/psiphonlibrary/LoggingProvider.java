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

import com.psiphon3.psiphonlibrary.Utils.MyLog;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Date;

/**
 * All logging is done directly to the LoggingProvider from all processes.
 */
public class LoggingProvider extends ContentProvider {
    public static final Uri INSERT_URI = Uri.parse("content://"+LoggingProvider.class.getCanonicalName()+"/");
    public static final String LOG_JSON_KEY = "logJSON";
    public static final String DIAGNOSTIC_LOG_JSON_KEY = "diagnosticlogJSON";

    /**
     * JSON-ify the arguments to be used in a call to the LoggingProvider content provider.
     * @param date Timestamp for the log.
     * @param stringResID String resource ID.
     * @param sensitivity Log sensitivity level.
     * @param formatArgs Arguments to be formatted into the log string.
     * @param priority One of the log priority levels supported by MyLog. Like: Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR, Log.VERBOSE
     * @return null on error.
     */
    public static String makeLogJSON(Date date, int stringResID, MyLog.Sensitivity sensitivity, Object[] formatArgs, int priority) {
        JSONObject json = new JSONObject();
        try {
            JSONArray jsonArray = new JSONArray();
            if (formatArgs != null) {
                for (Object arg : formatArgs) {
                    jsonArray.put(arg);
                }
            }

            json.put("timestamp", date.getTime()); // Store as millis since epoch
            json.put("stringResID", stringResID);
            json.put("sensitivity", sensitivity.name());
            json.put("formatArgs", jsonArray);
            json.put("priority", priority);
            return json.toString();
        } catch (JSONException e) {
            // pass
        }

        return null;
    }

    /**
     * JSON-ify the arguments to be used in a call to the LoggingProvider content provider.
     * @param date Timestamp for the log.
     * @param message String message.
     * @param data JSON data.
     * @return null on error.
     */
    public static String makeDiagnosticLogJSON(Date date, String message, JSONObject data) {
        JSONObject json = new JSONObject();
        try {
            json.put("timestamp", date.getTime()); // Store as millis since epoch
            json.put("message", message);
            json.put("data", data);
            return json.toString();
        } catch (JSONException e) {
            // pass
        }

        return null;
    }

    /**
     * To be called by StatusList when logs should be read from the provider DB into the StatusList.
     * @param context
     */
    public static void restoreLogs(Context context) {
        LogDatabaseHelper.restoreLogs(context);
    }

    @Override
    public boolean onCreate() {
        return false;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        assert(false);
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        assert(false);
        return null;
    }

    /**
     * Called when a content provider consumer wants to create a log.
     * @param uri Ignored.
     * @param values Must have LOG_JSON_KEY value, created by makeLogJSON().
     * @return Always returns null.
     */
    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        LogDatabaseHelper.insertLog(this.getContext(), values);
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        assert(false);
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        assert(false);
        return 0;
    }

    /**
     * The database where logs are stored until they can be consumed by the app.
     */
    public static class LogDatabaseHelper extends SQLiteOpenHelper {
        private static final String DATABASE_NAME = "loggingprovider.db";
        private static final int DATABASE_VERSION = 1;

        private static final String TABLE_NAME = "log";
        private static final String COLUMN_NAME_ID = "_ID";
        private static final String COLUMN_NAME_LOGJSON = "logjson";
        private static final String DICTIONARY_TABLE_CREATE =
                "CREATE TABLE " + TABLE_NAME + " (" +
                        COLUMN_NAME_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        COLUMN_NAME_LOGJSON + " TEXT NOT NULL" +
                ");";

        /**
         * The database object. Note that SQLite is thread-safe (by default).
         */
        private SQLiteDatabase mDB;

        // Singleton pattern
        private static LogDatabaseHelper mLogDatabaseHelper;
        public Object clone() throws CloneNotSupportedException
        {
            throw new CloneNotSupportedException();
        }
        public static synchronized LogDatabaseHelper get(Context context)
        {
            if (mLogDatabaseHelper == null)
            {
                mLogDatabaseHelper = new LogDatabaseHelper(context);
            }

            return mLogDatabaseHelper;
        }

        public synchronized SQLiteDatabase getDB()
        {
            if (mDB == null)
            {
                mDB = mLogDatabaseHelper.getWritableDatabase();
            }

            return mDB;
        }

        public LogDatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(DICTIONARY_TABLE_CREATE);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // nothing yet
        }

        /**
         * Insert a new log. May execute asynchronously.
         */
        public static void insertLog(Context context, ContentValues values) {
            // If this function is being called in the UI thread, then we need to do the work in an
            // async task. Otherwise we'll do the work directly.
            // For info about content provider thread use: http://stackoverflow.com/a/3571583
            if (Looper.myLooper() == Looper.getMainLooper()) {
                InsertLogTask task = new InsertLogTask(context);
                task.execute(values);
            }
            else {
                LogDatabaseHelper.insertLogHelper(context, values);
            }
        }

        /**
         * Task to do the async work.
         */
        private static class InsertLogTask extends AsyncTask<ContentValues, Void, Void> {
            private Context mContext;
            public InsertLogTask (Context context){
                mContext = context;
            }

            @Override
            protected Void doInBackground(ContentValues... params) {
                // DO NOT LOG WITHIN THIS FUNCTION

                // There will only ever be one item in the array, but...
                for (int i = 0; i < params.length; i++) {
                    LogDatabaseHelper.insertLogHelper(mContext, params[i]);
                }

                return null;
            }
        }

        /**
         * Inserts a new log. Should be called via insertLog or InsertLogTask.
         * @param context
         * @param values
         */
        private static void insertLogHelper(Context context, ContentValues values) {
            // DO NOT LOG WITHIN THIS FUNCTION

            SQLiteDatabase db = LogDatabaseHelper.get(context).getDB();

            db.beginTransaction();

            db.insert(TABLE_NAME, null, values);

            db.setTransactionSuccessful();
            db.endTransaction();
        }

        /**
         * To be called by StatusList at a time when it's appropriate to consume logs that were stored
         * by the provider. May execute asynchronously.
         */
        public static void restoreLogs(Context context) {
            // If this function is being called in the UI thread, then we need to do the work in an
            // async task. Otherwise we'll do the work directly.
            // For info about content provider thread use: http://stackoverflow.com/a/3571583
            if (Looper.myLooper() == Looper.getMainLooper()) {
                RestoreLogsTask task = new RestoreLogsTask(context);
                task.execute();
            }
            else {
                LogDatabaseHelper.restoreLogsHelper(context);
            }

        }

        /**
         * Task to do the async work.
         */
        private static class RestoreLogsTask extends AsyncTask<Void, Void, Void> {
            private Context mContext;
            public RestoreLogsTask (Context context){
                mContext = context;
            }

            @Override
            protected Void doInBackground(Void... params) {
                // DO NOT LOG WITHIN THIS FUNCTION

                LogDatabaseHelper.restoreLogsHelper(mContext);

                return null;
            }
        }

        /**
         * Does the log restore work. Should be called via restoreLogs or RestoreLogsTask.
         * @param context
         */
        private static void restoreLogsHelper(Context context) {
            // DO NOT LOG WITHIN THIS FUNCTION

            // We will cursor through DB records, passing them off to StatusList

            SQLiteDatabase db = LogDatabaseHelper.get(context).getDB();

            String[] projection = {
                    COLUMN_NAME_ID,
                    COLUMN_NAME_LOGJSON
            };

            String sortOrder = COLUMN_NAME_ID + " ASC";

            String limit = "1";

            // We will do repeated limit-1-query + delete transactions.
            while (true) {
                db.beginTransaction();

                Cursor cursor = db.query(
                        TABLE_NAME,
                        projection,
                        null, null,
                        null, null,
                        sortOrder,
                        limit);

                cursor.moveToFirst();
                if (cursor.isAfterLast()) {
                    // No records left.
                    cursor.close();
                    db.endTransaction();
                    break;
                }

                long recId = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_NAME_ID));
                String logJSON = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME_LOGJSON));

                // Don't need the cursor any longer
                cursor.close();

                // Extract log args from JSON.
                int stringResID, priority;
                MyLog.Sensitivity sensitivity;
                Object[] formatArgs;
                Date timestamp;
                try {
                    JSONObject jsonObj = new JSONObject(logJSON);
                    stringResID = jsonObj.getInt("stringResID");
                    sensitivity = MyLog.Sensitivity.valueOf(jsonObj.getString("sensitivity"));
                    priority = jsonObj.getInt("priority");
                    timestamp = new Date(jsonObj.getLong("timestamp"));

                    JSONArray formatArgsJSONArray = jsonObj.getJSONArray("formatArgs");
                    formatArgs = new Object[formatArgsJSONArray.length()];
                    for (int i = 0; i < formatArgsJSONArray.length(); i++) {
                        formatArgs[i] = formatArgsJSONArray.get(i);
                    }

                    // Pass the log info on to StatusList.
                    // Keep this call in the try block so it gets skipped if there's an exception above.
                    StatusList.getStatusList().addStatusEntry(
                            timestamp,
                            stringResID,
                            sensitivity,
                            formatArgs,
                            null,
                            priority);
                } catch (JSONException e) {
                    // Carry on with the deletion from DB
                }

                // TODO-TUNNEL-CORE: need another strategy to truncate logs
                /*
                // StatusList was in a state to receive the data, so delete the row.
                String selection = COLUMN_NAME_ID + " = ?";
                String[] selectionArgs = { String.valueOf(recId) };
                db.delete(TABLE_NAME, selection, selectionArgs);

                db.setTransactionSuccessful();
                db.endTransaction();
                */
            }
        }
    }
}
