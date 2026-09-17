/*
       Copyright 2020-2021 IBM Corp All Rights Reserved

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

package com.ibm.hybrid.cloud.sample.stocktrader.account.json;


import jakarta.nosql.Column;
import jakarta.nosql.Entity;
import jakarta.nosql.Id;

import java.util.UUID;

/** JSON-B POJO class representing an Account JSON object */
@Entity
public class Account {
    @Id() private String id;
    @Column private String owner;
    @Column private String loyalty;
    @Column private double balance;
    @Column private double commissions;
    @Column private int free;
    @Column private String sentiment;
    @Column private double nextCommission;
    @Column private String operation;

    public Account() { //default constructor
        setId(UUID.randomUUID().toString());
        setOwner("Someone Unknown");
        setLoyalty("Basic");
        setBalance(50.0);
        setCommissions(0.0);
        setFree(0);
        setSentiment("Unknown");
        setNextCommission(9.99);
    }

    public Account(String initialOwner) { //primary key constructor
        this();
        setOwner(initialOwner);
    }

    public Account(String initialOwner, String initialLoyalty, double initialBalance, double initialCommissions,
                     int initialFree, String initialSentiment, double initialNextCommission) {
        setId(UUID.randomUUID().toString());
        setOwner(initialOwner);
        setLoyalty(initialLoyalty);
        setBalance(initialBalance);
        setCommissions(initialCommissions);
        setFree(initialFree);
        setSentiment(initialSentiment);
        setNextCommission(initialNextCommission);
    }
    public Account(String id, String initialOwner, String initialLoyalty, double initialBalance, double initialCommissions,
                   int initialFree, String initialSentiment, double initialNextCommission) {
        setId(id);
        setOwner(initialOwner);
        setLoyalty(initialLoyalty);
        setBalance(initialBalance);
        setCommissions(initialCommissions);
        setFree(initialFree);
        setSentiment(initialSentiment);
        setNextCommission(initialNextCommission);
    }

    public String getId() {
        return id;
    }

    public void setId(String newId) {
        id = newId;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String newOwner) {
        owner = newOwner;
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

    public String getOperation() {
        return operation;
    }

    public void setOperation(String newOperation) {
        operation = newOperation;
    }

    public boolean equals(Object obj) {
        boolean isEqual = false;
        if ((obj != null) && (obj instanceof Account)) isEqual = toString().equals(obj.toString());
        return isEqual;
   }

    public String toString() {
        return "{\"_id\": \""+ id +"\", \"owner\": \""+owner+"\", \"loyalty\": \""+loyalty
                +"\", \"balance\": "+balance+", \"commissions\": "+commissions+", \"free\": "+free
                +", \"nextCommission\": "+nextCommission+", \"sentiment\": \""+sentiment+"\", \"operation\": \""+operation+"\"}";
    }
}
