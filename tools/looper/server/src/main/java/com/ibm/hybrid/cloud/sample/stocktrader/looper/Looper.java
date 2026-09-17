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

package com.ibm.hybrid.cloud.sample.stocktrader.looper;

import com.ibm.hybrid.cloud.sample.stocktrader.looper.client.BrokerClient;
import com.ibm.hybrid.cloud.sample.stocktrader.looper.json.Broker;

//JSON Web Token (JWT) construction
import com.ibm.websphere.security.jwt.InvalidBuilderException;
import com.ibm.websphere.security.jwt.JwtBuilder;
import com.ibm.websphere.security.jwt.JwtToken;

//String manipulation for logging
import java.io.PrintWriter;
import java.io.StringWriter;

//Logging (JSR 47)
import java.util.logging.Level;
import java.util.logging.Logger;

//CDI 1.2
import javax.inject.Inject;
import javax.enterprise.context.RequestScoped;

//JAX-RS 2.0  (JSR 339)
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.GET;
import javax.ws.rs.DELETE;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.Path;

//mpConfig 1.2
import org.eclipse.microprofile.config.inject.ConfigProperty;

//mpRestClient 1.0
import org.eclipse.microprofile.rest.client.inject.RestClient;


@ApplicationPath("/")
@Path("/")
@RequestScoped
/** Runs a set of Broker REST API calls in a loop. */
public class Looper extends Application {
	private static final String BASE_ID = "Looper";
	private static final String SYMBOL1 = "IBM";
	private static final String SYMBOL2 = "KD";
	private static final String SYMBOL3 = "AAPL";

	private static Logger logger = Logger.getLogger(Looper.class.getName());

	private @Inject @RestClient BrokerClient brokerClient;
	private @Inject @ConfigProperty(name = "JWT_AUDIENCE") String jwtAudience;
	private @Inject @ConfigProperty(name = "JWT_ISSUER") String jwtIssuer;

	// Override Broker Client URL if config map is configured to provide URL
	static {
		String mpUrlPropName = BrokerClient.class.getName() + "/mp-rest/url";
		String brokerURL = System.getenv("BROKER_URL");
		if ((brokerURL != null) && !brokerURL.isEmpty()) {
			logger.info("Using Broker URL from config map: " + brokerURL);
			System.setProperty(mpUrlPropName, brokerURL);
		} else {
			logger.info("Broker URL not found from env var from config map, so defaulting to value in jvm.options: " + System.getProperty(mpUrlPropName));
		}
	}

	public static void main(String[] args) {
		if (args.length == 1) try {
			Looper looper = new Looper();
			looper.loop(null, Integer.parseInt(args[0]));
		} catch (Throwable t) {
			t.printStackTrace();
		} else {
			System.out.println("Usage: Looper <count>");
		}
	}

	@GET
	@Path("/")
	@Produces("text/plain")
	public String loop(@QueryParam("id") String id, @QueryParam("count") Integer count) {
		StringBuffer response = new StringBuffer();

		try {
			if (id==null) id = BASE_ID;
			if (count==null) count=1; //isn't autoboxing cool?

			logger.info("Entering looper, with ID: "+id+" and count: "+count);

			String jwt = "Bearer "+createJWT(id);

			logger.fine("Created a JWT");

			long beginning = System.currentTimeMillis();

			for (int index=1; index<=count; index++) {
				if (count>1) { //only show if they asked for multiple iterations
					response.append("\nIteration #"+index+"\n");
				}

				long start = System.currentTimeMillis();

				response.append(iteration(id, jwt));

				long end = System.currentTimeMillis();

				response.append("\n\nElapsed time for this iteration: "+(end-start)+" ms\n\n");
			}

			if (count>1) { //only show if they asked for multiple iterations
				long ending = System.currentTimeMillis();
				double average = ((double) (ending-beginning))/((double) count);

				response.append("Overall average time per iteration: "+average+" ms\n");
			}

			logger.info("Exiting looper");
		} catch (Throwable t) {
			t.printStackTrace();
		}

		return response.toString();
	}

	@GET
	@Path("/jwt")
	@Produces("text/plain")
	public String getJWT() {
		StringBuffer response = new StringBuffer();

		try {
			logger.fine("Entering getJWT");

			String jwt = "Bearer "+createJWT("admin");

			response.append(jwt);
		} catch (Throwable t) {
			t.printStackTrace();
		}

		return response.toString();
	}
	
	public StringBuffer iteration(String id, String jwt) {
		StringBuffer response = new StringBuffer();
		Broker broker = null;
		Broker[] all = null;

		try {
			//note that for performance reasons, Broker does NOT return the stocks owned by each portfolio in the call that returns the "all brokers" array
			response.append("\n\n1:  GET /broker\n");
			all = brokerClient.getBrokers(jwt); //Summary of all brokers
			response.append(arrayToString(all));

			response.append("\n\n2:  POST /broker/"+id+"\n");
			broker = brokerClient.createBroker(jwt, id); //Create a new broker
			response.append(broker);

			response.append("\n\n3:  PUT /broker/"+id+"?symbol="+SYMBOL1+"&shares=1\n");
			broker = brokerClient.updateBroker(jwt, id, SYMBOL1, 1); //Buy stock for this broker
			response.append(broker);

			response.append("\n\n4:  PUT /broker/"+id+"?symbol="+SYMBOL2+"&shares=2\n");
			broker = brokerClient.updateBroker(jwt, id, SYMBOL2, 2); //Buy stock for this broker
			response.append(broker);

			response.append("\n\n5:  PUT /broker/"+id+"?symbol="+SYMBOL3+"&shares=3\n");
			broker = brokerClient.updateBroker(jwt, id, SYMBOL3, 3); //Buy stock for this broker
			response.append(broker);

			response.append("\n\n6:  GET /broker/"+id+"\n");
			broker = brokerClient.getBroker(jwt, id); //Get details of this broker
			response.append(broker);

			response.append("\n\n7:  GET /broker\n");
			all = brokerClient.getBrokers(jwt); //Summary of all brokers, to see results
			response.append(arrayToString(all));

			response.append("\n\n8:  PUT /broker/"+id+"?symbol="+SYMBOL1+"&shares=6\n");
			broker = brokerClient.updateBroker(jwt, id, SYMBOL1, 6); //Buy more of this stock for this broker
			response.append(broker);

			response.append("\n\n9:  PUT /broker/"+id+"?symbol="+SYMBOL3+"&shares=-3\n");
			broker = brokerClient.updateBroker(jwt, id, SYMBOL3, -3); //Sell all of this stock for this broker
			response.append(broker);

			response.append("\n\n10: GET /broker/"+id+"\n");
			broker = brokerClient.getBroker(jwt, id); //Get details of this broker again
			response.append(broker);

			response.append("\n\n11: DELETE /broker/"+id+"\n");
			broker = brokerClient.deleteBroker(jwt, id); //Remove this broker
			response.append(broker);

			response.append("\n\n12: GET /broker\n");
			all = brokerClient.getBrokers(jwt); //Summary of all brokers, to see back to beginning
			response.append(arrayToString(all));
		} catch (Throwable t) {
			StringWriter writer = new StringWriter();
			t.printStackTrace(new PrintWriter(writer));
			String stackTrace = writer.toString();
			logger.warning(stackTrace);
			response.append("\n"+stackTrace);
		}

		return response;
	}

	@DELETE
	@Path("/")
	@Produces("text/plain")
	public String cleanup(@QueryParam("id") String id, @QueryParam("count") Integer count) {
		StringBuffer response = new StringBuffer();

		if (id==null) id = BASE_ID;
		if (count==null) count=1; //isn't autoboxing cool?

		logger.info("Entering looper, with ID: "+id+" and count: "+count);

		String jwt = "Bearer "+createJWT(id);

		logger.fine("Created a JWT");

		long beginning = System.currentTimeMillis();

		for (int index=1; index<=count; index++) {
			String owner = id+index;
			Broker broker = null;
			try {
				response.append("Checking if there's a left-over broker named \""+owner+"\".\n");
				broker = brokerClient.getBroker(jwt, owner);
			} catch (Throwable t) {
				response.append("No left-over broker named \""+owner+"\" to delete.  That's OK, continuing on....");
			}

			if (broker != null) {
				response.append("DELETE /broker/"+owner+"\n");
				try {
					broker = brokerClient.deleteBroker(jwt, owner); //Remove this broker
					response.append(broker);
				} catch (Throwable t2) {
					logger.warning("Error occurred during cleanup: "+t2.getMessage());
				}
			} else {
				logger.info("No broker found to delete for "+owner);
			}
		}

		return response.toString();
	}

	private String arrayToString(Object[] objects) {
		StringBuffer buffer = new StringBuffer("[");
		if (objects != null) {
			for (int index=0; index<objects.length; index++) {
				if (index != 0) buffer.append(", ");
				buffer.append(objects[index]);
			}
		}
		buffer.append("]");
		return buffer.toString();
	}

	/**
	 * Create Json Web Token.
	 * return: the base64 encoded and signed token. 
	 */
	private String createJWT(String userName) {
		String jwtTokenString = null;

		try {
			// create() uses default settings.  
			// For other settings, specify a JWTBuilder element in server.xml
			// and call create(builder id)
			JwtBuilder builder = JwtBuilder.create("defaultJWT");

			if (userName == null) userName = "null";

			// Put the user info into a JWT Token
			builder.subject(userName);
			builder.claim("upn", userName);

			// Set the audience to our sample's value
			builder.claim("aud", jwtAudience);

			//builder.claim("groups", groups);

			//convention is the issuer is the url, but for demo portability a fixed value is used.
			//builder.claim("iss", request.getRequestURL().toString());
			builder.claim("iss", jwtIssuer);

			JwtToken theToken = builder.buildJwt();			
			jwtTokenString = theToken.compact();
		} catch (Throwable t) {
			t.printStackTrace();
			throw new RuntimeException(t);
		}

		return jwtTokenString;
	}
}
