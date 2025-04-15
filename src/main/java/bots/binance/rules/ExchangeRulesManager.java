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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExchangeRulesManager {
	private static final String CHECKING_ARBITRAGE = "checking arbitrage";
	private static final String FORWARD_ARBITRAGE = "🔄 Forward Arbitrage: {} → {}, {} → {}, {} → {} = {}";
	private static final String REVERSE_ARBITRAGE = "🔁 Reverse Arbitrage: {} → {}, {} → {}, {} → {} = {}";
	private static final String APPLYING_RULES = "applying rules";
	private static final String EXECUTING_ARBITRAGE_WITH_PRICES = "🧮 Executing arbitrage with prices: {}: {}, {}: {}, {}: {}";
	private static final String QUANTITY_OUT_OF_BOUNDS_FOR_MIN_MAX = "❌ Quantity {} out of bounds for {} (Min: {}, Max: {})";
	private static final String ETH = "ETH";
	private static final String BTC = "BTC";
	public static final String UNREASONABLY_LOW_PRICE_FOR_ETH_PAIR = "❌ Unreasonably low price for ETH pair {}: {}";
	public static final String UNREASONABLY_LOW_PRICE_FOR_BTC_PAIR = "❌ Unreasonably low price for BTC pair {}: {}";
	public static final String UNREASONABLY_LOW_PRICE_FOR_USDT_PAIR = "❌ Unreasonably low price for USDT pair {}: {}";
	public static final String INVALID_NA_N_OR_INFINITE_PRICE_FOR = "❌ Invalid (NaN or Infinite) price for {}: {}";
	private static final Logger logger = LoggerFactory.getLogger(ExchangeRulesManager.class);
	public static final String BUY = "BUY";
	public static final String SELL = "SELL";
	public static final String CHECK_FOR_ARBITRAGE_STARTED = "🧮 forward Arbitrage profit {}, {} {}, {} {}, {} {}";
	public static final String CHECK_FOR_ARBITRAGE_STARTED_REV= "🧮 reversee Arbitrage profit {}, {} {}, {} {}, {} {}";
	private static final String APPLYING_STEP_SIZE_AND_QUANTITY_RULES = "🧮 applying step size and quantity rules...";
	private static final String FAILED_OPPORTUNITY_AFTER_RULES_ON_PAIR = "❌ Failed Opportunity after rules on pair {}% ";
	private static final String OPPORTUNITY_FOUND_AFTER_RULES_EXECUTING_TRADES = "🧮  Opportunity Found after rules executing trades ";
	private static final String TRADE_EXECUTED_PROFIT_IN = "✅ Trade Executed: {} -> {} -> {} | Profit: {}% in {}";
	private static final String EXECUTING_TRADE_AFTER_RULES_PROFIT_OPPORTUNITY = "📈 Executing trade, after rules Profit Opportunity: {} {}% ";
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
	        String side1, String side2, String side3,
	        Double pair1Ask, Double pair2Ask, Double pair3Ask,
	        Double pair1Bid, Double pair2Bid, Double pair3Bid) {

	    if (pair1Ask == null || pair2Ask == null || pair3Ask == null ||
	        pair1Bid == null || pair2Bid == null || pair3Bid == null) {
	        return;
	    }
		PrimeLogNotifier.audit(CHECKING_ARBITRAGE,pair1,pair2,pair3,side1,side2,side3,pair1Ask,pair2Ask,pair3Ask,pair1Bid,pair2Bid,pair3Bid);

	    // ========== FORWARD DIRECTION ==========
	    double forwardProfit = calculateProfit(
	        side1, side2, side3,
	        pair1Ask, pair1Bid,
	        pair2Ask, pair2Bid,
	        pair3Ask, pair3Bid);

	    logger.info(FORWARD_ARBITRAGE, 
	        pair1, side1, pair2, side2, pair3, side3, forwardProfit);

	    if (forwardProfit > BinanceArbitrageConfig.PROFIT_MARGIN) {
	        applyTradeRulesAndExecute(
	                getPrice(pair1Ask, pair1Bid, side1),
	                getPrice(pair2Ask, pair2Bid, side2),
	                getPrice(pair3Ask, pair3Bid, side3),
	                pair1, pair2, pair3,
	                side1, side2, side3);
	    }

	    // ========== REVERSE DIRECTION ==========
	    double reverseProfit = calculateProfit(
	        reverseSide(side3), reverseSide(side2), reverseSide(side1),
	        pair3Ask, pair3Bid,
	        pair2Ask, pair2Bid,
	        pair1Ask, pair1Bid);

	    logger.info(REVERSE_ARBITRAGE, 
	        pair3, reverseSide(side3), pair2, reverseSide(side2), pair1, reverseSide(side1), reverseProfit);

	    if (reverseProfit > BinanceArbitrageConfig.PROFIT_MARGIN) {
	        applyTradeRulesAndExecute(
	                getPrice(pair3Ask, pair3Bid, reverseSide(side3)),
	                getPrice(pair2Ask, pair2Bid, reverseSide(side2)),
	                getPrice(pair1Ask, pair1Bid, reverseSide(side1)),
	                pair3, pair2, pair1,
	                reverseSide(side3), reverseSide(side2), reverseSide(side1));
	    }
	}

	private static double getPrice(double ask, double bid, String side) {
	    return side.equals(BUY) ? ask : bid;
	}

	private static String reverseSide(String side) {
	    return side.equals(BUY) ? SELL : BUY;
	}

	private static double calculateProfit(
	        String s1, String s2, String s3,
	        double p1Ask, double p1Bid,
	        double p2Ask, double p2Bid,
	        double p3Ask, double p3Bid) {

	    double price1 = getPrice(p1Ask, p1Bid, s1);
	    double price2 = getPrice(p2Ask, p2Bid, s2);
	    double price3 = getPrice(p3Ask, p3Bid, s3);

	    double amount1 = s1.equals(BUY) ? 1 / price1 : 1 * price1;
	    double amount2 = s2.equals(BUY) ? amount1 / price2 : amount1 * price2;
	    double amount3 = s3.equals(BUY) ? amount2 / price3 : amount2 * price3;

	    return amount3 - 1;
	}

	private static void applyTradeRulesAndExecute(double pair1Price, double pair2Price, double pair3Price, String pair1,
			String pair2, String pair3, String pair1Side, String pair2Side, String pair3Side) {

		logger.info(APPLYING_RULES, pair1, pair1Price, pair2, pair2Price,
				pair3, pair3Price);
		PrimeLogNotifier.audit(APPLYING_RULES,pair1,pair2,pair3,pair1Side,pair2Side,pair3Side,pair1Price,pair2Price,pair3Price,null,null,null);
// 🧮 Calculate quantity chain
		double value = BinanceArbitrageConfig.AMOUNT_TO_TRADE; // always in quote asset at start

		double pair1Quantity = calculateQuantity(pair1, value, pair1Price, pair1Side);
		value = calculateValueAfterTrade(value, pair1Price, pair1Side);

		double pair2Quantity = calculateQuantity(pair2, value, pair2Price, pair2Side);
		value = calculateValueAfterTrade(value, pair2Price, pair2Side);

		double pair3Quantity = calculateQuantity(pair3, value, pair3Price, pair3Side);
		value = calculateValueAfterTrade(value, pair3Price, pair3Side);
		PrimeLogNotifier.audit("validating quantity ",pair1,pair2,pair3,pair1Side,pair2Side,pair3Side,pair1Price,pair2Price,pair3Price,pair1Quantity,pair2Quantity,pair3Quantity);

// ✅ Validate quantities
		logger.info(APPLYING_STEP_SIZE_AND_QUANTITY_RULES);
		if (!isAmountInLimits(pair1, pair1Quantity)) {
			logger.info(FAILED_OPPORTUNITY_AFTER_RULES_ON_PAIR, pair1);
			return;
		}
		if (!isAmountInLimits(pair2, pair2Quantity)) {
			logger.info(FAILED_OPPORTUNITY_AFTER_RULES_ON_PAIR, pair2);
			return;
		}
		if (!isAmountInLimits(pair3, pair3Quantity)) {
			logger.info(FAILED_OPPORTUNITY_AFTER_RULES_ON_PAIR, pair3);
			return;
		}
		pair1Price = getRoundedPrice(pair1, pair1Price);
		pair2Price = getRoundedPrice(pair2, pair2Price);
		pair3Price = getRoundedPrice(pair3, pair3Price);
		
// 📈 Final profit validation
		double profit = value - BinanceArbitrageConfig.AMOUNT_TO_TRADE;
		double profitMargin = (profit / BinanceArbitrageConfig.AMOUNT_TO_TRADE) * 100;
		if (profit < BinanceArbitrageConfig.PROFIT_MARGIN_AFTER_RULES) {
			logger.info(FAILED_OPPORTUNITY_AFTER_RULES_PAIR_PRICES, profit, profitMargin, pair1Price, pair2Price,
					pair3Price);
			return;
		}

		logger.info(EXECUTING_TRADE_AFTER_RULES_PROFIT_OPPORTUNITY, profit, profitMargin);
		long startTime = System.currentTimeMillis();
		TradeExecutor.executeArbitrage(pair1, pair2, pair3, pair1Price, pair2Price, pair3Price,
				pair1Quantity, pair2Quantity, pair3Quantity, pair1Side, pair2Side, pair3Side);
		PrimeLogNotifier.transactions(OPPORTUNITY_FOUND_AFTER_RULES_EXECUTING_TRADES, pair1, pair2, pair3, pair1Price,
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
	
	private static double calculateValueAfterTrade(double inputValue, double price, String side) {
	    double output = side.equals(BUY) ? inputValue / price : inputValue * price;
	    return output * (1 - BinanceArbitrageConfig.TRADING_FEE_PERCENT);
	}

	private static double calculateQuantity(String pair, double value, double price, String side) {
	    double qty = side.equals(BUY) ? value / price : value;
	    double feeAdjusted = qty * (1 - BinanceArbitrageConfig.TRADING_FEE_PERCENT);
	    return getRoundedQuantity(pair, feeAdjusted);
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
			logger.error(QUANTITY_OUT_OF_BOUNDS_FOR_MIN_MAX, quantity, pair, min, max);
			return false;
		}
		return true;
	}
	
	public static boolean isValidPrice(String pair, double price) {
	    if (Double.isNaN(price) || Double.isInfinite(price)) {
	        logger.error(INVALID_NA_N_OR_INFINITE_PRICE_FOR, pair, price);
	        return false;
	    }	
		// Lower bounds for sanity based on base asset if (pair.endsWith("USDT") &&
		if (price < 0.001) {
			logger.error(UNREASONABLY_LOW_PRICE_FOR_USDT_PAIR, pair, price);
			return false;
		}
		if (pair.endsWith(BTC) && price < 1e-7) {
			logger.error(UNREASONABLY_LOW_PRICE_FOR_BTC_PAIR, pair, price);
			return false;
		}
		if (pair.endsWith(ETH) && price < 1e-6) {
			logger.error(UNREASONABLY_LOW_PRICE_FOR_ETH_PAIR, pair, price);
			return false;
		}

	    return true;
	}

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
