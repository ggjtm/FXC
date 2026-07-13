package com.webcohesion.ofx4j.domain.data.fxc;

import com.webcohesion.ofx4j.meta.Aggregate;
import com.webcohesion.ofx4j.meta.Element;

/**
 * One aggregated order-book price level in an FXC book snapshot ({@code FXCBOOKLVL}). Part of the
 * custom OFX market-data message set (FxcBroker/docs/stories/001). {@code side} is {@code BID} or
 * {@code OFFER}.
 */
@Aggregate("FXCBOOKLVL")
public class FxcBookLevel {

    private String side;
    private Double price;
    private Double size;

    public FxcBookLevel() {
    }

    public FxcBookLevel(String side, Double price, Double size) {
        this.side = side;
        this.price = price;
        this.size = size;
    }

    @Element(name = "SIDE", required = true, order = 10)
    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    @Element(name = "PRICE", required = true, order = 20)
    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    @Element(name = "SIZE", required = true, order = 30)
    public Double getSize() {
        return size;
    }

    public void setSize(Double size) {
        this.size = size;
    }
}
