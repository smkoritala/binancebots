package bots.binance.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.FileWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Scanner;

public class TriangularArbitragePairGenerator {

    public static void main(String[] args) throws IOException {
        Map<String, TradingPair> tradingPairs = fetchTradingPairs();
        List<JsonObject> triangleRoutes = generateTriangularRoutes(tradingPairs);
        saveToFile(triangleRoutes, "triangular_pairs.json");
        System.out.println("Saved " + triangleRoutes.size() + " triangular arbitrage routes.");
    }

    static class TradingPair {
        String symbol;
        String baseAsset;
        String quoteAsset;

        TradingPair(String symbol, String base, String quote) {
            this.symbol = symbol;
            this.baseAsset = base;
            this.quoteAsset = quote;
        }
    }

    public static Map<String, TradingPair> fetchTradingPairs() throws IOException {
    	URL url = new URL("https://api.binance.us/api/v3/exchangeInfo");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        Scanner scanner = new Scanner(conn.getInputStream());
        StringBuilder json = new StringBuilder();
        while (scanner.hasNext()) {
            json.append(scanner.nextLine());
        }
        scanner.close();

        Gson gson = new Gson();
        JsonObject obj = gson.fromJson(json.toString(), JsonObject.class);
        JsonArray symbolsArray = obj.getAsJsonArray("symbols");

        Map<String, TradingPair> pairs = new HashMap<>();
        for (int i = 0; i < symbolsArray.size(); i++) {
            JsonObject symbolObj = symbolsArray.get(i).getAsJsonObject();
            if (!symbolObj.get("status").getAsString().equals("TRADING")) continue;

            String symbol = symbolObj.get("symbol").getAsString();
            String base = symbolObj.get("baseAsset").getAsString();
            String quote = symbolObj.get("quoteAsset").getAsString();
            String key = base + "_" + quote;

            pairs.put(key, new TradingPair(symbol, base, quote));
        }
        return pairs;
    }

    public static List<JsonObject> generateTriangularRoutes(Map<String, TradingPair> pairs) {
        List<JsonObject> results = new ArrayList<>();
        List<String> assets = pairs.values().stream()
                .flatMap(p -> Arrays.stream(new String[]{p.baseAsset, p.quoteAsset}))
                .distinct()
                .collect(Collectors.toList());

        for (String assetA : assets) {
            for (String assetB : assets) {
                if (assetB.equals(assetA)) continue;
                for (String assetC : assets) {
                    if (assetC.equals(assetA) || assetC.equals(assetB)) continue;

                    // assetA -> assetB
                    JsonObject leg1 = determineSide(pairs, assetA, assetB);
                    if (leg1 == null) continue;

                    // assetB -> assetC
                    JsonObject leg2 = determineSide(pairs, assetB, assetC);
                    if (leg2 == null) continue;

                    // assetC -> assetA
                    JsonObject leg3 = determineSide(pairs, assetC, assetA);
                    if (leg3 == null) continue;

                    // Valid triangle found
                    JsonObject route = new JsonObject();
                    JsonArray symbols = new JsonArray();
                    symbols.add(leg1.get("symbol").getAsString());
                    symbols.add(leg2.get("symbol").getAsString());
                    symbols.add(leg3.get("symbol").getAsString());

                    JsonArray legs = new JsonArray();
                    legs.add(leg1);
                    legs.add(leg2);
                    legs.add(leg3);

                    route.add("route", symbols);
                    route.add("legs", legs);
                    results.add(route);
                }
            }
        }

        return results;
    }

    public static JsonObject determineSide(Map<String, TradingPair> pairs, String from, String to) {
        JsonObject obj = new JsonObject();
        obj.addProperty("from", from);
        obj.addProperty("to", to);

        String directKey = from + "_" + to;
        String reverseKey = to + "_" + from;

        if (pairs.containsKey(directKey)) {
            obj.addProperty("side", "BUY");
            obj.addProperty("symbol", pairs.get(directKey).symbol);
            return obj;
        } else if (pairs.containsKey(reverseKey)) {
            obj.addProperty("side", "SELL");
            obj.addProperty("symbol", pairs.get(reverseKey).symbol);
            return obj;
        } else {
            return null;
        }
    }

    public static void saveToFile(List<JsonObject> data, String filename) {
        try (FileWriter file = new FileWriter(filename)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(data, file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

