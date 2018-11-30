package com.ib.client;

public class OrderStateWrapper {

    public static OrderState genOrderState() {
        return new OrderState();
    }

    public static OrderState genOrderState(String status, String initMargin, String maintMargin,
                                String equityWithLoan, double commission, double minCommission,
                                double maxCommission, String commissionCurrency, String warningText) {
        return new OrderState(status, initMargin, maintMargin, equityWithLoan, commission, minCommission,
                              maxCommission, commissionCurrency, warningText);
    }
}
