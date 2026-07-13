package com.fxc.investor.ofx;

import com.fxc.common.ofx.OfxCodec;
import com.fxc.investor.strategy.MarketView;
import com.fxc.investor.strategy.PortfolioView;
import com.fxc.investor.strategy.Side;
import com.webcohesion.ofx4j.domain.data.RequestEnvelope;
import com.webcohesion.ofx4j.domain.data.RequestMessageSet;
import com.webcohesion.ofx4j.domain.data.ResponseEnvelope;
import com.webcohesion.ofx4j.domain.data.ResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.fxc.FxcOrderRequest;
import com.webcohesion.ofx4j.domain.data.fxc.FxcOrderRequestMessageSet;
import com.webcohesion.ofx4j.domain.data.fxc.FxcOrderRequestTransaction;
import com.webcohesion.ofx4j.domain.data.fxc.FxcBookLevel;
import com.webcohesion.ofx4j.domain.data.fxc.FxcBookRequest;
import com.webcohesion.ofx4j.domain.data.fxc.FxcBookRequestMessageSet;
import com.webcohesion.ofx4j.domain.data.fxc.FxcBookRequestTransaction;
import com.webcohesion.ofx4j.domain.data.fxc.FxcBookResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.fxc.FxcOrderResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.investment.accounts.InvestmentAccountDetails;
import com.webcohesion.ofx4j.domain.data.investment.positions.BasePosition;
import com.webcohesion.ofx4j.domain.data.investment.statements.IncludePosition;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementRequest;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementRequestMessageSet;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementRequestTransaction;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementResponse;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.seclist.SecurityId;
import com.webcohesion.ofx4j.domain.data.signon.SignonRequest;
import com.webcohesion.ofx4j.domain.data.signon.SignonRequestMessageSet;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FxcInvestor's OFX client to FxcBroker (docs/DESIGN.md §4.4): signon, order submission via the
 * shared custom order-entry message set ({@code FXCORDMSGSRQV1}), and statement sync. Uses the
 * shared {@link OfxCodec} and aggregates from {@code fxc-common}; OFX rides over HTTP.
 */
public final class OfxBrokerClient {

    private static final String FX_PREFIX = "FX:";
    private static final String HOME_CURRENCY = "USD";

    private final String url;
    private final String user;
    private final String password;
    private final String brokerId;
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicLong bookSeq = new AtomicLong();

    public OfxBrokerClient(String url, String user, String password, String brokerId) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.brokerId = brokerId;
    }

    /** Submit an order; returns the broker's order status (e.g. {@code ROUTED}, {@code REJECTED}). */
    public String submitOrder(String account, String clOrdId, String symbol, Side side,
                              BigDecimal price, BigDecimal quantity) throws Exception {
        ResponseEnvelope response = post(orderEnvelope(account, clOrdId, symbol, side, price, quantity));
        for (ResponseMessageSet rs : response.getMessageSets()) {
            if (rs instanceof FxcOrderResponseMessageSet ord && ord.getOrderResponse() != null
                    && ord.getOrderResponse().getMessage() != null) {
                return ord.getOrderResponse().getMessage().getOrderStatus();
            }
        }
        return "NO_RESPONSE";
    }

    /**
     * Build the marshalled OFX order-request bytes without sending them. Used by the Gatling
     * simulation, which drives the HTTP itself while reusing this production request-building.
     */
    public byte[] marshalOrder(String account, String clOrdId, String symbol, Side side,
                               BigDecimal price, BigDecimal quantity) {
        return OfxCodec.marshalRequest(orderEnvelope(account, clOrdId, symbol, side, price, quantity));
    }

    private RequestEnvelope orderEnvelope(String account, String clOrdId, String symbol, Side side,
                                          BigDecimal price, BigDecimal quantity) {
        FxcOrderRequest order = new FxcOrderRequest();
        order.setAccountId(account);
        order.setSecurityId(securityId(symbol));
        order.setSide(side.name());
        order.setUnits(quantity.doubleValue());
        order.setOrderType("LIMIT");
        order.setLimitPrice(price.doubleValue());

        FxcOrderRequestTransaction txn = new FxcOrderRequestTransaction();
        txn.setUID(clOrdId);
        txn.setWrappedMessage(order);

        FxcOrderRequestMessageSet set = new FxcOrderRequestMessageSet();
        set.setOrderRequest(txn);

        RequestEnvelope env = new RequestEnvelope();
        env.setUID(clOrdId);
        TreeSet<RequestMessageSet> sets = new TreeSet<>();
        sets.add(set);
        withSignon(env, sets);
        return env;
    }

    /**
     * Request an order-book snapshot for a symbol (FxcBroker/docs/stories/001). Returns the book
     * levels as {@code MarketView.Level}s (both sides combined) for the {@code booker} histogram.
     */
    public List<MarketView.Level> requestBook(String symbol, int depth) throws Exception {
        FxcBookRequest body = new FxcBookRequest();
        body.setSecurityId(securityId(symbol));
        body.setDepth(depth);
        FxcBookRequestTransaction txn = new FxcBookRequestTransaction();
        txn.setUID("BK-" + bookSeq.incrementAndGet());
        txn.setWrappedMessage(body);
        FxcBookRequestMessageSet set = new FxcBookRequestMessageSet();
        set.setBookRequest(txn);

        RequestEnvelope env = new RequestEnvelope();
        env.setUID(txn.getUID());
        TreeSet<RequestMessageSet> sets = new TreeSet<>();
        sets.add(set);
        withSignon(env, sets);

        List<MarketView.Level> levels = new ArrayList<>();
        ResponseEnvelope response = post(env);
        for (ResponseMessageSet rs : response.getMessageSets()) {
            if (rs instanceof FxcBookResponseMessageSet book && book.getBookResponse() != null
                    && book.getBookResponse().getMessage() != null) {
                for (FxcBookLevel level : book.getBookResponse().getMessage().getLevels()) {
                    if (level.getPrice() != null && level.getSize() != null) {
                        levels.add(new MarketView.Level(
                                BigDecimal.valueOf(level.getPrice()), BigDecimal.valueOf(level.getSize())));
                    }
                }
            }
        }
        return levels;
    }

    /** Fetch the account's current holdings from an OFX investment statement. */
    public PortfolioView fetchPortfolio(String account) throws Exception {
        InvestmentAccountDetails acct = new InvestmentAccountDetails();
        acct.setBrokerId(brokerId);
        acct.setAccountNumber(account);
        InvestmentStatementRequest stmt = new InvestmentStatementRequest();
        stmt.setAccount(acct);
        IncludePosition incPos = new IncludePosition();
        incPos.setIncludePositions(true);
        stmt.setIncludePosition(incPos);
        stmt.setIncludeOpenOrders(false);
        stmt.setIncludeBalance(true);
        InvestmentStatementRequestTransaction txn = new InvestmentStatementRequestTransaction();
        txn.setUID("STMT-" + account);
        txn.setMessage(stmt);
        InvestmentStatementRequestMessageSet set = new InvestmentStatementRequestMessageSet();
        set.setStatementRequest(txn);

        RequestEnvelope env = new RequestEnvelope();
        env.setUID("STMT-" + account);
        TreeSet<RequestMessageSet> sets = new TreeSet<>();
        sets.add(set);
        withSignon(env, sets);

        Map<String, BigDecimal> shares = new HashMap<>();
        Map<String, BigDecimal> cash = new HashMap<>();
        ResponseEnvelope response = post(env);
        for (ResponseMessageSet rs : response.getMessageSets()) {
            if (rs instanceof InvestmentStatementResponseMessageSet stmtSet
                    && stmtSet.getStatementResponse() != null) {
                InvestmentStatementResponse body = stmtSet.getStatementResponse().getMessage();
                if (body.getAccountBalance() != null && body.getAccountBalance().getAvailableCash() != null) {
                    cash.put(HOME_CURRENCY, BigDecimal.valueOf(body.getAccountBalance().getAvailableCash()));
                }
                if (body.getPositionList() != null && body.getPositionList().getPositions() != null) {
                    for (BasePosition p : body.getPositionList().getPositions()) {
                        if (p.getSecurityId() == null || p.getUnits() == null) {
                            continue;
                        }
                        String uid = p.getSecurityId().getUniqueId();
                        BigDecimal units = BigDecimal.valueOf(p.getUnits());
                        if (uid != null && uid.startsWith(FX_PREFIX)) {
                            cash.put(uid.substring(FX_PREFIX.length()), units);
                        } else {
                            shares.put(uid, units);
                        }
                    }
                }
            }
        }
        return new PortfolioView(cash, shares);
    }

    private ResponseEnvelope post(RequestEnvelope request) throws Exception {
        byte[] body = OfxCodec.marshalRequest(request);
        HttpResponse<byte[]> resp = http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", OfxCodec.CONTENT_TYPE)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("OFX POST failed " + resp.statusCode() + ": "
                    + new String(resp.body(), StandardCharsets.UTF_8));
        }
        return OfxCodec.unmarshalResponse(new ByteArrayInputStream(resp.body()));
    }

    private void withSignon(RequestEnvelope env, TreeSet<RequestMessageSet> sets) {
        SignonRequest signon = new SignonRequest();
        signon.setUserId(user);
        signon.setPassword(password);
        signon.setTimestamp(new Date());
        signon.setLanguage("ENG");
        signon.setApplicationId("FXC");
        signon.setApplicationVersion("0100");
        SignonRequestMessageSet signonSet = new SignonRequestMessageSet();
        signonSet.setSignonRequest(signon);
        sets.add(signonSet);
        env.setMessageSets(sets);
    }

    private static SecurityId securityId(String symbol) {
        SecurityId id = new SecurityId();
        if (symbol.contains("/")) {
            id.setUniqueId(FX_PREFIX + symbol);
            id.setUniqueIdType("FXC");
        } else {
            id.setUniqueId(symbol);
            id.setUniqueIdType("TICKER");
        }
        return id;
    }
}
