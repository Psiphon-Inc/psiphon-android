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

public class RegionAdapter extends ArrayAdapter<Integer>
{
    private static class Region
    {
        String code;
        int nameResourceId;
        int flagResourceId;
        
        Region(String code, int nameResourceId, int flagResourceId)
        {
            this.code = code;
            this.nameResourceId = nameResourceId;
            this.flagResourceId = flagResourceId;
        }
    }
    
    private static final Region[] regions =
    {
        new Region(ServerInterface.ServerEntry.REGION_CODE_ANY, R.string.region_name_any, R.drawable.flag_unknown),
        new Region("US", R.string.region_name_us, R.drawable.flag_us),
        new Region("GB", R.string.region_name_gb, R.drawable.flag_gb),
        new Region("CA", R.string.region_name_ca, R.drawable.flag_ca),
        new Region("JP", R.string.region_name_jp, R.drawable.flag_jp),
        new Region("DE", R.string.region_name_de, R.drawable.flag_de),
    };
    
    Context m_context;

    public RegionAdapter(Context context)
    {
        super(context, R.layout.region_row);
        m_context = context;
        populate();
    }
    
    public void populate()
    {
        ServerInterface serverInterface = new ServerInterface(m_context);
        clear();
        for (int index = 0; index < regions.length; index ++)
        {
            if (serverInterface.serverInRegionExists(regions[index].code))
            {
                add(index);
            }
        }
        notifyDataSetChanged();
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
        
        int index = getItem(position);

        ImageView icon = (ImageView)row.findViewById(R.id.regionRowImage);
        icon.setImageResource(regions[index].flagResourceId);

        TextView label = (TextView)row.findViewById(R.id.regionRowText);
        label.setText(m_context.getString(regions[index].nameResourceId));

        return row;
    }
    
    public String getSelectedRegionCode(int position)
    {
        int index = getItem(position);

        return regions[index].code;
    }

    
    public int getPositionForRegionCode(String regionCode)
    {
        for (int index = 0; index < regions.length; index ++)
        {
            if (regionCode.equals(regions[index].code))
            {
                return getPosition(index);
            }
        }

        // Default to ANY. Might happen if persistent selection is used
        // on different client version which doesn't have a corresponding Region
        assert(regions[0].code.equals(ServerInterface.ServerEntry.REGION_CODE_ANY));
        return 0;
    }
}
