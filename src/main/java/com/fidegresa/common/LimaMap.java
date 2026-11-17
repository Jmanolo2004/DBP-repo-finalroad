package com.fidegresa.common;

import java.util.Map;

/**
 * Distritos que cubre el mapa del panel, con su punto de referencia en el mapa ilustrado y en coordenadas reales.
 * Las conversiones son las mismas que usa el frontend.
 */
public final class LimaMap {
    public record Point(int x, int y, double latitude, double longitude) {
    }

    public static final double METERS_PER_UNIT = 2.5;

    public static final Map<String, Point> DISTRICTS = Map.of(
            "Barranco", new Point(300, 555, -12.1451, -77.0218),
            "Miraflores", new Point(410, 325, -12.1225, -77.0298),
            "San Isidro", new Point(625, 245, -12.0975, -77.0365),
            "San Borja", new Point(585, 455, -12.1014, -76.9993),
            "Santiago de Surco", new Point(735, 535, -12.1096, -76.9922));

    private LimaMap() {
    }

    public static boolean isDistrict(String name) {
        return name != null && DISTRICTS.containsKey(name);
    }

    public static Point of(String district) {
        return DISTRICTS.getOrDefault(district, DISTRICTS.get("Miraflores"));
    }

    public static int clamp(double value, int min, int max) {
        return (int) Math.max(min, Math.min(max, Math.round(value)));
    }

    /** Pasa de un punto del mapa ilustrado a latitud y longitud. */
    public static double[] coordinatesFromMap(String district, int mapX, int mapY) {
        Point origin = of(district);
        double longitudeScale = 111320 * Math.cos(Math.toRadians(origin.latitude()));
        return new double[]{
                origin.latitude() + ((origin.y() - mapY) * METERS_PER_UNIT) / 111320,
                origin.longitude() + ((mapX - origin.x()) * METERS_PER_UNIT) / longitudeScale
        };
    }

    /** Pasa de latitud y longitud a un punto del mapa ilustrado. */
    public static int[] mapFromCoordinates(String district, double latitude, double longitude) {
        Point origin = of(district);
        double x = origin.x() + ((longitude - origin.longitude()) * 111320 * Math.cos(Math.toRadians(origin.latitude()))) / METERS_PER_UNIT;
        double y = origin.y() - ((latitude - origin.latitude()) * 111320) / METERS_PER_UNIT;
        return new int[]{clamp(x, 45, 955), clamp(y, 45, 655)};
    }

    /** Distancia en metros redondeada a 10 m (mínimo 50 m), igual que el panel. */
    public static int distanceInMeters(double latA, double lngA, double latB, double lngB) {
        double lat1 = Math.toRadians(latA);
        double lat2 = Math.toRadians(latB);
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(lngB - lngA);
        double h = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dLng / 2), 2);
        double meters = 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
        return (int) Math.max(50, Math.round(meters / 10) * 10);
    }
}
