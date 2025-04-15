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
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bots.binance.client.BinanceHttpClient;
import bots.binance.client.TradeExecutor;
import bots.binance.config.BinanceArbitrageConfig;
import bots.binance.notifier.PrimeLogNotifier;
import bots.binance.rules.ExchangeRulesManager;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
public class OptimizedMultiTriArbitrageCPUBot {

 // [constants remain unchanged]

 private static final Logger logger = LoggerFactory.getLogger(OptimizedMultiTriArbitrageCPUBot.class);

 private static final String[][] TRIANGLES = {
         {"BTCUSDT", "ETHBTC", "ETHUSDT"},
         {"LTCUSDT", "ETHLTC", "ETHUSDT"},
         {"SOLUSDT", "ETHSOL", "ETHUSDT"}
 };

 private static final Set<String> uniquePairs = Arrays.stream(TRIANGLES)
         .flatMap(Arrays::stream)
         .collect(Collectors.toSet());

 private static final ConcurrentHashMap<String, Double> pricesAsk = new ConcurrentHashMap<>();
 private static final ConcurrentHashMap<String, Double> pricesBid = new ConcurrentHashMap<>();
 public static final String BUY = "BUY";
 public static final String SELL = "SELL";
 public static final String CHECK_FOR_ARBITRAGE_STARTED_AND_REV = "checkForArbitrage started {} and rev {}";
 public static final String UPDATE_PRICES = "updatePrices {} {} {}";
 public static final String A = "a";
 public static final String B = "b";
 public static final String S = "s";
 public static final String DATA = "data";

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

 public static void main(String[] args) throws Exception {
     logger.info("⏳ Preloading Binance exchange rules and syncing server time...");
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
//	                        arbitrageThreadPool.submit(() -> ExchangeRulesManager.checkForArbitrage(
//	                                p1, p2, p3, p1Ask, p2Ask, p3Ask, p1Bid, p2Bid, p3Bid));
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

