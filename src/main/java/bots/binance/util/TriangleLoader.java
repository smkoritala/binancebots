package bots.binance.util;

import com.google.gson.*;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;
public class TriangleLoader {
    public static class Triangle {
        public String[] symbols = new String[3];
        public String[] sides = new String[3];
    }

    private static final Set<String> acceptedStablecoins = Set.of("USDT", "USDC", "DAI", "TUSD");

    public static List<Triangle> loadTriangles(String path) {
        List<Triangle> result = new ArrayList<>();
        List<JsonObject> rawKeptTriangles = new ArrayList<>();

        int totalLoaded = 0;
        int totalKept = 0;

        try (FileReader reader = new FileReader(path)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement elem : array) {
                totalLoaded++;
                JsonObject obj = elem.getAsJsonObject();
                JsonArray route = obj.getAsJsonArray("route");
                JsonArray legs = obj.getAsJsonArray("legs");

                Triangle triangle = new Triangle();
                for (int i = 0; i < 3; i++) {
                    triangle.symbols[i] = route.get(i).getAsString();
                    triangle.sides[i] = legs.get(i).getAsJsonObject().get("side").getAsString();
                }

                String startSymbol = triangle.symbols[0];
                String endSymbol = triangle.symbols[2];

                String startStable = getStablecoinFromSymbol(startSymbol);
                String endStable = getStablecoinFromSymbol(endSymbol);

                if (startStable != null && startStable.equals(endStable)) {
                    result.add(triangle);
                    rawKeptTriangles.add(obj);
                    totalKept++;
                }
            }

            System.out.println("📊 Total triangles loaded: " + totalLoaded);
            System.out.println("✅ Triangles kept (round-trip stablecoin): " + totalKept);

            // Save filtered set to new file
            try (FileWriter writer = new FileWriter("filtered_triangles.json")) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                gson.toJson(rawKeptTriangles, writer);
                System.out.println("📁 Filtered triangles saved to filtered_triangles.json");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return result;
    }

    private static String getStablecoinFromSymbol(String symbol) {
        for (String stable : acceptedStablecoins) {
            if (symbol.endsWith(stable)) {
                return stable;
            }
        }
        return null;
    }
}
