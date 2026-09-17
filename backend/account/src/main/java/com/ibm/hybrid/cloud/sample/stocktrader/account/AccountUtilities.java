/*
       Copyright 2020-2021 IBM Corp All Rights Reserved
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
import com.ibm.hybrid.cloud.sample.stocktrader.account.json.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jms.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class that wraps communication with various types of services in the cloud
 */
@ApplicationScoped
public class AccountUtilities {
    private static final Logger logger = Logger.getLogger(AccountUtilities.class.getName());
    //Our ODM rule will return its own values for levels, generally in all caps
    private static final String BASIC = "Basic";
    private static final String BRONZE = "Bronze";
    private static final String SILVER = "Silver";
    private static final String GOLD = "Gold";
    private static final String PLATINUM = "Platinum";
    private boolean odmBroken = false; //used to only report failures of calls to ODM once, rather than every time
    @Inject
    private ConnectionFactory jmsConnectionFactory;

    @ConfigProperty(name = "mq.queue", defaultValue = "LoyaltyLevelChange")
    private String queueName;

    @ConfigProperty(name = "messaging.enabled")
    private boolean useJMS;

    @Inject
    private Tracer tracer;

    static void logException(Throwable t) {
        logger.warning(t.getClass().getName() + ": " + t.getMessage());

        //only log the stack trace if the level has been set to at least INFO
        if (logger.isLoggable(Level.INFO)) {
            StringWriter writer = new StringWriter();
            t.printStackTrace(new PrintWriter(writer));
            logger.info(writer.toString());
        }
    }

    @WithSpan
    String invokeODM(ODMClient odmClient, String odmId, String odmPwd, String owner, double overallTotal, String oldLoyalty, String user) {
        String loyalty = null;
        ODMLoyaltyRule input = new ODMLoyaltyRule(overallTotal);
        try {
            String credentials = odmId + ":" + odmPwd;
            String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());

            Span callOdmSpan = tracer.spanBuilder("odmClient.getLoyaltyLevel(input)").startSpan();
            try (Scope scope = callOdmSpan.makeCurrent()) {
                //call the LoyaltyLevel business rule to get the current loyalty level of this portfolio
                logger.fine("Calling loyalty-level ODM business rule for " + owner);
                logger.fine(input.toString());
                ODMLoyaltyRule result = odmClient.getLoyaltyLevel(/*basicAuth,*/ input);

                loyalty = result.determineLoyalty();
                logger.fine("New loyalty level for " + owner + " is " + loyalty);
            } catch (Throwable t) {
                callOdmSpan.recordException(t);
                logger.warning("Error invoking ODM:" + t.getClass().getName() + ": " + t.getMessage() + ".  Loyalty level will remain unchanged.");
                if (!odmBroken) logException(t);
                odmBroken = true; //so logs aren't full of this stack trace on every getAccount
            } finally {
                callOdmSpan.end();
            }

            if ((oldLoyalty == null) || (loyalty == null)) return loyalty;
            if (!oldLoyalty.equalsIgnoreCase(loyalty)) try {
                logger.info("Change in loyalty level detected for owner: " + owner);
                logger.fine("Should we put a JMS message? " + useJMS);

                if (useJMS) {
                    LoyaltyChange message = new LoyaltyChange(owner, oldLoyalty, loyalty);

                    if (user != null) message.setId(user); // User in jwt

                    logger.fine(message.toString());
                    Span invokeJMSSpan = tracer.spanBuilder("invokeJMS(message)").startSpan();
                    try (Scope scope = invokeJMSSpan.makeCurrent()) {
                        invokeJMS(message);
                    } catch (Throwable t) {
                        logException(t);
                        invokeJMSSpan.recordException(t);
                        logger.warning("Unable to send message to JMS provider.  Continuing without notification of change in loyalty level.");
                        if (t instanceof JMSException) {
                            Exception linked = ((JMSException) t).getLinkedException(); //get the nested exception from MQ
                            if (linked != null) logException(linked);
                        }
                    } finally {
                        invokeJMSSpan.end();
                    }
                }
            } catch (Throwable t) { //in case MQ is not configured, just log the exception and continue
                logger.warning("An unexpected error occurred.  Continuing without notification of change in loyalty level.");
                logException(t);
            }
        } catch (Throwable t) {
            logger.warning("Unable to get loyalty level, via " + input.toString() + ".  Using cached value instead");
            logException(t);
            loyalty = oldLoyalty;
        }
        return loyalty;
    }

    /**
     * Use the Watson Tone Analyzer to determine the user's sentiment
     */
    @WithSpan
    Feedback invokeWatson(WatsonClient watsonClient, String watsonId, String watsonPwd, WatsonInput input) {
        String sentiment = "Unknown";
        try {
            String credentials = watsonId + ":" + watsonPwd; //Watson accepts basic auth
            String authorization = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());

            logger.info("Calling Watson Tone Analyzer");

            WatsonOutput watson = watsonClient.getTone(authorization, input);
            sentiment = watson.determineSentiment();
        } catch (Throwable t) {
            logger.warning("Error from Watson, with following input: " + input.toString());
            logException(t);
        }

        int freeTrades = 1;
        String message = "Thanks for providing feedback.  Have a free trade on us!";

        if ("Anger".equalsIgnoreCase(sentiment)) {
            logger.info("Tone is angry");
            freeTrades = 3;
            message = "We're sorry you are upset.  Have three free trades on us!";
        } else if ("Unknown".equalsIgnoreCase(sentiment)) {
            logger.info("Tone is unknown");
            freeTrades = 0;
            message = "Error communicating with the Watson Tone Analyzer";
        }

        Feedback feedback = new Feedback(message, freeTrades, sentiment);
        return feedback;
    }

    /**
     * Send a JSON message to our notification queue.
     */
    @WithSpan
    void invokeJMS(Object json) throws JMSException {
        if (jmsConnectionFactory != null) {
            logger.fine("Preparing to send a JMS message.");
            // try-with-resources will close the context automatically
            try (JMSContext jmsContext = jmsConnectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                Queue queue = jmsContext.createQueue(queueName);

                String contents = json.toString();
                TextMessage message = jmsContext.createTextMessage(contents);

                logger.info("Sending " + contents + " to " + queue.getQueueName());

                //"mqclient" group needs "put" authority on the queue for next two lines to work

                jmsContext.createProducer().setDeliveryMode(DeliveryMode.PERSISTENT).send(queue, message);
            } catch (JMSRuntimeException jmsre) {
                logger.warning("Error creating JMS Context");
                logException(jmsre);
                jmsre.printStackTrace();
                throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode(), jmsre);
            }
            logger.info("JMS Message sent successfully!"); //exception would have occurred otherwise
        } else {
            logger.warning("Unable to inject JMS ConnectionFactory - check your MQ Broker configuration. No JMS message will be sent.");
        }
    }

    double getCommission(String loyalty) {
        //TODO: turn this into an ODM business rule or a FaaS function (such as in AWS Lambda)
        double commission = 9.99;
        logger.fine("Determining commission - loyalty level = " + loyalty);
        if (loyalty != null) {
            if (loyalty.equalsIgnoreCase(BRONZE)) {
                commission = 8.99;
            } else if (loyalty.equalsIgnoreCase(SILVER)) {
                commission = 7.99;
            } else if (loyalty.equalsIgnoreCase(GOLD)) {
                commission = 6.99;
            } else if (loyalty.equalsIgnoreCase(PLATINUM)) {
                commission = 5.99;
            }
        }
        logger.fine("Returning commission: " + commission);

        return commission;
    }
}
