/*
       Copyright 2020-2022 IBM Corp All Rights Reserved
       Copyright 2022-2024 Kyndryl Corp, All Rights Reserved

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

package com.ibm.hybrid.cloud.sample.stocktrader.account;

import com.ibm.hybrid.cloud.sample.stocktrader.account.client.ODMClient;
import com.ibm.hybrid.cloud.sample.stocktrader.account.client.WatsonClient;
import com.ibm.hybrid.cloud.sample.stocktrader.account.db.AccountRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.account.json.Account;
import com.ibm.hybrid.cloud.sample.stocktrader.account.json.Feedback;
import com.ibm.hybrid.cloud.sample.stocktrader.account.json.WatsonInput;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.common.annotation.NonBlocking;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.security.RolesAllowed;
import jakarta.data.Order;
import jakarta.data.Sort;
import jakarta.data.page.PageRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.With;
import org.eclipse.microprofile.auth.LoginConfig;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * This microservice takes care of non-stock related attributes of a customer's account.  This includes
 * commissions, account balance, sentiment, free trades, and loyalty level determination.  This version
 * persists data to a CouchDB-derived non-SQL datastore.
 */
@Path("/account")
@LoginConfig(authMethod = "MP-JWT", realmName = "jwt-jaspi")
@ApplicationScoped
// Run everything in an IO Thread which prevents blocking major threads
@NonBlocking
public class AccountService {
    private static final Logger logger = Logger.getLogger(AccountService.class.getName());

    private static final double DONT_RECALCULATE = -1.0;
    private static final int CONFLICT = 409;         //odd that JAX-RS has no ConflictException
    private static final String FAIL = "FAIL";      //trying to create an account with this name will always throw a 400
    private static final String DELAY = "com.ibm.hybrid.cloud.sample.stocktrader.account.delayUpdate";

    //we'll define an mpMetrics gauge per loyalty level
    private static final String BASIC = "basic";
    private static final String BRONZE = "bronze";
    private static final String SILVER = "silver";
    private static final String GOLD = "gold";
    private static final String PLATINUM = "platinum";
    private static final String UNKNOWN = "unknown";
    private static final String DOLLARS = "USD";
    private final AccountRepository accountDbRepository;
    private final int basic = 0;
    private final int bronze = 0;
    private final int silver = 0;
    private final int gold = 0;
    private final int platinum = 0;
    private final int unknown = 0; //loyalty level counts
    @Inject
    JsonWebToken jwt;
    @ConfigProperty(name = "odm.id", defaultValue = "odmAdmin")
    String odmId;
    @ConfigProperty(name = "odm.pwd", defaultValue = "odmAdmin")
    String odmPwd;
    @ConfigProperty(name = "watson.id", defaultValue = "apikey")
    String watsonId;
    @ConfigProperty(name = "watson.pwd")
    String watsonPwd; //if using an API Key, it goes here
    private boolean delayUpdate = false;
    @Inject
    private AccountUtilities utilities;
    @RestClient
    private ODMClient odmClient;
    @RestClient
    private WatsonClient watsonClient;
    @Inject
    private Tracer tracer;

    @Inject
    public AccountService(AccountRepository accountDbRepository) {
        this.accountDbRepository = accountDbRepository;
    }


    // Injection/initialization takes place after the class is instantiated, so we create the connection to CouchDB/Cloudant
    // afterward the no-arg constructor is called.
    //	https://stackoverflow.com/questions/3406555/why-use-postconstruct#3406631
    @PostConstruct
    public void postConstruct() {
        logger.fine("Constructing Watson and ODM Clients");
        synchronized (this) {
            if (watsonClient != null) {
                logger.info("Watson initialization completed successfully!");
            } else {
                logger.warning("WATSON config properties are unset");
            }

            if (odmClient != null) {
                logger.info("ODM initialization complete");
            } else {
                logger.warning("ODM config properties are unset");
            }
        }
    }

    @GET
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"StockTrader", "StockViewer"})
    @WithSpan
    //This is currently blocking due to long execution of findByOwnerInOrderByOwnerAsc
    //With lots of accounts this will take over 3 seconds to execute, which throws a warning in the logs
    //TODO switch to Reactive Patterns in Jakarta Data 1.1+ & JakartaEE 12
    public List<Account> getAccounts(@Parameter(description = "Which page to retrieve", required = false) @QueryParam("page") @DefaultValue("1") int pageNumber,
                                     @Parameter(description = "How many accounts per page", required = false) @QueryParam("pageSize") @DefaultValue("10") int pageSize,
                                     @Parameter(description = "List of owner names to retrieve account details for", required = false) @QueryParam("owners") List<String> owners) {
        Objects.requireNonNull(accountDbRepository, "accountDbRepository is not initialized so returning empty array.  Investigate why the CDI injection failed for details");
        logger.fine("Entering getAllAccounts");
        logger.fine("Page Number, " + pageNumber + " Page size: " + pageSize + ", owners to find: " + owners);
        List<Account> pageOfAccounts = null; //= new ArrayList<>(15);
        var pageable = PageRequest.ofPage(pageNumber).size(pageSize);
        Span getAllAccountsSpan = tracer.spanBuilder("accountDbRepository.findAll("+owners+").toList()").startSpan();
            getAllAccountsSpan.setAttribute("findAll", true);
        try (Scope scope = getAllAccountsSpan.makeCurrent()) {
            if(owners.isEmpty()) {
                getAllAccountsSpan.setAttribute("findAll", true);
                pageOfAccounts = accountDbRepository.findAll(pageable, Order.by(Sort.asc("owner"))).content();
            } else {
                getAllAccountsSpan.setAttribute("findAll", false);
                // This method of using a parllelStream to individually query is 23x faster (0.17s) than the
                // findByOwnerInOrderByOwnerAsc (3-4s) method.
                pageOfAccounts = owners.parallelStream()
                        .map(o-> accountDbRepository.findByOwner(o).get()).collect(Collectors.toList());
                //pageOfAccounts = accountDbRepository.findByOwnerInOrderByOwnerAsc(owners, pageable);
            }
        } catch (Throwable t) {
            logException(t);
            getAllAccountsSpan.recordException(t);
            logger.severe("Error getting page of accounts");
        } finally {
            getAllAccountsSpan.end();
        }

        if (pageOfAccounts != null && !pageOfAccounts.isEmpty()) {
            logger.fine("Returning " + pageOfAccounts.size() + " accounts");
            if (logger.isLoggable(Level.FINE)) {
                for (int index = 0; index < pageOfAccounts.size(); index++) {
                    Account account = pageOfAccounts.get(index);
                    logger.fine("account[" + index + "]=" + account);
                }
            }
        }

        /* Commenting out until https://github.com/OpenLiberty/open-liberty/issues/22592 is addressed
		try {
			basic=0; bronze=0; silver=0; gold=0; platinum=0; unknown=0; //reset loyalty level counts
			for (int index=0; index<size; index++) {
				Account account = accountArray[index];
				logger.fine("account["+index+"]="+account);

				String loyaltyLevel = account.getLoyalty();
				if (loyaltyLevel!=null) {
					if (loyaltyLevel.equalsIgnoreCase(BASIC)) basic++;
					else if (loyaltyLevel.equalsIgnoreCase(BRONZE)) bronze++;
					else if (loyaltyLevel.equalsIgnoreCase(SILVER)) silver++;
					else if (loyaltyLevel.equalsIgnoreCase(GOLD)) gold++;
					else if (loyaltyLevel.equalsIgnoreCase(PLATINUM)) platinum++;
					else unknown++;
				}
			}
		} catch (Throwable t) {
			logException(t);
		}
*/

        return pageOfAccounts;
    }

//    @GET
//    @Path("/byOwner")
//    @Produces(MediaType.APPLICATION_JSON)
//    @RolesAllowed({"StockTrader", "StockViewer"})
//    public List<Account> getAccountsByOwner(@QueryParam("owners") List<String> owners){
//        List<Account> accountList = null;
//        long size = 0;
//
//        try {
//            logger.fine("Entering getAccounts");
//            if (accountDbRepository != null) {
//                Span allAccountsByOwners = tracer.spanBuilder("repository.findAll(page).collect(Collectors.toList())").startSpan();
//                try(Scope childScope = allAccountsByOwners.makeCurrent()) {
//                     accountList = accountDbRepository.findByOwnerIn(owners);
//                } finally {
//                    allAccountsByOwners.end();
//                }
//                size = accountList.size();
//            } else {
//                logger.warning("accountDB is null, so returning empty array.  Investigate why the CDI injection failed for details");
//            }
//        } catch (Throwable t) {
//            logger.warning("Failure getting accounts");
//            logException(t);
//        }
//        if(accountList != null) {
//            logger.fine("Returning " + size + " accounts");
//            if (logger.isLoggable(Level.FINE)) for (int index = 0; index < size; index++) {
//                Account account = accountList.get(index);
//                logger.fine("account[" + index + "]=" + account);
//            }
//        }
//        return accountList;
//    }

    @POST
    @Path("/{owner}")
    @Produces(MediaType.APPLICATION_JSON)
//	@Counted(name="accounts", description="Number of accounts created in the Stock Trader application")
    @RolesAllowed({"StockTrader"})
    @WithSpan
    public Account createAccount(@PathParam("owner") String owner) {
        Account account;
        if (owner != null) try {
            if (owner.equalsIgnoreCase(FAIL)) {
                logger.warning("Throwing a 400 error for owner: " + owner);
                throw new BadRequestException("Invalid value for account owner: " + owner);
            }

            Span findByOwnerQuerySpan = tracer.spanBuilder("accountDbRepository.findByOwner(owner).findFirst()").startSpan();
            try (Scope childScope = findByOwnerQuerySpan.makeCurrent()) {
                boolean ownerExistInRepo = accountDbRepository.findByOwner(owner).isPresent();
                if (ownerExistInRepo) {
                    logger.warning("Account already exists for: " + owner);
                    throw new Exception();
                }
            } catch (Throwable t) {
                findByOwnerQuerySpan.recordException(t);
                throw new WebApplicationException("Account already exists for " + owner + "!", CONFLICT);
            } finally {
                findByOwnerQuerySpan.end();
            }

            //loyalty="Basic", balance=50.0, commissions=0.0, free=0, sentiment="Unknown", nextCommission=9.99
            account = new Account(owner);

            logger.fine("Creating account for " + owner);
            Span createAccountSpan = tracer.spanBuilder("accountDbRepository.save(account)").startSpan();
            try (Scope childScope = createAccountSpan.makeCurrent()) {
                account = accountDbRepository.save(account);
            } catch (Throwable t) {
                logException(t);
                createAccountSpan.recordException(t);
                account = null;
            } finally {
                createAccountSpan.end();
            }

            if (account != null) {
                String id = account.getId();
                logger.fine("Created new account for " + owner + " with id " + id);
            } else {
                logger.warning("Failed to get response from accountDbRepository.save()"); //shouldn't get here - exception should have been thrown if the save failed
            }
            logger.fine("Account created successfully: " + owner);
        } catch (Throwable t) {
            logger.warning("Failure to create account for " + owner);
            logException(t);
            account = null;
        }
        else {
            logger.warning("Owner is null in createAccount");
            account = null;
        }

        return account;
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"StockTrader", "StockViewer"})
    @Blocking
    @WithSpan
    public Account getAccount(@PathParam("id") String id, @QueryParam("total") double total) {
        Optional<Account> accountOptional;
        Account account = null;
        logger.fine("Entering getAccount for id " + id);
        try {
            // TODO add a span around this call
            accountOptional = accountDbRepository.findById(id);
            if (accountOptional.isPresent()) {
                account = accountOptional.get();
                if (total == DONT_RECALCULATE) {
                    logger.fine("Skipping recalculation of loyalty level and next commission as requested");
                } else {
                    String owner = account.getOwner();
                    String oldLoyalty = account.getLoyalty();

                    logger.fine("Invoking external business rule for " + id);
                    String loyalty = null;
                    Span callODMSpan = tracer.spanBuilder("utilities.invokeODM()").startSpan();
                    try (Scope scope = callODMSpan.makeCurrent()) {
                        //this can be a call to either IBM ODM, or my simple Lambda function alternative, depending on the URL configured in the CR yaml
                        loyalty = utilities.invokeODM(odmClient, odmId, odmPwd, owner, total, oldLoyalty, jwt.getName());
                    } catch (Throwable t) {
                        callODMSpan.recordException(t);
                        logger.warning("Error determining account level for " + id);
                        logException(t);
                    } finally {
                        callODMSpan.end();
                    }
                    if ((loyalty != null) && !loyalty.equalsIgnoreCase(oldLoyalty)) { //don't rev the doc if nothing's changed
                        account.setLoyalty(loyalty);

                        int free = account.getFree();
                        account.setNextCommission(free > 0 ? 0.0 : utilities.getCommission(loyalty));

                        if (!delayUpdate) { //if called from updateAccount, let it drive the update to Cloudant
                            logger.fine("Calling repository.save(account) for " + id + " in getAccount due to new loyalty level");
                            Span updateRepo = tracer.spanBuilder("accountDbRepository.save(account)").startSpan();
                            try (Scope scope = updateRepo.makeCurrent()) {
                                accountDbRepository.save(account);
                            } catch (Throwable t) {
                                callODMSpan.recordException(t);
                                logger.warning("Error saving account level update for " + id);
                                logException(t);
                            } finally {
                                updateRepo.end();
                            }
                        }
                    }
                }
                logger.fine("Returning " + account);
            } else {
                logger.warning("Got null in getAccount for " + id + ", rather than expected NoDocumentException");
            }
        } catch (Throwable t) {
            logger.warning("Unknown error finding account for " + id);
            logException(t);
        }

        return account;
    }

    @PUT
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"StockTrader"})
    @Blocking
    @WithSpan
    public Account updateAccount(@PathParam("id") String id, @QueryParam("total") double total) {
        logger.fine("Entering updateAccount");
        this.delayUpdate = true;

        Account account = null;
        try {
            account = getAccount(id, total); //this computes new loyalty, etc.

            if (account != null) {
                String owner = account.getOwner();
                String loyalty = account.getLoyalty();

                double commission = utilities.getCommission(loyalty);

                int free = account.getFree();
                if (free > 0) { //use a free trade if available
                    free--;
                    commission = 0.0;

                    logger.info("Using free trade for " + owner);
                    account.setFree(free);
                } else {
                    double commissions = account.getCommissions();
                    commissions += commission;

                    double balance = account.getBalance();
                    balance -= commission;

                    logger.fine("Charging commission of $" + commission + " for " + owner);
                    account.setCommissions(commissions);
                    account.setBalance(balance);
                }

                logger.fine("Updating account into Cloudant: " + account);
                Span saveToDatabaseSpan = tracer.spanBuilder("accountDbRepository.save(account)").startSpan();
                try (Scope scope = saveToDatabaseSpan.makeCurrent()) {
                    accountDbRepository.save(account);
                } catch (Throwable t) {
                    logException(t);
                    saveToDatabaseSpan.recordException(t);
                    logger.severe("Error getting accounts");
                } finally {
                    saveToDatabaseSpan.end();
                }
            } else {
                logger.warning("Account is null for " + id + " in updateAccount");
            }
        } catch (Throwable t) {
            logger.warning("Error in updateAccount for " + id);
            logException(t);
        }

        return account;
    }

    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"StockTrader"})
    @WithSpan
    public Account deleteAccount(@PathParam("id") String id) {
        Optional<Account> accountOptional;
        Account account = null;
        logger.fine("Entering deleteAccount for " + id);
        try {
            accountOptional = accountDbRepository.findById(id);

            if (accountOptional.isPresent()) {
                account = accountOptional.get();
                String owner = account.getOwner();
                logger.fine("Deleting account for " + owner);

                accountDbRepository.deleteById(id);

                logger.fine("Successfully deleted account for " + owner); //exception would have been thrown otherwise
            } else {
                logger.warning("Account not found for " + id + " in deleteAccount");
            }
        } catch (Throwable t) {
            logger.warning("Error occurred in deleteAccount for " + id);
            logException(t);
        }

        return account; //maybe this method should return void instead?
    }

    @POST
    @Path("/{id}/feedback")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"StockTrader"})
    @WithSpan
    public Feedback submitFeedback(@PathParam("id") String id, WatsonInput input) {
        String sentiment = "Unknown";
        Feedback feedback = null;
        try {
            logger.fine("Getting account for " + id + " in submitFeedback");
            Account account = getAccount(id, DONT_RECALCULATE);

            if (account != null) {
                int freeTrades = account.getFree();

                feedback = utilities.invokeWatson(watsonClient, watsonId, watsonPwd, input);
                freeTrades += feedback.getFree();

                account.setFree(freeTrades);
                account.setSentiment(feedback.getSentiment());
                accountDbRepository.save(account);

                logger.info("Returning feedback: " + feedback);
            } else {
                logger.warning("Account not found for " + id + " in submitFeedback");
            }
        } catch (Throwable t) {
            logger.warning("Failure submitting feedback for " + id);
            logException(t);
        }

        return feedback;
    }

    /* Commenting out until https://github.com/OpenLiberty/open-liberty/issues/22592 is addressed
        @Gauge(name="account_loyalty", tags="level=basic", displayName="Basic", unit=MetricUnits.NONE)
        public int getBasic() {
            return basic;
        }

        @Gauge(name="account_loyalty", tags="level=bronze", displayName="Bronze", unit=MetricUnits.NONE)
        public int getBronze() {
            return bronze;
        }

        @Gauge(name="account_loyalty", tags="level=silver", displayName="Silver", unit=MetricUnits.NONE)
        public int getSilver() {
            return silver;
        }

        @Gauge(name="account_loyalty", tags="level=gold", displayName="Gold", unit=MetricUnits.NONE)
        public int getGold() {
            return gold;
        }

        @Gauge(name="account_loyalty", tags="level=platinum", displayName="Platinum", unit=MetricUnits.NONE)
        public int getPlatinum() {
            return platinum;
        }

        @Gauge(name="account_loyalty", tags="level=unknown", displayName="Unknown", unit=MetricUnits.NONE)
        public int getUnknown() {
            return unknown;
        }
    */
    private void logException(Throwable t) {
        logger.warning(t.getClass().getName() + ": " + t.getMessage());

        //only log the stack trace if the level has been set to at least INFO
        if (logger.isLoggable(Level.INFO)) {
            StringWriter writer = new StringWriter();
            t.printStackTrace(new PrintWriter(writer));
            logger.severe(writer.toString());
        }
    }
}
