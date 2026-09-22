/*
       Copyright 2019-2021 IBM Corp, All Rights Reserved
       Copyright 2023-2024 Kyndryl, All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json;

import java.math.BigDecimal;

/** JSON-B POJO class representing an order submission request */
public class OrderRequest {
    private String clientOrderId;
    private String clientId;
    private String symbol;
    private String side;
    //quantity and limitPrice are boxed so an absent JSON key stays null: primitives would
    //arrive as 0, making "field is required" indistinguishable from "field must be greater
    //than zero", and the two cases owe the caller different messages.
    private Long quantity;
    private BigDecimal limitPrice;


    public OrderRequest() {
    }

    public OrderRequest(String initialClientOrderId, String initialClientId, String initialSymbol,
            String initialSide, Long initialQuantity, BigDecimal initialLimitPrice) {
        setClientOrderId(initialClientOrderId);
        setClientId(initialClientId);
        setSymbol(initialSymbol);
        setSide(initialSide);
        setQuantity(initialQuantity);
        setLimitPrice(initialLimitPrice);
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public void setClientOrderId(String newClientOrderId) {
        clientOrderId = newClientOrderId;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String newClientId) {
        clientId = newClientId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String newSymbol) {
        symbol = newSymbol;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String newSide) {
        side = newSide;
    }

    public Long getQuantity() {
        return quantity;
    }

    public void setQuantity(Long newQuantity) {
        quantity = newQuantity;
    }

    public BigDecimal getLimitPrice() {
        return limitPrice;
    }

    public void setLimitPrice(BigDecimal newLimitPrice) {
        limitPrice = newLimitPrice;
    }
}
