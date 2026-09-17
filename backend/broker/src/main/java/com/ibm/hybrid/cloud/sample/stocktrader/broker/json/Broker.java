/*
       Copyright 2017-2021 IBM Corp All Rights Reserved
       Copyright 2022-2024 Kyndrul, All Rights Reserved

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

package com.ibm.hybrid.cloud.sample.stocktrader.broker.json;

import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Iterator;

//JSON-P 1.0 (JSR 353).  This replaces my old usage of IBM's JSON4J (com.ibm.json.java.JSONObject)
import jakarta.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;


/** JSON-B POJO class representing a Broker JSON object */
public class Broker {
    private static String UNKNOWN_STRING = "Unknown";
    private static double UNKNOWN_DOUBLE = -1.0;
    private static int    UNKNOWN_INT    = -1;

    private String owner;
    private double total;
    private String loyalty;
    private double balance;
    private double commissions;
    private int free;
    private String sentiment;
    private double nextCommission;
    private double cashAccountBalance;
    private String cashAccountCurrency;
    private JsonObject stocks;
    private NumberFormat currencyFormatter = null;
    private static double ERROR = -1.0;


    public Broker() { //default constructor
    }

    public Broker(String initialOwner) { //primary key constructor
        setOwner(initialOwner);
    }

    public Broker(String initialOwner, double initialTotal, String initialLoyalty, double initialBalance,
                     double initialCommissions, int initialFree, String initialSentiment, double initialNextCommission) {
        setOwner(initialOwner);
        setTotal(initialTotal);
        setLoyalty(initialLoyalty);
        setBalance(initialBalance);
        setCommissions(initialCommissions);
        setFree(initialFree);
        setSentiment(initialSentiment);
        setNextCommission(initialNextCommission);
    }

    public Broker(Portfolio portfolio, Account account) {
        if (portfolio!=null) {
            setOwner(portfolio.getOwner());
            setTotal(portfolio.getTotal());
            setStocks(portfolio.getStocks());
        }

        if (account!=null) {
            setLoyalty(account.getLoyalty());
            setBalance(account.getBalance());
            setCommissions(account.getCommissions());
            setFree(account.getFree());
            setSentiment(account.getSentiment());
            setNextCommission(account.getNextCommission());
        } else {
            setLoyalty(UNKNOWN_STRING);
            setBalance(UNKNOWN_DOUBLE);
            setCommissions(UNKNOWN_DOUBLE);
            setFree(UNKNOWN_INT);
            setSentiment(UNKNOWN_STRING);
            setNextCommission(UNKNOWN_DOUBLE);
        }
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String newOwner) {
        owner = newOwner;
    }

    public double getTotal() {
        return total;
    }

    public void setTotal(double newTotal) {
        total = newTotal;
    }

    public String getLoyalty() {
        return loyalty;
    }

    public void setLoyalty(String newLoyalty) {
        loyalty = newLoyalty;
    }

    public double getBalance() {
        return balance;
    }

    public void setBalance(double newBalance) {
        balance = newBalance;
    }

    public double getCommissions() {
        return commissions;
    }

    public void setCommissions(double newCommissions) {
        commissions = newCommissions;
    }

    public int getFree() {
        return free;
    }

    public void setFree(int newFree) {
        free = newFree;
    }

    public String getSentiment() {
        return sentiment;
    }

    public void setSentiment(String newSentiment) {
        sentiment = newSentiment;
    }

    public double getNextCommission() {
        return nextCommission;
    }

    public void setNextCommission(double newNextCommission) {
        nextCommission = newNextCommission;
    }

    public double getCashAccountBalance() {
        return cashAccountBalance;
    }

    public void setCashAccountBalance(double newCashAccountBalance) {
        cashAccountBalance = newCashAccountBalance;
    }

    public String getCashAccountCurrency() {
        return cashAccountCurrency;
    }

    public void setCashAccountCurrency(String newCashAccountCurrency) {
        cashAccountCurrency = newCashAccountCurrency;
    }

    public JsonObject getStocks() {
        return stocks;
    }

    public void setStocks(JsonObject newStocks) {
        stocks = newStocks;
    }

    public void addStock(Stock newStock) {
        if (newStock != null) {
            String symbol = newStock.getSymbol();
            if (symbol != null) {
                JsonObjectBuilder stocksBuilder = Json.createObjectBuilder();
            
                if (stocks != null) { //JsonObject is immutable, so copy current "stocks" into new builder
                    Iterator<String> iter = stocks.keySet().iterator();
                    while (iter.hasNext()) {
                        String key = iter.next();
                        JsonObject obj = stocks.getJsonObject(key);
                        stocksBuilder.add(key, obj);
                    }
                }

                //can only add a JSON-P object to a JSON-P object; can't add a JSON-B object.  So converting...
                JsonObjectBuilder builder = Json.createObjectBuilder();

                builder.add("symbol", symbol);
                builder.add("shares", newStock.getShares());
                builder.add("commission", newStock.getCommission());
                builder.add("price", newStock.getPrice());
                builder.add("total", newStock.getTotal());
                builder.add("date", newStock.getDate());

                JsonObject stock = builder.build();

                stocksBuilder.add(symbol, stock); //might be replacing an item; caller needs to do any merge (like updatePortfolio does)
                stocks = stocksBuilder.build();
            }
        }
    }

    public boolean equals(Object obj) {
        boolean isEqual = false;
        if ((obj != null) && (obj instanceof Broker)) isEqual = toString().equals(obj.toString());
        return isEqual;
   }

    public String toString() {
        if (currencyFormatter == null) {
            currencyFormatter = NumberFormat.getNumberInstance();
            currencyFormatter.setMinimumFractionDigits(2);
            currencyFormatter.setMaximumFractionDigits(2);
            currencyFormatter.setRoundingMode(RoundingMode.HALF_UP);
        }

        return "{\"owner\": \""+owner+"\", \"total\": "+currencyFormatter.format(total)+", \"loyalty\": \""+loyalty
               +"\", \"balance\": "+currencyFormatter.format(balance)+", \"commissions\": "+currencyFormatter.format(commissions)
               +", \"free\": "+free+", \"nextCommission\": "+currencyFormatter.format(nextCommission)
               +", \"sentiment\": \""+sentiment+"\", \"stocks\": "+(stocks!=null?getStocksJSON():"{}")+"}";
    }

    private String getStocksJSON() {
        StringBuffer json = new StringBuffer();
        Iterator<String> keys = stocks.keySet().iterator();

        boolean first = true;
        while (keys.hasNext()) {
            if (first) {
                json.append("{");
            } else {
                json.append(", ");
                first = false;
            }
            String key = keys.next();
            JsonObject stock = stocks.getJsonObject(key);

            String symbol = stock.getString("symbol");
            int shares = stock.getInt("shares");
            JsonNumber number = stock.getJsonNumber("price");
            double price = (number != null) ? number.doubleValue() : ERROR;
            String date = stock.getString("date");
            number = stock.getJsonNumber("total");
            double totalValue = (number != null) ? number.doubleValue() : ERROR;
            number = stock.getJsonNumber("commission");
            double commission = (number != null) ? number.doubleValue() : ERROR;
            
            json.append("\"key\": {\"symbol\": \""+symbol+"\", \"shares\": "+shares+", \"price\": "+currencyFormatter.format(price)
                +", \"date\": \""+date+"\", \"total\": "+currencyFormatter.format(totalValue)+", \"commission\": "+currencyFormatter.format(commission)+"}");
        }

        return json.append("}").toString();
    }
}
