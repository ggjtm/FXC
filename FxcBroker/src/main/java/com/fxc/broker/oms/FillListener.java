package com.fxc.broker.oms;

import com.fxc.broker.model.Side;
import com.fxc.common.instrument.Instrument;
import java.math.BigDecimal;

/**
 * Notified after {@link OmsService} applies a fill to an account.
 *
 * <p>{@code OmsService.onExecutionReport} is the only place that knows the account, the instrument and
 * the fill together, which is why the P&amp;L series is built from this hook rather than from stored
 * rows: the broker does not historise marks, so a mark-to-market curve cannot be reconstructed after
 * the fact — it has to be sampled as fills happen (docs/DESIGN.md §6).
 */
@FunctionalInterface
public interface FillListener {

    /**
     * @param account    the account the fill belongs to
     * @param instrument the filled instrument
     * @param side       buy or sell
     * @param lastQty    quantity of this fill
     * @param lastPx     price of this fill
     * @param ts         when the fill was applied (epoch millis)
     */
    void onFill(String account, Instrument instrument, Side side, BigDecimal lastQty, BigDecimal lastPx, long ts);
}
