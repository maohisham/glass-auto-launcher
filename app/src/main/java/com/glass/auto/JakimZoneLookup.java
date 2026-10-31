package com.glass.auto;

/**
 * Maps a GPS coordinate to the nearest JAKIM prayer time zone.
 * Zone list sourced from JAKIM e-Solat (https://www.e-solat.gov.my).
 *
 * Each entry: { code, display name, center latitude, center longitude }
 * The nearest zone by Euclidean distance is selected.
 */
public class JakimZoneLookup {

    public static class Zone {
        public final String code;
        public final String name;
        public final double lat;
        public final double lon;
        Zone(String c, String n, double la, double lo) { code=c; name=n; lat=la; lon=lo; }
    }

    // All JAKIM zones with approximate centroid coordinates
    private static final Zone[] ZONES = {
        // Wilayah Persekutuan
        new Zone("WLY01", "Kuala Lumpur / Putrajaya",    3.1478, 101.6953),
        new Zone("WLY02", "Labuan",                      5.2767, 115.2417),

        // Selangor
        new Zone("SGR01", "Gombak / Hulu Langat / Sepang / Petaling / Hulu Selangor / Sabak Bernam", 3.3000, 101.5000),
        new Zone("SGR02", "Kuala Selangor / Shah Alam",  3.3500, 101.2000),
        new Zone("SGR03", "Klang / Kuala Langat",        2.9500, 101.3500),

        // Johor
        new Zone("JHR01", "Pulau Aur / Pulau Pemanggil", 2.4700, 104.3100),
        new Zone("JHR02", "Johor Bahru / Kota Tinggi / Mersing", 1.5700, 103.7700),
        new Zone("JHR03", "Kluang / Pontian",            1.8500, 103.3000),
        new Zone("JHR04", "Batu Pahat / Muar / Segamat / Gemas Johor", 2.1000, 102.9500),

        // Kedah
        new Zone("KDH01", "Kota Setar / Kubang Pasu / Pokok Sena", 6.1200, 100.3700),
        new Zone("KDH02", "Kuala Muda / Yan / Padang Terap", 5.7000, 100.5000),
        new Zone("KDH03", "Kulim / Bandar Baharu",       5.4000, 100.5700),
        new Zone("KDH04", "Baling",                      5.6800, 100.9200),
        new Zone("KDH05", "Sik / Merbok",                5.8500, 100.7500),
        new Zone("KDH06", "Pendang",                     5.9800, 100.5000),
        new Zone("KDH07", "Langkawi",                    6.3500, 99.8000),

        // Kelantan
        new Zone("KTN01", "Kota Bharu / Bachok / Pasir Puteh / Tumpat / Pasir Mas / Tanah Merah / Machang / Kuala Krai / Mukim Chiku", 5.9000, 102.1000),
        new Zone("KTN03", "Gua Musang (Mukim Galas dan Bertam) / Jeli / Hulu Kelantan", 5.0000, 101.9000),

        // Melaka
        new Zone("MLK01", "Melaka",                      2.1900, 102.2500),

        // Negeri Sembilan
        new Zone("NGS01", "Tampin / Jempol",             2.6500, 102.4200),
        new Zone("NGS02", "Jelebu / Kuala Pilah / Nilai", 2.8500, 102.0500),
        new Zone("NGS03", "Port Dickson / Rembau / Seremban", 2.7000, 101.9500),

        // Pahang
        new Zone("PHG01", "Pulau Tioman",                2.8100, 104.1600),
        new Zone("PHG02", "Kuantan / Pekan / Rompin / Muadzam Shah", 3.8000, 103.3300),
        new Zone("PHG03", "Jerantut / Temerloh / Maran / Bera / Chenor / Jengka", 3.5000, 102.5000),
        new Zone("PHG04", "Bentong / Raub / Lipis / Pawang / Sega", 3.8000, 101.9000),
        new Zone("PHG05", "Genting Sempah / Janda Baik / Bukit Tinggi", 3.4300, 101.7900),
        new Zone("PHG06", "Cameron Highlands / Gua Musang (Mukim Ulu Kelantan)", 4.4700, 101.3800),

        // Perak
        new Zone("PRK01", "Tapah / Slim River / Tanjung Malim", 3.7000, 101.3500),
        new Zone("PRK02", "Ipoh / Batu Gajah / Kampar / Sungai Siput / Kuala Kangsar", 4.6000, 101.0800),
        new Zone("PRK03", "Lenggong / Pengkalan Hulu / Grik",  5.2000, 101.1000),
        new Zone("PRK04", "Temengor / Belum",            5.5000, 101.3000),
        new Zone("PRK05", "Teluk Intan / Bagan Datuk / Kg.Gajah / Sri Iskandar / Beruas / Parit / Lumut / Sitiawan / Pulau Pangkor", 4.0200, 100.9700),
        new Zone("PRK06", "Selama / Taiping / Bagan Serai / Parit Buntar", 5.0500, 100.7300),
        new Zone("PRK07", "Bukit Fraser / Lojing",       3.8500, 101.7300),

        // Perlis
        new Zone("PLS01", "Perlis",                      6.4400, 100.2000),

        // Pulau Pinang
        new Zone("PNG01", "Seluruh Negeri Pulau Pinang", 5.4100, 100.3370),

        // Sabah
        new Zone("SBH01", "Bahagian Sandakan (Timur) termasuk Kinabatangan", 5.8300, 118.0700),
        new Zone("SBH02", "Bahagian Tawau (Timur) termasuk Lahad Datu", 4.2500, 117.8800),
        new Zone("SBH03", "Bahagian Kudat (Utara) termasuk Kota Marudu dan Pitas", 6.8800, 116.8500),
        new Zone("SBH04", "Bahagian Kota Kinabalu (Barat) termasuk Ranau", 6.0000, 116.1200),
        new Zone("SBH05", "Bahagian Keningau (Dalam) termasuk Tambunan dan Nabawan", 5.3400, 116.1700),
        new Zone("SBH06", "Bahagian Sipitang (Barat) termasuk Beaufort, Kuala Penyu", 5.1000, 115.5500),

        // Sarawak
        new Zone("SWK01", "Limbang / Lawas / Sundar / Trusan", 4.8000, 115.0000),
        new Zone("SWK02", "Miri / Niah / Bekenu / Sibuti / Marudi", 4.3900, 113.9900),
        new Zone("SWK03", "Pandan / Belaga / Suai / Tatau / Sebauh / Bintulu", 3.1700, 113.0300),
        new Zone("SWK04", "Sibu / Mukah / Dalat / Song / Igan / Oya / Balingian / Kanowit / Kapit", 2.3000, 111.8200),
        new Zone("SWK05", "Sarikei / Matu / Julau / Rajang / Daro / Belawai", 2.1200, 111.5200),
        new Zone("SWK06", "Lubok Antu / Sri Aman / Roban / Debak / Kabong / Lingga / Engkilili / Betong / Spaoh / Pusa / Saratok / Semop / Tanjung Manis", 1.2400, 111.4000),
        new Zone("SWK07", "Serian / Simunjan / Samarahan / Sebuyau / Meludam", 1.1700, 110.5000),
        new Zone("SWK08", "Kuching / Bau / Lundu / Sematan",  1.5500, 110.3400),
        new Zone("SWK09", "Sri Aman",                    1.2400, 111.4600),

        // Terengganu
        new Zone("TRG01", "Mersing",                     2.4100, 103.8200),
        new Zone("TRG02", "Besut / Setiu",               5.6000, 102.5500),
        new Zone("TRG03", "Kuala Terengganu / Marang / Kuala Nerus", 5.3200, 103.1400),
        new Zone("TRG04", "Hulu Terengganu",             4.9600, 102.7700),
        new Zone("TRG05", "Kemaman / Dungun",            4.2300, 103.4200),
    };

    /** Returns the JAKIM zone whose centroid is closest to the given coordinate. */
    public static Zone nearest(double lat, double lon) {
        Zone best = ZONES[0];
        double bestDist = Double.MAX_VALUE;
        for (Zone z : ZONES) {
            double dLat = z.lat - lat;
            double dLon = z.lon - lon;
            double dist = dLat * dLat + dLon * dLon; // no need for sqrt
            if (dist < bestDist) { bestDist = dist; best = z; }
        }
        return best;
    }
}
