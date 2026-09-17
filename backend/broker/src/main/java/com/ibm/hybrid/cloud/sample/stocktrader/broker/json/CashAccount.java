/*
       Copyright 2023 Kyndryl, All Rights Reserved

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


/** JSON-B POJO class representing a CashAccount JSON object */
public class CashAccount {
    private String owner;
    private double balance;
    private String currency;

    public CashAccount() { //default constructor
    }

    public CashAccount(String initialOwner) { //primary key constructor
        setOwner(initialOwner);
    }

    public CashAccount(String initialOwner, double initialBalance, String initialCurrency) {
        setOwner(initialOwner);
        setBalance(initialBalance);
        setCurrency(initialCurrency);
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String newOwner) {
        owner = newOwner;
    }

    public double getBalance() {
        return balance;
    }

    public void setBalance(double newBalance) {
        balance = newBalance;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String newCurrency) {
        currency = newCurrency;
    }

    public boolean equals(Object obj) {
        boolean isEqual = false;
        if ((obj != null) && (obj instanceof CashAccount)) isEqual = toString().equals(obj.toString());
        return isEqual;
   }

    public String toString() {
        return "{\"owner\": \""+owner+"\", \"balance\": "+balance+", \"currency\": \""+currency+"\"}";
    }
}
