package bots.binance.notifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.*;

public final class TelegramNotifier {
    private static final String TELEGRAM_BOT_TOKEN = "7517992059:AAExL8K18voEEj-PhICO1mSht5HRFjdkXeg";
    private static final String CHAT_ID = "-1002649780018";
    private static final OkHttpClient client = new OkHttpClient();
    private static Logger logger = LoggerFactory.getLogger(TelegramNotifier.class);

    public static void sendAlert(String message) {
        try {
            String url = "https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN +
                         "/sendMessage?chat_id=" + CHAT_ID +
                         "&text=" + message;
            Request request = new Request.Builder().url(url).get().build();
            client.newCall(request).execute();
            logger.info("telegram notification sent."+message);
        } catch (Exception e) {
            System.err.println("Error sending Telegram alert: " + e.getMessage());
            logger.error("Telegram notification failed. "+message);
        }
    }
    
    public static void main(String args[]) {
    	TelegramNotifier.sendAlert("Test");
    }
}
