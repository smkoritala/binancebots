package bots.binance.triarb;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bots.binance.client.BinanceHttpClient;
import bots.binance.client.TradeExecutor;
import bots.binance.rules.ExchangeRulesManager;

import java.io.*;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
//TODO needs debug
public class OptimizedArbitrageCPUDynaTriBot {

    private static final Logger logger = LoggerFactory.getLogger(OptimizedArbitrageBot.class);

    private static final List<String> topCoins = List.of("BTC", "ETH", "USDT", "USDC", "LTC", "SOL", "ADA", "AVAX", "DOGE", "BNB", "MATIC");
    private static final List<String[]> TRIANGLES = new ArrayList<>();
    private static final Set<String> uniquePairs = new HashSet<>();
    public static final String BUY = "BUY";
    public static final String SELL = "SELL";
    public static final String CHECK_FOR_ARBITRAGE_STARTED_AND_REV = "checkForArbitrage started {} and rev {}";
    public static final String UPDATE_PRICES = "updatePrices {} {} {}";
    public static final String A = "a";
    public static final String B = "b";
    public static final String S = "s";
    public static final String DATA = "data";

    private static final ConcurrentHashMap<String, Double> pricesAsk = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pricesBid = new ConcurrentHashMap<>();

    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
    private static final int MAX_THREAD_QUEUE = 100;
    private static final ExecutorService arbitrageThreadPool = new ThreadPoolExecutor(
            CPU_CORES,
            CPU_CORES * 2,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_THREAD_QUEUE),
            Executors.defaultThreadFactory(),
            (r, executor) -> logger.warn("⚠️ Arbitrage task rejected: thread pool is full"));

    private static final Map<String, Double> volumeMap = new HashMap<>();
    private static final double MIN_DAILY_VOLUME = 50000.0; // USD equivalent
    private static final String BACKUP_FOLDER = "triangle_backups";

    public static void main(String[] args) throws Exception {
        logger.info("⏳ Preloading Binance exchange rules and syncing server time...");
        BinanceHttpClient.createSecureClient();
        ExchangeRulesManager.preloadExchangeRules(uniquePairs.toArray(new String[0]));
        logger.info("✅ Exchange rules loaded.");
        TradeExecutor.syncServerTime();
        logger.info("✅ Server time synced!");

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(OptimizedArbitrageCPUDynaTriBot::refreshTriangles, 0, 1, TimeUnit.HOURS);
    }

    private static void saveTrianglesToDisk() {
        try {
            File dir = new File(BACKUP_FOLDER);
            if (!dir.exists()) dir.mkdirs();

            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            File file = new File(dir, "triangle-" + timestamp + ".txt");

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                for (String[] t : TRIANGLES) {
                    writer.write(String.join(",", t));
                    writer.newLine();
                }
            }
            logger.info("💾 Triangle list saved to disk: {}", file.getAbsolutePath());
        } catch (IOException e) {
            logger.error("❌ Failed to save triangle backup: {}", e.getMessage(), e);
        }
    }

    private static void refreshTriangles() {
        try {
            TRIANGLES.clear();
            uniquePairs.clear();
            volumeMap.clear();

            logger.info("⏳ Fetching symbols and generating trading triangles...");
            String exchangeInfo = BinanceHttpClient.sendGetRequest("https://api.binance.us/api/v3/exchangeInfo");
            String volumeData = BinanceHttpClient.sendGetRequest("https://api.binance.us/api/v3/ticker/24hr");

            JSONObject exchangeJson = new JSONObject(exchangeInfo);
            JSONArray volumeArray = new JSONArray(volumeData);

            for (int i = 0; i < volumeArray.length(); i++) {
                JSONObject obj = volumeArray.getJSONObject(i);
                String symbol = obj.getString("symbol");
                double quoteVolume = obj.optDouble("quoteVolume", 0);
                volumeMap.put(symbol, quoteVolume);
            }

            Set<String> filteredPairs = new HashSet<>();
            for (Object symbolObj : exchangeJson.getJSONArray("symbols")) {
                JSONObject symbol = (JSONObject) symbolObj;
                String symbolStr = symbol.getString("symbol");
                if (symbol.getString("status").equalsIgnoreCase("TRADING")
                        && symbol.getBoolean("isSpotTradingAllowed")
                        && volumeMap.getOrDefault(symbolStr, 0.0) >= MIN_DAILY_VOLUME) {
                    filteredPairs.add(symbolStr);
                }
            }

            for (String a : topCoins) {
                for (String b : topCoins) {
                    if (a.equals(b)) continue;
                    for (String c : topCoins) {
                        if (c.equals(a) || c.equals(b)) continue;

                        String ab = a + b;
                        String ba = b + a;
                        String bc = b + c;
                        String cb = c + b;
                        String ac = a + c;
                        String ca = c + a;

                        String[] triplet = null;

                        if (filteredPairs.contains(ab) && filteredPairs.contains(bc) && filteredPairs.contains(ac)) {
                            triplet = new String[]{ab, bc, ac};
                        } else if (filteredPairs.contains(ba) && filteredPairs.contains(cb) && filteredPairs.contains(ca)) {
                            triplet = new String[]{ba, cb, ca};
                        }

                        if (triplet != null) {
                            TRIANGLES.add(triplet);
                            uniquePairs.addAll(Arrays.asList(triplet));
                        }
                    }
                }
            }

            logger.info("✅ Found {} triangular routes from {} top coins:", TRIANGLES.size(), topCoins.size());
            for (String[] t : TRIANGLES) {
                logger.info("  → {} → {} → {}", t[0], t[1], t[2]);
            }

            saveTrianglesToDisk();

            BinanceHttpClient.createSecureClient();
            ExchangeRulesManager.preloadExchangeRules(uniquePairs.toArray(new String[0]));
            logger.info("✅ Exchange rules loaded.");
            TradeExecutor.syncServerTime();
            logger.info("✅ Server time synced!");

            String streamUrl = "wss://stream.binance.us:9443/stream?streams=" +
                    uniquePairs.stream().map(p -> p.toLowerCase() + "@bookTicker").collect(Collectors.joining("/"));

            EventLoopGroup group = new NioEventLoopGroup();
            SslContext sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            URI uri = new URI(streamUrl);

            WebSocketClientHandler handler = new WebSocketClientHandler(
                    WebSocketClientHandshakerFactory.newHandshaker(
                            uri, WebSocketVersion.V13, null, false, new DefaultHttpHeaders()));

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<>() {
                @Override
                protected void initChannel(Channel ch) {
                    ChannelPipeline p = ch.pipeline();
                    p.addLast(sslCtx.newHandler(ch.alloc(), uri.getHost(), 9443));
                    p.addLast(new HttpClientCodec());
                    p.addLast(new HttpObjectAggregator(8192));
                    p.addLast(handler);
                }
            });

            Channel channel = bootstrap.connect(uri.getHost(), 9443).sync().channel();
            handler.handshakeFuture().sync();

        } catch (Exception e) {
            logger.error("❌ Failed to refresh triangles: {}", e.getMessage(), e);
        }
    }
    private static class WebSocketClientHandler extends SimpleChannelInboundHandler<Object> {
        private final WebSocketClientHandshaker handshaker;
        private ChannelPromise handshakeFuture;

        public WebSocketClientHandler(WebSocketClientHandshaker handshaker) {
            this.handshaker = handshaker;
        }

        public ChannelFuture handshakeFuture() {
            return handshakeFuture;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            handshakeFuture = ctx.newPromise();
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            handshaker.handshake(ctx.channel());
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx, Object msg) {
            Channel ch = ctx.channel();
            if (!handshaker.isHandshakeComplete()) {
                handshaker.finishHandshake(ch, (FullHttpResponse) msg);
                handshakeFuture.setSuccess();
                return;
            }

            if (msg instanceof FullHttpResponse response) {
                throw new IllegalStateException("Unexpected FullHttpResponse: " + response.status());
            }

            WebSocketFrame frame = (WebSocketFrame) msg;
            if (frame instanceof TextWebSocketFrame textFrame) {
                String text = textFrame.text();
                JSONObject json = new JSONObject(text);
                if (json.has(DATA)) {
                    JSONObject data = json.getJSONObject(DATA);
                    String symbol = data.getString(S);
                    double bid = data.getDouble(B);
                    double ask = data.getDouble(A);
                    logger.info(UPDATE_PRICES, symbol, bid, ask);

                    if (ExchangeRulesManager.isValidTrade(symbol, ask, bid)) {
                        pricesAsk.put(symbol, ask);
                        pricesBid.put(symbol, bid);
                    }

                    for (String[] triangle : TRIANGLES) {
                        String p1 = triangle[0], p2 = triangle[1], p3 = triangle[2];
                        Double p1Ask = pricesAsk.get(p1), p2Ask = pricesAsk.get(p2), p3Ask = pricesAsk.get(p3);
                        Double p1Bid = pricesBid.get(p1), p2Bid = pricesBid.get(p2), p3Bid = pricesBid.get(p3);

                        logger.info("📊 Triangle status: {}={} {}={} {}={} (bids)",
                                p1, p1Bid, p2, p2Bid, p3, p3Bid);

                        if (p1Ask != null && p2Ask != null && p3Ask != null &&
                                p1Bid != null && p2Bid != null && p3Bid != null) {
//                            arbitrageThreadPool.submit(() -> ExchangeRulesManager.checkForArbitrage(
//                                    p1, p2, p3, p1Ask, p2Ask, p3Ask, p1Bid, p2Bid, p3Bid));
                        }
                    }
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }
}
