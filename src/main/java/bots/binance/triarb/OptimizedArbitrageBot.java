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
import java.util.concurrent.ConcurrentHashMap;

public class OptimizedArbitrageBot {

	public static final String UPDATE_PRICES = "updatePrices {} {} {}";

    public static final String A = "a";
	public static final String B = "b";
	public static final String S = "s";
	public static final String DATA = "data";	
	private static final Logger logger = LoggerFactory.getLogger(OptimizedArbitrageBot.class);

    private static final String BINANCE_WS_URL = "wss://stream.binance.us:9443/stream?streams=btcusdt@bookTicker/ethusdt@bookTicker/ethbtc@bookTicker";
    private static final String[] PAIRS = {"BTCUSDT", "ETHBTC", "ETHUSDT"};
//    private static final String BINANCE_WS_URL = "wss://stream.binance.us:9443/stream?streams=btcusdc@bookTicker/ethusdc@bookTicker/ethbtc@bookTicker";
//    private static final String[] PAIRS = {"BTCUSDC", "ETHBTC", "ETHUSDC"};
//    private static final String BINANCE_WS_URL = "wss://stream.binance.us:9443/stream?streams=btcusdt@bookTicker/xrpusdt@bookTicker/xrpbtc@bookTicker";
//    private static final String[] PAIRS = {"BTCUSDT", "XRPBTC", "XRPUSDT"};

    private static final ConcurrentHashMap<String, Double> pricesAsk = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pricesBid = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        logger.info("⏳ Preloading Binance exchange rules and syncing server time...");
        BinanceHttpClient.createSecureClient();
        ExchangeRulesManager.preloadExchangeRules(PAIRS);
        logger.info("✅ Exchange rules loaded.");
        TradeExecutor.syncServerTime();
        logger.info("✅ Server time synced!");

        EventLoopGroup group = new NioEventLoopGroup();
        SslContext sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        URI uri = new URI(BINANCE_WS_URL);

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

		private static final String UNEXPECTED_FULL_HTTP_RESPONSE = "Unexpected FullHttpResponse: ";
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
                throw new IllegalStateException(UNEXPECTED_FULL_HTTP_RESPONSE + response.status());
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
                    pricesAsk.put(symbol, ask);
                    pricesBid.put(symbol, bid);
                    if (ExchangeRulesManager.isValidTrade(symbol, ask, bid)) { 
//step 1 slippage                    	
                        String pair1 = PAIRS[0];
                        String pair2 = PAIRS[1];
                        String pair3 = PAIRS[2];

                        Double pair1Ask = pricesAsk.get(pair1);
                        Double pair2Ask = pricesAsk.get(pair2);
                        Double pair3Ask = pricesAsk.get(pair3);

                        Double pair1Bid = pricesBid.get(pair1);
                        Double pair2Bid = pricesBid.get(pair2);
                        Double pair3Bid = pricesBid.get(pair3);

 //                       ExchangeRulesManager.checkForArbitrage(pair1, pair2, pair3, pair1Ask, pair2Ask, pair3Ask, pair1Bid, pair2Bid, pair3Bid);
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
