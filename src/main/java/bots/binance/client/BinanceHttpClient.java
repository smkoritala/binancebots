package bots.binance.client;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.CertificatePinner;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/*
 * This class should be URL free, It is used for multiple purposes. should be the one to own keys
 */
public class BinanceHttpClient {

	private static final String BINANCE_API_KEY = System.getenv("BINANCE_API_KEY");
	private static final String BINANCE_SECRET_KEY = System.getenv("BINANCE_SECRET_KEY");
	private static OkHttpClient secClient = null;

	public final static String sendPostRequest(String url, String queryString) throws Exception {
		// 🔑 Sign the request
		String signature = hmacSHA256(queryString, BINANCE_SECRET_KEY);
		String signedBody = queryString + "&signature=" + signature;
		/*
		 * Request request = new Request.Builder().url(url)
		 * .post(RequestBody.create(signedBody,
		 * MediaType.parse("application/x-www-form-urlencoded")))
		 * .addHeader("X-MBX-APIKEY", BINANCE_API_KEY).build();
		 * 
		 * try (Response response = secClient.newCall(request).execute()) { if
		 * (!response.isSuccessful()) { throw new Exception("❌ Binance API Error: " +
		 * response.code() + " - " + response.body().string()); } return
		 * response.body().string(); }
		 */ return "";
	}

	public final static String sendGetRequest(String url) throws Exception {
		Request request = new Request.Builder().url(url).get().addHeader("X-MBX-APIKEY", BINANCE_API_KEY).build();
		try (Response response = secClient.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				throw new Exception("❌ Binance API Error: " + response.code() + " - " + response.body().string());
			}
			return response.body().string();
		}
	}

	public static Map<String, Double> getAccountBalance() throws Exception {
		String response = BinanceHttpClient.sendGetRequest("https://api.binance.us/api/v3/account");
		JsonNode jsonNode = new ObjectMapper().readTree(response);

		Map<String, Double> balanceMap = new HashMap<>();
		for (JsonNode balanceNode : jsonNode.get("balances")) {
			String asset = balanceNode.get("asset").asText();
			double freeBalance = balanceNode.get("free").asDouble();
			balanceMap.put(asset, freeBalance);
		}
		return balanceMap;
	}
	
	private static String hmacSHA256(String data, String key) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		mac.init(secretKeySpec);
		byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
		return bytesToHex(hash);
	}

	private static String bytesToHex(byte[] bytes) {
		StringBuilder hexString = new StringBuilder();
		for (byte b : bytes) {
			String hex = Integer.toHexString(0xff & b);
			if (hex.length() == 1)
				hexString.append('0');
			hexString.append(hex);
		}
		return hexString.toString();
	}

	public static void createSecureClient() {
		try {
			//get cert dyna
			Set<String> fingerprints = BinanceCertificateFetcher.getAllSSLFingerprints("api.binance.us");
            CertificatePinner.Builder builder = new CertificatePinner.Builder();
            for (String fingerprint : fingerprints) {
                builder.add("api.binance.us", fingerprint);
            }
			secClient = new OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
					.connectionPool(new ConnectionPool(10, 10, TimeUnit.MINUTES))
					.certificatePinner(builder.build())
					.build();
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException("❌ Failed to fetch Binance SSL certificates: " + e.getMessage());
		}
	}


}
