package bots.binance.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

import org.json.JSONArray;
import org.json.JSONObject;

public class StableCoinToStableCoinArb {

    private static final String DEPTH_API = "https://api.binance.us/api/v3/depth?symbol=";
    private static final int DEPTH_LIMIT = 5; // Only use top 5 levels

    public static void main(String[] args) throws Exception {
        double startAmount = 1000.0;

        List<String[]> routes = List.of(
            new String[]{"USDT", "BTC", "USDC"},
            new String[]{"USDT", "ETH", "USDC"},
            new String[]{"USDT", "SOL", "USDC"},
            new String[]{"USDC", "BTC", "USDT"},
            new String[]{"USDC", "ETH", "USDT"},
            new String[]{"USDC", "SOL", "USDT"}
        );

        for (String[] route : routes) {
            double result = simulateTriangular(route[0], route[1], route[2], startAmount);
            double profit = result - startAmount;
            System.out.printf("Cycle: %s → %s → %s → %s | Start: %.2f | End: %.2f | Profit: %.2f\n",
                    route[0], route[1], route[2], route[0], startAmount, result, profit);
        }
    }

    // Simulate full triangular arbitrage: A → B → C → A
    private static double simulateTriangular(String base, String middle, String second, double amount) throws Exception {
        double step1 = executeTrade(amount, base, middle);    // A → B
        double step2 = executeTrade(step1, middle, second);   // B → C
        double step3 = executeTrade(step2, second, base);     // C → A
        return step3;
    }

    // Execute trade based on best bid/ask from order book
    private static double executeTrade(double amountIn, String from, String to) throws Exception {
        String pair = from + to;
        boolean direct = true;

        JSONObject book = fetchOrderBook(pair);
        JSONArray levels;
        double price = -1;

        if (book.has("asks")) {
            // We are buying 'to' using 'from'
            levels = book.getJSONArray("asks");
            if (levels.length() > 0) {
                price = Double.parseDouble(levels.getJSONArray(0).getString(0));
            }
        }

        // If no direct market, try reverse pair
        if (price == -1 || price == 0) {
            pair = to + from;
            book = fetchOrderBook(pair);
            direct = false;

            if (book.has("bids")) {
                levels = book.getJSONArray("bids");
                if (levels.length() > 0) {
                    price = Double.parseDouble(levels.getJSONArray(0).getString(0));
                }
            }
        }

        if (price <= 0) {
            System.out.printf("⚠️ No liquidity for pair %s-%s\n", from, to);
            return 0;
        }

        return direct ? amountIn / price : amountIn * price;
    }

    private static JSONObject fetchOrderBook(String symbol) {
        try {
            URL url = new URL(DEPTH_API + symbol + "&limit=" + DEPTH_LIMIT);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() != 200) return new JSONObject();

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }

            return new JSONObject(json.toString());
        } catch (Exception e) {
            System.out.println("Error fetching order book for " + symbol);
            return new JSONObject();
        }
    }
}
