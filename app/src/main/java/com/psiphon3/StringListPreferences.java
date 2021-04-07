package com.psiphon3;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.psiphon3.psiphonlibrary.Utils;

import net.grandcentrix.tray.AppPreferences;
import net.grandcentrix.tray.core.ItemNotFoundException;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public class StringListPreferences extends AppPreferences {
    public StringListPreferences(Context context) {
        super(context);
    }

    public void put(@NonNull String key, List<String> value) {
        JSONArray jsonArray = new JSONArray(value);
        super.put(key, jsonArray.toString());
    }

    @NonNull
    public List<String> getStringList(@NonNull String key) throws ItemNotFoundException {
        List<String> result = new ArrayList<>();
        try {
            String jsonString = super.getString(key);
            if (TextUtils.isEmpty(jsonString)) {
                return result;
            }
            JSONArray jsonArray = new JSONArray(jsonString);

            for (int i = 0; i < jsonArray.length(); i++) {
                result.add(jsonArray.getString(i));
            }
            return result;
        } catch (JSONException e) {
            Utils.MyLog.g(String.format("%s : JSON exception parsing '%s': %s", getClass().getSimpleName(), key, e.toString()));
            return result;
        }
    }
}
