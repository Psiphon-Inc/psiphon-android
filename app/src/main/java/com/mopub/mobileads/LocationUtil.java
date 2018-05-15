/*
 * Copyright (c) 2017, Psiphon Inc.
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

package com.mopub.mobileads;

import android.location.Location;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;


class LocationUtil {

    private static class Coordinates {
        private double latitude;
        private double longitude;

        Coordinates(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        double getLatitude() {
            return latitude;
        }

        double getLongitude() {
            return longitude;
        }
    }

    private static final Map<String, Coordinates> COUNTRY_COORDINATES_MAP = createCountryCoordinatesMap();

    private static Map<String, Coordinates> createCountryCoordinatesMap() {
        Map<String, Coordinates> countryCoordinatesMap = new HashMap<>();

        // Adapted from countries.csv of Dataset Publishing Language:
        // https://developers.google.com/public-data/docs/canonical/countries_csv
        // which is licensed under the Creative Commons Attribution 3.0 License:
        // http://creativecommons.org/licenses/by/3.0/

        countryCoordinatesMap.put("AD", new Coordinates(42.546245, 1.601554));
        countryCoordinatesMap.put("AE", new Coordinates(23.424076, 53.847818));
        countryCoordinatesMap.put("AF", new Coordinates(33.93911, 67.709953));
        countryCoordinatesMap.put("AG", new Coordinates(17.060816, -61.796428));
        countryCoordinatesMap.put("AI", new Coordinates(18.220554, -63.068615));
        countryCoordinatesMap.put("AL", new Coordinates(41.153332, 20.168331));
        countryCoordinatesMap.put("AM", new Coordinates(40.069099, 45.038189));
        countryCoordinatesMap.put("AN", new Coordinates(12.226079, -69.060087));
        countryCoordinatesMap.put("AO", new Coordinates(-11.202692, 17.873887));
        countryCoordinatesMap.put("AQ", new Coordinates(-75.250973, -0.071389));
        countryCoordinatesMap.put("AR", new Coordinates(-38.416097, -63.616672));
        countryCoordinatesMap.put("AS", new Coordinates(-14.270972, -170.132217));
        countryCoordinatesMap.put("AT", new Coordinates(47.516231, 14.550072));
        countryCoordinatesMap.put("AU", new Coordinates(-25.274398, 133.775136));
        countryCoordinatesMap.put("AW", new Coordinates(12.52111, -69.968338));
        countryCoordinatesMap.put("AZ", new Coordinates(40.143105, 47.576927));
        countryCoordinatesMap.put("BA", new Coordinates(43.915886, 17.679076));
        countryCoordinatesMap.put("BB", new Coordinates(13.193887, -59.543198));
        countryCoordinatesMap.put("BD", new Coordinates(23.684994, 90.356331));
        countryCoordinatesMap.put("BE", new Coordinates(50.503887, 4.469936));
        countryCoordinatesMap.put("BF", new Coordinates(12.238333, -1.561593));
        countryCoordinatesMap.put("BG", new Coordinates(42.733883, 25.48583));
        countryCoordinatesMap.put("BH", new Coordinates(25.930414, 50.637772));
        countryCoordinatesMap.put("BI", new Coordinates(-3.373056, 29.918886));
        countryCoordinatesMap.put("BJ", new Coordinates(9.30769, 2.315834));
        countryCoordinatesMap.put("BM", new Coordinates(32.321384, -64.75737));
        countryCoordinatesMap.put("BN", new Coordinates(4.535277, 114.727669));
        countryCoordinatesMap.put("BO", new Coordinates(-16.290154, -63.588653));
        countryCoordinatesMap.put("BR", new Coordinates(-14.235004, -51.92528));
        countryCoordinatesMap.put("BS", new Coordinates(25.03428, -77.39628));
        countryCoordinatesMap.put("BT", new Coordinates(27.514162, 90.433601));
        countryCoordinatesMap.put("BV", new Coordinates(-54.423199, 3.413194));
        countryCoordinatesMap.put("BW", new Coordinates(-22.328474, 24.684866));
        countryCoordinatesMap.put("BY", new Coordinates(53.709807, 27.953389));
        countryCoordinatesMap.put("BZ", new Coordinates(17.189877, -88.49765));
        countryCoordinatesMap.put("CA", new Coordinates(56.130366, -106.346771));
        countryCoordinatesMap.put("CC", new Coordinates(-12.164165, 96.870956));
        countryCoordinatesMap.put("CD", new Coordinates(-4.038333, 21.758664));
        countryCoordinatesMap.put("CF", new Coordinates(6.611111, 20.939444));
        countryCoordinatesMap.put("CG", new Coordinates(-0.228021, 15.827659));
        countryCoordinatesMap.put("CH", new Coordinates(46.818188, 8.227512));
        countryCoordinatesMap.put("CI", new Coordinates(7.539989, -5.54708));
        countryCoordinatesMap.put("CK", new Coordinates(-21.236736, -159.777671));
        countryCoordinatesMap.put("CL", new Coordinates(-35.675147, -71.542969));
        countryCoordinatesMap.put("CM", new Coordinates(7.369722, 12.354722));
        countryCoordinatesMap.put("CN", new Coordinates(35.86166, 104.195397));
        countryCoordinatesMap.put("CO", new Coordinates(4.570868, -74.297333));
        countryCoordinatesMap.put("CR", new Coordinates(9.748917, -83.753428));
        countryCoordinatesMap.put("CU", new Coordinates(21.521757, -77.781167));
        countryCoordinatesMap.put("CV", new Coordinates(16.002082, -24.013197));
        countryCoordinatesMap.put("CX", new Coordinates(-10.447525, 105.690449));
        countryCoordinatesMap.put("CY", new Coordinates(35.126413, 33.429859));
        countryCoordinatesMap.put("CZ", new Coordinates(49.817492, 15.472962));
        countryCoordinatesMap.put("DE", new Coordinates(51.165691, 10.451526));
        countryCoordinatesMap.put("DJ", new Coordinates(11.825138, 42.590275));
        countryCoordinatesMap.put("DK", new Coordinates(56.26392, 9.501785));
        countryCoordinatesMap.put("DM", new Coordinates(15.414999, -61.370976));
        countryCoordinatesMap.put("DO", new Coordinates(18.735693, -70.162651));
        countryCoordinatesMap.put("DZ", new Coordinates(28.033886, 1.659626));
        countryCoordinatesMap.put("EC", new Coordinates(-1.831239, -78.183406));
        countryCoordinatesMap.put("EE", new Coordinates(58.595272, 25.013607));
        countryCoordinatesMap.put("EG", new Coordinates(26.820553, 30.802498));
        countryCoordinatesMap.put("EH", new Coordinates(24.215527, -12.885834));
        countryCoordinatesMap.put("ER", new Coordinates(15.179384, 39.782334));
        countryCoordinatesMap.put("ES", new Coordinates(40.463667, -3.74922));
        countryCoordinatesMap.put("ET", new Coordinates(9.145, 40.489673));
        countryCoordinatesMap.put("FI", new Coordinates(61.92411, 25.748151));
        countryCoordinatesMap.put("FJ", new Coordinates(-16.578193, 179.414413));
        countryCoordinatesMap.put("FK", new Coordinates(-51.796253, -59.523613));
        countryCoordinatesMap.put("FM", new Coordinates(7.425554, 150.550812));
        countryCoordinatesMap.put("FO", new Coordinates(61.892635, -6.911806));
        countryCoordinatesMap.put("FR", new Coordinates(46.227638, 2.213749));
        countryCoordinatesMap.put("GA", new Coordinates(-0.803689, 11.609444));
        countryCoordinatesMap.put("GB", new Coordinates(55.378051, -3.435973));
        countryCoordinatesMap.put("GD", new Coordinates(12.262776, -61.604171));
        countryCoordinatesMap.put("GE", new Coordinates(42.315407, 43.356892));
        countryCoordinatesMap.put("GF", new Coordinates(3.933889, -53.125782));
        countryCoordinatesMap.put("GG", new Coordinates(49.465691, -2.585278));
        countryCoordinatesMap.put("GH", new Coordinates(7.946527, -1.023194));
        countryCoordinatesMap.put("GI", new Coordinates(36.137741, -5.345374));
        countryCoordinatesMap.put("GL", new Coordinates(71.706936, -42.604303));
        countryCoordinatesMap.put("GM", new Coordinates(13.443182, -15.310139));
        countryCoordinatesMap.put("GN", new Coordinates(9.945587, -9.696645));
        countryCoordinatesMap.put("GP", new Coordinates(16.995971, -62.067641));
        countryCoordinatesMap.put("GQ", new Coordinates(1.650801, 10.267895));
        countryCoordinatesMap.put("GR", new Coordinates(39.074208, 21.824312));
        countryCoordinatesMap.put("GS", new Coordinates(-54.429579, -36.587909));
        countryCoordinatesMap.put("GT", new Coordinates(15.783471, -90.230759));
        countryCoordinatesMap.put("GU", new Coordinates(13.444304, 144.793731));
        countryCoordinatesMap.put("GW", new Coordinates(11.803749, -15.180413));
        countryCoordinatesMap.put("GY", new Coordinates(4.860416, -58.93018));
        countryCoordinatesMap.put("GZ", new Coordinates(31.354676, 34.308825));
        countryCoordinatesMap.put("HK", new Coordinates(22.396428, 114.109497));
        countryCoordinatesMap.put("HM", new Coordinates(-53.08181, 73.504158));
        countryCoordinatesMap.put("HN", new Coordinates(15.199999, -86.241905));
        countryCoordinatesMap.put("HR", new Coordinates(45.1, 15.2));
        countryCoordinatesMap.put("HT", new Coordinates(18.971187, -72.285215));
        countryCoordinatesMap.put("HU", new Coordinates(47.162494, 19.503304));
        countryCoordinatesMap.put("ID", new Coordinates(-0.789275, 113.921327));
        countryCoordinatesMap.put("IE", new Coordinates(53.41291, -8.24389));
        countryCoordinatesMap.put("IL", new Coordinates(31.046051, 34.851612));
        countryCoordinatesMap.put("IM", new Coordinates(54.236107, -4.548056));
        countryCoordinatesMap.put("IN", new Coordinates(20.593684, 78.96288));
        countryCoordinatesMap.put("IO", new Coordinates(-6.343194, 71.876519));
        countryCoordinatesMap.put("IQ", new Coordinates(33.223191, 43.679291));
        countryCoordinatesMap.put("IR", new Coordinates(32.427908, 53.688046));
        countryCoordinatesMap.put("IS", new Coordinates(64.963051, -19.020835));
        countryCoordinatesMap.put("IT", new Coordinates(41.87194, 12.56738));
        countryCoordinatesMap.put("JE", new Coordinates(49.214439, -2.13125));
        countryCoordinatesMap.put("JM", new Coordinates(18.109581, -77.297508));
        countryCoordinatesMap.put("JO", new Coordinates(30.585164, 36.238414));
        countryCoordinatesMap.put("JP", new Coordinates(36.204824, 138.252924));
        countryCoordinatesMap.put("KE", new Coordinates(-0.023559, 37.906193));
        countryCoordinatesMap.put("KG", new Coordinates(41.20438, 74.766098));
        countryCoordinatesMap.put("KH", new Coordinates(12.565679, 104.990963));
        countryCoordinatesMap.put("KI", new Coordinates(-3.370417, -168.734039));
        countryCoordinatesMap.put("KM", new Coordinates(-11.875001, 43.872219));
        countryCoordinatesMap.put("KN", new Coordinates(17.357822, -62.782998));
        countryCoordinatesMap.put("KP", new Coordinates(40.339852, 127.510093));
        countryCoordinatesMap.put("KR", new Coordinates(35.907757, 127.766922));
        countryCoordinatesMap.put("KW", new Coordinates(29.31166, 47.481766));
        countryCoordinatesMap.put("KY", new Coordinates(19.513469, -80.566956));
        countryCoordinatesMap.put("KZ", new Coordinates(48.019573, 66.923684));
        countryCoordinatesMap.put("LA", new Coordinates(19.85627, 102.495496));
        countryCoordinatesMap.put("LB", new Coordinates(33.854721, 35.862285));
        countryCoordinatesMap.put("LC", new Coordinates(13.909444, -60.978893));
        countryCoordinatesMap.put("LI", new Coordinates(47.166, 9.555373));
        countryCoordinatesMap.put("LK", new Coordinates(7.873054, 80.771797));
        countryCoordinatesMap.put("LR", new Coordinates(6.428055, -9.429499));
        countryCoordinatesMap.put("LS", new Coordinates(-29.609988, 28.233608));
        countryCoordinatesMap.put("LT", new Coordinates(55.169438, 23.881275));
        countryCoordinatesMap.put("LU", new Coordinates(49.815273, 6.129583));
        countryCoordinatesMap.put("LV", new Coordinates(56.879635, 24.603189));
        countryCoordinatesMap.put("LY", new Coordinates(26.3351, 17.228331));
        countryCoordinatesMap.put("MA", new Coordinates(31.791702, -7.09262));
        countryCoordinatesMap.put("MC", new Coordinates(43.750298, 7.412841));
        countryCoordinatesMap.put("MD", new Coordinates(47.411631, 28.369885));
        countryCoordinatesMap.put("ME", new Coordinates(42.708678, 19.37439));
        countryCoordinatesMap.put("MG", new Coordinates(-18.766947, 46.869107));
        countryCoordinatesMap.put("MH", new Coordinates(7.131474, 171.184478));
        countryCoordinatesMap.put("MK", new Coordinates(41.608635, 21.745275));
        countryCoordinatesMap.put("ML", new Coordinates(17.570692, -3.996166));
        countryCoordinatesMap.put("MM", new Coordinates(21.913965, 95.956223));
        countryCoordinatesMap.put("MN", new Coordinates(46.862496, 103.846656));
        countryCoordinatesMap.put("MO", new Coordinates(22.198745, 113.543873));
        countryCoordinatesMap.put("MP", new Coordinates(17.33083, 145.38469));
        countryCoordinatesMap.put("MQ", new Coordinates(14.641528, -61.024174));
        countryCoordinatesMap.put("MR", new Coordinates(21.00789, -10.940835));
        countryCoordinatesMap.put("MS", new Coordinates(16.742498, -62.187366));
        countryCoordinatesMap.put("MT", new Coordinates(35.937496, 14.375416));
        countryCoordinatesMap.put("MU", new Coordinates(-20.348404, 57.552152));
        countryCoordinatesMap.put("MV", new Coordinates(3.202778, 73.22068));
        countryCoordinatesMap.put("MW", new Coordinates(-13.254308, 34.301525));
        countryCoordinatesMap.put("MX", new Coordinates(23.634501, -102.552784));
        countryCoordinatesMap.put("MY", new Coordinates(4.210484, 101.975766));
        countryCoordinatesMap.put("MZ", new Coordinates(-18.665695, 35.529562));
        countryCoordinatesMap.put("NA", new Coordinates(-22.95764, 18.49041));
        countryCoordinatesMap.put("NC", new Coordinates(-20.904305, 165.618042));
        countryCoordinatesMap.put("NE", new Coordinates(17.607789, 8.081666));
        countryCoordinatesMap.put("NF", new Coordinates(-29.040835, 167.954712));
        countryCoordinatesMap.put("NG", new Coordinates(9.081999, 8.675277));
        countryCoordinatesMap.put("NI", new Coordinates(12.865416, -85.207229));
        countryCoordinatesMap.put("NL", new Coordinates(52.132633, 5.291266));
        countryCoordinatesMap.put("NO", new Coordinates(60.472024, 8.468946));
        countryCoordinatesMap.put("NP", new Coordinates(28.394857, 84.124008));
        countryCoordinatesMap.put("NR", new Coordinates(-0.522778, 166.931503));
        countryCoordinatesMap.put("NU", new Coordinates(-19.054445, -169.867233));
        countryCoordinatesMap.put("NZ", new Coordinates(-40.900557, 174.885971));
        countryCoordinatesMap.put("OM", new Coordinates(21.512583, 55.923255));
        countryCoordinatesMap.put("PA", new Coordinates(8.537981, -80.782127));
        countryCoordinatesMap.put("PE", new Coordinates(-9.189967, -75.015152));
        countryCoordinatesMap.put("PF", new Coordinates(-17.679742, -149.406843));
        countryCoordinatesMap.put("PG", new Coordinates(-6.314993, 143.95555));
        countryCoordinatesMap.put("PH", new Coordinates(12.879721, 121.774017));
        countryCoordinatesMap.put("PK", new Coordinates(30.375321, 69.345116));
        countryCoordinatesMap.put("PL", new Coordinates(51.919438, 19.145136));
        countryCoordinatesMap.put("PM", new Coordinates(46.941936, -56.27111));
        countryCoordinatesMap.put("PN", new Coordinates(-24.703615, -127.439308));
        countryCoordinatesMap.put("PR", new Coordinates(18.220833, -66.590149));
        countryCoordinatesMap.put("PS", new Coordinates(31.952162, 35.233154));
        countryCoordinatesMap.put("PT", new Coordinates(39.399872, -8.224454));
        countryCoordinatesMap.put("PW", new Coordinates(7.51498, 134.58252));
        countryCoordinatesMap.put("PY", new Coordinates(-23.442503, -58.443832));
        countryCoordinatesMap.put("QA", new Coordinates(25.354826, 51.183884));
        countryCoordinatesMap.put("RE", new Coordinates(-21.115141, 55.536384));
        countryCoordinatesMap.put("RO", new Coordinates(45.943161, 24.96676));
        countryCoordinatesMap.put("RS", new Coordinates(44.016521, 21.005859));
        countryCoordinatesMap.put("RU", new Coordinates(61.52401, 105.318756));
        countryCoordinatesMap.put("RW", new Coordinates(-1.940278, 29.873888));
        countryCoordinatesMap.put("SA", new Coordinates(23.885942, 45.079162));
        countryCoordinatesMap.put("SB", new Coordinates(-9.64571, 160.156194));
        countryCoordinatesMap.put("SC", new Coordinates(-4.679574, 55.491977));
        countryCoordinatesMap.put("SD", new Coordinates(12.862807, 30.217636));
        countryCoordinatesMap.put("SE", new Coordinates(60.128161, 18.643501));
        countryCoordinatesMap.put("SG", new Coordinates(1.352083, 103.819836));
        countryCoordinatesMap.put("SH", new Coordinates(-24.143474, -10.030696));
        countryCoordinatesMap.put("SI", new Coordinates(46.151241, 14.995463));
        countryCoordinatesMap.put("SJ", new Coordinates(77.553604, 23.670272));
        countryCoordinatesMap.put("SK", new Coordinates(48.669026, 19.699024));
        countryCoordinatesMap.put("SL", new Coordinates(8.460555, -11.779889));
        countryCoordinatesMap.put("SM", new Coordinates(43.94236, 12.457777));
        countryCoordinatesMap.put("SN", new Coordinates(14.497401, -14.452362));
        countryCoordinatesMap.put("SO", new Coordinates(5.152149, 46.199616));
        countryCoordinatesMap.put("SR", new Coordinates(3.919305, -56.027783));
        countryCoordinatesMap.put("ST", new Coordinates(0.18636, 6.613081));
        countryCoordinatesMap.put("SV", new Coordinates(13.794185, -88.89653));
        countryCoordinatesMap.put("SY", new Coordinates(34.802075, 38.996815));
        countryCoordinatesMap.put("SZ", new Coordinates(-26.522503, 31.465866));
        countryCoordinatesMap.put("TC", new Coordinates(21.694025, -71.797928));
        countryCoordinatesMap.put("TD", new Coordinates(15.454166, 18.732207));
        countryCoordinatesMap.put("TF", new Coordinates(-49.280366, 69.348557));
        countryCoordinatesMap.put("TG", new Coordinates(8.619543, 0.824782));
        countryCoordinatesMap.put("TH", new Coordinates(15.870032, 100.992541));
        countryCoordinatesMap.put("TJ", new Coordinates(38.861034, 71.276093));
        countryCoordinatesMap.put("TK", new Coordinates(-8.967363, -171.855881));
        countryCoordinatesMap.put("TL", new Coordinates(-8.874217, 125.727539));
        countryCoordinatesMap.put("TM", new Coordinates(38.969719, 59.556278));
        countryCoordinatesMap.put("TN", new Coordinates(33.886917, 9.537499));
        countryCoordinatesMap.put("TO", new Coordinates(-21.178986, -175.198242));
        countryCoordinatesMap.put("TR", new Coordinates(38.963745, 35.243322));
        countryCoordinatesMap.put("TT", new Coordinates(10.691803, -61.222503));
        countryCoordinatesMap.put("TV", new Coordinates(-7.109535, 177.64933));
        countryCoordinatesMap.put("TW", new Coordinates(23.69781, 120.960515));
        countryCoordinatesMap.put("TZ", new Coordinates(-6.369028, 34.888822));
        countryCoordinatesMap.put("UA", new Coordinates(48.379433, 31.16558));
        countryCoordinatesMap.put("UG", new Coordinates(1.373333, 32.290275));
        countryCoordinatesMap.put("US", new Coordinates(37.09024, -95.712891));
        countryCoordinatesMap.put("UY", new Coordinates(-32.522779, -55.765835));
        countryCoordinatesMap.put("UZ", new Coordinates(41.377491, 64.585262));
        countryCoordinatesMap.put("VA", new Coordinates(41.902916, 12.453389));
        countryCoordinatesMap.put("VC", new Coordinates(12.984305, -61.287228));
        countryCoordinatesMap.put("VE", new Coordinates(6.42375, -66.58973));
        countryCoordinatesMap.put("VG", new Coordinates(18.420695, -64.639968));
        countryCoordinatesMap.put("VI", new Coordinates(18.335765, -64.896335));
        countryCoordinatesMap.put("VN", new Coordinates(14.058324, 108.277199));
        countryCoordinatesMap.put("VU", new Coordinates(-15.376706, 166.959158));
        countryCoordinatesMap.put("WF", new Coordinates(-13.768752, -177.156097));
        countryCoordinatesMap.put("WS", new Coordinates(-13.759029, -172.104629));
        countryCoordinatesMap.put("XK", new Coordinates(42.602636, 20.902977));
        countryCoordinatesMap.put("YE", new Coordinates(15.552727, 48.516388));
        countryCoordinatesMap.put("YT", new Coordinates(-12.8275, 45.166244));
        countryCoordinatesMap.put("ZA", new Coordinates(-30.559482, 22.937506));
        countryCoordinatesMap.put("ZM", new Coordinates(-13.133897, 27.849332));
        countryCoordinatesMap.put("ZW", new Coordinates(-19.015438, 29.154857));

        return Collections.unmodifiableMap(countryCoordinatesMap);
    }

    static Location locationFromCountryCode(String countryCode) {
        if (COUNTRY_COORDINATES_MAP.containsKey(countryCode)) {
            Coordinates coordinates = COUNTRY_COORDINATES_MAP.get(countryCode);
            Location location = new Location("");
            location.setLatitude(coordinates.getLatitude());
            location.setLongitude(coordinates.getLongitude());
            return location;
        }

        return null;
    }
}
