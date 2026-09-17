/*
       Copyright 2017-2021 IBM Corp All Rights Reserved

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

package com.ibm.hybrid.cloud.sample.stocktrader.messaging;

//Subpackages for this microservice
import com.ibm.hybrid.cloud.sample.stocktrader.messaging.client.NotificationClient;
import com.ibm.hybrid.cloud.sample.stocktrader.messaging.json.LoyaltyChange;
import com.ibm.hybrid.cloud.sample.stocktrader.messaging.json.NotificationResult;

//JSON Web Token (JWT) construction
import com.ibm.websphere.security.jwt.JwtBuilder;
import com.ibm.websphere.security.jwt.JwtToken;

//Standard I/O classes
import java.io.PrintWriter;
import java.io.StringWriter;

//Logging (JSR 47)
import java.util.logging.Level;
import java.util.logging.Logger;

//CDI 2.0
import javax.inject.Inject;
import javax.enterprise.context.ApplicationScoped;

//EJB 3.2
import javax.ejb.MessageDriven;

//JMS 2.0
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;

//JSON-B (JSR 367).  This largely replaces the need for JSON-P
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;

//mpRestClient 1.2
import org.eclipse.microprofile.rest.client.inject.RestClient;


/**
 * Message-Driven Bean implementation class for: MessagingMDB
 */
@MessageDriven(name = "MessagingMDB", mappedName = "jms/StockTrader/NotificationQueue")
@ApplicationScoped //CDI scope
public class MessagingMDB implements MessageListener {
	private static Logger logger = Logger.getLogger(MessagingMDB.class.getName());

	private @Inject @RestClient NotificationClient notificationClient;

	// Override Notification Client URL if config map is configured to provide URL
	static {
		String mpUrlPropName = NotificationClient.class.getName() + "/mp-rest/url";
		String notificationURL = System.getenv("NOTIFICATION_URL");
		if ((notificationURL != null) && !notificationURL.isEmpty()) {
			logger.info("Using Notification URL from config map: " + notificationURL);
			System.setProperty(mpUrlPropName, notificationURL);
		} else {
			logger.info("Notification URL not found from env var from config map, so defaulting to value in jvm.options: " + System.getProperty(mpUrlPropName));
		}
	}

	/**
	 * @see MessageListener#onMessage(Message)
	 */
	public void onMessage(Message message) {
		if (message instanceof TextMessage) try {
			TextMessage text = (TextMessage) message;
			String payload = text.getText();
			logger.info("Sending "+payload+" to the Notification microservice");

			Jsonb jsonb = JsonbBuilder.create();
			LoyaltyChange loyaltyChange = jsonb.fromJson(payload, LoyaltyChange.class);
			String owner = loyaltyChange.getOwner();
			String id = loyaltyChange.getId();
			String jwt = createJWT(id); //temporarily create the JWT manually.  soon we'll get from new OIDC microservice instead

			NotificationResult result = notificationClient.notify("Bearer "+jwt, owner, loyaltyChange);
			logger.info("Received the following response from the Notification microservice: "+result);
		} catch (Throwable t) {
			logger.warning("An error occurred processing a JMS message from the queue");
			logException(t);
		} else {
			logger.warning("onMessage received a non-TextMessage!");
		}
	}

	/**
	 * Create Json Web Token.
	 * return: the base64 encoded and signed token. 
	 */
	private static String createJWT(String userName) {
		String jwtTokenString = null;		
		try {
			// create() uses default settings.  
			// For other settings, specify a JWTBuilder element in server.xml
			// and call create(builder id)
			JwtBuilder builder = JwtBuilder.create("defaultJWT");

			// Put the user info into a JWT Token
			builder.subject(userName);
			builder.claim("upn", userName);

			//builder.claim("groups", groups);

			JwtToken theToken = builder.buildJwt();			
			jwtTokenString = theToken.compact();
		} catch (Exception e) {			
			logException(e);
			throw new RuntimeException(e);
		}
		return jwtTokenString;
	}

	private static void logException(Throwable t) {
		logger.warning(t.getClass().getName()+": "+t.getMessage());

		//only log the stack trace if the level has been set to at least FINE
		if (logger.isLoggable(Level.FINE)) {
			StringWriter writer = new StringWriter();
			t.printStackTrace(new PrintWriter(writer));
			logger.fine(writer.toString());
		}
	}
}
