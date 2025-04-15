package bots.binance.client;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bots.binance.config.BinanceArbitrageConfig;
import bots.binance.notifier.PrimeLogNotifier;
import bots.binance.notifier.TelegramNotifier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class TradeExecutor {
    private static final String HTTPS_API_BINANCE_US_API_V3_TIME = "https://api.binance.us/api/v3/time";
	private static final String SERVER_TIME = "serverTime";
	private static final String EXECUTING_TRADES = "✅ Executing trades:";
	private static final String _02X = "%02x";
	private static final String HMAC_SHA256 = "HmacSHA256";
	private static final String FAILED_TO_EXECUTE_ORDER_FOR = "❌ Failed to execute order for {} - {}";
	private static final String ORDER_RESPONSE = "Order Response ({}): {}";
	private static final String POST = "POST";
	private static final String X_MBX_APIKEY = "X-MBX-APIKEY";
	private static final String SIGNATURE2 = "&signature=";
	private static final String HTTPS_API_BINANCE_US_API_V3_ORDER = "https://api.binance.us/api/v3/order?";
	private static final String SYMBOL_S_SIDE_S_TYPE_LIMIT_TIME_IN_FORCE_GTC_QUANTITY_S_PRICE_S_TIMESTAMP_S = "symbol=%s&side=%s&type=LIMIT&timeInForce=GTC&quantity=%s&price=%s&timestamp=%s";
	private static final String TRADE_EXECUTED_PROFIT_IN_MS = "✅ Trade Executed: {} -> {} -> {} | Profit: {}% in {}ms";
	private static final String OPPORTUNITY_REJECTED_AFTER_RULES_PROFIT_MARGIN = "⛔ Opportunity rejected after rules: Profit: {} | Margin: {}%";
    private static final String API_KEY = System.getenv("BINANCE_API_KEY");
    private static final String SECRET_KEY = System.getenv("BINANCE_SECRET_KEY");
	private static final Logger logger = LoggerFactory.getLogger(TradeExecutor.class);
	private static long timeOffset = 0;

    public static void syncServerTime() throws Exception {
        String response = BinanceHttpClient.sendGetRequest(HTTPS_API_BINANCE_US_API_V3_TIME);
        long serverTime = new JSONObject(response).getLong(SERVER_TIME);
        timeOffset = serverTime - System.currentTimeMillis();
    }

    public static void executeArbitrage(String pair1, String pair2, String pair3,
                                        double price1, double price2, double price3,
                                        double qty1, double qty2, double qty3, String sidePair1, String sidePair2, String sidePair3) {
/*
        double profit = qty3 - BinanceArbitrageConfig.AMOUNT_TO_TRADE;
        double profitMargin = (profit / BinanceArbitrageConfig.AMOUNT_TO_TRADE) * 100;

        if (profitMargin < BinanceArbitrageConfig.PROFIT_MARGIN_AFTER_RULES) {
            logger.info(OPPORTUNITY_REJECTED_AFTER_RULES_PROFIT_MARGIN, profit, profitMargin);
            return;
        }

        PrimeLogNotifier.audit(EXECUTING_TRADES, pair1, pair2, pair3, price1, price2, price3,
                qty1, qty2, qty3, profit, profitMargin);

        long startTime = System.currentTimeMillis();
//TODO check if trades are successful and send telegram message
        sendOrder(pair1, sidePair1, price1, qty1);
        sendOrder(pair2, sidePair2, price2, qty2);
        sendOrder(pair3, sidePair3, price3, qty3);

        logger.info(TRADE_EXECUTED_PROFIT_IN_MS, pair1, pair2, pair3, profit,
                System.currentTimeMillis() - startTime);
*/    }

    private static boolean sendOrder(String symbol, String side, double price, double quantity) {
        try {
            String priceStr = String.valueOf(price);
            String quantityStr = String.valueOf(quantity);
            String timestamp = String.valueOf(System.currentTimeMillis() + timeOffset);

            String query = String.format(
                    SYMBOL_S_SIDE_S_TYPE_LIMIT_TIME_IN_FORCE_GTC_QUANTITY_S_PRICE_S_TIMESTAMP_S,
                    symbol, side, quantityStr, priceStr, timestamp);

            String signature = hmacSHA256(query, SECRET_KEY);

            String endpoint = HTTPS_API_BINANCE_US_API_V3_ORDER + query + SIGNATURE2 + signature;
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(POST);
            conn.setRequestProperty(X_MBX_APIKEY, API_KEY);
            conn.setDoOutput(true);

            int responseCode = conn.getResponseCode();
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    (responseCode >= 200 && responseCode < 300) ? conn.getInputStream() : conn.getErrorStream()));

            String inputLine;
            StringBuilder response = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            logger.info(ORDER_RESPONSE, symbol, response);
            return true;
        } catch (Exception e) {
            logger.error(FAILED_TO_EXECUTE_ORDER_FOR, symbol, e.getMessage());
            return false;
        }
    }

    private static String hmacSHA256(String data, String key) throws Exception {
        Mac hmacSha256 = Mac.getInstance(HMAC_SHA256);
        SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
        hmacSha256.init(secretKeySpec);
        byte[] hash = hmacSha256.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : hash) {
            result.append(String.format(_02X, b));
        }
        return result.toString();
    }
}
