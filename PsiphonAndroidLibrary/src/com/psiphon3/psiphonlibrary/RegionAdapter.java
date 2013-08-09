/*
 * Copyright (c) 2013, Psiphon Inc.
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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

public class RegionAdapter extends ArrayAdapter<String>
{
    static String[] regionNames = {"Any", "United States", "United Kingdom", "Japan", "Canada", "Germany"};
    static int regionFlags[] =  {R.drawable.flag_unknown, R.drawable.flag_us, R.drawable.flag_gb, R.drawable.flag_jp, R.drawable.flag_ca, R.drawable.flag_de};
    
    Context m_context;

    public RegionAdapter(Context context)
    {
        super(context, R.layout.region_row, regionNames);
        m_context = context;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent)
    {
        return getCustomView(position, convertView, parent);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent)
    {
        return getCustomView(position, convertView, parent);
    }

    public View getCustomView(int position, View convertView, ViewGroup parent)
    {
        LayoutInflater inflater = (LayoutInflater)m_context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View row = inflater.inflate(R.layout.region_row, parent, false);

        ImageView icon = (ImageView)row.findViewById(R.id.regionRowImage);
        icon.setImageResource(regionFlags[position]);

        TextView label = (TextView)row.findViewById(R.id.regionRowText);
        label.setText(regionNames[position]);

        return row;
    }
}
