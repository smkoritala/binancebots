package bots.binance.notifier;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PrimeLogNotifier {
	
    private static Logger logger = LoggerFactory.getLogger(PrimeLogNotifier.class);

    public static final void logSuccessTrade(String symbol, String side, double quantity) {
        try (FileWriter writer = new FileWriter("Success-trades.csv", true)) {
            writer.append(String.format("%s,%s,%.6f,%s\n",
                    symbol, side, quantity, LocalDateTime.now()));
        } catch (IOException e) {
            logger.error("Error writing to success trade log: {}", e.getMessage());
        }
    }  
    
    public static final void logFailedTrade(String symbol, String side, double quantity, String reason) {
        try (FileWriter writer = new FileWriter("failed-trades.csv", true)) {
            writer.append(String.format("%s,%s,%.6f,%s,%s\n",
                    symbol, side, quantity, reason, LocalDateTime.now()));
        } catch (IOException e) {
            logger.error("Error writing to failed trade log: {}", e.getMessage());
        }
    }  
    
    public static void logMissedTrade(String pair, double bestAsk, double bestBid, double buySlippage, double sellSlippage, double maxSlippage) {
        try (FileWriter writer = new FileWriter("missed-arbitrage.csv", true)) {
            writer.append(String.format("%s,%.6f,%.6f,%.2f%%,%.2f%%,%.2f%%,%s\n",
                    pair, bestAsk, bestBid, buySlippage, sellSlippage, maxSlippage, LocalDateTime.now()));
        } catch (IOException e) {
            logger.error("Error writing to missed trade log: {}", e.getMessage());
        }
    }

	public static void audit(String string, String pair1, String pair2, String pair3, double pair1Price,
			double pair2Price, double pair3Price, double pair1Quant,
			double pair2Quant, double pair3Quant, double profit, double profitMargin) {
        try (FileWriter writer = new FileWriter("Audit-trades.csv", true)) {
            writer.append(String.format("%s,%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%s\n",
            		string, pair1, pair2, pair3, pair1Price, pair2Price, pair3Price, pair1Quant, pair2Quant, pair3Quant, profit, profitMargin, LocalDateTime.now()));
        } catch (IOException e) {
            logger.error("Error writing to audit log: {}", e.getMessage());
        }		
	}

	public static void audit(String string, String pair1, String pair2, String pair3, String pair1Side,
	        String pair2Side, String pair3Side, Double pair1Ask, Double pair2Ask, Double pair3Ask, Double pair1Bid,
	        Double pair2Bid, Double pair3Bid) {/*
	    try (FileWriter writer = new FileWriter("Audit-trades.csv", true)) {
	        writer.append(String.format("%s,%s,%s,%s,%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%s\n",
	                string,
	                pair1, pair2, pair3,
	                pair1Side, pair2Side, pair3Side,
	                pair1Ask, pair2Ask, pair3Ask,
	                pair1Bid, pair2Bid, pair3Bid,
	                java.time.LocalDateTime.now()));
	    } catch (IOException e) {
	        logger.error("Error writing to audit log: {}", e.getMessage());
	    }*/
	}

	public static void transactions(String string, String pair1, String pair2,
			String pair3, double pair1Price, double pair2Price, double pair3Price, double pair1Quantity,
			double pair2Quantity, double pair3Quantity, double profit, double profitMargin) {
	    try (FileWriter writer = new FileWriter("transactions-trades.csv", true)) {
	        writer.append(String.format("%s,%s,%s,%s,%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%s\n",
	                string,
	                pair1, pair2, pair3,
	                pair1Price, pair2Price, pair3Price,
	                pair1Quantity, pair2Quantity, pair3Quantity,
	                profit, profitMargin,
	                java.time.LocalDateTime.now()));
	    } catch (IOException e) {
	        logger.error("Error writing to audit log: {}", e.getMessage());
	    }		
	}	

}
