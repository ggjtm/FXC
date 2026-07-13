package com.fxc.broker.ofx;

import com.fxc.broker.account.AccountService;
import com.fxc.broker.model.HoldingType;
import com.fxc.broker.model.OrderType;
import com.fxc.broker.model.Position;
import com.fxc.broker.model.Side;
import com.fxc.broker.oms.OmsService;
import com.fxc.broker.oms.OrderResult;
import com.webcohesion.ofx4j.domain.data.RequestEnvelope;
import com.webcohesion.ofx4j.domain.data.RequestMessageSet;
import com.webcohesion.ofx4j.domain.data.ResponseEnvelope;
import com.webcohesion.ofx4j.domain.data.ResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.common.Status;
import com.webcohesion.ofx4j.domain.data.fxc.FxcOrderRequest;
import com.webcohesion.ofx4j.domain.data.fxc.FxcOrderRequestMessageSet;
import com.webcohesion.ofx4j.domain.data.fxc.FxcOrderResponse;
import com.webcohesion.ofx4j.domain.data.fxc.FxcOrderResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.fxc.FxcOrderResponseTransaction;
import com.webcohesion.ofx4j.domain.data.investment.accounts.InvestmentAccountDetails;
import com.webcohesion.ofx4j.domain.data.investment.positions.BasePosition;
import com.webcohesion.ofx4j.domain.data.investment.positions.InvestmentPosition;
import com.webcohesion.ofx4j.domain.data.investment.positions.InvestmentPositionList;
import com.webcohesion.ofx4j.domain.data.investment.positions.OtherPosition;
import com.webcohesion.ofx4j.domain.data.investment.positions.StockPosition;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentBalance;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementRequestMessageSet;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementResponse;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementResponseTransaction;
import com.webcohesion.ofx4j.domain.data.seclist.SecurityId;
import com.webcohesion.ofx4j.domain.data.signon.SignonRequest;
import com.webcohesion.ofx4j.domain.data.signon.SignonRequestMessageSet;
import com.webcohesion.ofx4j.domain.data.signon.SignonResponse;
import com.webcohesion.ofx4j.domain.data.signon.SignonResponseMessageSet;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TreeSet;

/**
 * Turns an inbound OFX {@link RequestEnvelope} into a {@link ResponseEnvelope} (docs/DESIGN.md
 * §4.2). Handles signon (static dev credentials), the investment statement (positions + balance),
 * and the custom order-entry message set (routes to {@link OmsService}). This is all application
 * code — OFX4J's server contract is just marshalling (PROBLEMS.md B1).
 *
 * <p>Statement mapping (DESIGN §6.6): equities → native {@link StockPosition}; the home currency
 * ({@code USD}) balance → {@link InvestmentBalance#setAvailableCash}; other currency balances →
 * {@link OtherPosition} FX pseudo-securities with a synthetic {@code FX:CCY} security id.
 */
public final class OfxService {

    private static final String HOME_CURRENCY = "USD";
    private static final String FX_ID_TYPE = "FXC";
    private static final String FX_PREFIX = "FX:";

    private final OmsService oms;
    private final AccountService accounts;
    private final String expectedUser;
    private final String expectedPassword;
    private final String brokerId;

    public OfxService(OmsService oms, AccountService accounts, String expectedUser,
                      String expectedPassword, String brokerId) {
        this.oms = oms;
        this.accounts = accounts;
        this.expectedUser = expectedUser;
        this.expectedPassword = expectedPassword;
        this.brokerId = brokerId;
    }

    public ResponseEnvelope handle(RequestEnvelope request) {
        ResponseEnvelope response = new ResponseEnvelope();
        response.setUID(request.getUID());
        TreeSet<ResponseMessageSet> responseSets = new TreeSet<>();

        SignonRequest signon = signonRequest(request);
        boolean authOk = signon != null
                && expectedUser.equals(signon.getUserId())
                && expectedPassword.equals(signon.getPassword());
        responseSets.add(signonResponse(authOk));

        if (authOk) {
            for (RequestMessageSet set : request.getMessageSets()) {
                if (set instanceof InvestmentStatementRequestMessageSet stmt) {
                    responseSets.add(statementResponse(stmt));
                } else if (set instanceof FxcOrderRequestMessageSet order) {
                    responseSets.add(orderResponse(order));
                }
            }
        }

        response.setMessageSets(responseSets);
        return response;
    }

    // --- signon ---

    private SignonRequest signonRequest(RequestEnvelope request) {
        for (RequestMessageSet set : request.getMessageSets()) {
            if (set instanceof SignonRequestMessageSet signon) {
                return signon.getSignonRequest();
            }
        }
        return null;
    }

    private SignonResponseMessageSet signonResponse(boolean ok) {
        SignonResponse signon = new SignonResponse();
        signon.setTimestamp(new Date());
        signon.setLanguage("ENG");
        signon.setStatus(status(ok ? Status.KnownCode.SUCCESS : Status.KnownCode.SIGNON_INVALID,
                ok ? Status.Severity.INFO : Status.Severity.ERROR));
        SignonResponseMessageSet set = new SignonResponseMessageSet();
        set.setSignonResponse(signon);
        return set;
    }

    // --- statement ---

    private InvestmentStatementResponseMessageSet statementResponse(InvestmentStatementRequestMessageSet request) {
        String trnUid = request.getStatementRequest() != null ? request.getStatementRequest().getUID() : null;
        String account = request.getStatementRequest() != null
                && request.getStatementRequest().getMessage() != null
                && request.getStatementRequest().getMessage().getAccount() != null
                ? request.getStatementRequest().getMessage().getAccount().getAccountNumber()
                : null;

        InvestmentStatementResponse statement = new InvestmentStatementResponse();
        statement.setCurrencyCode(HOME_CURRENCY); // CURDEF (required)
        statement.setDateOfStatement(new Date());
        InvestmentAccountDetails acct = new InvestmentAccountDetails();
        acct.setBrokerId(brokerId);
        acct.setAccountNumber(account);
        statement.setAccount(acct);

        List<BasePosition> positions = new ArrayList<>();
        BigDecimal homeCash = BigDecimal.ZERO;
        if (account != null) {
            for (Position p : accounts.positions(account)) {
                if (p.quantity().signum() == 0) {
                    continue;
                }
                if (p.holdingType() == HoldingType.SHARE) {
                    positions.add(stockPosition(p));
                } else if (p.instrument().equals(HOME_CURRENCY)) {
                    homeCash = p.quantity();
                } else {
                    positions.add(fxPosition(p)); // non-home currency balance
                }
            }
        }
        InvestmentPositionList positionList = new InvestmentPositionList();
        positionList.setPositions(positions);
        statement.setPositionList(positionList);

        InvestmentBalance balance = new InvestmentBalance();
        balance.setAvailableCash(homeCash.doubleValue());
        balance.setMarginBalance(0.0);
        balance.setShortBalance(0.0);
        statement.setAccountBalance(balance);

        InvestmentStatementResponseTransaction txn = new InvestmentStatementResponseTransaction();
        txn.setUID(trnUid);
        txn.setStatus(status(Status.KnownCode.SUCCESS, Status.Severity.INFO));
        txn.setMessage(statement);

        InvestmentStatementResponseMessageSet set = new InvestmentStatementResponseMessageSet();
        set.setStatementResponse(txn);
        return set;
    }

    private StockPosition stockPosition(Position p) {
        StockPosition position = new StockPosition();
        position.setInvestmentPosition(investmentPosition(
                securityId(p.instrument(), "TICKER"), p.quantity(), p.avgPrice()));
        return position;
    }

    private OtherPosition fxPosition(Position p) {
        OtherPosition position = new OtherPosition();
        // Synthetic FX security id per DESIGN §6.6 (per-currency for a cash FX book).
        position.setInvestmentPosition(investmentPosition(
                securityId(FX_PREFIX + p.instrument(), FX_ID_TYPE), p.quantity(), BigDecimal.ONE));
        return position;
    }

    private InvestmentPosition investmentPosition(SecurityId secId, BigDecimal units, BigDecimal unitPrice) {
        InvestmentPosition ip = new InvestmentPosition();
        ip.setSecurityId(secId);
        ip.setPositionType("LONG");
        ip.setHeldInAccount("CASH");
        ip.setUnits(units.doubleValue());
        ip.setUnitPrice(unitPrice.doubleValue());
        ip.setMarketValue(units.multiply(unitPrice).doubleValue());
        ip.setMarketValueDate(new Date());
        return ip;
    }

    // --- order entry ---

    private FxcOrderResponseMessageSet orderResponse(FxcOrderRequestMessageSet request) {
        FxcOrderResponseTransaction txn = new FxcOrderResponseTransaction();
        FxcOrderResponse body = new FxcOrderResponse();

        if (request.getOrderRequest() == null || request.getOrderRequest().getMessage() == null) {
            txn.setStatus(status(Status.KnownCode.GENERAL_ERROR, Status.Severity.ERROR));
            body.setBrokerOrderId("");
            body.setOrderStatus("REJECTED");
            txn.setMessage(body);
            FxcOrderResponseMessageSet set = new FxcOrderResponseMessageSet();
            set.setOrderResponse(txn);
            return set;
        }

        String clientOrderId = request.getOrderRequest().getUID();
        FxcOrderRequest order = request.getOrderRequest().getMessage();
        String symbol = symbolFromSecurityId(order.getSecurityId());
        Side side = "SELL".equalsIgnoreCase(order.getSide()) ? Side.SELL : Side.BUY;
        OrderType type = "MARKET".equalsIgnoreCase(order.getOrderType()) ? OrderType.MARKET : OrderType.LIMIT;
        BigDecimal qty = order.getUnits() == null ? null : BigDecimal.valueOf(order.getUnits());
        BigDecimal price = order.getLimitPrice() == null ? null : BigDecimal.valueOf(order.getLimitPrice());

        OrderResult result = oms.submit(order.getAccountId(), clientOrderId, symbol, side, type, price, qty);

        txn.setUID(clientOrderId);
        txn.setStatus(status(result.accepted() ? Status.KnownCode.SUCCESS : Status.KnownCode.GENERAL_ERROR,
                result.accepted() ? Status.Severity.INFO : Status.Severity.ERROR));
        if (!result.accepted()) {
            txn.setStatus(withMessage(txn.getStatus(), result.reason()));
        }
        body.setBrokerOrderId(result.order().exchangeOrderId() != null
                ? result.order().exchangeOrderId() : clientOrderId);
        body.setOrderStatus(result.order().status().name());
        txn.setMessage(body);

        FxcOrderResponseMessageSet set = new FxcOrderResponseMessageSet();
        set.setOrderResponse(txn);
        return set;
    }

    private String symbolFromSecurityId(SecurityId secId) {
        if (secId == null || secId.getUniqueId() == null) {
            return "";
        }
        String uid = secId.getUniqueId();
        return uid.startsWith(FX_PREFIX) ? uid.substring(FX_PREFIX.length()) : uid;
    }

    // --- helpers ---

    private static SecurityId securityId(String uniqueId, String type) {
        SecurityId secId = new SecurityId();
        secId.setUniqueId(uniqueId);
        secId.setUniqueIdType(type);
        return secId;
    }

    private static Status status(Status.KnownCode code, Status.Severity severity) {
        Status status = new Status();
        status.setCode(code);
        status.setSeverity(severity);
        return status;
    }

    private static Status withMessage(Status status, String message) {
        status.setMessage(message);
        return status;
    }
}
