package bots.binance.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import bots.binance.client.BinanceHttpClient;
import bots.binance.client.TradeExecutor;
import bots.binance.config.BinanceArbitrageConfig;
import bots.binance.notifier.PrimeLogNotifier;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExchangeRulesManager__bak {
	private static final Logger logger = LoggerFactory.getLogger(ExchangeRulesManager__bak.class);
	public static final String BUY = "BUY";
	public static final String SELL = "SELL";
	public static final String CHECK_FOR_ARBITRAGE_STARTED = "forward Arbitrage profit {}, {} {}, {} {}, {} {}";
	public static final String CHECK_FOR_ARBITRAGE_STARTED_REV= "reversee Arbitrage profit {}, {} {}, {} {}, {} {}";
	private static final String APPLYING_PRICE_RULES = "Applying price rules...";
	private static final String APPLYING_STEP_SIZE_AND_QUANTITY_RULES = "applying step size and quantity rules...";
	private static final String FAILED_OPPORTUNITY_AFTER_RULES_ON_PAIR = "Failed Opportunity after rules on pair {}% ";
	private static final String OPPORTUNITY_FOUND_AFTER_RULES_EXECUTING_TRADES = " Opportunity Found after rules executing trades ";
	private static final String TRADE_EXECUTED_PROFIT_IN = "✅ Trade Executed: {} -> {} -> {} | Profit: {}% in {}";
	private static final String EXECUTING_TRADE_AFTER_RULES_PROFIT_OPPORTUNITY = "executing trade, after rules Profit Opportunity: {} {}% ";
	private static final String FAILED_OPPORTUNITY_AFTER_RULES_PAIR_PRICES = "Failed Opportunity after rules {} {}% pair prices {}, {}, {}";

	private static final Map<String, Double> tickSizeMap = new HashMap<>();
	private static final Map<String, Integer> pricePrecisionMap = new HashMap<>();
	private static final Map<String, Integer> quantityPrecisionMap = new HashMap<>();
	private static final Map<String, Double> minQtyMap = new HashMap<>();
	private static final Map<String, Double> maxQtyMap = new HashMap<>();
	private static final Map<String, Double> stepSizeMap = new HashMap<>();

	public static void preloadExchangeRules(String[] pairs) throws Exception {
		String response = BinanceHttpClient.sendGetRequest("https://api.binance.us/api/v3/exchangeInfo");
		JsonNode jsonNode = new ObjectMapper().readTree(response);
		Set<String> pairSet = new HashSet<>(Set.of(pairs));

		for (JsonNode symbolNode : jsonNode.get("symbols")) {
			String symbol = symbolNode.get("symbol").asText();
			if (pairSet.contains(symbol)) {
				for (JsonNode filter : symbolNode.get("filters")) {
					if (filter.get("filterType").asText().equals("PRICE_FILTER")) {
						tickSizeMap.put(symbol, filter.get("tickSize").asDouble());
					} else if (filter.get("filterType").asText().equals("LOT_SIZE")) {
						minQtyMap.put(symbol, filter.get("minQty").asDouble());
						maxQtyMap.put(symbol, filter.get("maxQty").asDouble());
						stepSizeMap.put(symbol, filter.get("stepSize").asDouble());
					}
				}
				pricePrecisionMap.put(symbol, symbolNode.get("quoteAssetPrecision").asInt());
				quantityPrecisionMap.put(symbol, symbolNode.get("baseAssetPrecision").asInt());
			}
		}
	}

	public static boolean isValidTrade(String pair, double ask, double bid) {
		if (Double.isNaN(ask) || Double.isInfinite(ask) || Double.isNaN(bid) || Double.isInfinite(bid)){
			return false;
		}
		double mid = (ask + bid) / 2.0;
		double buySlippage = ((ask - mid) / mid) * 100;
		double sellSlippage = ((mid - bid) / mid) * 100;
		double maxSlippage = getMaxSlippage(pair, ask, bid);
		return Math.abs(buySlippage) <= maxSlippage && Math.abs(sellSlippage) <= maxSlippage;
	}

	public static void checkForArbitrage(
	        String pair1, String pair2, String pair3,
	        String pair1Side, String pair2Side, String pair3Side,
	        Double pair1Ask, Double pair2Ask, Double pair3Ask,
	        Double pair1Bid, Double pair2Bid, Double pair3Bid) {

	    // 🔍 Step 2: Select actual execution prices based on side
	    double price1 = pair1Side.equals("BUY") ? pair1Ask : pair1Bid;
	    double price2 = pair2Side.equals("BUY") ? pair2Ask : pair2Bid;
	    double price3 = pair3Side.equals("BUY") ? pair3Ask : pair3Bid;

	    // 🧮 Step 3: Compute profit from $1 base (or 1 unit)
	    double amountAfter1 = pair1Side.equals("BUY") ? (1 / price1) : (1 * price1);
	    double amountAfter2 = pair2Side.equals("BUY") ? (amountAfter1 / price2) : (amountAfter1 * price2);
	    double amountAfter3 = pair3Side.equals("BUY") ? (amountAfter2 / price3) : (amountAfter2 * price3);

	    double profit = amountAfter3 - 1;

	    logger.info("🔍 checkForArbitrage started for triangle: [{} → {}, {} → {}, {} → {}]", 
	        pair1, pair1Side, pair2, pair2Side, pair3, pair3Side);
	    logger.info("💰 Calculated profit: {}", profit);

	    if (profit > BinanceArbitrageConfig.PROFIT_MARGIN) {
	        applyTradeRulesAndExecute(
	                price1, price2, price3,
	                pair1, pair2, pair3,
	                pair1Side, pair2Side, pair3Side);
	    }
	}

	private static void applyTradeRulesAndExecute(double pair1Price, double pair2Price, double pair3Price, String pair1,
			String pair2, String pair3, String sidePair1, String sidePair2, String sidePair3) {

		logger.info("Executing arbitrage with prices: {}: {}, {}: {}, {}: {}", pair1, pair1Price, pair2, pair2Price,
				pair3, pair3Price);

// 🧮 Step 2: Calculate quantity chain
		logger.info("Calculating quantity to buy after trading fee...");
		double pair1Quantity = getRoundedQuantity(pair1, BinanceArbitrageConfig.AMOUNT_TO_TRADE / pair1Price);
		logger.debug("Raw pair1Quantity before fees: {}", pair1Quantity);

		double fee1 = pair1Quantity * BinanceArbitrageConfig.TRADING_FEE_PERCENT;
		double executedPair1Quantity = pair1Quantity - fee1;

		double pair2Quantity = getRoundedQuantity(pair2, executedPair1Quantity / pair2Price);
		double fee2 = pair2Quantity * BinanceArbitrageConfig.TRADING_FEE_PERCENT;
		pair2Quantity -= fee2;

		double pair3Quantity = getRoundedQuantity(pair3, pair2Quantity * pair3Price);
		double fee3 = pair3Quantity * BinanceArbitrageConfig.TRADING_FEE_PERCENT;
		pair3Quantity -= fee3;

// 📏 Step 3: Apply quantity rounding
//		pair1Quantity = getRoundedQuantity(pair1, pair1Quantity);
//		pair2Quantity = getRoundedQuantity(pair2, pair2Quantity);
//		pair3Quantity = getRoundedQuantity(pair3, pair3Quantity);
		logger.debug("Rounded quantities -> {}: {}, {}: {}, {}: {}", pair1, pair1Quantity, pair2, pair2Quantity, pair3,
				pair3Quantity);

// ✅ Step 4: Validate quantities
		logger.info(APPLYING_STEP_SIZE_AND_QUANTITY_RULES);
		if (!isAmountInLimits(pair1, pair1Quantity) || !isAmountInLimits(pair2, pair2Quantity)
				|| !isAmountInLimits(pair3, pair3Quantity)) {
			logger.info(FAILED_OPPORTUNITY_AFTER_RULES_ON_PAIR, pair1);
			return;
		}

// 📏 Step 5: Apply price rounding
		logger.info(APPLYING_PRICE_RULES);
//		pair1Price = getRoundedPrice(pair1, pair1Price);
//		pair2Price = getRoundedPrice(pair2, pair2Price);
//		pair3Price = getRoundedPrice(pair3, pair3Price);

// 📈 Step 6: Final profit validation
		double profit = pair3Quantity - BinanceArbitrageConfig.AMOUNT_TO_TRADE;
		double profitMargin = (profit / BinanceArbitrageConfig.AMOUNT_TO_TRADE) * 100;
		if (profitMargin < BinanceArbitrageConfig.PROFIT_MARGIN_AFTER_RULES) {
			logger.info(FAILED_OPPORTUNITY_AFTER_RULES_PAIR_PRICES, profit, profitMargin, pair1Price, pair2Price,
					pair3Price);
			return;
		}

		logger.info(EXECUTING_TRADE_AFTER_RULES_PROFIT_OPPORTUNITY, profit, profitMargin);

		long startTime = System.currentTimeMillis();
//TradeExecutor.executeArbitrage(pair1, pair2, pair3, pair1Price, pair2Price, pair3Price,
//pair1Quantity, pair2Quantity, pair3Quantity, sidePair1, sidePair2, sidePair3);
		PrimeLogNotifier.audit(OPPORTUNITY_FOUND_AFTER_RULES_EXECUTING_TRADES, pair1, pair2, pair3, pair1Price,
				pair2Price, pair3Price, pair1Quantity, pair2Quantity, pair3Quantity, profit, profitMargin);
		logger.info(TRADE_EXECUTED_PROFIT_IN, pair1, pair2, pair3, profit, System.currentTimeMillis() - startTime);
	}

	public static double getMaxSlippage(String pair, double ask, double bid) {
		double base = switch (pair.toUpperCase()) {
		case "BTCUSDT", "ETHUSDT" -> 0.3;
		case "ETHBTC" -> 0.5;
		default -> 1.0;
		};
		double spread = ((ask - bid) / ask) * 100;
		if (spread > 0.2)
			base += 0.2;
		return base;
	}

	public static double getRoundedPrice(String pair, double price) {
		int precision = pricePrecisionMap.getOrDefault(pair, 2);
		double tickSize = tickSizeMap.getOrDefault(pair, 0.01);
		double adjustedPrice = roundToTickSize(price, tickSize);
		return roundToPrecision(adjustedPrice, precision);
	}

	public static double getRoundedQuantity(String pair, double quantity) {
		int precision = quantityPrecisionMap.getOrDefault(pair, 6);
		double stepSize = stepSizeMap.getOrDefault(pair, 0.000001);
		double adjustedQty = adjustToStepSize(quantity, stepSize);
		return roundToPrecision(adjustedQty, precision);
	}

	public static boolean isAmountInLimits(String pair, double quantity) {
		double min = minQtyMap.getOrDefault(pair, 0.001);
		double max = maxQtyMap.getOrDefault(pair, 1000.0);
		if (quantity < min || quantity > max) {
			logger.error("❌ Quantity {} out of bounds for {} (Min: {}, Max: {})", quantity, pair, min, max);
			return false;
		}
		return true;
	}
	/*
	private static boolean isValidPrice(String pair, double price) {
	    if (Double.isNaN(price) || Double.isInfinite(price)) {
	        logger.error("❌ Invalid (NaN or Infinite) price for {}: {}", pair, price);
	        return false;
	    }	
		// Lower bounds for sanity based on base asset if (pair.endsWith("USDT") &&
		if (price < 0.001) {
			logger.error("❌ Unreasonably low price for USDT pair {}: {}", pair, price);
			return false;
		}
		if (pair.endsWith("BTC") && price < 1e-7) {
			logger.error("❌ Unreasonably low price for BTC pair {}: {}", pair, price);
			return false;
		}
		if (pair.endsWith("ETH") && price < 1e-6) {
			logger.error("❌ Unreasonably low price for ETH pair {}: {}", pair, price);
			return false;
		}

	    return true;
	}
*/
	private static double roundToTickSize(double price, double tickSize) {
		BigDecimal bd = new BigDecimal(price);
		BigDecimal tick = new BigDecimal(tickSize);
		bd = bd.divide(tick, 0, RoundingMode.DOWN).multiply(tick);
		return bd.doubleValue();
	}

	private static double roundToPrecision(double value, int precision) {
		BigDecimal bd = new BigDecimal(value);
		return bd.setScale(precision, RoundingMode.DOWN).doubleValue();
	}

	private static double adjustToStepSize(double quantity, double stepSize) {
		BigDecimal qty = new BigDecimal(quantity);
		BigDecimal step = new BigDecimal(stepSize);
		return qty.divide(step, 0, RoundingMode.DOWN).multiply(step).doubleValue();
	}

}
