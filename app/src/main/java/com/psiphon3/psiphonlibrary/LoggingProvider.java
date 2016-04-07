package com.psiphon3.psiphonlibrary;

import com.psiphon3.R;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Debug;
import android.support.annotation.Nullable;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 *
 */
public class LoggingProvider extends ContentProvider {
    public static final Uri INSERT_URI = Uri.parse("content://com.psiphon3.psiphonlibrary.LoggingProvider/");
    public static final String LOG_JSON_KEY = "logJSON";

    /**
     * JSON-ify the arguments to be used in a call to the LoggingProvider content provider.
     * @param stringResID String resource ID.
     * @param sensitivity Log sensitivity level.
     * @param priority One of the log priority levels supported by MyLog. Like: Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR, Log.VERBOSE
     * @param formatArgs Arguments to be formatted into the log string.
     * @return null on error.
     */
    public static String makeLogJSON(int stringResID, MyLog.Sensitivity sensitivity, int priority, Object[] formatArgs) {
        JSONObject json = new JSONObject();
        try {
            JSONArray jsonArray = new JSONArray();
            if (formatArgs != null) {
                for (Object arg : formatArgs) {
                    jsonArray.put(arg);
                }
            }

            json.put("stringResID", stringResID);
            json.put("sensitivity", sensitivity.name());
            json.put("priority", priority);
            json.put("formatArgs", jsonArray);
            json.put("timestamp", new Date().getTime()); // Store as millis since epoch
            return json.toString();
        } catch (JSONException e) {
            // pass
        }

        return null;
    }

    /**
     * To be called by MyLog when logs should be moved from the provider DB to MyLog.
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
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        assert(false);
        return null;
    }

    @Nullable
    @Override
    public String getType(Uri uri) {
        assert(false);
        return null;
    }

    /**
     * LOG_JSON_KEY value must have the form:
     * {
     *     stringResID int
     *     sensitivity string -- corresponding to MyLog.Sensitivity value
     *     formatArgs string array -- arguments formatted into string
     * }
     * @param uri Ignored.
     * @param values Must have LOG_JSON_KEY value.
     * @return Always returns null.
     */
    @Nullable
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        LogDatabaseHelper.insertLog(this.getContext(), values);

        // If we're a debug build, output something to LogCat right now so we can see it.
        if (PsiphonConstants.DEBUG) {
            try {
                JSONObject jsonObj = new JSONObject(values.getAsString(LOG_JSON_KEY));
                int stringResID = jsonObj.getInt("stringResID");
                String logString = getContext().getString(stringResID);
                MyLog.d(logString);
            } catch (JSONException e) {
                // pass
            }
        }

        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        assert(false);
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        assert(false);
        return 0;
    }

    public static class LogDatabaseHelper extends SQLiteOpenHelper {
        private static final Object mDbLock = new Object();

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
         * Async call to insert log values.
         */
        public static void insertLog(Context context, ContentValues values) {
            InsertLogTask task = new InsertLogTask(context);
            task.execute(values);
        }

        private static class InsertLogTask extends AsyncTask<ContentValues, Void, Void> {
            private Context mContext;
            public InsertLogTask (Context context){
                mContext = context;
            }

            @Override
            protected Void doInBackground(ContentValues... params) {
                // DO NOT LOG WITHIN THIS FUNCTION

                synchronized (mDbLock) {
                    LogDatabaseHelper dbHelper = new LogDatabaseHelper(mContext);
                    SQLiteDatabase db = dbHelper.getWritableDatabase();

                    for (int i = 0; i < params.length; i++) {
                        db.insert(TABLE_NAME, null, params[i]);
                    }
                }
                return null;
            }
        }

        /**
         * To be called by MyLog at a time when it's appropriate to create logs that were stored
         * by the provider.
         */
        public static void restoreLogs(Context context) {
            RestoreLogsTask task = new RestoreLogsTask(context);
            task.execute();
        }

        private static class RestoreLogsTask extends AsyncTask<Void, Void, Void> {
            private Context mContext;
            public RestoreLogsTask (Context context){
                mContext = context;
            }

            @Override
            protected Void doInBackground(Void... params) {
                // DO NOT LOG WITHIN THIS FUNCTION

                // We will cursor through DB records, passing them off to MyLog and deleting them.

                synchronized (mDbLock) {
                    LogDatabaseHelper dbHelper = new LogDatabaseHelper(mContext);
                    SQLiteDatabase db = dbHelper.getWritableDatabase();

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
                            db.endTransaction();
                            break;
                        }

                        long recId = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_NAME_ID));
                        String logJSON = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME_LOGJSON));

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
                            timestamp = new Date(jsonObj.getInt("timestamp"));

                            JSONArray formatArgsJSONArray = jsonObj.getJSONArray("formatArgs");
                            formatArgs = new Object[formatArgsJSONArray.length()];
                            for (int i = 0; i < formatArgsJSONArray.length(); i++) {
                                formatArgs[i] = formatArgsJSONArray.get(i);
                            }

                            // Pass the log info on to MyLog.
                            // Keep this call in the try block so it gets skipped if there's an exception above.
                            if (!MyLog.logFromProvider(stringResID, sensitivity, priority, formatArgs, timestamp)) {
                                // MyLog is not in a state to receive logs. Abort.
                                db.endTransaction();
                                break;
                            }
                        } catch (JSONException e) {
                            // Carry on with the deletion from DB
                            int i = 0;
                        }

                        // MyLog was in a state to receive the data, so delete the row.
                        String selection = COLUMN_NAME_ID + " = ?";
                        String[] selectionArgs = { String.valueOf(recId) };
                        db.delete(TABLE_NAME, selection, selectionArgs);

                        db.setTransactionSuccessful();
                        db.endTransaction();
                    }
                }

                return null;
            }
        }
    }
}
